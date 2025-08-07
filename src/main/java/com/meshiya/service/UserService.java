package com.meshiya.service;

import com.meshiya.dto.UserProfile;
import com.meshiya.dto.ChatMessage;
import com.meshiya.model.MessageType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

/**
 * Service to manage user profiles, activity tracking, and timeout handling for MVP
 */
@Service
public class UserService {
    
    private static final Logger logger = LoggerFactory.getLogger(UserService.class);
    private static final String USER_PROFILE_KEY_PREFIX = "user_profile:";
    
    @Value("${meshiya.user.timeout.minutes:10}")
    private int inactiveTimeoutMinutes;
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private RoomService roomService;
    
    @Autowired
    private OrderService orderService;
    
    private final ObjectMapper objectMapper;
    
    public UserService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }
    
    /**
     * Update user activity - creates profile if needed
     */
    public void updateUserActivity(String userId, String userName, String roomId) {
        UserProfile profile = getUserProfile(userId);
        
        if (profile == null) {
            profile = new UserProfile(userId, userName, roomId);
            logger.info("Created user profile for {} ({})", userName, userId);
        } else {
            profile.updateActivity();
            if (!roomId.equals(profile.getRoomId())) {
                profile.setRoomId(roomId);
            }
        }
        
        saveUserProfile(profile);
        logger.debug("Updated activity for user {} in room {}", userName, roomId);
    }
    
    /**
     * Update user's current seat
     */
    public void updateUserSeat(String userId, Integer seatId) {
        UserProfile profile = getUserProfile(userId);
        if (profile != null) {
            profile.setCurrentSeat(seatId);
            profile.updateActivity();
            saveUserProfile(profile);
            logger.info("Updated seat for user {} ({}) to seat {}", profile.getUserName(), userId, seatId);
        } else {
            logger.warn("Cannot update seat for non-existent user profile: {}", userId);
        }
    }
    
    /**
     * Remove user's seat assignment
     */
    public void removeUserSeat(String userId) {
        UserProfile profile = getUserProfile(userId);
        if (profile != null) {
            Integer previousSeat = profile.getCurrentSeat();
            profile.setCurrentSeat(null);
            profile.updateActivity();
            saveUserProfile(profile);
            logger.info("Removed seat {} for user {} ({})", previousSeat, profile.getUserName(), userId);
        }
    }
    
    /**
     * Get user profile from Redis
     */
    public UserProfile getUserProfile(String userId) {
        try {
            Object profileData = redisTemplate.opsForValue().get(USER_PROFILE_KEY_PREFIX + userId);
            if (profileData != null) {
                return objectMapper.readValue(profileData.toString(), UserProfile.class);
            }
        } catch (JsonProcessingException e) {
            logger.error("Error deserializing user profile for {}: {}", userId, e.getMessage());
        }
        return null;
    }
    
    /**
     * Save user profile to Redis
     */
    private void saveUserProfile(UserProfile profile) {
        try {
            String profileJson = objectMapper.writeValueAsString(profile);
            redisTemplate.opsForValue().set(USER_PROFILE_KEY_PREFIX + profile.getUserId(), profileJson, 
                                          inactiveTimeoutMinutes + 5, TimeUnit.MINUTES);
            logger.debug("Saved user profile for {} to Redis", profile.getUserId());
        } catch (JsonProcessingException e) {
            logger.error("Error serializing user profile for {}: {}", profile.getUserId(), e.getMessage());
        }
    }
    
    /**
     * Check if user is active based on last activity
     */
    public boolean isUserActive(String userId) {
        UserProfile profile = getUserProfile(userId);
        if (profile == null) return false;
        
        LocalDateTime cutoff = LocalDateTime.now().minus(inactiveTimeoutMinutes, ChronoUnit.MINUTES);
        return profile.getLastActivity().isAfter(cutoff) && profile.isActive();
    }
    
    /**
     * Get user's last activity time
     */
    public LocalDateTime getUserLastActivity(String userId) {
        UserProfile profile = getUserProfile(userId);
        return profile != null ? profile.getLastActivity() : null;
    }
    
    /**
     * Scheduled task to clean up inactive users - runs every 2 minutes
     */
    @Scheduled(fixedRate = 120000) // 2 minutes
    public void cleanupInactiveUsers() {
        logger.debug("Running inactive user cleanup task");
        
        Set<String> profileKeys = redisTemplate.keys(USER_PROFILE_KEY_PREFIX + "*");
        if (profileKeys == null || profileKeys.isEmpty()) {
            return;
        }
        
        LocalDateTime cutoff = LocalDateTime.now().minus(inactiveTimeoutMinutes, ChronoUnit.MINUTES);
        int removedCount = 0;
        
        for (String key : profileKeys) {
            String userId = key.substring(USER_PROFILE_KEY_PREFIX.length());
            
            try {
                UserProfile profile = getUserProfile(userId);
                if (profile == null) {
                    continue;
                }
                
                // If user is inactive, remove them
                if (profile.getLastActivity().isBefore(cutoff)) {
                    removeInactiveUser(profile);
                    removedCount++;
                }
                
            } catch (Exception e) {
                logger.error("Error processing inactive user cleanup for {}: {}", userId, e.getMessage());
                // Clean up corrupted entry
                redisTemplate.delete(key);
            }
        }
        
        if (removedCount > 0) {
            logger.info("Cleaned up {} inactive users", removedCount);
        }
    }
    
    /**
     * Remove an inactive user from the system
     */
    private void removeInactiveUser(UserProfile profile) {
        logger.info("Removing inactive user {} ({}) from room {} - last activity: {}", 
                   profile.getUserName(), profile.getUserId(), profile.getRoomId(), profile.getLastActivity());
        
        // Remove user from their seat if they have one
        if (profile.getRoomId() != null && profile.getCurrentSeat() != null) {
            boolean seatRemoved = roomService.leaveSeat(profile.getRoomId(), profile.getUserId());
            if (seatRemoved) {
                // Add system message about user leaving due to inactivity
                roomService.addMessageToRoom(profile.getRoomId(), createInactivityMessage(profile));
            }
        }
        
        // Clean up any pending orders
        cleanupUserOrders(profile);
        
        // Remove user profile from Redis
        redisTemplate.delete(USER_PROFILE_KEY_PREFIX + profile.getUserId());
    }
    
    /**
     * Clean up orders for an inactive user
     */
    private void cleanupUserOrders(UserProfile profile) {
        try {
            var userOrders = orderService.getUserCurrentOrders(profile.getUserId());
            if (!userOrders.isEmpty()) {
                logger.info("Cleaning up {} orders for inactive user {}", userOrders.size(), profile.getUserName());
                for (var order : userOrders) {
                    orderService.completeOrder(profile.getUserId(), order.getOrderId());
                }
            }
        } catch (Exception e) {
            logger.error("Error cleaning up orders for {}: {}", profile.getUserId(), e.getMessage());
        }
    }
    
    /**
     * Create a system message for user leaving due to inactivity
     */
    private ChatMessage createInactivityMessage(UserProfile profile) {
        ChatMessage message = new ChatMessage();
        message.setType(MessageType.SYSTEM_MESSAGE);
        message.setUserName("System");
        message.setUserId("system");
        message.setContent(profile.getUserName() + " left the diner (inactive)");
        message.setRoomId(profile.getRoomId());
        message.setTimestamp(LocalDateTime.now());
        return message;
    }
    
    /**
     * Get statistics about user activity
     */
    public Map<String, Object> getActivityStats() {
        Set<String> profileKeys = redisTemplate.keys(USER_PROFILE_KEY_PREFIX + "*");
        int totalUsers = profileKeys != null ? profileKeys.size() : 0;
        int activeUsers = 0;
        
        LocalDateTime cutoff = LocalDateTime.now().minus(inactiveTimeoutMinutes, ChronoUnit.MINUTES);
        
        if (profileKeys != null) {
            for (String key : profileKeys) {
                try {
                    UserProfile profile = getUserProfile(key.substring(USER_PROFILE_KEY_PREFIX.length()));
                    if (profile != null && profile.getLastActivity().isAfter(cutoff) && profile.isActive()) {
                        activeUsers++;
                    }
                } catch (Exception e) {
                    // Skip corrupted entries
                }
            }
        }
        
        return Map.of(
            "totalTrackedUsers", totalUsers,
            "activeUsers", activeUsers,
            "inactiveUsers", totalUsers - activeUsers,
            "timeoutMinutes", inactiveTimeoutMinutes
        );
    }
    
    /**
     * Manually remove a user (for testing/admin purposes)
     */
    public void removeUser(String userId) {
        UserProfile profile = getUserProfile(userId);
        if (profile != null) {
            removeInactiveUser(profile);
        }
    }
}