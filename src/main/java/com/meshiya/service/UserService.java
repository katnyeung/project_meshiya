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
    private SeatService seatService;
    
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
            // Log who's creating profiles for unknown users
            if ("Unknown".equals(userName)) {
                logger.warn("Creating profile for Unknown user {} - called from: {}", 
                           userId, getCallLocation());
            }
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
     * Get the location where this method was called from (for debugging)
     */
    private String getCallLocation() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        if (stack.length > 3) {
            StackTraceElement caller = stack[3]; // Skip getStackTrace, getCallLocation, updateUserActivity
            return caller.getClassName() + "::" + caller.getMethodName() + "(" + caller.getLineNumber() + ")";
        }
        return "unknown";
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
            // NOTE: Don't call updateActivity() here - this prevents proper cleanup 
            // of inactive users during timeout processing
            saveUserProfile(profile);
            logger.info("Removed seat {} for user {} ({})", previousSeat, profile.getUserName(), userId);
        }
    }
    
    /**
     * Get user's current seat
     */
    public Integer getUserSeat(String userId) {
        UserProfile profile = getUserProfile(userId);
        return profile != null ? profile.getCurrentSeat() : null;
    }
    
    /**
     * Mark user as inactive (for disconnection/timeout handling)
     */
    public void markUserInactive(String userId) {
        UserProfile profile = getUserProfile(userId);
        if (profile != null) {
            profile.markInactive();
            saveUserProfile(profile);
            logger.info("Marked user {} ({}) as inactive", profile.getUserName(), userId);
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
     * Simple: Remove users from room after timeout, regardless of seat status
     */
    @Scheduled(fixedRate = 120000) // 2 minutes
    public void cleanupInactiveUsers() {
        logger.info("Running inactive user cleanup task");
        
        Set<String> profileKeys = redisTemplate.keys(USER_PROFILE_KEY_PREFIX + "*");
        if (profileKeys == null || profileKeys.isEmpty()) {
            logger.info("No user profiles found for cleanup");
            return;
        }
        
        LocalDateTime cutoff = LocalDateTime.now().minus(inactiveTimeoutMinutes, ChronoUnit.MINUTES);
        int removedCount = 0;
        int checkedCount = 0;
        
        logger.info("Checking {} user profiles for cleanup (cutoff: {})", profileKeys.size(), cutoff);
        
        for (String key : profileKeys) {
            String userId = key.substring(USER_PROFILE_KEY_PREFIX.length());
            checkedCount++;
            
            try {
                UserProfile profile = getUserProfile(userId);
                if (profile == null) {
                    logger.debug("Profile {} is null, skipping", userId);
                    continue;
                }
                
                logger.debug("User {}: lastActivity={}, active={}, cutoff check={}", 
                           profile.getUserName(), profile.getLastActivity(), profile.isActive(), 
                           profile.getLastActivity().isBefore(cutoff));
                
                // Simple check: if last activity is old, remove user from room
                if (profile.getLastActivity().isBefore(cutoff)) {
                    logger.info("Removing inactive user {}: last activity {} is before cutoff {}", 
                               profile.getUserName(), profile.getLastActivity(), cutoff);
                    removeUserFromRoom(profile);
                    removedCount++;
                } else {
                    logger.debug("User {} is still active (last activity: {})", 
                               profile.getUserName(), profile.getLastActivity());
                }
                
            } catch (Exception e) {
                logger.error("Error processing inactive user cleanup for {}: {}", userId, e.getMessage());
                // Clean up corrupted entry
                redisTemplate.delete(key);
            }
        }
        
        logger.info("Cleanup complete: checked {} profiles, removed {} inactive users", checkedCount, removedCount);
        
        // Also clean up ghost seat assignments without user profiles
        cleanupGhostSeatAssignments();
    }
    
    /**
     * Simple: Remove user from room (seat, orders, profile) due to inactivity
     */
    private void removeUserFromRoom(UserProfile profile) {
        logger.info("Removing inactive user {} from room {} - last activity: {}", 
                   profile.getUserName(), profile.getRoomId(), profile.getLastActivity());
        
        String userId = profile.getUserId();
        String roomId = profile.getRoomId();
        
        try {
            // Remove from seat if they have one (this also cleans consumables)
            if (profile.getCurrentSeat() != null) {
                roomService.leaveSeat(roomId, userId);
            }
            
            // Note: Orders will be cleaned up by OrderService's own scheduled cleanup
            // when it detects the user profile no longer exists
            
            // Remove user profile from Redis
            redisTemplate.delete(USER_PROFILE_KEY_PREFIX + userId);
            
            // Add system message
            roomService.addMessageToRoom(roomId, createInactivityMessage(profile));
            
            logger.info("Successfully removed inactive user {} from room {}", profile.getUserName(), roomId);
            
        } catch (Exception e) {
            logger.error("Error removing user {} from room: {}", userId, e.getMessage());
            // Still try to remove profile even if seat cleanup fails
            redisTemplate.delete(USER_PROFILE_KEY_PREFIX + userId);
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
            removeUserFromRoom(profile);
        }
    }
    
    /**
     * Clean up ghost seat assignments that don't have corresponding user profiles
     */
    private void cleanupGhostSeatAssignments() {
        try {
            SeatService.RoomMapping allRooms = seatService.getAllRooms();
            int ghostCount = 0;
            
            for (SeatService.RoomInfo room : allRooms.getRooms().values()) {
                List<String> ghostUserIds = new ArrayList<>();
                
                for (SeatService.UserInfo user : room.getSeats().values()) {
                    // Check if user has a profile in UserService
                    UserProfile profile = getUserProfile(user.getUserId());
                    if (profile == null) {
                        // Ghost user - has seat assignment but no profile
                        logger.info("Found ghost user in seat: {} ({}) in room {} seat {}", 
                                   user.getUserName(), user.getUserId(), user.getRoomId(), user.getSeatId());
                        ghostUserIds.add(user.getUserId());
                    }
                }
                
                // Remove ghost users from seats
                for (String ghostUserId : ghostUserIds) {
                    logger.info("Removing ghost user {} from seat assignments", ghostUserId);
                    seatService.leaveSeat(room.getRoomId(), ghostUserId);
                    
                    // Also remove from room seat occupancy
                    roomService.leaveSeat(room.getRoomId(), ghostUserId);
                    
                    ghostCount++;
                }
            }
            
            if (ghostCount > 0) {
                logger.info("Cleaned up {} ghost seat assignments", ghostCount);
            } else {
                logger.debug("No ghost seat assignments found");
            }
            
        } catch (Exception e) {
            logger.error("Error during ghost seat assignment cleanup: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Force cleanup of inactive users (for admin/testing)
     */
    public void forceCleanupInactiveUsers() {
        logger.info("Force cleanup of inactive users initiated");
        cleanupInactiveUsers();
    }
}