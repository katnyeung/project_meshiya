package com.meshiya.service;

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

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class AvatarStateService {
    
    private static final Logger logger = LoggerFactory.getLogger(AvatarStateService.class);
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    
    @Autowired
    private UserService userService;
    
    @Autowired
    private SeatService seatService;
    
    @Autowired
    private ConsumableService consumableService;
    
    private final ObjectMapper objectMapper;
    
    public AvatarStateService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }
    
    // Avatar states
    public enum AvatarState {
        NORMAL, IDLE, CHATTING, EATING
    }
    
    // Redis key patterns
    private static final String AVATAR_STATE_KEY_PATTERN = "avatar:state:%s"; // userId
    private static final String LAST_ACTIVITY_KEY_PATTERN = "avatar:activity:%s"; // userId
    
    // Local state tracking for timers
    private final Map<String, LocalDateTime> userLastActivity = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> chattingStateTimers = new ConcurrentHashMap<>();
    
    /**
     * Record user activity (chatting, ordering, etc.)
     */
    public void recordUserActivity(String userId, String roomId, Integer seatId) {
        LocalDateTime now = LocalDateTime.now();
        userLastActivity.put(userId, now);
        
        // Save to Redis for persistence
        try {
            String key = String.format(LAST_ACTIVITY_KEY_PATTERN, userId);
            redisTemplate.opsForValue().set(key, now.toString(), 10, TimeUnit.MINUTES);
            logger.debug("Recorded activity for user {} at {}", userId, now);
        } catch (Exception e) {
            logger.error("Error recording user activity for {}: {}", userId, e.getMessage());
        }
    }
    
    /**
     * Trigger chatting state when user sends a message
     */
    public void triggerChattingState(String userId, String roomId, Integer seatId) {
        logger.info("triggerChattingState called: userId={}, roomId={}, seatId={}", userId, roomId, seatId);
        
        if (seatId == null) {
            logger.warn("Skipping chatting state for user {} - not in a seat", userId);
            return;
        }
        
        LocalDateTime now = LocalDateTime.now();
        chattingStateTimers.put(userId, now);
        logger.info("Added user {} to chatting timers at {}", userId, now);
        
        // Set chatting state immediately
        setUserAvatarState(userId, roomId, seatId, AvatarState.CHATTING);
        
        logger.info("Triggered chatting state for user {} in room {} seat {}", userId, roomId, seatId);
    }
    
    /**
     * Set user avatar state and broadcast to room
     */
    public void setUserAvatarState(String userId, String roomId, Integer seatId, AvatarState state) {
        logger.info("setUserAvatarState called: userId={}, roomId={}, seatId={}, state={}", 
                   userId, roomId, seatId, state);
        try {
            // Save state to Redis
            String key = String.format(AVATAR_STATE_KEY_PATTERN, userId);
            redisTemplate.opsForValue().set(key, state.name(), 10, TimeUnit.MINUTES);
            logger.info("Saved avatar state {} to Redis for user {}", state, userId);
            
            // Broadcast state change to room
            broadcastAvatarStateUpdate(userId, roomId, seatId, state);
            
            logger.info("Set avatar state for user {} to {} in room {} seat {}", userId, state, roomId, seatId);
            
        } catch (Exception e) {
            logger.error("Error setting avatar state for user {}: {}", userId, e.getMessage(), e);
        }
    }
    
    /**
     * Get current avatar state for user
     */
    public AvatarState getUserAvatarState(String userId) {
        try {
            String key = String.format(AVATAR_STATE_KEY_PATTERN, userId);
            String stateStr = (String) redisTemplate.opsForValue().get(key);
            
            if (stateStr != null) {
                return AvatarState.valueOf(stateStr);
            }
        } catch (Exception e) {
            logger.warn("Error getting avatar state for user {}: {}", userId, e.getMessage());
        }
        
        return AvatarState.NORMAL; // Default state
    }
    
    /**
     * Broadcast avatar state update to room via WebSocket
     */
    private void broadcastAvatarStateUpdate(String userId, String roomId, Integer seatId, AvatarState state) {
        try {
            Map<String, Object> message = Map.of(
                "type", "AVATAR_STATE_UPDATE",
                "userId", userId,
                "roomId", roomId,
                "seatId", seatId,
                "avatarState", state.name().toLowerCase(),
                "timestamp", System.currentTimeMillis()
            );
            
            String destination = "/topic/room/" + roomId + "/avatar-state";
            logger.info("Broadcasting avatar state message: {} to destination: {}", message, destination);
            messagingTemplate.convertAndSend(destination, message);
            
            logger.info("Broadcasted avatar state {} for user {} to room {}", state, userId, roomId);
            
        } catch (Exception e) {
            logger.error("Error broadcasting avatar state update: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Process idle users - run every 30 seconds to check for idle state
     */
    @Scheduled(fixedRate = 30000) // Every 30 seconds
    public void processIdleUsers() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime idleThreshold = now.minus(30, ChronoUnit.SECONDS);
        
        // Get all users in seats
        SeatService.RoomMapping allRooms = seatService.getAllRooms();
        
        for (SeatService.RoomInfo room : allRooms.getRooms().values()) {
            for (SeatService.UserInfo user : room.getSeats().values()) {
                String userId = user.getUserId();
                Integer seatId = user.getSeatId();
                String roomId = user.getRoomId();
                
                // Check if user has consumables (eating takes priority over idle)
                boolean hasConsumables = !consumableService.getConsumables(userId, roomId, seatId).isEmpty();
                if (hasConsumables) {
                    continue; // Skip idle check for users who are eating
                }
                
                // Check if user is currently in chatting state (don't interrupt)
                AvatarState currentState = getUserAvatarState(userId);
                if (currentState == AvatarState.CHATTING) {
                    continue; // Let chatting timer handle the transition
                }
                
                // Check last activity
                LocalDateTime lastActivity = userLastActivity.get(userId);
                if (lastActivity == null) {
                    // Try to get from Redis
                    try {
                        String key = String.format(LAST_ACTIVITY_KEY_PATTERN, userId);
                        String activityStr = (String) redisTemplate.opsForValue().get(key);
                        if (activityStr != null) {
                            lastActivity = LocalDateTime.parse(activityStr);
                            userLastActivity.put(userId, lastActivity);
                        } else {
                            lastActivity = now; // Default to now if no activity recorded
                        }
                    } catch (Exception e) {
                        lastActivity = now; // Fallback
                    }
                }
                
                // Trigger idle state if user has been inactive
                if (lastActivity.isBefore(idleThreshold) && currentState != AvatarState.IDLE) {
                    setUserAvatarState(userId, roomId, seatId, AvatarState.IDLE);
                    logger.info("Set user {} to idle state (inactive for {} seconds)", 
                               userId, ChronoUnit.SECONDS.between(lastActivity, now));
                }
            }
        }
    }
    
    /**
     * Process chatting state timeouts - run every 2 seconds for responsive fallback
     */
    @Scheduled(fixedRate = 2000) // Every 2 seconds
    public void processChattingStateTimeouts() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime chattingTimeout = now.minus(10, ChronoUnit.SECONDS);
        
        chattingStateTimers.entrySet().removeIf(entry -> {
            String userId = entry.getKey();
            LocalDateTime chattingTime = entry.getValue();
            
            if (chattingTime.isBefore(chattingTimeout)) {
                // Find user's current seat for state update
                SeatService.RoomMapping allRooms = seatService.getAllRooms();
                for (SeatService.RoomInfo room : allRooms.getRooms().values()) {
                    for (SeatService.UserInfo user : room.getSeats().values()) {
                        if (userId.equals(user.getUserId())) {
                            // Check if user still has consumables (eating takes priority)
                            var consumables = consumableService.getConsumables(userId, user.getRoomId(), user.getSeatId());
                            boolean hasConsumables = !consumables.isEmpty();
                            AvatarState newState = hasConsumables ? AvatarState.EATING : AvatarState.NORMAL;
                            
                            setUserAvatarState(userId, user.getRoomId(), user.getSeatId(), newState);
                            logger.info("Chatting timeout: user {} transitioned from CHATTING to {} after 10 seconds (consumables: {})", 
                                       userId, newState, consumables.size());
                            return true; // Remove from timer map
                        }
                    }
                }
                return true; // Remove if user not found in any seat
            }
            return false; // Keep in timer map
        });
    }
    
    /**
     * Process eating state - run every 5 seconds to check consumable status
     */
    @Scheduled(fixedRate = 5000) // Every 5 seconds
    public void processEatingState() {
        // Get all users in seats
        SeatService.RoomMapping allRooms = seatService.getAllRooms();
        
        for (SeatService.RoomInfo room : allRooms.getRooms().values()) {
            for (SeatService.UserInfo user : room.getSeats().values()) {
                String userId = user.getUserId();
                Integer seatId = user.getSeatId();
                String roomId = user.getRoomId();
                
                // Check if user has consumables
                var consumables = consumableService.getConsumables(userId, roomId, seatId);
                boolean hasConsumables = !consumables.isEmpty();
                AvatarState currentState = getUserAvatarState(userId);
                
                logger.debug("User {} eating state check: hasConsumables={}, currentState={}, consumableCount={}", 
                           userId, hasConsumables, currentState, consumables.size());
                
                if (hasConsumables && currentState != AvatarState.EATING && currentState != AvatarState.CHATTING) {
                    // User has consumables but not in eating state (and not chatting) - set to eating
                    setUserAvatarState(userId, roomId, seatId, AvatarState.EATING);
                    logger.info("Set user {} to eating state (has {} consumables)", userId, consumables.size());
                } else if (!hasConsumables && currentState == AvatarState.EATING) {
                    // User no longer has consumables but is in eating state - return to normal
                    setUserAvatarState(userId, roomId, seatId, AvatarState.NORMAL);
                    logger.info("Set user {} to normal state (no longer has consumables)", userId);
                }
            }
        }
    }
    
    /**
     * Clear user state when leaving seat
     */
    public void clearUserState(String userId) {
        userLastActivity.remove(userId);
        chattingStateTimers.remove(userId);
        
        // Remove from Redis
        try {
            redisTemplate.delete(String.format(AVATAR_STATE_KEY_PATTERN, userId));
            redisTemplate.delete(String.format(LAST_ACTIVITY_KEY_PATTERN, userId));
            logger.debug("Cleared avatar state for user {}", userId);
        } catch (Exception e) {
            logger.error("Error clearing user state for {}: {}", userId, e.getMessage());
        }
    }
    
    /**
     * Get debug info for monitoring
     */
    public Map<String, Object> getDebugInfo() {
        return Map.of(
            "activeUsers", userLastActivity.size(),
            "chattingUsers", chattingStateTimers.size(),
            "userLastActivity", userLastActivity,
            "chattingTimers", chattingStateTimers
        );
    }
}