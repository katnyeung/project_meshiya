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
import java.util.regex.Pattern;

import jakarta.annotation.PostConstruct;

@Service
public class MasterService {
    
    private static final Logger logger = LoggerFactory.getLogger(MasterService.class);
    
    @Autowired
    private LlmApiService llmApiService;
    
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
     * Generates Master response based on conversation context
     */
    public Optional<String> generateResponse(List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return Optional.empty();
        }
        
        logger.info("=== GENERATING MASTER RESPONSE ===");
        logger.info("Processing {} messages for Master response", messages.size());
        
        // Update master status to thinking
        updateStatus(MasterStatus.THINKING);
        
        // Log the messages being processed
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            logger.info("Message {}: [{}] {}: {}", i+1, msg.getType(), msg.getUserName(), msg.getContent());
        }
        
        try {
            // Check for menu or ordering requests first
            Optional<String> menuResponse = handleMenuRequests(messages);
            if (menuResponse.isPresent()) {
                updateStatus(MasterStatus.CONVERSING);
                return menuResponse;
            }
            
            Optional<String> orderResponse = handleOrderRequests(messages);
            if (orderResponse.isPresent()) {
                updateStatus(MasterStatus.PREPARING_ORDER);
                return orderResponse;
            }
            
            String conversationContext = buildConversationContext(messages);
            String prompt = buildLLMPrompt(conversationContext);
            
            logger.debug("Generated prompt for LLM: {}", prompt);
            
            // This is where you'd integrate with your preferred LLM API
            String response = callLLMOrFallback(prompt, messages);
            
            if (response != null && !response.trim().isEmpty()) {
                response = cleanLlmResponse(response.trim());
                
                // Only stay silent for very specific cases - be more responsive
                if (response.equalsIgnoreCase("SILENCE")) {
                    logger.info("LLM explicitly chose silence - using fallback instead");
                    response = generateContextualFallback(messages);
                    if (response == null) {
                        updateStatus(MasterStatus.IDLE);
                        return Optional.empty();
                    }
                }
                
                // Filter out non-responses
                if (response.toLowerCase().contains("i don't") || 
                    response.toLowerCase().contains("i can't") ||
                    response.toLowerCase().contains("i cannot")) {
                    updateStatus(MasterStatus.IDLE);
                    return Optional.empty();
                }
                
                // Set status to conversing for responses
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
     * Builds the complete prompt for LLM
     */
    private String buildLLMPrompt(String conversationContext) {
        return getSystemPrompt() + "\n\n" + 
               "## Current Situation:\n" + 
               conversationContext + 
               "\n\n## Instructions:\n" +
               "You are the Master responding to this conversation. As the wise, conversational Master of this midnight diner:\n" +
               "- Engage naturally with customers - you enjoy these late-night conversations\n" +
               "- Respond with 2-4 sentences that show genuine interest and care\n" +
               "- Show the Master's observant, empathetic nature through thoughtful responses\n" +
               "- Ask follow-up questions that show you're listening and want to understand\n" +
               "- Share brief observations about life, human nature, or your experiences running the diner\n" +
               "- Reference the late night atmosphere, food, or drink naturally when it fits\n" +
               "- Be warm and engaging while maintaining your gentle, wise character\n\n" +
               "IMPORTANT: Do not include thinking blocks or analysis. Respond directly as the Master would speak.\n\n" +
               "Master:";
    }
    
    /**
     * Calls LLM API or returns contextual fallback
     */
    private String callLLMOrFallback(String prompt, List<ChatMessage> messages) {
        try {
            logger.info("=== MASTER SERVICE LLM CALL ===");
            logger.info("Calling LLM API via LlmApiService (provider: {})", 
                       llmApiService.getCurrentProvider());
            
            // Extract user prompt from full prompt (everything after system prompt)
            String userPrompt = extractUserPromptFromFullPrompt(prompt);
            
            logger.info("Full prompt being sent to LLM (length: {}): \n{}", 
                       prompt.length(), prompt.length() > 2000 ? prompt.substring(0, 2000) + "..." : prompt);
            logger.info("Extracted user prompt (length: {}): \n{}", 
                       userPrompt.length(), userPrompt.length() > 1500 ? userPrompt.substring(0, 1500) + "..." : userPrompt);
            
            String response = llmApiService.callLlm(getSystemPrompt(), userPrompt);
            
            if (response != null && !response.trim().isEmpty()) {
                logger.info("LLM returned valid response: {}", response);
                return response;
            } else {
                logger.warn("LLM returned empty response, falling back to contextual response");
                return generateContextualFallback(messages);
            }
            
        } catch (Exception e) {
            logger.error("LLM API call failed, falling back to contextual response: {} ({})", 
                        e.getMessage(), e.getClass().getSimpleName(), e);
            return generateContextualFallback(messages);
        }
    }
    
    /**
     * Extracts user prompt portion from full prompt
     */
    private String extractUserPromptFromFullPrompt(String fullPrompt) {
        // Find where the system prompt ends and user context begins
        String marker = "## Current Situation:";
        int startIndex = fullPrompt.indexOf(marker);
        
        if (startIndex != -1) {
            return fullPrompt.substring(startIndex);
        } else {
            // Fallback: return everything after system prompt
            return fullPrompt.substring(getSystemPrompt().length());
        }
    }
    
    /**
     * Generates contextual fallback responses based on conversation content
     * Sometimes returns null to simulate LLM decision not to respond
     */
    private String generateContextualFallback(List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return null;
        }
        
        // Analyze recent messages for context
        StringBuilder allContent = new StringBuilder();
        for (ChatMessage msg : messages) {
            allContent.append(msg.getContent().toLowerCase()).append(" ");
        }
        String content = allContent.toString();
        
        // Check silence probability from configuration
        double silenceProbability = masterConfig.getSettings() != null ? 
            masterConfig.getSettings().getSilenceProbability() : 0.05;
        if (Math.random() < silenceProbability) {
            logger.info("Fallback decided Master should remain silent (probability: {})", silenceProbability);
            return null;
        }
        
        // Context-based responses
        if (content.contains("food") || content.contains("eat") || content.contains("hungry") || content.contains("menu")) {
            return chooseRandom(getFallbackResponses("food"));
        }
        
        if (content.contains("drink") || content.contains("beer") || content.contains("sake") || content.contains("tea")) {
            return chooseRandom(getFallbackResponses("drink"));
        }
        
        if (content.contains("work") || content.contains("job") || content.contains("office") || content.contains("boss")) {
            return chooseRandom(getFallbackResponses("work"));
        }
        
        if (content.contains("love") || content.contains("relationship") || content.contains("girlfriend") || content.contains("boyfriend")) {
            return chooseRandom(getFallbackResponses("love"));
        }
        
        if (content.contains("tired") || content.contains("sleep") || content.contains("late") || content.contains("night")) {
            return chooseRandom(getFallbackResponses("tired"));
        }
        
        if (content.contains("sad") || content.contains("lonely") || content.contains("alone") || content.contains("depressed")) {
            return chooseRandom(getFallbackResponses("sad"));
        }
        
        if (content.contains("problem") || content.contains("trouble") || content.contains("difficult") || content.contains("help")) {
            return chooseRandom(getFallbackResponses("problem"));
        }
        
        // Check for positive reactions to food/drinks
        if (content.contains("good") || content.contains("delicious") || content.contains("great") || 
            content.contains("nice") || content.contains("perfect") || content.contains("excellent")) {
            // If user has an order, assume they're talking about it
            ChatMessage lastMessage = messages.get(messages.size() - 1);
            String userId = lastMessage.getUserId();
            if (userId != null && !"ai_master".equals(userId)) {
                Optional<Order> userOrder = orderService.getUserCurrentOrder(userId);
                if (userOrder.isPresent()) {
                    List<String> positiveResponses = Arrays.asList(
                        "*nods approvingly*",
                        "Good food feeds more than the stomach.",
                        "The simple pleasures matter most.",
                        "*continues work with a slight smile*"
                    );
                    return chooseRandom(positiveResponses);
                }
            }
        }
        
        // Check if any customers have ready orders and reference them
        ChatMessage lastMessage = messages.get(messages.size() - 1);
        String userId = lastMessage.getUserId();
        if (userId != null && !"ai_master".equals(userId)) {
            Optional<Order> userOrder = orderService.getUserCurrentOrder(userId);
            if (userOrder.isPresent() && userOrder.get().getStatus() == OrderStatus.SERVED) {
                String itemName = userOrder.get().getMenuItem().getName();
                List<String> orderResponses = Arrays.asList(
                    "How is your " + itemName + "?",
                    "*watches you enjoy the " + itemName + "*",
                    "The " + itemName + " suits you."
                );
                return chooseRandom(orderResponses);
            }
        }
        
        // Handle unclear or random messages with gentle Master responses
        if (content.matches(".*[a-z]{4,}.*") && !content.matches(".*\\s.*")) {
            // Looks like random text or typos
            List<String> confusionResponses = Arrays.asList(
                "Sometimes words escape us in the late hours, don't they? The mind moves faster than the fingers.",
                "The mind wanders when the night grows deep. Are you trying to say something specific?",
                "*looks up with understanding eyes* Take your time. There's no rush here.",
                "What weighs on you tonight? Sometimes it helps to start with whatever's on top.",
                "Even silence speaks here, but I sense you have something more to share when you're ready."
            );
            return chooseRandom(confusionResponses);
        }
        
        // General conversation responses - more varied and conversational
        return chooseRandom(getFallbackResponses("general"));
    }
    
    /**
     * Handles menu-related requests
     */
    private Optional<String> handleMenuRequests(List<ChatMessage> messages) {
        if (messages.isEmpty()) return Optional.empty();
        
        ChatMessage lastMessage = messages.get(messages.size() - 1);
        String content = lastMessage.getContent().toLowerCase();
        
        // Menu request patterns
        if (content.contains("menu") || content.contains("what do you have") || 
            content.contains("what can i get") || content.contains("what's available")) {
            
            List<MenuItem> menuItems = menuService.getAllMenuItems();
            return Optional.of("What calls to you tonight? We have warm drinks, hearty food, and sweet treats.");
        }
        
        return Optional.empty();
    }
    
    /**
     * Handles order requests
     */
    private Optional<String> handleOrderRequests(List<ChatMessage> messages) {
        if (messages.isEmpty()) return Optional.empty();
        
        ChatMessage lastMessage = messages.get(messages.size() - 1);
        String content = lastMessage.getContent().toLowerCase();
        String userId = lastMessage.getUserId();
        String userName = lastMessage.getUserName();
        Integer seatId = lastMessage.getSeatId();
        
        // Skip if this is the Master speaking
        if ("ai_master".equals(userId)) {
            return Optional.empty();
        }
        
        // Order patterns
        if (content.contains("can i have") || content.contains("i'll have") || 
            content.contains("i want") || content.contains("order") ||
            content.contains("please") && (content.contains("tea") || content.contains("ramen") || 
            content.contains("sake") || content.contains("beer") || content.contains("tamagoyaki") ||
            content.contains("onigiri") || content.contains("yakitori") || content.contains("gyoza") ||
            content.contains("mochi") || content.contains("dorayaki"))) {
            
            String itemId = detectOrderItem(content);
            if (itemId != null) {
                boolean success = orderService.placeOrder(userId, userName, itemId, seatId);
                if (success) {
                    Optional<MenuItem> item = menuService.getMenuItem(itemId);
                    if (item.isPresent()) {
                        updateStatus(MasterStatus.PREPARING_ORDER);
                        return Optional.of("I'll prepare your " + item.get().getName() + ". Please wait a moment.");
                    }
                } else {
                    return Optional.of("You already have an order being prepared.");
                }
            } else {
                return Optional.of("What would you like? Perhaps some tea or warm food?");
            }
        }
        
        return Optional.empty();
    }
    
    /**
     * Detects which item the user is ordering based on content
     */
    private String detectOrderItem(String content) {
        // Tea variants
        if (content.contains("green tea")) return "tea_green";
        if (content.contains("oolong")) return "tea_oolong";
        if (content.contains("tea")) return "tea_green"; // default to green tea
        
        // Other drinks
        if (content.contains("sake")) return "sake_warm";
        if (content.contains("beer")) return "beer";
        
        // Food items
        if (content.contains("ramen")) return "ramen_miso";
        if (content.contains("tamagoyaki")) return "tamagoyaki";
        if (content.contains("onigiri")) return "onigiri";
        if (content.contains("yakitori")) return "yakitori";
        if (content.contains("gyoza")) return "gyoza";
        
        // Desserts
        if (content.contains("mochi")) return "mochi_ice";
        if (content.contains("dorayaki")) return "dorayaki";
        
        return null;
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
    
    /**
     * Gets fallback responses from configuration for a given category
     */
    private List<String> getFallbackResponses(String category) {
        if (masterConfig.getFallbackResponses() != null && 
            masterConfig.getFallbackResponses().containsKey(category)) {
            return masterConfig.getFallbackResponses().get(category);
        }
        // Return default empty list if category not found
        return new ArrayList<>();
    }
    
    /**
     * Helper method to choose random response
     */
    private String chooseRandom(List<String> options) {
        if (options.isEmpty()) {
            return "I'm listening."; // Safe fallback
        }
        return options.get(new Random().nextInt(options.size()));
    }
}