package com.meshiya.scheduler;

import com.meshiya.dto.ChatMessage;
import com.meshiya.model.MessageType;
import com.meshiya.service.MasterService;
import com.meshiya.service.ChatService;
import com.meshiya.service.RoomService;
import com.meshiya.service.RedisService;
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
    
    @Autowired
    private RedisService redisService;
    
    // Message queue system for processing multiple messages
    private final Map<String, Queue<ChatMessage>> messageQueues = new HashMap<>();
    private final Map<String, AtomicBoolean> roomProcessingStatus = new HashMap<>();
    
    // Message buffers per room for batch processing (kept for compatibility)
    private final Map<String, Queue<ChatMessage>> messageBuffers = new HashMap<>();
    
    // Response timing control per room
    private final Map<String, LocalDateTime> lastResponses = new HashMap<>();
    private final Map<String, LocalDateTime> lastLlmCalls = new HashMap<>();
    
    // Enhanced conversation state management per room
    private final Map<String, Boolean> conversationActive = new HashMap<>();
    private final Map<String, LocalDateTime> conversationStartTime = new HashMap<>();
    private final Map<String, LocalDateTime> lastUserMessage = new HashMap<>();
    
    // Configuration - enhanced conversation management
    private static final int MIN_MESSAGES_BEFORE_ANALYSIS = 1; // Allow single messages to trigger
    private static final int MIN_SECONDS_BETWEEN_RESPONSES = 10; // Faster response - reduced from 30 to 10
    private static final int MIN_SECONDS_BETWEEN_LLM_CALLS = 15; // Faster LLM calls - reduced from 45 to 15
    
    // New conversation timing configurations
    private static final int CONVERSATION_TIMEOUT_SECONDS = 30; // Keep responding for 30 seconds after last master mention
    private static final int IDLE_MONITORING_MINUTES = 2; // Check for proactive engagement after 2min idle
    private static final int DENSITY_CHECK_MINUTES = 3; // Check message density over 3min window
    private static final int DENSITY_THRESHOLD_MESSAGES = 5; // 5 messages triggers conversation leadership
    
    // Default room for now (can be made configurable)
    private static final String DEFAULT_ROOM = "room1";
    
    /**
     * Listen to chat message events and add to buffer for analysis
     * Enhanced with multiple trigger keywords ('master', 'chef', 'bartender', 'waiter') 
     * and '/order' command detection for conversation state management
     */
    @EventListener
    public void handleChatMessageEvent(ChatMessageEvent event) {
        ChatMessage message = event.getChatMessage();
        logger.info("🎯 MasterResponseScheduler received ChatMessageEvent: type={}, userId={}, content={}", 
                   message.getType(), message.getUserId(), message.getContent());
        
        if (message.getType() == MessageType.CHAT_MESSAGE && 
            !"ai_master".equals(message.getUserId()) && 
            !"master".equals(message.getUserId())) {
            
            String roomId = message.getRoomId() != null ? message.getRoomId() : DEFAULT_ROOM;
            LocalDateTime now = LocalDateTime.now();
            
            // Update last user message time for this room
            lastUserMessage.put(roomId, now);
            
            // Check for trigger keywords or commands to activate conversation mode
            String content = message.getContent().toLowerCase();
            boolean hasOrderCommand = content.trim().startsWith("/order");
            boolean mentionsMaster = content.contains("master") || 
                                   content.contains("chef") || 
                                   content.contains("bartender") || 
                                   content.contains("waiter");
            
            if (mentionsMaster || hasOrderCommand) {
                if (hasOrderCommand) {
                    logger.info("Room {}: '/order' command detected in message: {}", roomId, message.getContent());
                } else {
                    logger.info("Room {}: trigger keyword detected in message: {}", roomId, message.getContent());
                }
                conversationActive.put(roomId, true);
                conversationStartTime.put(roomId, now);
            }
            
            // If in conversation mode, only extend if message mentions master or is an order
            if (conversationActive.getOrDefault(roomId, false)) {
                if (mentionsMaster || hasOrderCommand) {
                    conversationStartTime.put(roomId, now); // Reset timeout only on relevant messages
                    logger.debug("Room {}: Conversation extended due to master mention or order", roomId);
                } else {
                    logger.debug("Room {}: Message during conversation but no master mention - not extending timeout", roomId);
                }
            }
            
            // Add to queue for immediate processing (queue system only now)
            messageQueues.computeIfAbsent(roomId, k -> new ConcurrentLinkedQueue<>()).offer(message);
            
            // Process queue immediately if not already processing for this room
            processMessageQueue(roomId);
            
            logger.debug("Added message to queue for room {}: {} (queue size: {}, conversation active: {})", 
                        roomId, message.getContent(), messageQueues.get(roomId).size(), 
                        conversationActive.getOrDefault(roomId, false));
        }
    }
    
    /**
     * Enhanced scheduler with smart conversation triggers
     * Runs every 5 seconds to check for various engagement scenarios
     */
    @Scheduled(fixedRate = 10000) // Every 10 seconds to reduce log spam
    public void quickAnalysis() {
        if (messageBuffers.size() > 0) {
            logger.info("📊 MasterScheduler: Analyzing {} rooms with messages", messageBuffers.size());
        }
        
        LocalDateTime now = LocalDateTime.now();
        
        // Check all known rooms (both with messages and conversation states)
        Set<String> allRooms = new HashSet<>();
        allRooms.addAll(messageBuffers.keySet());
        allRooms.addAll(conversationActive.keySet());
        allRooms.addAll(lastUserMessage.keySet());
        
        if (allRooms.isEmpty()) {
            logger.debug("No active rooms to analyze");
            return;
        }
        
        // Process each room separately with enhanced logic
        for (String roomId : allRooms) {
            Queue<ChatMessage> buffer = messageBuffers.get(roomId);
            boolean isInConversation = conversationActive.getOrDefault(roomId, false);
            LocalDateTime lastUserMsgTime = lastUserMessage.get(roomId);
            LocalDateTime conversationStart = conversationStartTime.get(roomId);
            
            // Check conversation timeout (1 minute)
            if (isInConversation && conversationStart != null) {
                long secondsSinceConversationStart = java.time.Duration.between(conversationStart, now).toSeconds();
                if (secondsSinceConversationStart > CONVERSATION_TIMEOUT_SECONDS) {
                    logger.info("Room {}: Conversation timeout - exiting conversation mode after {} seconds", 
                               roomId, secondsSinceConversationStart);
                    conversationActive.put(roomId, false);
                    conversationStartTime.remove(roomId);
                    isInConversation = false;
                }
            }
            
            // Skip if no messages to process
            if (buffer == null || buffer.isEmpty()) {
                // Check for idle monitoring scenario (2 minutes no messages + users present)
                if (lastUserMsgTime != null) {
                    long minutesSinceLastMessage = java.time.Duration.between(lastUserMsgTime, now).toMinutes();
                    if (minutesSinceLastMessage >= IDLE_MONITORING_MINUTES) {
                        checkIdleEngagement(roomId, now);
                    }
                }
                continue;
            }
            
            // Process any queued messages for this room (primary system)
            processMessageQueue(roomId);
            
            // DISABLE BATCH PROCESSING - Queue system handles all messages now
            logger.debug("⏭️ Room {}: Batch processing disabled - using queue system only (buffer: {}, queue active)", 
                       roomId, buffer != null ? buffer.size() : 0);
        }
    }
    
    /**
     * Determines if master should respond based on enhanced triggers
     */
    private String determineTriggerReason(String roomId, Queue<ChatMessage> buffer, boolean isInConversation, LocalDateTime now) {
        // Basic rate limiting checks
        LocalDateTime lastResponseForRoom = lastResponses.getOrDefault(roomId, now.minusMinutes(10));
        LocalDateTime lastLlmCallForRoom = lastLlmCalls.getOrDefault(roomId, now.minusMinutes(10));
        
        long secondsSinceLastResponse = java.time.Duration.between(lastResponseForRoom, now).toSeconds();
        long secondsSinceLastLlmCall = java.time.Duration.between(lastLlmCallForRoom, now).toSeconds();
        
        if (secondsSinceLastResponse < MIN_SECONDS_BETWEEN_RESPONSES) {
            logger.info("Room {}: Rate limited - too soon since last response ({} seconds ago)", roomId, secondsSinceLastResponse);
            return null;
        }
        
        if (secondsSinceLastLlmCall < MIN_SECONDS_BETWEEN_LLM_CALLS) {
            logger.info("Room {}: Rate limited - too soon since last LLM call ({} seconds ago)", roomId, secondsSinceLastLlmCall);
            return null;
        }
        
        // Trigger 1: Active conversation mode (respond only to messages mentioning master or orders)
        if (isInConversation && buffer.size() >= MIN_MESSAGES_BEFORE_ANALYSIS) {
            // Check if any message in buffer mentions master or has order command
            for (ChatMessage msg : buffer) {
                String content = msg.getContent().toLowerCase();
                boolean hasOrderCommand = content.trim().startsWith("/order");
                boolean mentionsMaster = content.contains("master") || 
                                       content.contains("chef") || 
                                       content.contains("bartender") || 
                                       content.contains("waiter");
                
                if (mentionsMaster || hasOrderCommand) {
                    return "Active conversation mode - message mentions master or has order";
                }
            }
            logger.debug("Room {}: In conversation mode but no messages mention master", roomId);
        }
        
        // Trigger 2: 'master' keyword detection (handled in event listener, but ensure processing)
        if (buffer.size() >= MIN_MESSAGES_BEFORE_ANALYSIS) {
            // Check recent messages for master keyword
            for (ChatMessage msg : buffer) {
                if (msg.getContent().toLowerCase().contains("master")) {
                    return "'master' keyword detected in recent messages";
                }
            }
        }
        
        // Trigger 3: Message density check (DISABLED - was causing responses to all messages)
        // boolean densityTrigger = checkMessageDensity(roomId, now);
        // logger.info("🔍 Room {}: Density check result: {}", roomId, densityTrigger);
        // if (densityTrigger) {
        //     return "High message density detected - conversation leadership";
        // }
        logger.debug("Room {}: Message density trigger disabled to prevent responding to all messages", roomId);
        
        logger.debug("Room {}: No trigger conditions met - buffer size: {}, conversation: {}", 
                   roomId, buffer.size(), isInConversation);
        return null; // No trigger conditions met
    }
    
    /**
     * Checks for idle engagement scenario (2 minutes no messages + users present)
     */
    private void checkIdleEngagement(String roomId, LocalDateTime now) {
        try {
            // Check if there are users present using enhanced user presence check
            int activeUserCount = redisService.getActiveUserCount();
            boolean hasUsers = activeUserCount > 0;
            
            if (hasUsers) {
                LocalDateTime lastResponseForRoom = lastResponses.getOrDefault(roomId, now.minusMinutes(10));
                LocalDateTime lastLlmCallForRoom = lastLlmCalls.getOrDefault(roomId, now.minusMinutes(10));
                
                long secondsSinceLastResponse = java.time.Duration.between(lastResponseForRoom, now).toSeconds();
                long secondsSinceLastLlmCall = java.time.Duration.between(lastLlmCallForRoom, now).toSeconds();
                
                // Apply rate limiting for idle engagement too
                if (secondsSinceLastResponse >= MIN_SECONDS_BETWEEN_RESPONSES && 
                    secondsSinceLastLlmCall >= MIN_SECONDS_BETWEEN_LLM_CALLS) {
                    
                    logger.info("Room {}: Idle engagement - {} users present but no messages for 2+ minutes", roomId, activeUserCount);
                    // Create a synthetic message buffer for idle engagement
                    messageBuffers.computeIfAbsent(roomId, k -> new ConcurrentLinkedQueue<>());
                    analyzeAndRespondForIdleEngagement(roomId);
                }
            }
        } catch (Exception e) {
            logger.warn("Room {}: Error checking idle engagement", roomId, e);
        }
    }
    
    /**
     * Checks if message density exceeds threshold (5 messages in 3 minutes)
     */
    private boolean checkMessageDensity(String roomId, LocalDateTime now) {
        try {
            // Get recent messages from Room (not from global RedisService - that's a different key!)
            List<ChatMessage> recentMessages = roomService.getRoomMessages(roomId);
            logger.info("🔍 Room {}: Density check - retrieved {} total messages from Room", 
                       roomId, recentMessages != null ? recentMessages.size() : 0);
            
            if (recentMessages == null || recentMessages.isEmpty()) {
                logger.info("🔍 Room {}: No messages retrieved from Room", roomId);
                return false;
            }
            
            // Filter messages for this room and count within time window
            LocalDateTime threeMinutesAgo = now.minusMinutes(DENSITY_CHECK_MINUTES);
            int messageCount = 0;
            int totalChecked = 0;
            
            logger.info("🔍 Room {}: Checking messages since {} (3 minutes ago)", roomId, threeMinutesAgo);
            
            for (ChatMessage msg : recentMessages) {
                totalChecked++;
                String msgRoomId = msg.getRoomId() != null ? msg.getRoomId() : DEFAULT_ROOM;
                boolean timeMatch = msg.getTimestamp() != null && msg.getTimestamp().isAfter(threeMinutesAgo);
                boolean roomMatch = roomId.equals(msgRoomId);
                boolean notAI = !"ai_master".equals(msg.getUserId()) && !"master".equals(msg.getUserId()) && !"system".equals(msg.getUserId());
                
                logger.info("🔍 Message #{}: content='{}', timestamp={}, room={}, userId={}, timeMatch={}, roomMatch={}, notAI={}", 
                           totalChecked, msg.getContent(), msg.getTimestamp(), msgRoomId, msg.getUserId(), 
                           timeMatch, roomMatch, notAI);
                
                if (timeMatch && roomMatch && notAI) {
                    messageCount++;
                    logger.info("✅ Message #{} COUNTS: '{}'", messageCount, msg.getContent());
                }
            }
            
            logger.info("🔍 Room {}: Final count: {} valid messages in last {} minutes (threshold: {})", 
                       roomId, messageCount, DENSITY_CHECK_MINUTES, DENSITY_THRESHOLD_MESSAGES);
            return messageCount >= DENSITY_THRESHOLD_MESSAGES;
        } catch (Exception e) {
            logger.warn("Room {}: Error checking message density", roomId, e);
            return false;
        }
    }
    
    /**
     * Queue-based message processor for handling multiple messages per room
     */
    private void processMessageQueue(String roomId) {
        // Get or create processing status for this room
        AtomicBoolean roomProcessing = roomProcessingStatus.computeIfAbsent(roomId, k -> new AtomicBoolean(false));
        
        if (roomProcessing.get()) {
            logger.debug("Room {}: Already processing queue, message added to queue", roomId);
            return;
        }
        
        Queue<ChatMessage> queue = messageQueues.get(roomId);
        if (queue == null || queue.isEmpty()) {
            return;
        }
        
        roomProcessing.set(true);
        
        try {
            // Process messages one by one from the queue
            while (!queue.isEmpty()) {
                ChatMessage nextMessage = queue.poll();
                if (nextMessage != null) {
                    logger.info("Room {}: Processing queued message: {}", roomId, nextMessage.getContent());
                    processIndividualMessage(roomId, nextMessage);
                    
                    // Small delay between processing messages to prevent overwhelming
                    Thread.sleep(500);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Room {}: Message queue processing interrupted", roomId);
        } catch (Exception e) {
            logger.error("Room {}: Error processing message queue", roomId, e);
        } finally {
            roomProcessing.set(false);
        }
    }
    
    /**
     * Process individual message and generate response
     */
    private void processIndividualMessage(String roomId, ChatMessage message) {
        try {
            // Check if message should trigger master response
            String content = message.getContent().toLowerCase();
            boolean hasOrderCommand = content.trim().startsWith("/order");
            boolean mentionsMaster = content.contains("master") || 
                                   content.contains("chef") || 
                                   content.contains("bartender") || 
                                   content.contains("waiter");
            
            boolean isInConversation = conversationActive.getOrDefault(roomId, false);
            
            // Only process if message mentions master/order OR if we're in active conversation mode
            if (!mentionsMaster && !hasOrderCommand) {
                logger.debug("Room {}: Message '{}' does not mention master or have order - ignoring", roomId, message.getContent());
                return;
            }
            
            // Create message list with the individual message
            List<ChatMessage> messagesToAnalyze = new ArrayList<>();
            messagesToAnalyze.add(message);
            
            // Add some context from recent messages if available
            List<ChatMessage> recentMessages = roomService.getRoomMessages(roomId);
            if (recentMessages != null && recentMessages.size() > 1) {
                // Add last 3 messages for context (excluding the current one)
                int contextCount = Math.min(3, recentMessages.size() - 1);
                for (int i = recentMessages.size() - contextCount - 1; i < recentMessages.size() - 1; i++) {
                    if (i >= 0) {
                        messagesToAnalyze.add(0, recentMessages.get(i)); // Add at beginning for chronological order
                    }
                }
            }
            
            logger.info("Room {}: Processing message '{}' with {} context messages", 
                       roomId, message.getContent(), messagesToAnalyze.size() - 1);
            
            // Update LLM call timestamp
            lastLlmCalls.put(roomId, LocalDateTime.now());
            
            // Generate response
            Optional<String> response = masterService.generateResponse(messagesToAnalyze);
            
            if (response.isPresent()) {
                sendMasterResponse(response.get(), roomId);
                lastResponses.put(roomId, LocalDateTime.now());
                logger.info("Room {}: Generated response for message: {}", roomId, message.getContent());
                
                // Message processed successfully by queue system
            } else {
                logger.info("Room {}: No response generated for message: {}", roomId, message.getContent());
            }
            
        } catch (Exception e) {
            logger.error("Room {}: Error processing individual message: {}", roomId, message.getContent(), e);
        }
    }
    
    /**
     * Main analysis method - always sends to LLM for decision (Legacy method for batch processing)
     */
    private void analyzeAndRespond(String roomId) {
        // Get or create processing status for this room  
        AtomicBoolean roomProcessing = roomProcessingStatus.computeIfAbsent(roomId, k -> new AtomicBoolean(false));
        
        if (roomProcessing.get()) {
            logger.debug("Room {}: Already processing, skipping batch analysis", roomId);
            return;
        }
        
        roomProcessing.set(true);
        
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
            roomProcessing.set(false);
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
     * Special analysis for idle engagement scenarios
     */
    private void analyzeAndRespondForIdleEngagement(String roomId) {
        // Get or create processing status for this room
        AtomicBoolean roomProcessing = roomProcessingStatus.computeIfAbsent(roomId, k -> new AtomicBoolean(false));
        
        if (roomProcessing.get()) {
            logger.debug("Already processing, skipping idle engagement for room {}", roomId);
            return;
        }
        
        roomProcessing.set(true);
        
        try {
            // Get recent conversation context from Redis for idle engagement
            List<ChatMessage> recentMessages = chatService.getRecentMessages();
            List<ChatMessage> contextMessages = new ArrayList<>();
            
            // Filter for this room and get last few messages for context
            for (ChatMessage msg : recentMessages) {
                String msgRoomId = msg.getRoomId() != null ? msg.getRoomId() : DEFAULT_ROOM;
                if (roomId.equals(msgRoomId)) {
                    contextMessages.add(msg);
                }
            }
            
            // Limit to last 5 messages for context
            if (contextMessages.size() > 5) {
                contextMessages = contextMessages.subList(contextMessages.size() - 5, contextMessages.size());
            }
            
            logger.info("Room {}: Generating idle engagement response with {} context messages", roomId, contextMessages.size());
            
            // Update LLM call timestamp before making the call
            lastLlmCalls.put(roomId, LocalDateTime.now());
            
            // Generate proactive response
            Optional<String> response = masterService.generateResponse(contextMessages);
            
            if (response.isPresent()) {
                sendMasterResponse(response.get(), roomId);
                updateResponseState(roomId);
            } else {
                logger.info("Room {}: LLM decided not to engage proactively during idle period", roomId);
            }
            
        } catch (Exception e) {
            logger.error("Room {}: Error during idle engagement analysis", roomId, e);
        } finally {
            roomProcessing.set(false);
        }
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
        
        // If this was a response during active conversation, extend conversation time
        if (conversationActive.getOrDefault(roomId, false)) {
            conversationStartTime.put(roomId, LocalDateTime.now());
            logger.debug("Room {}: Conversation time extended due to master response", roomId);
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
        stats.put("roomProcessingStatus", roomProcessingStatus.size());
        stats.put("totalMessageQueues", messageQueues.size());
        stats.put("minSecondsBetweenResponses", MIN_SECONDS_BETWEEN_RESPONSES);
        stats.put("minSecondsBetweenLlmCalls", MIN_SECONDS_BETWEEN_LLM_CALLS);
        stats.put("totalRooms", messageBuffers.size());
        
        // Enhanced conversation features
        stats.put("conversationTimeoutSeconds", CONVERSATION_TIMEOUT_SECONDS);
        stats.put("idleMonitoringMinutes", IDLE_MONITORING_MINUTES);
        stats.put("densityCheckMinutes", DENSITY_CHECK_MINUTES);
        stats.put("densityThresholdMessages", DENSITY_THRESHOLD_MESSAGES);
        
        // Current conversation states
        stats.put("activeConversations", conversationActive.size());
        stats.put("conversationStates", new HashMap<>(conversationActive));
        
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