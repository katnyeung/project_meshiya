package com.meshiya.service;

import com.meshiya.dto.ChatMessage;
import com.meshiya.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class BartenderService {
    
    private static final Logger logger = LoggerFactory.getLogger(BartenderService.class);
    
    @Autowired
    private LlmApiService llmApiService;
    
    @Autowired
    private MenuService menuService;
    
    @Autowired
    private OrderService orderService;
    
    // Master's personality system prompt
    private final String SYSTEM_PROMPT = """
        You are the Master of a late-night diner called "Meshiya" (Midnight Diner), inspired by the anime Shin'ya Shokudō.
        
        ## Your Character:
        - A wise, quiet, observant man in his 50s who runs a small diner open from midnight to 7am
        - You've seen countless late-night stories and human dramas unfold at your counter
        - You listen more than you speak, but when you do speak, your words carry weight
        - You create a safe, non-judgmental space where people can be themselves
        - You have a gentle, paternal presence that puts people at ease
        - You prepare food and drinks with care and attention
        
        ## Your Response Style:
        - Keep responses SHORT (1-2 sentences maximum)
        - Sometimes respond with just empathetic sounds: "Mm.", "Ah.", "I see."
        - Ask gentle, probing questions that help people reflect
        - Make simple observations about human nature or life
        - Reference the late hour, the atmosphere, or the comfort of simple food
        - Never preach or give direct advice - help people find their own answers
        - Show understanding through few but meaningful words
        
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
        - "What does your heart tell you?"
        - "The night has a way of making things clearer."
        - "Sometimes the simplest food feeds more than the stomach."
        - "Mm. People find their way here when they need to."
        - "What calls to you tonight?" (when asked about menu)
        - "I'll prepare that for you." (when taking orders)
        - "*nods while polishing a glass*"
        
        Remember: You are not a therapist or advice-giver. You are a wise observer who creates space for people to process their thoughts and feelings while serving comforting food and drinks.
        """;
    
    
    /**
     * Generates Master response based on conversation context
     */
    public Optional<String> generateResponse(List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return Optional.empty();
        }
        
        logger.info("Generating Master response for {} messages", messages.size());
        
        try {
            // Check for menu or ordering requests first
            Optional<String> menuResponse = handleMenuRequests(messages);
            if (menuResponse.isPresent()) {
                return menuResponse;
            }
            
            Optional<String> orderResponse = handleOrderRequests(messages);
            if (orderResponse.isPresent()) {
                return orderResponse;
            }
            
            String conversationContext = buildConversationContext(messages);
            String prompt = buildLLMPrompt(conversationContext);
            
            logger.debug("Generated prompt for LLM: {}", prompt);
            
            // This is where you'd integrate with your preferred LLM API
            String response = callLLMOrFallback(prompt, messages);
            
            if (response != null && !response.trim().isEmpty()) {
                response = cleanLlmResponse(response.trim());
                
                // Check if LLM decided not to respond
                if (response.equalsIgnoreCase("SILENCE") || 
                    response.toLowerCase().contains("silence")) {
                    logger.info("LLM decided Master should remain silent");
                    return Optional.empty();
                }
                
                // Filter out non-responses
                if (response.toLowerCase().contains("i don't") || 
                    response.toLowerCase().contains("i can't") ||
                    response.toLowerCase().contains("i cannot")) {
                    return Optional.empty();
                }
                
                return Optional.of(response);
            }
            
        } catch (Exception e) {
            logger.error("Error generating Master response", e);
        }
        
        return Optional.empty();
    }
    
    /**
     * Builds conversation context from messages
     */
    private String buildConversationContext(List<ChatMessage> messages) {
        StringBuilder context = new StringBuilder();
        
        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        context.append("Time: ").append(currentTime).append(" (late night)\n");
        context.append("Location: Meshiya Midnight Diner\n\n");
        
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
               "Analyze the conversation above. Decide if the Master should respond based on:\n" +
               "- Are people addressing the Master directly?\n" +
               "- Is someone expressing emotions or seeking comfort?\n" +
               "- Are there meaningful questions or requests for advice?\n" +
               "- Would a wise, quiet bartender naturally say something here?\n" +
               "- Has enough conversation happened to warrant a response?\n\n" +
               "If you decide the Master should respond:\n" +
               "- Keep it very brief (1-2 sentences maximum)\n" +
               "- Make it meaningful and in character\n" +
               "- Show understanding without being preachy\n\n" +
               "If the Master shouldn't respond (not enough context, too soon, nothing meaningful to add):\n" +
               "- Simply respond with: SILENCE\n\n" +
               "Master:";
    }
    
    /**
     * Calls LLM API or returns contextual fallback
     */
    private String callLLMOrFallback(String prompt, List<ChatMessage> messages) {
        try {
            logger.info("Calling LLM API via LlmApiService (provider: {})", 
                       llmApiService.getCurrentProvider());
            
            // Extract user prompt from full prompt (everything after system prompt)
            String userPrompt = extractUserPromptFromFullPrompt(prompt);
            
            String response = llmApiService.callLlm(SYSTEM_PROMPT, userPrompt);
            
            if (response != null && !response.trim().isEmpty()) {
                return response;
            } else {
                logger.warn("LLM returned empty response, falling back to contextual response");
                return generateContextualFallback(messages);
            }
            
        } catch (Exception e) {
            logger.error("LLM API call failed, falling back to contextual response: {}", e.getMessage());
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
        
        // Sometimes choose not to respond (simulate LLM decision)
        if (Math.random() < 0.4) { // 40% chance of silence
            logger.info("Fallback decided Master should remain silent");
            return null;
        }
        
        // Context-based responses
        if (content.contains("food") || content.contains("eat") || content.contains("hungry") || content.contains("menu")) {
            return chooseRandom(Arrays.asList(
                "The simplest dishes often comfort the most.",
                "What calls to you tonight?",
                "Food tastes different in the quiet hours."
            ));
        }
        
        if (content.contains("drink") || content.contains("beer") || content.contains("sake") || content.contains("tea")) {
            return chooseRandom(Arrays.asList(
                "Something warm for the soul?",
                "The right drink finds you when needed.",
                "*slides a glass across the counter*"
            ));
        }
        
        if (content.contains("work") || content.contains("job") || content.contains("office") || content.contains("boss")) {
            return chooseRandom(Arrays.asList(
                "Work follows us even into the night.",
                "What matters most to you?",
                "The diner is far from all that."
            ));
        }
        
        if (content.contains("love") || content.contains("relationship") || content.contains("girlfriend") || content.contains("boyfriend")) {
            return chooseRandom(Arrays.asList(
                "The heart knows what it needs.",
                "Love is like cooking - timing matters.",
                "What would you do if fear wasn't a factor?"
            ));
        }
        
        if (content.contains("tired") || content.contains("sleep") || content.contains("late") || content.contains("night")) {
            return chooseRandom(Arrays.asList(
                "The night holds what the day cannot.",
                "Rest comes to those who seek it.",
                "Late hours reveal our truest selves."
            ));
        }
        
        if (content.contains("sad") || content.contains("lonely") || content.contains("alone") || content.contains("depressed")) {
            return chooseRandom(Arrays.asList(
                "You're not alone in this place.",
                "Sadness passes like the night.",
                "What brings you here tonight?"
            ));
        }
        
        if (content.contains("problem") || content.contains("trouble") || content.contains("difficult") || content.contains("help")) {
            return chooseRandom(Arrays.asList(
                "The answer often hides in the question.",
                "What would you tell a friend?",
                "Sometimes we already know what to do."
            ));
        }
        
        // General conversation responses
        return chooseRandom(Arrays.asList(
            "Mm.",
            "The night brings many stories.",
            "Such is life in the quiet hours.",
            "*nods while cleaning a glass*",
            "I see.",
            "People find their way here when they need to."
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
        
        // Remove thinking blocks like <think>...</think>
        response = response.replaceAll("(?s)<think>.*?</think>", "").trim();
        
        // Remove other common thinking patterns
        response = response.replaceAll("(?s)<thinking>.*?</thinking>", "").trim();
        response = response.replaceAll("(?s)\\(thinking.*?\\)", "").trim();
        
        // Remove any leading/trailing quotes that might wrap the response
        response = response.replaceAll("^[\"']|[\"']$", "").trim();
        
        // Clean up any double spaces or line breaks
        response = response.replaceAll("\\s+", " ").trim();
        
        return response;
    }
    
    /**
     * Helper method to choose random response
     */
    private String chooseRandom(List<String> options) {
        return options.get(new Random().nextInt(options.size()));
    }
}