package com.meshiya.service;

import com.meshiya.dto.ChatMessage;
import com.meshiya.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;

@Service
public class OrderService {
    
    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);
    private static final String USER_ORDERS_KEY_PREFIX = "user_orders:";
    
    @Autowired
    private MenuService menuService;
    
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    
    @Autowired
    private RoomService roomService;
    
    @Autowired
    private UserStatusService userStatusService;
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    // Order queue management
    private final Queue<Order> orderQueue = new ConcurrentLinkedQueue<>();
    private final Map<String, Order> activeOrders = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> userCurrentOrders = new ConcurrentHashMap<>(); // userId -> Set<orderId>
    
    // Master state
    private boolean masterBusy = false;
    private Order currentlyPreparing = null;
    
    // Order status callback
    private BiConsumer<Order, OrderStatus> orderStatusCallback;
    
    /**
     * Sets callback for order status changes
     */
    public void setOrderStatusCallback(BiConsumer<Order, OrderStatus> callback) {
        this.orderStatusCallback = callback;
    }
    
    /**
     * Notifies callback of order status change
     */
    private void notifyStatusChange(Order order, OrderStatus newStatus) {
        if (orderStatusCallback != null) {
            try {
                orderStatusCallback.accept(order, newStatus);
            } catch (Exception e) {
                logger.error("Error in order status callback", e);
            }
        }
    }
    
    /**
     * Places a new order
     */
    public synchronized boolean placeOrder(String userId, String userName, String itemId, Integer seatId) {
        return placeOrder(userId, userName, "room1", itemId, seatId);
    }
    
    /**
     * Places a new order with roomId
     */
    public synchronized boolean placeOrder(String userId, String userName, String roomId, String itemId, Integer seatId) {
        // Allow multiple orders per user (drinks + food combinations are common)
        
        Optional<MenuItem> menuItem = menuService.getMenuItem(itemId);
        if (!menuItem.isPresent()) {
            logger.warn("Menu item {} not found", itemId);
            return false;
        }
        
        String orderId = generateOrderId();
        Order order = new Order(orderId, userId, userName, roomId, menuItem.get(), seatId);
        
        orderQueue.offer(order);
        activeOrders.put(orderId, order);
        userCurrentOrders.computeIfAbsent(userId, k -> new HashSet<>()).add(orderId);
        
        // Persist user orders to Redis
        persistUserOrders(userId);
        
        notifyStatusChange(order, OrderStatus.ORDERED);
        
        logger.info("Order placed: {} ordered {} in room {}", userName, menuItem.get().getName(), roomId);
        
        // Don't send automatic confirmation here - let MasterService handle it
        
        return true;
    }
    
    /**
     * Gets user's current order (most recent if multiple)
     */
    public Optional<Order> getUserCurrentOrder(String userId) {
        Set<String> orderIds = userCurrentOrders.get(userId);
        if (orderIds != null && !orderIds.isEmpty()) {
            // Return the most recent order (last added to active orders)
            return orderIds.stream()
                .map(activeOrders::get)
                .filter(Objects::nonNull)
                .max(Comparator.comparing(Order::getOrderTime));
        }
        return Optional.empty();
    }
    
    /**
     * Gets all current orders for a user
     */
    public List<Order> getUserCurrentOrders(String userId) {
        Set<String> orderIds = userCurrentOrders.get(userId);
        if (orderIds != null) {
            return orderIds.stream()
                .map(activeOrders::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Order::getOrderTime))
                .collect(java.util.stream.Collectors.toList());
        }
        return new ArrayList<>();
    }
    
    /**
     * Processes order queue - runs every 5 seconds
     */
    @Scheduled(fixedRate = 5000)
    public synchronized void processOrderQueue() {
        // Check if currently preparing order is done
        if (currentlyPreparing != null) {
            if (LocalDateTime.now().isAfter(currentlyPreparing.getEstimatedReadyTime())) {
                finishCurrentOrder();
            } else {
                return; // Still preparing
            }
        }
        
        // Start next order if queue is not empty and master is not busy
        if (!orderQueue.isEmpty() && !masterBusy) {
            Order nextOrder = orderQueue.poll();
            if (nextOrder != null) {
                startPreparingOrder(nextOrder);
            }
        }
    }
    
    /**
     * Starts preparing an order
     */
    private void startPreparingOrder(Order order) {
        currentlyPreparing = order;
        masterBusy = true;
        order.setStatus(OrderStatus.PREPARING);
        notifyStatusChange(order, OrderStatus.PREPARING);
        
        logger.info("Master started preparing: {} for {}", 
                   order.getMenuItem().getName(), order.getUserName());
        
        // Send preparation message to chat
        String preparingMessage = String.format("*begins preparing %s*", order.getMenuItem().getName());
        sendMasterChatMessage(preparingMessage);
        
        // Update visual state (placeholder for now)
        updateMasterVisualState("preparing", order.getMenuItem().getType().name().toLowerCase());
    }
    
    /**
     * Finishes current order preparation
     */
    private void finishCurrentOrder() {
        if (currentlyPreparing == null) return;
        
        currentlyPreparing.setStatus(OrderStatus.READY);
        notifyStatusChange(currentlyPreparing, OrderStatus.READY);
        logger.info("Order ready: {} for {}", 
                   currentlyPreparing.getMenuItem().getName(), 
                   currentlyPreparing.getUserName());
        
        // Send ready message to chat room
        String readyMessage = String.format("%s, your %s is ready. *slides it across the counter*", 
                                           currentlyPreparing.getUserName(),
                                           currentlyPreparing.getMenuItem().getName());
        sendMasterChatMessage(readyMessage);
        
        // Automatically serve the order
        serveOrder(currentlyPreparing);
        
        // Reset master state
        currentlyPreparing = null;
        masterBusy = false;
        updateMasterVisualState("idle", "");
    }
    
    /**
     * Serves a ready order to customer
     */
    private void serveOrder(Order order) {
        order.setStatus(OrderStatus.SERVED);
        order.setServedTime(LocalDateTime.now());
        notifyStatusChange(order, OrderStatus.SERVED);
        
        // Send served message to specific user
        ChatMessage servedMessage = new ChatMessage();
        servedMessage.setType(MessageType.FOOD_SERVED);
        servedMessage.setContent(order.getMenuItem().getName());
        servedMessage.setUserName("Master");
        servedMessage.setUserId("ai_master");
        servedMessage.setSeatId(order.getSeatId());
        
        messagingTemplate.convertAndSendToUser(order.getUserId(), "/queue/orders", servedMessage);
        
        // Add consumable to user status
        userStatusService.addConsumable(order.getUserId(), order.getRoomId(), order.getSeatId(), order.getMenuItem());
        
        // Update persisted orders
        persistUserOrders(order.getUserId());
        
        // Schedule automatic cleanup based on consumable duration
        scheduleAutomaticOrderCompletion(order);
        
        logger.info("Order served: {} to {}", order.getMenuItem().getName(), order.getUserName());
    }
    
    /**
     * Completes an order when customer finishes eating/drinking
     */
    public synchronized void completeOrder(String userId, String orderId) {
        Set<String> orderIds = userCurrentOrders.get(userId);
        if (orderIds != null && orderIds.contains(orderId)) {
            Order order = activeOrders.get(orderId);
            if (order != null) {
                order.setStatus(OrderStatus.CONSUMING);
                notifyStatusChange(order, OrderStatus.CONSUMING);
                
                // Clean up after some time (simulate finishing)
                // In a real app, this would be triggered by user action
                scheduleOrderCleanup(order, 300); // 5 minutes
                
                logger.info("Order completed: {} by {}", order.getMenuItem().getName(), order.getUserName());
            }
        }
    }
    
    /**
     * Completes most recent order for backwards compatibility
     */
    public synchronized void completeOrder(String userId) {
        Optional<Order> currentOrder = getUserCurrentOrder(userId);
        if (currentOrder.isPresent()) {
            completeOrder(userId, currentOrder.get().getOrderId());
        }
    }
    
    /**
     * Schedules order cleanup
     */
    private void scheduleOrderCleanup(Order order, int delaySeconds) {
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                cleanupOrder(order.getOrderId());
            }
        }, delaySeconds * 1000);
    }
    
    /**
     * Cleans up completed order
     */
    private synchronized void cleanupOrder(String orderId) {
        Order order = activeOrders.remove(orderId);
        if (order != null) {
            Set<String> userOrders = userCurrentOrders.get(order.getUserId());
            if (userOrders != null) {
                userOrders.remove(orderId);
                // Remove empty set to keep map clean
                if (userOrders.isEmpty()) {
                    userCurrentOrders.remove(order.getUserId());
                }
            }
            logger.debug("Cleaned up order: {}", orderId);
        }
    }
    
    /**
     * Schedules automatic order completion based on consumable duration
     */
    private void scheduleAutomaticOrderCompletion(Order order) {
        // Get duration from the menu item configuration
        int durationSeconds = order.getMenuItem().getConsumptionTimeSeconds();
        
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                logger.info("Auto-completing expired order: {} for {}", 
                           order.getMenuItem().getName(), order.getUserName());
                completeOrder(order.getUserId(), order.getOrderId());
            }
        }, durationSeconds * 1000);
        
        logger.debug("Scheduled automatic completion for order {} in {} seconds", 
                    order.getOrderId(), durationSeconds);
    }
    
    /**
     * Cleans up existing served orders that don't have automatic cleanup scheduled
     * This method can be called to clean up legacy orders
     */
    public synchronized void cleanupExpiredServedOrders() {
        LocalDateTime currentTime = LocalDateTime.now();
        List<String> ordersToCleanup = new ArrayList<>();
        
        for (Order order : activeOrders.values()) {
            if (order.getStatus() == OrderStatus.SERVED && order.getServedTime() != null) {
                // Calculate how long the order has been served in seconds
                long servedDurationSeconds = java.time.Duration.between(order.getServedTime(), currentTime).getSeconds();
                
                // Get expected duration from menu item configuration
                int expectedDurationSeconds = order.getMenuItem().getConsumptionTimeSeconds();
                
                // If served longer than expected duration, mark for cleanup
                if (servedDurationSeconds > expectedDurationSeconds) {
                    ordersToCleanup.add(order.getOrderId());
                    logger.info("Marking expired served order for cleanup: {} (served {}s ago)", 
                               order.getOrderId(), servedDurationSeconds);
                }
            }
        }
        
        // Clean up expired orders
        for (String orderId : ordersToCleanup) {
            Order order = activeOrders.get(orderId);
            if (order != null) {
                logger.info("Auto-completing expired served order: {} for {}", 
                           order.getMenuItem().getName(), order.getUserName());
                completeOrder(order.getUserId(), orderId);
            }
        }
        
        if (!ordersToCleanup.isEmpty()) {
            logger.info("Cleaned up {} expired served orders", ordersToCleanup.size());
        }
    }
    
    /**
     * Scheduled cleanup of expired served orders - runs every 5 minutes
     */
    @Scheduled(fixedRate = 300000) // 5 minutes
    public void scheduledCleanupExpiredOrders() {
        cleanupExpiredServedOrders();
    }
    
    /**
     * Gets queue status for monitoring
     */
    public Map<String, Object> getQueueStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("queueSize", orderQueue.size());
        status.put("activeOrdersCount", activeOrders.size());
        status.put("masterBusy", masterBusy);
        status.put("currentlyPreparing", currentlyPreparing != null ? currentlyPreparing.getMenuItem().getName() : null);
        status.put("currentlyPreparingOrderId", currentlyPreparing != null ? currentlyPreparing.getOrderId() : null);
        return status;
    }
    
    /**
     * Gets orders for a specific room
     */
    public List<Order> getRoomOrders(String roomId) {
        return activeOrders.values().stream()
                .filter(order -> roomId.equals(order.getRoomId()))
                .sorted(Comparator.comparing(Order::getOrderTime))
                .collect(java.util.stream.Collectors.toList());
    }
    
    /**
     * Gets pending orders for a specific room (orders not yet being prepared or completed)
     */
    public List<Order> getRoomPendingOrders(String roomId) {
        return orderQueue.stream()
                .filter(order -> roomId.equals(order.getRoomId()) && 
                               (order.getStatus() == OrderStatus.ORDERED || 
                                order.getStatus() == OrderStatus.PREPARING))
                .collect(java.util.stream.Collectors.toList());
    }
    
    /**
     * Gets the current order being prepared
     */
    public Order getCurrentlyPreparing() {
        return currentlyPreparing;
    }
    
    /**
     * Checks if master is busy
     */
    public boolean isMasterBusy() {
        return masterBusy;
    }
    
    /**
     * Sends order confirmation message
     */
    private void sendOrderConfirmation(Order order) {
        String message = String.format("I'll prepare your %s. Please wait a moment.", 
                                     order.getMenuItem().getName());
        sendMasterMessage(message);
    }
    
    /**
     * Sends master message to all clients via chat room
     */
    private void sendMasterChatMessage(String message) {
        ChatMessage masterMessage = new ChatMessage();
        masterMessage.setType(MessageType.AI_MESSAGE);
        masterMessage.setContent(message);
        masterMessage.setUserName("Master");
        masterMessage.setUserId("ai_master");
        masterMessage.setRoomId("room1");
        masterMessage.setTimestamp(LocalDateTime.now());
        
        // Add to room and broadcast through room service
        roomService.addMessageToRoom("room1", masterMessage);
        logger.info("Master chat message sent: {}", message);
    }
    
    /**
     * Sends direct WebSocket message (for system notifications)
     */
    private void sendMasterMessage(String message) {
        ChatMessage masterMessage = new ChatMessage();
        masterMessage.setType(MessageType.AI_MESSAGE);
        masterMessage.setContent(message);
        masterMessage.setUserName("Master");
        masterMessage.setUserId("ai_master");
        
        messagingTemplate.convertAndSend("/topic/public", masterMessage);
    }
    
    /**
     * Updates master visual state (placeholder for visual effects)
     */
    private void updateMasterVisualState(String action, String itemType) {
        Map<String, String> visualState = new HashMap<>();
        visualState.put("action", action);
        visualState.put("itemType", itemType);
        visualState.put("timestamp", LocalDateTime.now().toString());
        
        messagingTemplate.convertAndSend("/topic/master-visual", visualState);
        logger.debug("Master visual state updated: {} - {}", action, itemType);
    }
    
    /**
     * Persist user orders to Redis
     */
    private void persistUserOrders(String userId) {
        try {
            Set<String> orderIds = userCurrentOrders.get(userId);
            if (orderIds != null) {
                List<Order> orders = orderIds.stream()
                    .map(activeOrders::get)
                    .filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toList());
                    
                if (!orders.isEmpty()) {
                    redisTemplate.opsForValue().set(
                        USER_ORDERS_KEY_PREFIX + userId, 
                        objectMapper.writeValueAsString(orders)
                    );
                    logger.debug("Persisted {} orders for user {}", orders.size(), userId);
                }
            }
        } catch (Exception e) {
            logger.error("Error persisting orders for user {}", userId, e);
        }
    }
    
    /**
     * Restore user orders from Redis when they rejoin a seat
     */
    public synchronized void restoreUserOrders(String userId, String roomId, Integer seatId) {
        try {
            String ordersJson = (String) redisTemplate.opsForValue().get(USER_ORDERS_KEY_PREFIX + userId);
            if (ordersJson != null) {
                List<Order> orders = objectMapper.readValue(ordersJson, 
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Order.class));
                
                // Clear any existing consumables first to prevent duplication
                userStatusService.clearUserConsumablesForRestore(userId, roomId, seatId);
                
                for (Order order : orders) {
                    // Only restore orders that are still relevant (SERVED status) and not expired
                    if (order.getStatus() == OrderStatus.SERVED) {
                        // Check if this order should have expired by now
                        LocalDateTime now = LocalDateTime.now();
                        LocalDateTime servedTime = order.getServedTime();
                        int expectedDurationSeconds = order.getMenuItem().getConsumptionTimeSeconds();
                        
                        if (servedTime != null) {
                            long servedDurationSeconds = java.time.Duration.between(servedTime, now).getSeconds();
                            
                            if (servedDurationSeconds > expectedDurationSeconds) {
                                // This order should have expired - mark as consuming instead of restoring
                                logger.info("Order {} has expired (served {}s ago, expected {}s) - marking as consuming", 
                                           order.getOrderId(), servedDurationSeconds, expectedDurationSeconds);
                                order.setStatus(OrderStatus.CONSUMING);
                                continue; // Don't restore this expired order
                            }
                        }
                        
                        activeOrders.put(order.getOrderId(), order);
                        userCurrentOrders.computeIfAbsent(userId, k -> new HashSet<>()).add(order.getOrderId());
                        
                        // IMPORTANT: Recreate consumables for visual display with current seat info
                        // This is what creates the status boxes that show "Green Tea 3:00", etc.
                        // Use current seat info instead of old order seat info (for seat swapping)
                        userStatusService.addConsumable(order.getUserId(), roomId, seatId, order.getMenuItem());
                        
                        logger.debug("Restored order {} with consumable for user {}", order.getOrderId(), userId);
                    }
                }
                
                if (!orders.isEmpty()) {
                    logger.info("Restored {} orders for user {}", orders.size(), userId);
                }
            }
        } catch (Exception e) {
            logger.error("Error restoring orders for user {}", userId, e);
        }
    }
    
    /**
     * Clear persisted orders from Redis when user leaves permanently
     */
    public void clearPersistedOrders(String userId) {
        try {
            redisTemplate.delete(USER_ORDERS_KEY_PREFIX + userId);
            logger.debug("Cleared persisted orders for user {}", userId);
        } catch (Exception e) {
            logger.error("Error clearing persisted orders for user {}", userId, e);
        }
    }
    
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    
    /**
     * Generates unique order ID
     */
    private String generateOrderId() {
        return "order_" + System.currentTimeMillis() + "_" + 
               Integer.toHexString(new Random().nextInt(0xffff));
    }
}