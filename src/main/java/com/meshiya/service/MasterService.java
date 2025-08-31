package com.meshiya.service;

import com.meshiya.config.MasterConfiguration;
import com.meshiya.dto.ChatMessage;
import com.meshiya.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PostConstruct;

@Service
public class MasterService {
    
    private static final Logger logger = LoggerFactory.getLogger(MasterService.class);
    
    @Autowired
    private ChatLLMService chatLLMService;
    
    @Autowired
    private MenuService menuService;
    
    @Autowired
    private OrderService orderService;
    
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    
    @Autowired
    private MasterConfiguration.MasterConfig masterConfig;
    
    // Status management
    private MasterStatus currentStatus = MasterStatus.IDLE;
    private LocalDateTime statusChangedAt = LocalDateTime.now();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    @PostConstruct
    public void init() {
        // Register callback to track order status changes
        orderService.setOrderStatusCallback(this::handleOrderStatusChange);
    }
    
    /**
     * Handles order status changes from OrderService
     */
    private void handleOrderStatusChange(Order order, OrderStatus newStatus) {
        logger.info("Order status changed: {} -> {}", order.getOrderId(), newStatus);
        
        // Update master status based on order status
        switch (newStatus) {
            case ORDERED:
                // Don't change status here - let the response handling do it
                break;
            case PREPARING:
                if (currentStatus == MasterStatus.IDLE || currentStatus == MasterStatus.THINKING || currentStatus == MasterStatus.PREPARING_ORDER) {
                    updateStatus(MasterStatus.PREPARING_ORDER);
                }
                break;
            case READY:
                if (currentStatus == MasterStatus.PREPARING_ORDER) {
                    updateStatusWithTimeout(MasterStatus.SERVING, 5);
                }
                break;
            case SERVED:
                if (currentStatus == MasterStatus.SERVING) {
                    updateStatus(MasterStatus.IDLE);
                }
                break;
        }
    }
    
    /**
     * Gets the system prompt from configuration
     */
    private String getSystemPrompt() {
        return masterConfig.getPersonality().getSystemPrompt();
    }
    
    // ===== STATUS MANAGEMENT METHODS =====
    
    /**
     * Updates master status and broadcasts to all clients
     */
    public void updateStatus(MasterStatus newStatus) {
        if (currentStatus != newStatus) {
            MasterStatus previousStatus = currentStatus;
            currentStatus = newStatus;
            statusChangedAt = LocalDateTime.now();
            
            StackTraceElement caller = Thread.currentThread().getStackTrace()[2];
            logger.info("Master status changed from {} to {} (called from {}:{})", 
                       previousStatus, newStatus, caller.getClassName(), caller.getLineNumber());
            
            // Broadcast status update to all clients
            broadcastStatusUpdate();
            
            // Auto-return to IDLE for temporary statuses
            scheduleAutoReturn(newStatus);
        }
    }
    
    /**
     * Updates status with automatic return to IDLE after specified duration
     */
    public void updateStatusWithTimeout(MasterStatus newStatus, long timeoutSeconds) {
        updateStatus(newStatus);
        
        scheduler.schedule(() -> {
            if (currentStatus == newStatus) {
                updateStatus(MasterStatus.IDLE);
            }
        }, timeoutSeconds, TimeUnit.SECONDS);
    }
    
    /**
     * Gets current master status
     */
    public MasterStatus getCurrentStatus() {
        return currentStatus;
    }
    
    /**
     * Gets when status was last changed
     */
    public LocalDateTime getStatusChangedAt() {
        return statusChangedAt;
    }
    
    /**
     * Broadcasts current status to all WebSocket clients
     */
    private void broadcastStatusUpdate() {
        try {
            messagingTemplate.convertAndSend("/topic/master-status", 
                createStatusMessage());
        } catch (Exception e) {
            logger.error("Failed to broadcast master status update", e);
        }
    }
    
    /**
     * Creates status message for WebSocket broadcast
     */
    private Object createStatusMessage() {
        return new Object() {
            public final String type = "MASTER_STATUS_UPDATE";
            public final String status = currentStatus.name();
            public final String displayName = currentStatus.getDisplayName();
            public final String description = currentStatus.getDescription();
            public final String timestamp = statusChangedAt.toString();
        };
    }
    
    /**
     * Schedules automatic return to IDLE for temporary statuses
     */
    private void scheduleAutoReturn(MasterStatus status) {
        long autoReturnSeconds = switch (status) {
            case THINKING -> 0; // Let LLM call completion handle this
            case PREPARING_ORDER -> 0; // Let OrderService callbacks handle this
            case SERVING -> 5;
            case CLEANING -> 10;
            case CONVERSING -> 0; // Don't auto-return, let conversation flow determine this
            case BUSY -> 20;
            case IDLE -> 0; // Already idle
        };
        
        if (autoReturnSeconds > 0) {
            scheduler.schedule(() -> {
                if (currentStatus == status) {
                    updateStatus(MasterStatus.IDLE);
                }
            }, autoReturnSeconds, TimeUnit.SECONDS);
        }
    }
    
    /**
     * Force status to IDLE (for system resets)
     */
    public void forceIdle() {
        updateStatus(MasterStatus.IDLE);
    }
    
    /**
     * Check if master is currently busy with tasks
     */
    public boolean isBusy() {
        return currentStatus == MasterStatus.PREPARING_ORDER || 
               currentStatus == MasterStatus.SERVING ||
               currentStatus == MasterStatus.BUSY;
    }
    
    /**
     * Check if master is available for conversation
     */
    public boolean isAvailableForConversation() {
        return currentStatus == MasterStatus.IDLE || 
               currentStatus == MasterStatus.CONVERSING ||
               currentStatus == MasterStatus.CLEANING;
    }
    
    // ===== CONVERSATION MANAGEMENT METHODS =====
    
    /**
     * Generates Master response using LLM
     */
    public Optional<String> generateResponse(List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return Optional.empty();
        }
        
        updateStatus(MasterStatus.THINKING);
        
        try {
            // Check for order commands first
            Optional<String> orderResponse = handleOrderCommands(messages);
            if (orderResponse.isPresent()) {
                updateStatus(MasterStatus.PREPARING_ORDER);
                return orderResponse;
            }
            
            String conversationContext = buildConversationContext(messages);
            String fullPrompt = buildLLMPrompt(conversationContext);
            
            String response = chatLLMService.callLlm("", fullPrompt);
            
            if (response != null && !response.trim().isEmpty()) {
                response = cleanLlmResponse(response.trim());
                updateStatusWithTimeout(MasterStatus.CONVERSING, 10);
                return Optional.of(response);
            }
            
        } catch (Exception e) {
            logger.error("Error generating Master response", e);
        }
        
        updateStatus(MasterStatus.IDLE);
        return Optional.empty();
    }
    
    /**
     * Handles /order commands
     */
    private Optional<String> handleOrderCommands(List<ChatMessage> messages) {
        if (messages.isEmpty()) return Optional.empty();
        
        ChatMessage lastMessage = messages.get(messages.size() - 1);
        String content = lastMessage.getContent();
        String userId = lastMessage.getUserId();
        String userName = lastMessage.getUserName();
        Integer seatId = lastMessage.getSeatId();
        String roomId = lastMessage.getRoomId();
        
        // Skip if this is the Master speaking
        if ("ai_master".equals(userId)) {
            return Optional.empty();
        }
        
        // Check for /order command
        if (content.trim().startsWith("/order ")) {
            String orderRequest = content.trim().substring(7).trim(); // Remove "/order " prefix
            if (!orderRequest.isEmpty()) {
                return processLlmOrder(orderRequest, userId, userName, roomId, seatId);
            } else {
                return Optional.of("What would you like to order? Try '/order green tea' or '/order ramen'.");
            }
        }
        
        return Optional.empty();
    }
    
    /**
     * Processes LLM-powered order using menu context
     */
    private Optional<String> processLlmOrder(String orderRequest, String userId, String userName, String roomId, Integer seatId) {
        try {
            logger.info("Processing /order command: '{}' for user {}", orderRequest, userName);
            
            // Build menu context
            List<MenuItem> allItems = menuService.getAllMenuItems();
            StringBuilder menuContext = new StringBuilder();
            menuContext.append("Available menu items by category:\n\n");
            
            // Group by category
            Map<String, List<MenuItem>> itemsByCategory = new HashMap<>();
            for (MenuItem item : allItems) {
                String category = item.getType().name().toLowerCase();
                itemsByCategory.computeIfAbsent(category, k -> new ArrayList<>()).add(item);
            }
            
            for (Map.Entry<String, List<MenuItem>> entry : itemsByCategory.entrySet()) {
                menuContext.append(entry.getKey().toUpperCase()).append(":\n");
                for (MenuItem item : entry.getValue()) {
                    menuContext.append("- ").append(item.getName())
                              .append(" (").append(item.getId()).append("): ")
                              .append(item.getDescription()).append("\n");
                }
                menuContext.append("\n");
            }
            
            // Use the creative XML order approach - let OrderService handle it
            boolean success = orderService.placeLlmOrder(userId, userName, roomId, orderRequest, seatId);
            if (success) {
                updateStatus(MasterStatus.PREPARING_ORDER);
                return Optional.of("*rolls up sleeves and heads to the kitchen* Let me fire up the stove and get this ready for you.");
            } else {
                return Optional.of("Let me check what we have available. What specifically would you like?");
            }
            
        } catch (Exception e) {
            logger.error("Error processing /order command", e);
        }
        
        return Optional.of("What would you like? Perhaps some tea or warm food?");
    }
    
    /**
     * Extracts room ID from the messages list
     */
    private String extractRoomIdFromMessages(List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return null;
        }
        
        // Get room ID from the most recent message that has one
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            String roomId = msg.getRoomId();
            if (roomId != null && !roomId.trim().isEmpty()) {
                return roomId;
            }
        }
        
        return null;
    }
    
    /**
     * Builds conversation context from messages including order states
     */
    private String buildConversationContext(List<ChatMessage> messages) {
        StringBuilder context = new StringBuilder();
        
        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        context.append("Time: ").append(currentTime).append(" (late night)\n");
        
        // Extract room ID from messages and include in location context
        String roomId = extractRoomIdFromMessages(messages);
        if (roomId != null && !roomId.isEmpty()) {
            logger.debug("Building conversation context for room: {}", roomId);
            context.append("Location: Meshiya Midnight Diner - Room ").append(roomId).append("\n\n");
        } else {
            logger.debug("Building conversation context for main diner (no room ID found)");
            context.append("Location: Meshiya Midnight Diner\n\n");
        }
        
        // Add customer order states
        Set<String> customers = new HashSet<>();
        for (ChatMessage msg : messages) {
            if (!"ai_master".equals(msg.getUserId())) {
                customers.add(msg.getUserId());
            }
        }
        
        if (!customers.isEmpty()) {
            context.append("Customer Order Status:\n");
            for (String userId : customers) {
                Optional<Order> currentOrder = orderService.getUserCurrentOrder(userId);
                String userName = messages.stream()
                    .filter(msg -> userId.equals(msg.getUserId()))
                    .findFirst()
                    .map(ChatMessage::getUserName)
                    .orElse("Customer");
                
                if (currentOrder.isPresent()) {
                    Order order = currentOrder.get();
                    context.append("- ").append(userName).append(": ")
                           .append(order.getMenuItem().getName())
                           .append(" (").append(order.getStatus().name().toLowerCase()).append(")\n");
                } else {
                    context.append("- ").append(userName).append(": no current order\n");
                }
            }
            context.append("\n");
        }
        
        context.append("Recent conversation:\n");
        for (ChatMessage msg : messages) {
            String speaker = "ai_master".equals(msg.getUserId()) ? "Master" : msg.getUserName();
            context.append(speaker).append(": ").append(msg.getContent()).append("\n");
        }
        
        return context.toString();
    }
    
    /**
     * Builds the complete prompt for LLM using configuration
     */
    private String buildLLMPrompt(String conversationContext) {
        String template = masterConfig.getConversationPrompts().getInstructionsTemplate();
        return getSystemPrompt() + "\n\n" + 
               template.replace("{conversationContext}", conversationContext);
    }
    
    
    
    /**
     * Cleans LLM response by removing thinking blocks and unwanted artifacts
     */
    private String cleanLlmResponse(String response) {
        if (response == null || response.isEmpty()) {
            return response;
        }
        
        logger.debug("Cleaning LLM response: {}", response);
        
        // Remove thinking blocks like <think>...</think>
        response = response.replaceAll("(?s)<think>.*?</think>", "").trim();
        
        // Remove other common thinking patterns
        response = response.replaceAll("(?s)<thinking>.*?</thinking>", "").trim();
        response = response.replaceAll("(?s)\\(thinking.*?\\)", "").trim();
        
        // Remove analysis blocks or explanatory text
        response = response.replaceAll("(?s)Analysis:.*?(?=\\n\\n|$)", "").trim();
        response = response.replaceAll("(?s)Reasoning:.*?(?=\\n\\n|$)", "").trim();
        
        // Remove any "Master:" prefix if it appears
        response = response.replaceAll("^Master:\\s*", "").trim();
        
        // Remove any leading/trailing quotes that might wrap the response
        response = response.replaceAll("^[\"']|[\"']$", "").trim();
        
        // Clean up any double spaces or line breaks
        response = response.replaceAll("\\s+", " ").trim();
        
        logger.debug("Cleaned LLM response: {}", response);
        
        return response;
    }
    
}