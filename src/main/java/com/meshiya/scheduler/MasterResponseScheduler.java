package com.meshiya.scheduler;

import com.meshiya.dto.ChatMessage;
import com.meshiya.model.MessageType;
import com.meshiya.service.MasterService;
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
    private MasterService masterService;
    
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    
    @Autowired
    private ChatService chatService;
    
    @Autowired  
    private RoomService roomService;
    
    // Message buffers per room for batch processing
    private final Map<String, Queue<ChatMessage>> messageBuffers = new HashMap<>();
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    
    // Response timing control per room
    private final Map<String, LocalDateTime> lastResponses = new HashMap<>();
    private final Map<String, LocalDateTime> lastLlmCalls = new HashMap<>();
    
    // Configuration - with rate limiting
    private static final int MIN_MESSAGES_BEFORE_ANALYSIS = 1; // Allow single messages to trigger
    private static final int MIN_SECONDS_BETWEEN_RESPONSES = 30; // Increased from 15 to 30 seconds
    private static final int MIN_SECONDS_BETWEEN_LLM_CALLS = 45; // Rate limit LLM calls independently
    
    // Default room for now (can be made configurable)
    private static final String DEFAULT_ROOM = "room1";
    
    /**
     * Listen to chat message events and add to buffer for analysis
     */
    @EventListener
    public void handleChatMessageEvent(ChatMessageEvent event) {
        ChatMessage message = event.getChatMessage();
        if (message.getType() == MessageType.CHAT_MESSAGE && 
            !"ai_master".equals(message.getUserId()) && 
            !"master".equals(message.getUserId())) {
            
            String roomId = message.getRoomId() != null ? message.getRoomId() : DEFAULT_ROOM;
            
            // Get or create buffer for this room
            messageBuffers.computeIfAbsent(roomId, k -> new ConcurrentLinkedQueue<>()).offer(message);
            
            logger.debug("Added message to buffer for room {}: {} (buffer size: {})", 
                        roomId, message.getContent(), messageBuffers.get(roomId).size());
        }
    }
    
    /**
     * Slower scheduler - runs every 15 seconds to check for conversations to analyze
     * Let the LLM decide whether Master should respond, with rate limiting
     */
    @Scheduled(fixedRate = 15000) // Every 15 seconds (reduced frequency)
    public void quickAnalysis() {
        logger.debug("Running quick analysis check for {} rooms", messageBuffers.size());
        
        if (messageBuffers.isEmpty()) {
            logger.debug("No messages in any room buffers");
            return;
        }
        
        // Process each room separately
        for (Map.Entry<String, Queue<ChatMessage>> entry : messageBuffers.entrySet()) {
            String roomId = entry.getKey();
            Queue<ChatMessage> buffer = entry.getValue();
            
            if (buffer.isEmpty()) {
                continue;
            }
            
            // Basic minimum conditions per room
            if (buffer.size() < MIN_MESSAGES_BEFORE_ANALYSIS) {
                logger.debug("Room {}: Not enough messages for analysis (need {})", roomId, MIN_MESSAGES_BEFORE_ANALYSIS);
                continue;
            }
            
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime lastResponseForRoom = lastResponses.getOrDefault(roomId, now.minusMinutes(10));
            LocalDateTime lastLlmCallForRoom = lastLlmCalls.getOrDefault(roomId, now.minusMinutes(10));
            
            long secondsSinceLastResponse = java.time.Duration.between(lastResponseForRoom, now).toSeconds();
            long secondsSinceLastLlmCall = java.time.Duration.between(lastLlmCallForRoom, now).toSeconds();
            
            if (secondsSinceLastResponse < MIN_SECONDS_BETWEEN_RESPONSES) {
                logger.debug("Room {}: Too soon since last response ({} sec ago, need {})", 
                            roomId, secondsSinceLastResponse, MIN_SECONDS_BETWEEN_RESPONSES);
                continue;
            }
            
            if (secondsSinceLastLlmCall < MIN_SECONDS_BETWEEN_LLM_CALLS) {
                logger.debug("Room {}: Rate limiting: Too soon since last LLM call ({} sec ago, need {})", 
                            roomId, secondsSinceLastLlmCall, MIN_SECONDS_BETWEEN_LLM_CALLS);
                continue;
            }
            
            logger.info("Room {}: Starting conversation analysis with {} messages (last response: {}s ago, last LLM call: {}s ago)", 
                       roomId, buffer.size(), secondsSinceLastResponse, secondsSinceLastLlmCall);
            analyzeAndRespond(roomId);
        }
    }
    
    /**
     * Main analysis method - always sends to LLM for decision
     */
    private void analyzeAndRespond(String roomId) {
        if (isProcessing.get()) {
            logger.debug("Already processing, skipping this cycle");
            return;
        }
        
        isProcessing.set(true);
        
        try {
            List<ChatMessage> messagesToAnalyze = extractMessagesForAnalysis(roomId);
            
            if (messagesToAnalyze.isEmpty()) {
                logger.debug("Room {}: No messages to analyze after extraction", roomId);
                return;
            }
            
            logger.info("Room {}: Sending {} messages to LLM for analysis and response decision", roomId, messagesToAnalyze.size());
            
            // Update LLM call timestamp before making the call
            lastLlmCalls.put(roomId, LocalDateTime.now());
            
            // Always let LLM decide - it's smarter than our rules
            Optional<String> response = masterService.generateResponse(messagesToAnalyze);
            
            if (response.isPresent()) {
                sendMasterResponse(response.get(), roomId);
                updateResponseState(roomId);
            } else {
                logger.info("Room {}: LLM decided Master should not respond to current conversation", roomId);
            }
            
        } catch (Exception e) {
            logger.error("Room {}: Error during conversation analysis", roomId, e);
        } finally {
            isProcessing.set(false);
        }
    }
    
    
    /**
     * Extracts messages from buffer for analysis
     */
    private List<ChatMessage> extractMessagesForAnalysis(String roomId) {
        List<ChatMessage> messages = new ArrayList<>();
        
        Queue<ChatMessage> roomBuffer = messageBuffers.get(roomId);
        if (roomBuffer == null || roomBuffer.isEmpty()) {
            return messages;
        }
        
        // Convert queue to list for easier processing
        Iterator<ChatMessage> iterator = roomBuffer.iterator();
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
     * Sends the Master's response to clients in the specific room
     */
    private void sendMasterResponse(String response, String roomId) {
        ChatMessage masterMessage = new ChatMessage();
        masterMessage.setType(MessageType.AI_MESSAGE);
        masterMessage.setContent(response);
        masterMessage.setUserName("Master");
        masterMessage.setUserId("master");
        masterMessage.setRoomId(roomId);
        masterMessage.setSeatId(null);
        masterMessage.setTimestamp(LocalDateTime.now());
        
        // Add to room and broadcast
        roomService.addMessageToRoom(roomId, masterMessage);
        
        logger.info("Master responded: '{}'", response);
    }
    
    /**
     * Updates state after Master responds
     */
    private void updateResponseState(String roomId) {
        lastResponses.put(roomId, LocalDateTime.now());
        
        // Clear processed messages from buffer for this room
        Queue<ChatMessage> roomBuffer = messageBuffers.get(roomId);
        if (roomBuffer != null) {
            roomBuffer.clear();
        }
        
        logger.debug("Room {}: Response state updated - buffer cleared", roomId);
    }
    
    /**
     * Get current conversation statistics (for monitoring)
     */
    public Map<String, Object> getStats() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> stats = new HashMap<>();
        
        // Overall stats
        stats.put("isProcessing", isProcessing.get());
        stats.put("minSecondsBetweenResponses", MIN_SECONDS_BETWEEN_RESPONSES);
        stats.put("minSecondsBetweenLlmCalls", MIN_SECONDS_BETWEEN_LLM_CALLS);
        stats.put("totalRooms", messageBuffers.size());
        
        // Per-room stats
        Map<String, Object> roomStats = new HashMap<>();
        int totalBufferSize = 0;
        
        for (Map.Entry<String, Queue<ChatMessage>> entry : messageBuffers.entrySet()) {
            String roomId = entry.getKey();
            Queue<ChatMessage> buffer = entry.getValue();
            int bufferSize = buffer.size();
            totalBufferSize += bufferSize;
            
            LocalDateTime lastResponseForRoom = lastResponses.getOrDefault(roomId, now.minusMinutes(10));
            LocalDateTime lastLlmCallForRoom = lastLlmCalls.getOrDefault(roomId, now.minusMinutes(10));
            
            Map<String, Object> roomInfo = new HashMap<>();
            roomInfo.put("bufferSize", bufferSize);
            roomInfo.put("lastResponse", lastResponseForRoom);
            roomInfo.put("lastLlmCall", lastLlmCallForRoom);
            roomInfo.put("secondsSinceLastResponse", java.time.Duration.between(lastResponseForRoom, now).toSeconds());
            roomInfo.put("secondsSinceLastLlmCall", java.time.Duration.between(lastLlmCallForRoom, now).toSeconds());
            
            roomStats.put(roomId, roomInfo);
        }
        
        stats.put("totalBufferSize", totalBufferSize);
        stats.put("roomStats", roomStats);
        
        return stats;
    }
}