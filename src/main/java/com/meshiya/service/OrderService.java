package com.meshiya.service;

import com.meshiya.dto.ChatMessage;
import com.meshiya.model.*;
import com.meshiya.config.MasterConfiguration;
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
    private ConsumableService consumableService;
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private OrderLLMService orderLLMService;
    
    @Autowired
    private MasterConfiguration.MasterConfig masterConfig;
    
    @Autowired
    private ImageGenerationService imageGenerationService;
    
    @Autowired
    private AvatarStateService avatarStateService;
    
    @Autowired
    private UserService userService;
    
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
        
        // Update user activity timestamp for cleanup tracking
        userService.updateUserActivity(userId, userName, roomId);
        
        // Record avatar activity for state management
        if (avatarStateService != null) {
            avatarStateService.recordUserActivity(userId, roomId, seatId);
        }
        
        notifyStatusChange(order, OrderStatus.ORDERED);
        
        logger.info("Order placed: {} ordered {} in room {}", userName, menuItem.get().getName(), roomId);
        
        // Don't send automatic confirmation here - let MasterService handle it
        
        return true;
    }
    
    /**
     * Places an LLM-generated order that creates a custom consumable
     */
    public synchronized boolean placeLlmOrder(String userId, String userName, String roomId, String orderRequest, Integer seatId) {
        try {
            logger.info("Processing LLM order: '{}' for user {}", orderRequest, userName);
            
            // Build menu context for LLM
            List<MenuItem> allItems = menuService.getAllMenuItems();
            StringBuilder menuContext = new StringBuilder();
            menuContext.append("Available menu categories and examples:\n\n");
            
            // Group by category
            Map<String, List<MenuItem>> itemsByCategory = new HashMap<>();
            for (MenuItem item : allItems) {
                String category = item.getType().name().toLowerCase();
                itemsByCategory.computeIfAbsent(category, k -> new ArrayList<>()).add(item);
            }
            
            for (Map.Entry<String, List<MenuItem>> entry : itemsByCategory.entrySet()) {
                menuContext.append(entry.getKey().toUpperCase()).append(":\n");
                for (MenuItem item : entry.getValue()) {
                    menuContext.append("- ").append(item.getName()).append(": ").append(item.getDescription())
                              .append(" (prep: ").append(item.getPreparationTimeSeconds()).append("s, ")
                              .append("consume: ").append(item.getConsumptionTimeSeconds()).append("s)\n");
                }
                menuContext.append("\n");
            }
            
            // Create LLM prompt for creative consumable generation using configuration
            String systemPrompt = getOrderSystemPrompt();
            String userPrompt = buildOrderUserPrompt(menuContext.toString(), orderRequest);
            
            logger.debug("LLM consumable generation prompt: {}", userPrompt);
            
            String llmResponse = orderLLMService.callLlm(systemPrompt, userPrompt);
            
            if (llmResponse != null && !llmResponse.trim().isEmpty()) {
                logger.info("LLM response: {}", llmResponse);
                
                // Parse LLM response as simple text format
                MenuItem customItem = parseSimpleTextResponse(llmResponse.trim(), orderRequest);
                
                if (customItem != null) {
                    String orderId = generateOrderId();
                    Order order = new Order(orderId, userId, userName, roomId, customItem, seatId);
                    
                    orderQueue.offer(order);
                    activeOrders.put(orderId, order);
                    userCurrentOrders.computeIfAbsent(userId, k -> new HashSet<>()).add(orderId);
                    
                    // Persist user orders to Redis
                    persistUserOrders(userId);
                    
                    // Update user activity timestamp for cleanup tracking
                    userService.updateUserActivity(userId, userName, roomId);
                    
                    // Record avatar activity for state management
                    if (avatarStateService != null) {
                        avatarStateService.recordUserActivity(userId, roomId, seatId);
                    }
                    
                    notifyStatusChange(order, OrderStatus.ORDERED);
                    
                    // Send confirmation message while we generate the image
                    sendOrderConfirmationMessage(userName, customItem.getName());
                    
                    // Generate image asynchronously (in background)
                    generateImageForOrder(order, customItem);
                    
                    logger.info("LLM order placed: {} ordered custom {} in room {}", userName, customItem.getName(), roomId);
                    return true;
                } else {
                    logger.warn("Failed to parse LLM response for consumable generation");
                    return false;
                }
            } else {
                logger.warn("LLM returned empty response for consumable generation");
                return false;
            }
            
        } catch (Exception e) {
            logger.error("Error placing LLM order", e);
            return false;
        }
    }
    
    /**
     * Parses XML-tagged response to create a custom MenuItem
     * Expected format: <foodname>...</foodname><description>...</description><type>...</type><preptime>...</preptime><consumetime>...</consumetime>
     */
    private MenuItem parseSimpleTextResponse(String llmResponse, String originalRequest) {
        try {
            logger.debug("Parsing LLM response with regex: {}", llmResponse);
            
            // Use regex to extract values from XML-like tags
            String name = extractTagValue(llmResponse, "foodname");
            String description = extractTagValue(llmResponse, "description");
            String typeStr = extractTagValue(llmResponse, "type");
            String prepTimeStr = extractTagValue(llmResponse, "preptime");
            String consumeTimeStr = extractTagValue(llmResponse, "consumetime");
            
            logger.debug("Extracted values - name: {}, description: {}, type: {}, prep: {}, consume: {}", 
                        name, description, typeStr, prepTimeStr, consumeTimeStr);
            
            if (name == null || description == null || typeStr == null) {
                logger.warn("LLM response missing required fields. Creating fallback item. name={}, desc={}, type={}", 
                           name, description, typeStr);
                return createFallbackItem(originalRequest);
            }
            
            // Parse type - be flexible with LLM responses
            MenuItemType type = parseFlexibleType(typeStr);
            
            // Parse times with smart defaults based on type
            int prepTime = getDefaultPrepTime(type);
            int consumeTime = getDefaultConsumeTime(type);
            
            try {
                if (prepTimeStr != null && !prepTimeStr.isEmpty()) {
                    prepTime = Integer.parseInt(prepTimeStr.trim());
                }
                if (consumeTimeStr != null && !consumeTimeStr.isEmpty()) {
                    consumeTime = Integer.parseInt(consumeTimeStr.trim());
                }
            } catch (NumberFormatException e) {
                logger.warn("Invalid time values from LLM, using defaults: prep={}, consume={}", prepTime, consumeTime);
            }
            
            // Create custom MenuItem with generated ID
            String customId = "custom_" + System.currentTimeMillis();
            MenuItem customItem = new MenuItem(customId, name, description, type, prepTime, consumeTime, 
                                             "creative", "all");
            
            logger.info("Created custom item: {} ({}) - prep:{}s, consume:{}s", name, type, prepTime, consumeTime);
            return customItem;
            
        } catch (Exception e) {
            logger.error("Error parsing XML response: {}", llmResponse, e);
            return createFallbackItem(originalRequest);
        }
    }
    
    /**
     * Extracts content from XML-like tags using regex
     */
    private String extractTagValue(String text, String tagName) {
        try {
            // Create regex pattern like <foodname>(.*?)</foodname>
            String pattern = "<" + tagName + ">(.*?)</" + tagName + ">";
            java.util.regex.Pattern regexPattern = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher matcher = regexPattern.matcher(text);
            
            if (matcher.find()) {
                String value = matcher.group(1).trim();
                logger.debug("Extracted {} = '{}'", tagName, value);
                return value;
            } else {
                logger.debug("Tag {} not found in response", tagName);
                return null;
            }
        } catch (Exception e) {
            logger.warn("Error extracting tag {}: {}", tagName, e.getMessage());
            return null;
        }
    }
    
    /**
     * Parse type flexibly - handles multiple formats from LLM
     */
    private MenuItemType parseFlexibleType(String typeStr) {
        if (typeStr == null || typeStr.trim().isEmpty()) {
            logger.warn("Empty type from LLM, defaulting to FOOD");
            return MenuItemType.FOOD;
        }
        
        String cleanType = typeStr.toUpperCase().trim();
        logger.debug("Parsing flexible type: '{}'", cleanType);
        
        // Check for exact matches first
        try {
            return MenuItemType.valueOf(cleanType);
        } catch (IllegalArgumentException e) {
            // Handle multiple types separated by | or other characters
            logger.debug("Not exact match, checking for partial matches in: {}", cleanType);
        }
        
        // Look for valid types within the string (handles cases like "DESSERT|FOOD|RAMEN")
        if (cleanType.contains("FOOD")) {
            logger.debug("Found FOOD in type string");
            return MenuItemType.FOOD;
        } else if (cleanType.contains("DRINK")) {
            logger.debug("Found DRINK in type string");
            return MenuItemType.DRINK;
        } else if (cleanType.contains("DESSERT")) {
            logger.debug("Found DESSERT in type string");
            return MenuItemType.DESSERT;
        }
        
        // Ultimate fallback
        logger.warn("Could not parse item type '{}', defaulting to FOOD", typeStr);
        return MenuItemType.FOOD;
    }

    /**
     * Get default preparation time based on item type
     */
    private int getDefaultPrepTime(MenuItemType type) {
        return switch (type) {
            case DRINK -> 15; // 15 seconds for drinks
            case FOOD -> 60; // 1 minute for food  
            case DESSERT -> 30; // 30 seconds for desserts
        };
    }
    
    /**
     * Get default consumption time based on item type
     */
    private int getDefaultConsumeTime(MenuItemType type) {
        return switch (type) {
            case DRINK -> 300; // 5 minutes for drinks
            case FOOD -> 600; // 10 minutes for food
            case DESSERT -> 240; // 4 minutes for desserts
        };
    }
    
    /**
     * Creates a fallback item when LLM parsing fails
     */
    private MenuItem createFallbackItem(String orderRequest) {
        // Guess the type based on common keywords
        MenuItemType type = MenuItemType.FOOD; // Default
        String name = "Special " + orderRequest;
        String description = "A special creation inspired by your request";
        
        String lowerRequest = orderRequest.toLowerCase();
        if (lowerRequest.contains("tea") || lowerRequest.contains("coffee") || 
            lowerRequest.contains("drink") || lowerRequest.contains("juice") ||
            lowerRequest.contains("water") || lowerRequest.contains("sake") || 
            lowerRequest.contains("beer")) {
            type = MenuItemType.DRINK;
            name = "Special " + orderRequest + " Drink";
        } else if (lowerRequest.contains("cake") || lowerRequest.contains("sweet") ||
                  lowerRequest.contains("dessert") || lowerRequest.contains("ice cream") ||
                  lowerRequest.contains("cookie") || lowerRequest.contains("chocolate")) {
            type = MenuItemType.DESSERT;
            name = "Sweet " + orderRequest + " Treat";
        }
        
        String customId = "fallback_" + System.currentTimeMillis();
        MenuItem fallbackItem = new MenuItem(customId, name, description, type, 
                                           getDefaultPrepTime(type), getDefaultConsumeTime(type),
                                           "creative", "all");
        
        logger.info("Created fallback item: {} ({})", name, type);
        return fallbackItem;
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
        
        // Send preparation message to chat with description if available
        String preparingMessage;
        MenuItem item = order.getMenuItem();
        
        // Check if this is a custom LLM-generated item with a creative description
        if (item.getId().startsWith("custom_") && item.getDescription() != null && 
            !item.getDescription().equals("A special creation inspired by your request")) {
            // Use the LLM description for custom items
            preparingMessage = String.format("\"%s\" *begins preparing %s*", 
                                           item.getDescription(), item.getName());
        } else {
            // Standard message for regular menu items
            preparingMessage = String.format("*begins preparing %s*", item.getName());
        }
        
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
        
        // Add consumable to user status (with image data if available)
        // Use user's CURRENT seat, not the order's original seat (handles seat swapping)
        Integer currentSeat = userService.getUserSeat(order.getUserId());
        if (currentSeat != null) {
            logger.info("Order completion for user {} - using current seat {} instead of order seat {}", 
                       order.getUserId(), currentSeat, order.getSeatId());
            consumableService.addConsumableWithImage(order.getUserId(), order.getRoomId(), currentSeat, 
                                                    order.getMenuItem(), order.getImageData());
        } else {
            // Fallback to order seat if user has no current seat (disconnected/left)
            logger.warn("Order completion for user {} - no current seat found, using order seat {}", 
                       order.getUserId(), order.getSeatId());
            consumableService.addConsumableWithImage(order.getUserId(), order.getRoomId(), order.getSeatId(), 
                                                    order.getMenuItem(), order.getImageData());
        }
        
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
     * Get order system prompt from configuration
     */
    private String getOrderSystemPrompt() {
        if (masterConfig != null && masterConfig.getOrderPrompts() != null 
            && masterConfig.getOrderPrompts().getSystemPrompt() != null) {
            return masterConfig.getOrderPrompts().getSystemPrompt();
        }
        
        // Fallback default
        return "You are the Master of a midnight diner. You can create anything the customer wants! " +
               "Be creative and loose - turn any request into FOOD, DRINK, or DESSERT. " +
               "There are no wrong answers - just make something that fits one of the three categories.";
    }
    
    /**
     * Build order user prompt from template
     */
    private String buildOrderUserPrompt(String menuContext, String orderRequest) {
        if (masterConfig != null && masterConfig.getOrderPrompts() != null 
            && masterConfig.getOrderPrompts().getUserPromptTemplate() != null) {
            return masterConfig.getOrderPrompts().getUserPromptTemplate()
                    .replace("{menuContext}", menuContext)
                    .replace("{orderRequest}", orderRequest);
        }
        
        // Fallback default
        return menuContext + 
            "\nCustomer order: \"" + orderRequest + "\"\n\n" +
            "Create something for this order. It must be FOOD, DRINK, or DESSERT.\n" +
            "Include your response in these XML tags:\n" +
            "<foodname>Creative Name</foodname>\n" +
            "<description>Brief description</description>\n" +
            "<type>FOOD|DRINK|DESSERT</type>\n" +
            "<preptime>60</preptime>\n" +
            "<consumetime>600</consumetime>\n\n" +
            "Examples:\n" +
            "- 'tea' → <foodname>Warm Green Tea</foodname><type>DRINK</type><preptime>15</preptime>\n" +
            "- 'spicy food' → <foodname>Spicy Ramen</foodname><type>FOOD</type><preptime>60</preptime>\n" +
            "- 'sweet' → <foodname>Sweet Treat</foodname><type>DESSERT</type><preptime>30</preptime>";
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
                consumableService.clearUserConsumablesForRestore(userId, roomId, seatId);
                
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
                        consumableService.addConsumable(order.getUserId(), roomId, seatId, order.getMenuItem());
                        
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
    
    /**
     * Send confirmation message to user when LLM order is received
     */
    private void sendOrderConfirmationMessage(String userName, String itemName) {
        String message = String.format("%s, I'll prepare your %s. Let me create something special for you...", 
                                     userName, itemName);
        sendMasterChatMessage(message);
    }
    
    /**
     * Generate image for an order asynchronously
     */
    private void generateImageForOrder(Order order, MenuItem menuItem) {
        // Run image generation in a separate thread to avoid blocking the order queue
        new Thread(() -> {
            try {
                logger.debug("Starting image generation for order {} - {}", order.getOrderId(), menuItem.getName());
                
                String imageData = imageGenerationService.generateFoodImage(
                    menuItem.getName(), 
                    menuItem.getDescription(), 
                    menuItem.getType().name()
                );
                
                if (imageData != null) {
                    // Store the image data with the order for later use
                    order.setImageData(imageData);
                    logger.info("Image generated successfully for order {} - {}", order.getOrderId(), menuItem.getName());
                } else {
                    logger.warn("Image generation failed for order {} - {}", order.getOrderId(), menuItem.getName());
                }
                
            } catch (Exception e) {
                logger.error("Error generating image for order {} - {}: {}", 
                           order.getOrderId(), menuItem.getName(), e.getMessage(), e);
            }
        }).start();
    }
}