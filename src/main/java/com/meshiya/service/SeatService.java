package com.meshiya.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized manager for Room -> Seat -> User relationships
 * Simplifies the complex relationship tracking across the system
 */
@Service
public class SeatService {
    
    private static final Logger logger = LoggerFactory.getLogger(SeatService.class);
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    
    @Autowired
    @Lazy
    private UserService userService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // Redis key for the centralized mapping
    private static final String ROOM_MAPPING_KEY = "cafe:room_seat_user_mapping";
    
    /**
     * Internal data structure for room/seat/user relationships
     */
    public static class RoomMapping {
        private Map<String, RoomInfo> rooms = new ConcurrentHashMap<>();
        
        public Map<String, RoomInfo> getRooms() { return rooms; }
        public void setRooms(Map<String, RoomInfo> rooms) { this.rooms = rooms; }
    }
    
    public static class RoomInfo {
        private String roomId;
        private Map<Integer, UserInfo> seats = new ConcurrentHashMap<>();
        
        public RoomInfo() {}
        public RoomInfo(String roomId) { this.roomId = roomId; }
        
        public String getRoomId() { return roomId; }
        public void setRoomId(String roomId) { this.roomId = roomId; }
        public Map<Integer, UserInfo> getSeats() { return seats; }
        public void setSeats(Map<Integer, UserInfo> seats) { this.seats = seats; }
    }
    
    public static class UserInfo {
        private String userId;
        private String userName;
        private Integer seatId;
        private String roomId;
        private long joinTime;
        
        public UserInfo() {}
        public UserInfo(String userId, String userName, Integer seatId, String roomId) {
            this.userId = userId;
            this.userName = userName;
            this.seatId = seatId;
            this.roomId = roomId;
            this.joinTime = System.currentTimeMillis();
        }
        
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getUserName() { return userName; }
        public void setUserName(String userName) { this.userName = userName; }
        public Integer getSeatId() { return seatId; }
        public void setSeatId(Integer seatId) { this.seatId = seatId; }
        public String getRoomId() { return roomId; }
        public void setRoomId(String roomId) { this.roomId = roomId; }
        public long getJoinTime() { return joinTime; }
        public void setJoinTime(long joinTime) { this.joinTime = joinTime; }
    }
    
    /**
     * Get the current room mapping from Redis
     */
    private RoomMapping getRoomMapping() {
        try {
            String json = (String) redisTemplate.opsForValue().get(ROOM_MAPPING_KEY);
            if (json == null || json.isEmpty()) {
                return new RoomMapping();
            }
            return objectMapper.readValue(json, RoomMapping.class);
        } catch (JsonProcessingException e) {
            logger.error("Error reading room mapping from Redis", e);
            return new RoomMapping();
        }
    }
    
    /**
     * Save the room mapping to Redis
     */
    private void saveRoomMapping(RoomMapping mapping) {
        try {
            String json = objectMapper.writeValueAsString(mapping);
            redisTemplate.opsForValue().set(ROOM_MAPPING_KEY, json);
        } catch (JsonProcessingException e) {
            logger.error("Error saving room mapping to Redis", e);
        }
    }
    
    /**
     * User joins a seat in a room
     */
    public synchronized boolean joinSeat(String roomId, Integer seatId, String userId, String userName) {
        RoomMapping mapping = getRoomMapping();
        
        // Ensure room exists
        RoomInfo room = mapping.getRooms().computeIfAbsent(roomId, k -> new RoomInfo(roomId));
        
        // Check if seat is already occupied
        if (room.getSeats().containsKey(seatId)) {
            UserInfo existingUser = room.getSeats().get(seatId);
            if (!existingUser.getUserId().equals(userId)) {
                // Check if this might be a ghost user with same username but different session format
                if (existingUser.getUserName().equals(userName) && isLikelyGhostUser(existingUser.getUserId(), userId)) {
                    logger.info("Removing ghost user {} and allowing real user {} to join seat {}", 
                               existingUser.getUserId(), userId, seatId);
                    // Remove the ghost user and continue with joining
                    room.getSeats().remove(seatId);
                } else {
                    logger.warn("Seat {} in room {} is already occupied by {} (not a ghost)", 
                               seatId, roomId, existingUser.getUserId());
                    return false;
                }
            }
        }
        
        // Remove user from any other seat in this room first
        removeUserFromRoom(roomId, userId, mapping);
        
        // Add user to the seat
        UserInfo userInfo = new UserInfo(userId, userName, seatId, roomId);
        room.getSeats().put(seatId, userInfo);
        
        saveRoomMapping(mapping);
        
        // Broadcast seat occupancy update to frontend
        broadcastSeatUpdate(roomId, room);
        
        logger.info("User {} ({}) joined seat {} in room {}", userName, userId, seatId, roomId);
        return true;
    }
    
    /**
     * User leaves their seat
     */
    public synchronized boolean leaveSeat(String roomId, String userId) {
        RoomMapping mapping = getRoomMapping();
        
        boolean removed = removeUserFromRoom(roomId, userId, mapping);
        
        if (removed) {
            saveRoomMapping(mapping);
            
            // Broadcast seat occupancy update to frontend
            RoomInfo room = mapping.getRooms().get(roomId);
            if (room != null) {
                broadcastSeatUpdate(roomId, room);
            }
            
            logger.info("User {} left their seat in room {}", userId, roomId);
        }
        
        return removed;
    }
    
    /**
     * Remove user from any seat in the specified room
     */
    private boolean removeUserFromRoom(String roomId, String userId, RoomMapping mapping) {
        RoomInfo room = mapping.getRooms().get(roomId);
        if (room == null) return false;
        
        // Find and remove user from any seat
        Integer userSeatId = null;
        for (Map.Entry<Integer, UserInfo> seat : room.getSeats().entrySet()) {
            if (seat.getValue().getUserId().equals(userId)) {
                userSeatId = seat.getKey();
                break;
            }
        }
        
        if (userSeatId != null) {
            room.getSeats().remove(userSeatId);
            return true;
        }
        
        return false;
    }
    
    /**
     * Check if a userId is likely a ghost user compared to another userId for the same user
     */
    private boolean isLikelyGhostUser(String existingUserId, String newUserId) {
        // Pattern matching for ghost detection:
        // - session_ format vs user_ format
        // - Different session IDs but same user
        
        boolean existingIsSession = existingUserId.startsWith("session_");
        boolean newIsSession = newUserId.startsWith("session_");
        boolean existingIsUser = existingUserId.startsWith("user_");
        boolean newIsUser = newUserId.startsWith("user_");
        
        // If one is session format and other is user format, likely ghost
        if ((existingIsUser && newIsSession) || (existingIsSession && newIsUser)) {
            logger.debug("Ghost user detected: {} vs {} (different ID formats)", existingUserId, newUserId);
            return true;
        }
        
        // If both are session format but different IDs, the newer session should take precedence
        if (existingIsSession && newIsSession && !existingUserId.equals(newUserId)) {
            // Extract timestamp from session IDs to determine which is newer
            try {
                long existingTimestamp = extractSessionTimestamp(existingUserId);
                long newTimestamp = extractSessionTimestamp(newUserId);
                
                // If new session is newer, existing is ghost
                if (newTimestamp > existingTimestamp) {
                    logger.debug("Ghost user detected: {} is older session than {}", existingUserId, newUserId);
                    return true;
                }
            } catch (Exception e) {
                logger.debug("Could not compare session timestamps, treating as potential ghost");
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Extract timestamp from session ID format: session_TIMESTAMP_UUID
     */
    private long extractSessionTimestamp(String sessionId) throws NumberFormatException {
        if (sessionId.startsWith("session_")) {
            String[] parts = sessionId.split("_");
            if (parts.length >= 3) {
                return Long.parseLong(parts[1]);
            }
        }
        throw new NumberFormatException("Invalid session ID format");
    }
    
    /**
     * Get user info by userId in a specific room
     */
    public UserInfo getUserInRoom(String roomId, String userId) {
        RoomMapping mapping = getRoomMapping();
        RoomInfo room = mapping.getRooms().get(roomId);
        if (room == null) return null;
        
        for (UserInfo user : room.getSeats().values()) {
            if (user.getUserId().equals(userId)) {
                return user;
            }
        }
        return null;
    }
    
    /**
     * Get user info by seatId in a specific room
     */
    public UserInfo getUserInSeat(String roomId, Integer seatId) {
        RoomMapping mapping = getRoomMapping();
        RoomInfo room = mapping.getRooms().get(roomId);
        if (room == null) return null;
        
        return room.getSeats().get(seatId);
    }
    
    /**
     * Get all users in a room
     */
    public List<UserInfo> getUsersInRoom(String roomId) {
        RoomMapping mapping = getRoomMapping();
        RoomInfo room = mapping.getRooms().get(roomId);
        if (room == null) return new ArrayList<>();
        
        return new ArrayList<>(room.getSeats().values());
    }
    
    /**
     * Get all occupied seats in a room
     */
    public Map<Integer, UserInfo> getOccupiedSeats(String roomId) {
        RoomMapping mapping = getRoomMapping();
        RoomInfo room = mapping.getRooms().get(roomId);
        if (room == null) return new HashMap<>();
        
        return new HashMap<>(room.getSeats());
    }
    
    /**
     * Check if a seat is occupied
     */
    public boolean isSeatOccupied(String roomId, Integer seatId) {
        return getUserInSeat(roomId, seatId) != null;
    }
    
    /**
     * Get seat ID for a user in a room
     */
    public Integer getUserSeatId(String roomId, String userId) {
        UserInfo user = getUserInRoom(roomId, userId);
        return user != null ? user.getSeatId() : null;
    }
    
    /**
     * Clean up ghost users for a specific username across all rooms
     * This should be called when a user connects to prevent ghost user accumulation
     */
    public synchronized void cleanupGhostUsersForUserName(String userName, String currentUserId) {
        RoomMapping mapping = getRoomMapping();
        boolean hasChanges = false;
        
        for (RoomInfo room : mapping.getRooms().values()) {
            List<Integer> seatsToRemove = new ArrayList<>();
            
            for (Map.Entry<Integer, UserInfo> seat : room.getSeats().entrySet()) {
                UserInfo existingUser = seat.getValue();
                
                // If same username but different userId, check if it's a ghost
                if (existingUser.getUserName().equals(userName) && 
                    !existingUser.getUserId().equals(currentUserId)) {
                    
                    if (isLikelyGhostUser(existingUser.getUserId(), currentUserId)) {
                        logger.info("Cleaning up ghost user {} (username: {}) from seat {} in room {}", 
                                   existingUser.getUserId(), userName, seat.getKey(), room.getRoomId());
                        seatsToRemove.add(seat.getKey());
                        hasChanges = true;
                    }
                }
            }
            
            // Remove ghost users
            for (Integer seatId : seatsToRemove) {
                room.getSeats().remove(seatId);
            }
        }
        
        if (hasChanges) {
            saveRoomMapping(mapping);
            logger.info("Cleaned up ghost users for username: {}", userName);
        }
    }
    
    /**
     * Get all rooms and their occupancy
     */
    public RoomMapping getAllRooms() {
        return getRoomMapping();
    }
    
    /**
     * Clear all mappings (for testing/reset)
     */
    public synchronized void clearAll() {
        redisTemplate.delete(ROOM_MAPPING_KEY);
        logger.info("Cleared all room/seat/user mappings");
    }
    
    /**
     * Get debug info as a simple map
     */
    public Map<String, Object> getDebugInfo() {
        RoomMapping mapping = getRoomMapping();
        Map<String, Object> debug = new HashMap<>();
        
        for (Map.Entry<String, RoomInfo> roomEntry : mapping.getRooms().entrySet()) {
            String roomId = roomEntry.getKey();
            RoomInfo room = roomEntry.getValue();
            
            Map<String, Object> roomDebug = new HashMap<>();
            roomDebug.put("roomId", roomId);
            roomDebug.put("totalSeats", room.getSeats().size());
            
            Map<String, Object> seatsDebug = new HashMap<>();
            for (Map.Entry<Integer, UserInfo> seatEntry : room.getSeats().entrySet()) {
                Integer seatId = seatEntry.getKey();
                UserInfo user = seatEntry.getValue();
                
                Map<String, Object> seatInfo = new HashMap<>();
                seatInfo.put("seatId", seatId);
                seatInfo.put("userId", user.getUserId());
                seatInfo.put("userName", user.getUserName());
                seatInfo.put("joinTime", user.getJoinTime());
                
                seatsDebug.put("seat" + seatId, seatInfo);
            }
            roomDebug.put("seats", seatsDebug);
            
            debug.put(roomId, roomDebug);
        }
        
        debug.put("timestamp", System.currentTimeMillis());
        debug.put("totalRooms", mapping.getRooms().size());
        
        return debug;
    }
    
    /**
     * Broadcast seat occupancy update to all users in room
     */
    private void broadcastSeatUpdate(String roomId, RoomInfo room) {
        try {
            // Create enhanced seat occupancy with username information
            Map<String, Object> enhancedOccupancy = new HashMap<>();
            
            for (Map.Entry<Integer, UserInfo> entry : room.getSeats().entrySet()) {
                UserInfo userInfo = entry.getValue();
                
                // Get additional user profile information if available
                String userName = userInfo.getUserName();
                if (userService != null) {
                    var profile = userService.getUserProfile(userInfo.getUserId());
                    if (profile != null) {
                        userName = profile.getUserName();
                    }
                }
                
                Map<String, Object> seatInfo = Map.of(
                    "userId", userInfo.getUserId(),
                    "userName", userName != null ? userName : "Unknown"
                );
                
                enhancedOccupancy.put(entry.getKey().toString(), seatInfo);
            }
            
            Map<String, Object> seatUpdate = Map.of(
                "type", "SEAT_OCCUPANCY_UPDATE",
                "roomId", roomId,
                "occupancy", enhancedOccupancy
            );
            
            messagingTemplate.convertAndSend("/topic/room/" + roomId + "/seats", seatUpdate);
            logger.debug("Broadcasted seat update for room {}: {} occupied seats", roomId, enhancedOccupancy.size());
            
        } catch (Exception e) {
            logger.error("Error broadcasting seat update for room {}: {}", roomId, e.getMessage());
        }
    }
    
    /**
     * Refresh seat data for a specific user (useful after registration/profile updates)
     */
    public void refreshUserSeatData(String userId) {
        try {
            RoomMapping mapping = getRoomMapping();
            
            for (RoomInfo room : mapping.getRooms().values()) {
                for (UserInfo userInfo : room.getSeats().values()) {
                    if (userInfo.getUserId().equals(userId)) {
                        // Found the user, refresh the room's seat data
                        logger.info("Refreshing seat data for user {} in room {}", userId, room.getRoomId());
                        broadcastSeatUpdate(room.getRoomId(), room);
                        return;
                    }
                }
            }
            
            logger.debug("User {} not found in any seat, no refresh needed", userId);
            
        } catch (Exception e) {
            logger.error("Error refreshing user seat data for {}: {}", userId, e.getMessage());
        }
    }
}