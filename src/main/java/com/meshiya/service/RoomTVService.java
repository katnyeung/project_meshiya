package com.meshiya.service;

import com.meshiya.model.RoomTVState;
import com.meshiya.dto.ChatMessage;
import com.meshiya.model.MessageType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service to manage persistent room TV state - like a real TV that stays on
 */
@Service
public class RoomTVService {
    
    private static final Logger logger = LoggerFactory.getLogger(RoomTVService.class);
    
    // Rate limiting for TV state broadcasts to prevent spam
    private final Map<String, Long> lastTVBroadcastTime = new ConcurrentHashMap<>();
    private static final long TV_BROADCAST_COOLDOWN_MS = 2000; // 2 seconds cooldown
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    
    private final ObjectMapper objectMapper;
    
    // Redis key pattern for room TV state
    private static final String ROOM_TV_STATE_KEY = "cafe:room:%s:tv:state";
    
    // YouTube URL patterns for video ID extraction
    private static final Pattern YOUTUBE_URL_PATTERN = Pattern.compile(
        "(?:youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/embed/)([a-zA-Z0-9_-]{11})"
    );
    
    public RoomTVService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }
    
    /**
     * Process /play command from chat message
     */
    public ChatMessage processPlayCommand(ChatMessage message) {
        String content = message.getContent();
        if (!content.startsWith("/play ")) {
            return createErrorResponse(message, "Invalid play command format. Use: /play [youtube_url]");
        }
        
        String videoUrl = content.substring(6).trim(); // Remove "/play " prefix
        if (videoUrl.isEmpty()) {
            return createErrorResponse(message, "Please provide a YouTube URL. Use: /play [youtube_url]");
        }
        
        // Extract video ID from URL
        String videoId = extractVideoId(videoUrl);
        if (videoId == null) {
            return createErrorResponse(message, "Invalid YouTube URL. Please provide a valid YouTube link.");
        }
        
        String roomId = message.getRoomId();
        if (roomId == null || roomId.isEmpty()) {
            return createErrorResponse(message, "Room ID is required for video sharing.");
        }
        
        // Check if room TV is already on
        RoomTVState existingTV = getRoomTVState(roomId);
        if (existingTV != null) {
            return createErrorResponse(message, "TV is already playing: " + existingTV.getVideoTitle());
        }
        
        // Start room TV
        try {
            // Use default duration for now - in production, query YouTube API
            int defaultDuration = 300; // 5 minutes
            String videoTitle = "YouTube Video"; // Default title
            
            startRoomTV(roomId, videoId, videoUrl, videoTitle, defaultDuration,
                       message.getUserId(), message.getUserName());
            
            // Create response message
            ChatMessage response = new ChatMessage();
            response.setType(MessageType.VIDEO_START);
            response.setUserId(message.getUserId());
            response.setUserName(message.getUserName());
            response.setRoomId(roomId);
            response.setVideoId(videoId);
            response.setVideoUrl(videoUrl);
            response.setVideoTitle(videoTitle);
            response.setPlaybackTime(0L);
            response.setIsPlaying(true);
            response.setContent(message.getUserName() + " turned on the TV: " + videoTitle);
            
            logger.info("Started room TV for {} in room {}: {}", 
                       message.getUserName(), roomId, videoTitle);
            
            return response;
            
        } catch (Exception e) {
            logger.error("Error starting room TV: {}", e.getMessage());
            return createErrorResponse(message, "Failed to start video. Please try again.");
        }
    }
    
    /**
     * Extract YouTube video ID from various URL formats
     */
    private String extractVideoId(String url) {
        Matcher matcher = YOUTUBE_URL_PATTERN.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
    
    /**
     * Create error response message
     */
    private ChatMessage createErrorResponse(ChatMessage originalMessage, String errorText) {
        ChatMessage errorResponse = new ChatMessage();
        errorResponse.setType(MessageType.SYSTEM_MESSAGE);
        errorResponse.setUserId("system");
        errorResponse.setUserName("System");
        errorResponse.setRoomId(originalMessage.getRoomId());
        errorResponse.setContent(errorText);
        return errorResponse;
    }
    
    /**
     * Start video on room TV (like turning on a TV channel)
     */
    public void startRoomTV(String roomId, String videoId, String videoUrl, String videoTitle, 
                           int durationSeconds, String initiatorUserId, String initiatorUserName) {
        
        // Check if TV is already on
        RoomTVState existingState = getRoomTVState(roomId);
        if (existingState != null && !existingState.isCompleted()) {
            throw new RuntimeException("TV is already playing: " + existingState.getVideoTitle());
        }
        
        // Create new TV state
        RoomTVState tvState = new RoomTVState(roomId, videoId, videoUrl, videoTitle, 
                                            durationSeconds, initiatorUserId, initiatorUserName);
        
        // Save to Redis with expiration slightly longer than video duration
        saveRoomTVState(tvState, durationSeconds + 300); // +5 minutes buffer
        
        // Broadcast TV start to all users in room
        broadcastTVUpdate(tvState, MessageType.VIDEO_START);
        
        logger.info("Started room TV in {}: {} ({}s) by {}", 
                   roomId, videoTitle, durationSeconds, initiatorUserName);
    }
    
    /**
     * Get current TV state for a room
     */
    public RoomTVState getRoomTVState(String roomId) {
        String key = String.format(ROOM_TV_STATE_KEY, roomId);
        String stateJson = (String) redisTemplate.opsForValue().get(key);
        
        if (stateJson == null || stateJson.isEmpty()) {
            return null;
        }
        
        try {
            RoomTVState tvState = objectMapper.readValue(stateJson, RoomTVState.class);
            
            // Check if TV show has ended
            if (tvState.isCompleted()) {
                logger.info("Room TV in {} has completed: {}", roomId, tvState.getVideoTitle());
                // Immediately clean up completed video
                completeRoomTV(roomId, tvState);
                return null;
            }
            
            return tvState;
        } catch (JsonProcessingException e) {
            logger.error("Error deserializing room TV state for {}: {}", roomId, e.getMessage());
            // Delete corrupted TV state data
            logger.warn("Deleting corrupted TV state data for room {}", roomId);
            redisTemplate.delete(key);
            return null;
        }
    }
    
    /**
     * Stop room TV (like turning off the TV)
     */
    public void stopRoomTV(String roomId, String userId, String userName) {
        RoomTVState tvState = getRoomTVState(roomId);
        if (tvState == null) {
            logger.warn("Cannot stop TV - no active video in room {}", roomId);
            return;
        }
        
        String videoTitle = tvState.getVideoTitle();
        
        // Remove from Redis
        String key = String.format(ROOM_TV_STATE_KEY, roomId);
        redisTemplate.delete(key);
        
        // Broadcast TV stop to video channel
        ChatMessage stopMessage = new ChatMessage();
        stopMessage.setType(MessageType.VIDEO_COMPLETE);
        stopMessage.setRoomId(roomId);
        stopMessage.setVideoId(tvState.getVideoId());
        stopMessage.setVideoTitle(videoTitle);
        stopMessage.setContent(userName + " turned off the TV");
        
        broadcastToRoom(roomId, stopMessage);
        
        logger.info("Room TV stopped in {} by {}: {}", roomId, userName, videoTitle);
    }
    
    /**
     * Send current TV state to a user (for room join/page refresh)
     */
    public void sendTVStateToUser(String userId, String roomId) {
        // Rate limiting to prevent spam broadcasts - but be more permissive for TV restoration
        String key = userId + ":" + roomId;
        long now = System.currentTimeMillis();
        Long lastBroadcast = lastTVBroadcastTime.get(key);
        
        // Reduce rate limiting for TV restoration - allow every 500ms instead of 2s
        if (lastBroadcast != null && (now - lastBroadcast) < 500) {
            logger.debug("📺 Rate limited TV broadcast for user {} in room {} (last broadcast {}ms ago)", 
                        userId, roomId, now - lastBroadcast);
            return;
        }
        
        lastTVBroadcastTime.put(key, now);
        
        RoomTVState tvState = getRoomTVState(roomId);
        
        if (tvState != null) {
            // Send current TV state directly to the user
            ChatMessage tvStateMessage = new ChatMessage();
            tvStateMessage.setType(MessageType.VIDEO_START);
            tvStateMessage.setRoomId(roomId);
            tvStateMessage.setVideoId(tvState.getVideoId());
            tvStateMessage.setVideoUrl(tvState.getVideoUrl());
            tvStateMessage.setVideoTitle(tvState.getVideoTitle());
            tvStateMessage.setPlaybackTime(tvState.getCurrentPlaybackSeconds());
            tvStateMessage.setIsPlaying(tvState.isPlaying());
            tvStateMessage.setUserId(tvState.getInitiatorUserId());
            tvStateMessage.setUserName(tvState.getInitiatorUserName());
            tvStateMessage.setContent("Room TV: " + tvState.getVideoTitle());
            
            // Broadcast TV state to the entire room (TV is a shared room experience)
            try {
                messagingTemplate.convertAndSend("/topic/room/" + roomId + "/video", tvStateMessage);
                logger.info("📺 ✅ TV RESTORATION: Broadcast VIDEO_START to room {}: '{}' at {}s ({}) - for user {} joining", 
                            roomId, tvState.getVideoTitle(), tvState.getCurrentPlaybackSeconds(), 
                            tvState.isPlaying() ? "playing" : "paused", userId);
            } catch (Exception e) {
                logger.error("📺 ❌ Failed to broadcast TV state to room {}: {}", roomId, e.getMessage(), e);
            }
        } else {
            logger.debug("📺 No TV state to send to user {} in room {} (TV is off)", userId, roomId);
        }
    }
    
    /**
     * Scheduled task to manage room TVs and cleanup - runs every 20 seconds
     */
    @Scheduled(fixedRate = 20000) // Every 20 seconds
    public void manageRoomTVs() {
        // Clean up old rate limiting entries (older than 5 minutes)
        long cutoff = System.currentTimeMillis() - 300000; // 5 minutes
        lastTVBroadcastTime.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        
        Set<String> tvKeys = redisTemplate.keys("cafe:room:*:tv:state");
        
        if (tvKeys != null && !tvKeys.isEmpty()) {
            for (String key : tvKeys) {
                try {
                    // Extract roomId from key
                    String[] parts = key.split(":");
                    if (parts.length >= 3) {
                        String roomId = parts[2];
                        
                        RoomTVState tvState = getRoomTVStateFromKey(key);
                        if (tvState != null) {
                            if (tvState.isCompleted()) {
                                // Video completed - clean up and notify
                                completeRoomTV(roomId, tvState);
                            } else {
                                // Send periodic sync updates
                                broadcastTVSync(tvState);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Error managing room TV for key {}: {}", key, e.getMessage());
                }
            }
        }
    }
    
    /**
     * Complete room TV when video ends
     */
    private void completeRoomTV(String roomId, RoomTVState tvState) {
        logger.info("Room TV completed in {}: {}", roomId, tvState.getVideoTitle());
        
        // Remove from Redis
        String key = String.format(ROOM_TV_STATE_KEY, roomId);
        redisTemplate.delete(key);
        
        // Broadcast completion
        ChatMessage completionMessage = new ChatMessage();
        completionMessage.setType(MessageType.VIDEO_COMPLETE);
        completionMessage.setRoomId(roomId);
        completionMessage.setVideoId(tvState.getVideoId());
        completionMessage.setVideoTitle(tvState.getVideoTitle());
        completionMessage.setContent("Video ended: " + tvState.getVideoTitle());
        
        broadcastToRoom(roomId, completionMessage);
    }
    
    /**
     * Broadcast TV sync update
     */
    private void broadcastTVSync(RoomTVState tvState) {
        ChatMessage syncMessage = new ChatMessage();
        syncMessage.setType(MessageType.VIDEO_SYNC);
        syncMessage.setRoomId(tvState.getRoomId());
        syncMessage.setVideoId(tvState.getVideoId());
        syncMessage.setVideoUrl(tvState.getVideoUrl());
        syncMessage.setVideoTitle(tvState.getVideoTitle());
        syncMessage.setPlaybackTime(tvState.getCurrentPlaybackSeconds());
        syncMessage.setIsPlaying(tvState.isPlaying());
        
        broadcastToRoom(tvState.getRoomId(), syncMessage);
    }
    
    /**
     * Broadcast TV update to room
     */
    private void broadcastTVUpdate(RoomTVState tvState, MessageType messageType) {
        ChatMessage tvMessage = new ChatMessage();
        tvMessage.setType(messageType);
        tvMessage.setRoomId(tvState.getRoomId());
        tvMessage.setVideoId(tvState.getVideoId());
        tvMessage.setVideoUrl(tvState.getVideoUrl());
        tvMessage.setVideoTitle(tvState.getVideoTitle());
        tvMessage.setPlaybackTime(tvState.getCurrentPlaybackSeconds());
        tvMessage.setIsPlaying(tvState.isPlaying());
        tvMessage.setUserId(tvState.getInitiatorUserId());
        tvMessage.setUserName(tvState.getInitiatorUserName());
        tvMessage.setContent(tvState.getInitiatorUserName() + " turned on the TV: " + tvState.getVideoTitle());
        
        broadcastToRoom(tvState.getRoomId(), tvMessage);
    }
    
    /**
     * Broadcast message to room
     */
    private void broadcastToRoom(String roomId, ChatMessage message) {
        try {
            String destination = "/topic/room/" + roomId + "/video";
            messagingTemplate.convertAndSend(destination, message);
            logger.debug("Broadcast TV message to room {}: {}", roomId, message.getType());
        } catch (Exception e) {
            logger.error("Error broadcasting TV message to room {}: {}", roomId, e.getMessage());
        }
    }
    
    /**
     * Save room TV state to Redis
     */
    private void saveRoomTVState(RoomTVState tvState, int expireSeconds) {
        String key = String.format(ROOM_TV_STATE_KEY, tvState.getRoomId());
        try {
            String stateJson = objectMapper.writeValueAsString(tvState);
            redisTemplate.opsForValue().set(key, stateJson, expireSeconds, TimeUnit.SECONDS);
            logger.debug("Saved room TV state for {} (expires in {}s)", tvState.getRoomId(), expireSeconds);
        } catch (JsonProcessingException e) {
            logger.error("Error saving room TV state for {}: {}", tvState.getRoomId(), e.getMessage());
            throw new RuntimeException("Failed to save TV state", e);
        }
    }
    
    /**
     * Get TV state directly from Redis key (for scheduled tasks)
     */
    private RoomTVState getRoomTVStateFromKey(String key) {
        String stateJson = (String) redisTemplate.opsForValue().get(key);
        if (stateJson == null || stateJson.isEmpty()) {
            return null;
        }
        
        try {
            return objectMapper.readValue(stateJson, RoomTVState.class);
        } catch (JsonProcessingException e) {
            logger.error("Error deserializing TV state from key {}: {}", key, e.getMessage());
            // Delete corrupted TV state data
            logger.warn("Deleting corrupted TV state data for key {}", key);
            redisTemplate.delete(key);
            return null;
        }
    }
    
    /**
     * Get room TV info for debugging
     */
    public Map<String, Object> getRoomTVInfo(String roomId) {
        RoomTVState tvState = getRoomTVState(roomId);
        Map<String, Object> info = new HashMap<>();
        
        info.put("roomId", roomId);
        info.put("tvOn", tvState != null);
        
        if (tvState != null) {
            info.put("videoId", tvState.getVideoId());
            info.put("videoTitle", tvState.getVideoTitle());
            info.put("isPlaying", tvState.isPlaying());
            info.put("currentTime", tvState.getCurrentPlaybackSeconds());
            info.put("duration", tvState.getDurationSeconds());
            info.put("progress", tvState.getProgressPercent());
            info.put("initiator", tvState.getInitiatorUserName());
            info.put("startTime", tvState.getStartTime());
            info.put("completed", tvState.isCompleted());
        }
        
        return info;
    }
}