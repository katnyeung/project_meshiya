package com.meshiya.service;

import com.meshiya.model.Consumable;
import com.meshiya.model.MenuItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class ConsumableService {
    
    private static final Logger logger = LoggerFactory.getLogger(ConsumableService.class);
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    
    @Autowired
    private RedisService redisService;
    
    @Autowired
    private SeatService seatService;
    
    private final ObjectMapper objectMapper;
    
    // Redis key patterns - consumables now follow the user, not the seat
    private static final String CONSUMABLES_KEY_PATTERN = "cafe:room:%s:user:%s:consumables";
    private static final String USER_PRESENCE_KEY_PATTERN = "cafe:room:%s:seat:%d:user:%s:presence";
    
    // Note: Consumption durations are now defined per menu item in the JSON configuration
    
    public ConsumableService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }
    
    /**
     * Add consumable when order is served (with optional image data)
     */
    public void addConsumableWithImage(String userId, String roomId, Integer seatId, MenuItem menuItem, String imageData) {
        // Validate parameters
        if (seatId == null || userId == null || roomId == null || menuItem == null) {
            logger.warn("Invalid parameters for adding consumable: userId={}, roomId={}, seatId={}, menuItem={}", 
                       userId, roomId, seatId, menuItem != null ? menuItem.getName() : "null");
            return;
        }
        
        // Verify user is actually in the seat using centralized manager
        SeatService.UserInfo userInSeat = seatService.getUserInSeat(roomId, seatId);
        if (userInSeat == null || !userId.equals(userInSeat.getUserId())) {
            logger.warn("User {} not found in seat {} of room {} during seat validation, but order exists so proceeding with consumable for {}", 
                       userId, seatId, roomId, menuItem.getName());
            // Don't return - continue with adding the consumable since the order was valid
        }
        
        // Use consumption duration from menu item configuration
        int duration = menuItem.getConsumptionTimeSeconds();
        
        Consumable consumable = new Consumable(
            menuItem.getId(),
            menuItem.getName(),
            menuItem.getType().name(),
            duration,
            roomId,
            seatId,
            userId
        );
        
        // Add image data if available
        if (imageData != null && !imageData.trim().isEmpty()) {
            consumable.setImageData(imageData);
            logger.info("Added image data to consumable for {}", menuItem.getName());
        }
        
        String key = String.format(CONSUMABLES_KEY_PATTERN, roomId, userId);
        
        try {
            // Get existing consumables
            List<Consumable> consumables = getConsumables(userId, roomId, seatId);
            consumables.add(consumable);
            
            // Save back to Redis
            String json = objectMapper.writeValueAsString(consumables);
            redisTemplate.opsForValue().set(key, json);
            
            logger.info("Added consumable {} for user {} in room {} seat {}", 
                       menuItem.getName(), userId, roomId, seatId);
            
            // Broadcast status update
            broadcastUserStatusUpdate(userId, roomId, seatId);
            
        } catch (JsonProcessingException e) {
            logger.error("Error adding consumable", e);
        }
    }
    
    /**
     * Add consumable when order is served (without image - backward compatibility)
     */
    public void addConsumable(String userId, String roomId, Integer seatId, MenuItem menuItem) {
        addConsumableWithImage(userId, roomId, seatId, menuItem, null);
    }
    
    /**
     * Get all active consumables for a user in a specific room/seat
     */
    public List<Consumable> getConsumables(String userId, String roomId, Integer seatId) {
        String key = String.format(CONSUMABLES_KEY_PATTERN, roomId, userId);
        String json = (String) redisTemplate.opsForValue().get(key);
        
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        
        try {
            return objectMapper.readValue(json, new TypeReference<List<Consumable>>() {});
        } catch (JsonProcessingException e) {
            logger.error("Error deserializing consumables for user {}", userId, e);
            return new ArrayList<>();
        }
    }
    
    /**
     * Remove expired consumables and update timers every second
     */
    @Scheduled(fixedRate = 1000) // Run every second
    public void updateConsumableTimers() {
        // Update consumables for ALL users, not just those currently in seats
        // This ensures timers keep ticking even when users temporarily disconnect
        Set<String> userKeys = redisTemplate.keys("cafe:room:*:user:*:consumables");
        
        if (userKeys != null) {
            for (String key : userKeys) {
                try {
                    // Extract roomId and userId from key pattern: cafe:room:{roomId}:user:{userId}:consumables
                    String[] parts = key.split(":");
                    if (parts.length >= 5) {
                        String roomId = parts[2];
                        String userId = parts[4];
                        
                        // Get user's current seat (may be null if offline)
                        Integer seatId = getUserCurrentSeat(userId, roomId);
                        updateUserConsumables(userId, roomId, seatId);
                    }
                } catch (Exception e) {
                    logger.warn("Error processing consumable key {}: {}", key, e.getMessage());
                }
            }
        }
    }
    
    /**
     * Broadcast timer updates every 10 seconds for live display
     */
    @Scheduled(fixedRate = 10000) // Every 10 seconds
    public void broadcastTimerUpdates() {
        Set<String> userKeys = redisTemplate.keys("cafe:room:*:user:*:consumables");
        
        if (userKeys != null) {
            for (String key : userKeys) {
                try {
                    String[] parts = key.split(":");
                    if (parts.length >= 5) {
                        String roomId = parts[2];
                        String userId = parts[4];
                        Integer seatId = getUserCurrentSeat(userId, roomId);
                        
                        if (seatId != null) {
                            List<Consumable> consumables = getConsumables(userId, roomId, seatId);
                            if (!consumables.isEmpty()) {
                                broadcastUserStatusUpdate(userId, roomId, seatId);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Error broadcasting timer update for key {}: {}", key, e.getMessage());
                }
            }
        }
    }
    
    /**
     * Get user's current seat for timer updates
     */
    private Integer getUserCurrentSeat(String userId, String roomId) {
        // Try to get from centralized manager first
        SeatService.RoomMapping allRooms = seatService.getAllRooms();
        SeatService.RoomInfo room = allRooms.getRooms().get(roomId);
        
        if (room != null) {
            for (SeatService.UserInfo user : room.getSeats().values()) {
                if (userId.equals(user.getUserId())) {
                    return user.getSeatId();
                }
            }
        }
        
        // Return null if not found in centralized manager
        return null;
    }
    
    /**
     * Update consumables for a specific user/room/seat
     */
    private void updateUserConsumables(String userId, String roomId, Integer seatId) {
        List<Consumable> consumables = getConsumables(userId, roomId, seatId);
        boolean hasChanges = false;
        
        Iterator<Consumable> iterator = consumables.iterator();
        while (iterator.hasNext()) {
            Consumable consumable = iterator.next();
            
            // Tick down the timer
            consumable.tick();
            
            // Remove if expired
            if (consumable.isExpired()) {
                logger.info("Consumable {} expired for user {} in room {} seat {}", 
                           consumable.getItemName(), userId, roomId, seatId);
                iterator.remove();
                hasChanges = true;
            }
        }
        
        if (hasChanges) {
            // Update Redis
            saveConsumables(userId, roomId, seatId, consumables);
            
            // Broadcast update when items expire
            broadcastUserStatusUpdate(userId, roomId, seatId);
        }
        
        // Always save updated consumables (with ticked-down timers) for accurate retrieval
        if (!consumables.isEmpty()) {
            saveConsumables(userId, roomId, seatId, consumables);
        }
    }
    
    /**
     * Save consumables to Redis
     */
    private void saveConsumables(String userId, String roomId, Integer seatId, List<Consumable> consumables) {
        String key = String.format(CONSUMABLES_KEY_PATTERN, roomId, userId);
        
        try {
            if (consumables.isEmpty()) {
                redisTemplate.delete(key);
            } else {
                String json = objectMapper.writeValueAsString(consumables);
                redisTemplate.opsForValue().set(key, json);
            }
        } catch (JsonProcessingException e) {
            logger.error("Error saving consumables for user {}", userId, e);
        }
    }
    
    /**
     * Clear all consumables when user leaves seat (with delay for reconnection)
     */
    public void clearUserConsumables(String userId, String roomId, Integer seatId) {
        // Don't immediately delete consumables - set expiration instead
        // This allows users to rejoin and restore their consumables
        String key = String.format(CONSUMABLES_KEY_PATTERN, roomId, userId);
        
        // Set expiration to 5 minutes - if user doesn't return, consumables will be cleaned up
        redisTemplate.expire(key, 300, java.util.concurrent.TimeUnit.SECONDS);
        
        logger.info("Set expiration for consumables - user {} leaving room {} seat {} (expires in 5 minutes)", userId, roomId, seatId);
        
        // Still broadcast empty status immediately for UI cleanup
        broadcastEmptyUserStatus(userId, roomId, seatId);
    }
    
    /**
     * Restore consumables when user rejoins a seat
     */
    public void restoreUserConsumables(String userId, String roomId, Integer seatId) {
        String key = String.format(CONSUMABLES_KEY_PATTERN, roomId, userId);
        List<Consumable> consumables = getConsumables(userId, roomId, seatId);
        
        if (!consumables.isEmpty()) {
            // Remove expiration since user has returned
            redisTemplate.persist(key);
            
            // Filter out expired consumables before restoration
            List<Consumable> validConsumables = new ArrayList<>();
            for (Consumable consumable : consumables) {
                if (!consumable.isExpired()) {
                    validConsumables.add(consumable);
                } else {
                    logger.info("Skipping expired consumable {} during restoration for user {}", 
                               consumable.getItemName(), userId);
                }
            }
            
            // Update seat information in valid consumables to reflect new seat
            updateConsumableSeats(validConsumables, seatId);
            saveConsumables(userId, roomId, seatId, validConsumables);
            
            logger.info("Restored {} valid consumables (filtered out {} expired) for user {} in room {} seat {}", 
                       validConsumables.size(), consumables.size() - validConsumables.size(), userId, roomId, seatId);
            
            // Broadcast restored status (only valid consumables)
            broadcastUserStatusUpdate(userId, roomId, seatId);
        } else {
            logger.debug("No consumables to restore for user {} in room {} seat {} (consumables follow user)", userId, roomId, seatId);
        }
    }
    
    /**
     * Broadcast empty user status (for when user leaves)
     */
    private void broadcastEmptyUserStatus(String userId, String roomId, Integer seatId) {
        Map<String, Object> statusUpdate = Map.of(
            "type", "USER_STATUS_UPDATE",
            "userId", userId,
            "roomId", roomId,
            "seatId", seatId,
            "consumables", Collections.emptyList(),
            "timestamp", System.currentTimeMillis()
        );
        
        String destination = "/topic/room/" + roomId + "/status";
        messagingTemplate.convertAndSend(destination, statusUpdate);
    }
    
    /**
     * Clear user consumables for restoration (prevents duplication)
     */
    public void clearUserConsumablesForRestore(String userId, String roomId, Integer seatId) {
        String key = String.format(CONSUMABLES_KEY_PATTERN, roomId, userId);
        redisTemplate.delete(key);
        
        logger.info("Cleared existing consumables for user {} before restoration to prevent duplication", userId);
    }
    
    /**
     * Check if user has existing consumables (to avoid overwriting during seat swaps)
     */
    public boolean hasExistingConsumables(String userId, String roomId, Integer seatId) {
        List<Consumable> consumables = getConsumables(userId, roomId, seatId);
        boolean hasConsumables = !consumables.isEmpty();
        
        logger.debug("User {} has {} existing consumables in room {} seat {}", 
                    userId, consumables.size(), roomId, seatId);
        
        return hasConsumables;
    }
    
    /**
     * Update seat information in consumables when user changes seats
     */
    private void updateConsumableSeats(List<Consumable> consumables, Integer newSeatId) {
        for (Consumable consumable : consumables) {
            consumable.setSeatId(newSeatId);
        }
    }
    
    /**
     * Transfer consumables when user changes seats (for explicit seat swapping)
     */
    public void transferConsumablesOnSeatChange(String userId, String roomId, Integer oldSeatId, Integer newSeatId) {
        // Since consumables follow the user (not the seat), we just need to update seat IDs and broadcast
        List<Consumable> consumables = getConsumables(userId, roomId, newSeatId); // Already follows user
        
        if (!consumables.isEmpty()) {
            logger.info("User {} changing seats from {} to {} with {} consumables:", 
                       userId, oldSeatId, newSeatId, consumables.size());
            
            // Log current state
            for (Consumable c : consumables) {
                logger.info("  - {} has {}s remaining ({}s total)", 
                           c.getItemName(), c.getRemainingSeconds(), c.getDurationSeconds());
            }
            
            // Update seat info in each consumable (preserves remainingSeconds)
            updateConsumableSeats(consumables, newSeatId);
            
            // Save with updated seat info (preserves timing)
            saveConsumables(userId, roomId, newSeatId, consumables);
            
            logger.info("Seat change complete - consumables moved to seat {} with preserved timers", newSeatId);
            
            // First, broadcast empty status for old seat to clear ghost images
            broadcastEmptyUserStatus(userId, roomId, oldSeatId);
            
            // Small delay to ensure old seat is cleared first
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // Then broadcast new status for new seat
            broadcastUserStatusUpdate(userId, roomId, newSeatId);
        } else {
            logger.info("User {} changing seats from {} to {} - no consumables to transfer", userId, oldSeatId, newSeatId);
        }
    }
    
    
    /**
     * Broadcast user status update to room
     */
    private void broadcastUserStatusUpdate(String userId, String roomId, Integer seatId) {
        List<Consumable> consumables = getConsumables(userId, roomId, seatId);
        
        UserStatusUpdate statusUpdate = new UserStatusUpdate(userId, roomId, seatId, consumables);
        
        try {
            messagingTemplate.convertAndSend("/topic/room/" + roomId + "/user-status", statusUpdate);
            logger.debug("Broadcast user status update for {} in room {} seat {}", userId, roomId, seatId);
        } catch (Exception e) {
            logger.error("Error broadcasting user status update", e);
        }
    }
    
    /**
     * Broadcast all user statuses in a room (for refresh requests)
     */
    public void broadcastAllUserStatuses(String roomId) {
        logger.info("Broadcasting all user statuses for room {}", roomId);
        
        try {
            // Get all users in the room using centralized manager
            SeatService.RoomMapping allRooms = seatService.getAllRooms();
            SeatService.RoomInfo room = allRooms.getRooms().get(roomId);
            
            if (room == null) {
                logger.warn("Room {} not found for status refresh", roomId);
                return;
            }
            
            int statusCount = 0;
            for (SeatService.UserInfo user : room.getSeats().values()) {
                broadcastUserStatusUpdate(user.getUserId(), user.getRoomId(), user.getSeatId());
                statusCount++;
            }
            
            logger.info("Broadcasted {} user statuses for room {}", statusCount, roomId);
            
        } catch (Exception e) {
            logger.error("Error broadcasting all user statuses for room {}", roomId, e);
        }
    }
    
    /**
     * Clear all consumables for all users (for fixing corrupted data)
     */
    public void clearAllConsumables() {
        try {
            // Get all users in all rooms
            SeatService.RoomMapping allRooms = seatService.getAllRooms();
            
            for (SeatService.RoomInfo room : allRooms.getRooms().values()) {
                for (SeatService.UserInfo user : room.getSeats().values()) {
                    String key = String.format(CONSUMABLES_KEY_PATTERN, user.getRoomId(), user.getSeatId(), user.getUserId());
                    redisTemplate.delete(key);
                }
            }
            
            logger.info("Cleared all consumables from Redis");
        } catch (Exception e) {
            logger.error("Error clearing all consumables", e);
        }
    }
    
    /**
     * Get user status for debug/admin purposes
     */
    public Map<String, Object> getUserStatusInfo(String userId, String roomId, Integer seatId) {
        List<Consumable> consumables = getConsumables(userId, roomId, seatId);
        
        Map<String, Object> info = new HashMap<>();
        info.put("userId", userId);
        info.put("roomId", roomId);
        info.put("seatId", seatId);
        info.put("consumables", consumables);
        info.put("consumableCount", consumables.size());
        
        return info;
    }
    
    /**
     * Inner class for WebSocket status updates
     */
    public static class UserStatusUpdate {
        public final String type = "USER_STATUS_UPDATE";
        public final String userId;
        public final String roomId;
        public final Integer seatId;
        public final List<Consumable> consumables;
        public final long timestamp;
        
        public UserStatusUpdate(String userId, String roomId, Integer seatId, List<Consumable> consumables) {
            this.userId = userId;
            this.roomId = roomId;
            this.seatId = seatId;
            this.consumables = consumables;
            this.timestamp = System.currentTimeMillis();
        }
    }
}