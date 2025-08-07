package com.meshiya.scheduler;

import com.meshiya.dto.ChatMessage;
import com.meshiya.model.MessageType;
import com.meshiya.service.BartenderService;
import com.meshiya.service.ChatService;
import com.meshiya.service.RoomService;
import com.meshiya.event.ChatMessageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class MasterResponseScheduler {
    
    private static final Logger logger = LoggerFactory.getLogger(MasterResponseScheduler.class);
    
    @Autowired
    private BartenderService bartenderService;
    
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    
    @Autowired
    private ChatService chatService;
    
    @Autowired  
    private RoomService roomService;
    
    // Message buffer for batch processing
    private final Queue<ChatMessage> messageBuffer = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    
    // Simple response timing control
    private LocalDateTime lastResponse = LocalDateTime.now();
    
    // Configuration - with rate limiting
    private static final int MIN_MESSAGES_BEFORE_ANALYSIS = 1; // Allow single messages to trigger
    private static final int MIN_SECONDS_BETWEEN_RESPONSES = 30; // Increased from 15 to 30 seconds
    private static final int MIN_SECONDS_BETWEEN_LLM_CALLS = 45; // Rate limit LLM calls independently
    
    // Track last LLM call time separately from last response time
    private LocalDateTime lastLlmCall = LocalDateTime.now().minusMinutes(1);
    
    /**
     * Listen to chat message events and add to buffer for analysis
     */
    @EventListener
    public void handleChatMessageEvent(ChatMessageEvent event) {
        ChatMessage message = event.getChatMessage();
        if (message.getType() == MessageType.CHAT_MESSAGE && 
            !"ai_master".equals(message.getUserId())) {
            
            messageBuffer.offer(message);
            
            logger.debug("Added message to buffer: {} (buffer size: {})", 
                        message.getContent(), messageBuffer.size());
        }
    }
    
    /**
     * Slower scheduler - runs every 15 seconds to check for conversations to analyze
     * Let the LLM decide whether Master should respond, with rate limiting
     */
    @Scheduled(fixedRate = 15000) // Every 15 seconds (reduced frequency)
    public void quickAnalysis() {
        logger.debug("Running quick analysis check (buffer size: {})", messageBuffer.size());
        
        if (messageBuffer.isEmpty()) {
            logger.debug("No messages in buffer");
            return;
        }
        
        // Basic minimum conditions
        if (messageBuffer.size() < MIN_MESSAGES_BEFORE_ANALYSIS) {
            logger.debug("Not enough messages for analysis (need {})", MIN_MESSAGES_BEFORE_ANALYSIS);
            return;
        }
        
        LocalDateTime now = LocalDateTime.now();
        long secondsSinceLastResponse = java.time.Duration.between(lastResponse, now).toSeconds();
        long secondsSinceLastLlmCall = java.time.Duration.between(lastLlmCall, now).toSeconds();
        
        if (secondsSinceLastResponse < MIN_SECONDS_BETWEEN_RESPONSES) {
            logger.debug("Too soon since last response ({} sec ago, need {})", 
                        secondsSinceLastResponse, MIN_SECONDS_BETWEEN_RESPONSES);
            return;
        }
        
        if (secondsSinceLastLlmCall < MIN_SECONDS_BETWEEN_LLM_CALLS) {
            logger.debug("Rate limiting: Too soon since last LLM call ({} sec ago, need {})", 
                        secondsSinceLastLlmCall, MIN_SECONDS_BETWEEN_LLM_CALLS);
            return;
        }
        
        logger.info("Starting conversation analysis with {} messages (last response: {}s ago, last LLM call: {}s ago)", 
                   messageBuffer.size(), secondsSinceLastResponse, secondsSinceLastLlmCall);
        analyzeAndRespond();
    }
    
    /**
     * Main analysis method - always sends to LLM for decision
     */
    private void analyzeAndRespond() {
        if (isProcessing.get()) {
            logger.debug("Already processing, skipping this cycle");
            return;
        }
        
        isProcessing.set(true);
        
        try {
            List<ChatMessage> messagesToAnalyze = extractMessagesForAnalysis();
            
            if (messagesToAnalyze.isEmpty()) {
                logger.debug("No messages to analyze after extraction");
                return;
            }
            
            logger.info("Sending {} messages to LLM for analysis and response decision", messagesToAnalyze.size());
            
            // Update LLM call timestamp before making the call
            lastLlmCall = LocalDateTime.now();
            
            // Always let LLM decide - it's smarter than our rules
            Optional<String> response = bartenderService.generateResponse(messagesToAnalyze);
            
            if (response.isPresent()) {
                sendMasterResponse(response.get());
                updateResponseState();
            } else {
                logger.info("LLM decided Master should not respond to current conversation");
            }
            
        } catch (Exception e) {
            logger.error("Error during conversation analysis", e);
        } finally {
            isProcessing.set(false);
        }
    }
    
    
    /**
     * Extracts messages from buffer for analysis
     */
    private List<ChatMessage> extractMessagesForAnalysis() {
        List<ChatMessage> messages = new ArrayList<>();
        
        // Convert queue to list for easier processing
        Iterator<ChatMessage> iterator = messageBuffer.iterator();
        List<ChatMessage> allMessages = new ArrayList<>();
        while (iterator.hasNext()) {
            allMessages.add(iterator.next());
        }
        
        // Take last 10 messages for context
        int startIndex = Math.max(0, allMessages.size() - 10);
        messages.addAll(allMessages.subList(startIndex, allMessages.size()));
        
        return messages;
    }
    
    /**
     * Gets recent messages for quick analysis
     */
    private List<ChatMessage> getRecentMessages(int count) {
        List<ChatMessage> recent = new ArrayList<>();
        Iterator<ChatMessage> iterator = messageBuffer.iterator();
        List<ChatMessage> allMessages = new ArrayList<>();
        while (iterator.hasNext()) {
            allMessages.add(iterator.next());
        }
        
        int startIndex = Math.max(0, allMessages.size() - count);
        recent.addAll(allMessages.subList(startIndex, allMessages.size()));
        
        return recent;
    }
    
    /**
     * Sends the Master's response to all clients
     */
    private void sendMasterResponse(String response) {
        ChatMessage masterMessage = new ChatMessage();
        masterMessage.setType(MessageType.AI_MESSAGE);
        masterMessage.setContent(response);
        masterMessage.setUserName("Master");
        masterMessage.setUserId("master");
        masterMessage.setRoomId("room1");
        masterMessage.setSeatId(null);
        masterMessage.setTimestamp(LocalDateTime.now());
        
        // Add to room and broadcast
        roomService.addMessageToRoom("room1", masterMessage);
        
        logger.info("Master responded: '{}'", response);
    }
    
    /**
     * Updates state after Master responds
     */
    private void updateResponseState() {
        lastResponse = LocalDateTime.now();
        
        // Clear processed messages from buffer
        messageBuffer.clear();
        
        logger.debug("Response state updated - buffer cleared");
    }
    
    /**
     * Get current conversation statistics (for monitoring)
     */
    public Map<String, Object> getStats() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> stats = new HashMap<>();
        stats.put("bufferSize", messageBuffer.size());
        stats.put("lastResponse", lastResponse);
        stats.put("lastLlmCall", lastLlmCall);
        stats.put("secondsSinceLastResponse", java.time.Duration.between(lastResponse, now).toSeconds());
        stats.put("secondsSinceLastLlmCall", java.time.Duration.between(lastLlmCall, now).toSeconds());
        stats.put("isProcessing", isProcessing.get());
        stats.put("minSecondsBetweenResponses", MIN_SECONDS_BETWEEN_RESPONSES);
        stats.put("minSecondsBetweenLlmCalls", MIN_SECONDS_BETWEEN_LLM_CALLS);
        return stats;
    }
}