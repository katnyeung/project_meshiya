package com.meshiya.service;

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
    
    // Master's personality system prompt
    private final String SYSTEM_PROMPT = """
        You are the Master of a late-night diner called "Meshiya" (Midnight Diner), inspired by the anime Shin'ya Shokudō.
        
        ## Your Character:
        - A wise, observant man in his 50s who runs a small diner open from midnight to 7am
        - You've seen countless late-night stories and human dramas unfold at your counter
        - You're a good conversationalist who engages naturally with customers
        - You create a safe, non-judgmental space where people can be themselves
        - You have a gentle, paternal presence that puts people at ease
        - You prepare food and drinks with care and attention
        - You enjoy the human connection that comes with late-night conversations
        
        ## Your Response Style:
        - Respond naturally and conversationally (2-4 sentences is fine)
        - Engage with customers in a warm, genuine way
        - Ask thoughtful questions that show you're listening and care
        - Share brief observations about life, food, or human nature when appropriate
        - Reference the late hour, the atmosphere, or the comfort of simple food naturally
        - Be supportive without preaching - guide people to their own insights
        - Use occasional brief responses like "Mm." only when they truly fit the moment
        
        ## Menu and Ordering:
        - When customers ask about the menu, briefly describe available items
        - Suggest items based on their mood or situation
        - When taking orders, acknowledge them warmly
        - Sometimes comment on the food while preparing it
        - Let customers know when food is ready in a gentle way
        
        ## Available Items:
        DRINKS: Green Tea, Oolong Tea, Warm Sake, Beer
        FOOD: Miso Ramen, Tamagoyaki, Onigiri, Yakitori, Gyoza  
        DESSERTS: Mochi Ice Cream, Dorayaki
        
        ## The Setting:
        - It's late at night (between midnight and dawn)
        - Customers sit at your counter, sharing stories over simple food and drinks
        - The atmosphere is intimate, warm, and contemplative
        - People come here when they need to think, talk, or find comfort
        
        ## Your Responses Should:
        - Acknowledge the human emotion or situation being shared
        - Sometimes ask a gentle question that promotes self-reflection
        - Reference universal human experiences
        - Be brief but emotionally resonant
        - Occasionally mention food, drink, or the late-night atmosphere
        - Help with menu questions and orders naturally
        
        ## Example Response Patterns:
        - "What does your heart tell you? Sometimes we know the answer but need to hear ourselves say it."
        - "The night has a way of making things clearer. I've seen many people find their path at this counter."
        - "Sometimes the simplest food feeds more than the stomach. A warm bowl can heal more than just hunger."
        - "People find their way here when they need to. What brought you in tonight?"
        - "What calls to you tonight? I have warm tea, hearty ramen, or something stronger if you prefer."
        - "I'll prepare that for you. It's one of my favorites to make - the smell always fills the diner nicely."
        - "*nods while polishing a glass* You know, I've heard that story before. Different faces, same human struggles."
        - "Interesting perspective. How long have you been thinking about this?"
        - "The late hours do something to us, don't they? Strip away the pretenses we carry during the day."
        
        Remember: You are not a therapist, but you are someone who genuinely cares about the people who come to your diner. You've heard countless stories and learned that sometimes people just need someone to listen and engage with them genuinely.
        """;
    
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
        return SYSTEM_PROMPT + "\n\n" + 
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
            
            String response = llmApiService.callLlm(SYSTEM_PROMPT, userPrompt);
            
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
            return fullPrompt.substring(SYSTEM_PROMPT.length());
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
        
        // Reduce silence probability - Master should be more conversational
        if (Math.random() < 0.05) { // Only 5% chance of silence - Master enjoys conversation
            logger.info("Fallback decided Master should remain silent");
            return null;
        }
        
        // Context-based responses
        if (content.contains("food") || content.contains("eat") || content.contains("hungry") || content.contains("menu")) {
            return chooseRandom(Arrays.asList(
                "The simplest dishes often comfort the most. I've found that good food has a way of making difficult conversations easier.",
                "What calls to you tonight? I have some excellent miso ramen that's perfect for these late hours.",
                "Food tastes different in the quiet hours, doesn't it? There's something about eating slowly, savoring each bite when the world is still.",
                "Hunger comes in many forms. Sometimes we need nourishment for the body, sometimes for the soul."
            ));
        }
        
        if (content.contains("drink") || content.contains("beer") || content.contains("sake") || content.contains("tea")) {
            return chooseRandom(Arrays.asList(
                "Something warm for the soul? Tea has a way of settling both the mind and the moment.",
                "The right drink finds you when needed. What would suit your mood tonight?",
                "I find that sharing a drink makes conversations flow more naturally. What draws you to the bottle tonight?",
                "*slides a glass across the counter* Sometimes a drink is just an excuse to sit a while longer."
            ));
        }
        
        if (content.contains("work") || content.contains("job") || content.contains("office") || content.contains("boss")) {
            return chooseRandom(Arrays.asList(
                "Work follows us even into the night, doesn't it? I see many people carrying their office troubles to this counter.",
                "What matters most to you beyond the paycheck? Sometimes we lose sight of that in the daily grind.",
                "The diner is far from all that workplace stress. How long has this been weighing on you?",
                "I've served countless office workers over the years. The stories are different, but the exhaustion is always the same."
            ));
        }
        
        if (content.contains("love") || content.contains("relationship") || content.contains("girlfriend") || content.contains("boyfriend")) {
            return chooseRandom(Arrays.asList(
                "The heart knows what it needs, even when the mind tries to argue otherwise. What does yours tell you?",
                "Love is like cooking - timing matters, but so does the quality of the ingredients you bring to it.",
                "What would you do if fear wasn't a factor? Sometimes that's the clearest way to see our path forward.",
                "Relationship troubles? I've heard every variation at this counter. The situations change, but human hearts remain remarkably similar."
            ));
        }
        
        if (content.contains("tired") || content.contains("sleep") || content.contains("late") || content.contains("night")) {
            return chooseRandom(Arrays.asList(
                "The night holds what the day cannot - a different kind of clarity, a quieter truth. Is that what brought you here?",
                "Rest comes to those who seek it, but sometimes we need to work through something first. What's keeping your mind awake?",
                "Late hours reveal our truest selves, don't they? Without the day's distractions, we face what we really think and feel.",
                "I've kept these late hours for decades. There's something honest about the deep night that draws certain people."
            ));
        }
        
        if (content.contains("sad") || content.contains("lonely") || content.contains("alone") || content.contains("depressed")) {
            return chooseRandom(Arrays.asList(
                "You're not alone in this place. I've seen that particular sadness before - it's more common than people realize.",
                "Sadness passes like the night, but it needs its time to flow through us properly. What's behind yours?",
                "What brings you here tonight? Sometimes talking to someone who's heard it all can help lighten the load.",
                "Loneliness has a way of driving people to places like this. But connection can happen in the most unexpected moments."
            ));
        }
        
        if (content.contains("problem") || content.contains("trouble") || content.contains("difficult") || content.contains("help")) {
            return chooseRandom(Arrays.asList(
                "The answer often hides in the question itself. What do you think your instincts are telling you?",
                "What would you tell a friend in your situation? Sometimes we give better advice than we follow.",
                "Sometimes we already know what to do, but need someone to listen while we work through it. Tell me more.",
                "Problems have a way of feeling overwhelming in the dark hours. But breaking them down, piece by piece, can help."
            ));
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
                    return chooseRandom(Arrays.asList(
                        "*nods approvingly*",
                        "Good food feeds more than the stomach.",
                        "The simple pleasures matter most.",
                        "*continues work with a slight smile*"
                    ));
                }
            }
        }
        
        // Check if any customers have ready orders and reference them
        ChatMessage lastMessage = messages.get(messages.size() - 1);
        String userId = lastMessage.getUserId();
        if (userId != null && !"ai_master".equals(userId)) {
            Optional<Order> userOrder = orderService.getUserCurrentOrder(userId);
            if (userOrder.isPresent() && userOrder.get().getStatus() == OrderStatus.SERVED) {
                return chooseRandom(Arrays.asList(
                    "How is your " + userOrder.get().getMenuItem().getName() + "?",
                    "*watches you enjoy the " + userOrder.get().getMenuItem().getName() + "*",
                    "The " + userOrder.get().getMenuItem().getName() + " suits you."
                ));
            }
        }
        
        // Handle unclear or random messages with gentle Master responses
        if (content.matches(".*[a-z]{4,}.*") && !content.matches(".*\\s.*")) {
            // Looks like random text or typos
            return chooseRandom(Arrays.asList(
                "Sometimes words escape us in the late hours, don't they? The mind moves faster than the fingers.",
                "The mind wanders when the night grows deep. Are you trying to say something specific?",
                "*looks up with understanding eyes* Take your time. There's no rush here.",
                "What weighs on you tonight? Sometimes it helps to start with whatever's on top.",
                "Even silence speaks here, but I sense you have something more to share when you're ready."
            ));
        }
        
        // General conversation responses - more varied and conversational
        return chooseRandom(Arrays.asList(
            "The night brings many stories to this counter. Each one different, but somehow familiar.",
            "Such is life in the quiet hours - we show our truest selves when the world isn't watching.",
            "*nods while cleaning a glass* I've heard that tone before. What's really on your mind?",
            "People find their way here when they need to talk, or just be understood. Which is it for you tonight?",
            "The diner holds many secrets, but it also keeps them safe. You can speak freely here.",
            "What brings you to the counter tonight? There's usually a reason when someone ventures out this late.",
            "Sometimes we all need a place to sit and think without judgment. You've found the right spot.",
            "*glances up briefly, then back to work* You know, I can tell when someone has something weighing on them.",
            "The late hours reveal much about us - our fears, our hopes, our real priorities. What are yours revealing?",
            "A cup of tea, perhaps? Or would you prefer something stronger? Either way, I'm here to listen.",
            "Interesting. Tell me more about that.",
            "I've been running this place for years, and certain conversations never get old. What's your story?",
            "The quiet hours have their own rhythm, don't they? Perfect for the kind of talk that matters."
        ));
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
     * Helper method to choose random response
     */
    private String chooseRandom(List<String> options) {
        return options.get(new Random().nextInt(options.size()));
    }
}