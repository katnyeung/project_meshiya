package com.meshiya.service;

import com.meshiya.dto.ChatMessage;
import com.meshiya.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
public class OrderService {
    
    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);
    
    @Autowired
    private MenuService menuService;
    
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    
    // Order queue management
    private final Queue<Order> orderQueue = new ConcurrentLinkedQueue<>();
    private final Map<String, Order> activeOrders = new ConcurrentHashMap<>();
    private final Map<String, String> userCurrentOrders = new ConcurrentHashMap<>(); // userId -> orderId
    
    // Master state
    private boolean masterBusy = false;
    private Order currentlyPreparing = null;
    
    /**
     * Places a new order
     */
    public synchronized boolean placeOrder(String userId, String userName, String itemId, Integer seatId) {
        // Check if user already has an active order
        if (userCurrentOrders.containsKey(userId)) {
            logger.debug("User {} already has an active order", userName);
            return false;
        }
        
        Optional<MenuItem> menuItem = menuService.getMenuItem(itemId);
        if (!menuItem.isPresent()) {
            logger.warn("Menu item {} not found", itemId);
            return false;
        }
        
        String orderId = generateOrderId();
        Order order = new Order(orderId, userId, userName, menuItem.get(), seatId);
        
        orderQueue.offer(order);
        activeOrders.put(orderId, order);
        userCurrentOrders.put(userId, orderId);
        
        logger.info("Order placed: {} ordered {}", userName, menuItem.get().getName());
        
        // Send confirmation message
        sendOrderConfirmation(order);
        
        return true;
    }
    
    /**
     * Gets user's current order
     */
    public Optional<Order> getUserCurrentOrder(String userId) {
        String orderId = userCurrentOrders.get(userId);
        if (orderId != null) {
            return Optional.ofNullable(activeOrders.get(orderId));
        }
        return Optional.empty();
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
        
        logger.info("Master started preparing: {} for {}", 
                   order.getMenuItem().getName(), order.getUserName());
        
        // Send preparation message
        sendMasterMessage(String.format("*begins preparing %s for %s*", 
                                       order.getMenuItem().getName(), order.getUserName()));
        
        // Update visual state (placeholder for now)
        updateMasterVisualState("preparing", order.getMenuItem().getType().name().toLowerCase());
    }
    
    /**
     * Finishes current order preparation
     */
    private void finishCurrentOrder() {
        if (currentlyPreparing == null) return;
        
        currentlyPreparing.setStatus(OrderStatus.READY);
        logger.info("Order ready: {} for {}", 
                   currentlyPreparing.getMenuItem().getName(), 
                   currentlyPreparing.getUserName());
        
        // Send ready message
        sendMasterMessage(String.format("*slides %s across the counter to %s*", 
                                       currentlyPreparing.getMenuItem().getName(), 
                                       currentlyPreparing.getUserName()));
        
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
        
        // Send served message to specific user
        ChatMessage servedMessage = new ChatMessage();
        servedMessage.setType(MessageType.FOOD_SERVED);
        servedMessage.setContent(order.getMenuItem().getName());
        servedMessage.setUserName("Master");
        servedMessage.setUserId("ai_master");
        servedMessage.setSeatId(order.getSeatId());
        
        messagingTemplate.convertAndSendToUser(order.getUserId(), "/queue/orders", servedMessage);
        
        logger.info("Order served: {} to {}", order.getMenuItem().getName(), order.getUserName());
    }
    
    /**
     * Completes an order when customer finishes eating/drinking
     */
    public synchronized void completeOrder(String userId) {
        String orderId = userCurrentOrders.get(userId);
        if (orderId != null) {
            Order order = activeOrders.get(orderId);
            if (order != null) {
                order.setStatus(OrderStatus.CONSUMING);
                
                // Clean up after some time (simulate finishing)
                // In a real app, this would be triggered by user action
                scheduleOrderCleanup(order, 300); // 5 minutes
                
                logger.info("Order completed: {} by {}", order.getMenuItem().getName(), order.getUserName());
            }
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
            userCurrentOrders.remove(order.getUserId());
            logger.debug("Cleaned up order: {}", orderId);
        }
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
        return status;
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
     * Sends master message to all clients
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
     * Generates unique order ID
     */
    private String generateOrderId() {
        return "order_" + System.currentTimeMillis() + "_" + 
               Integer.toHexString(new Random().nextInt(0xffff));
    }
}