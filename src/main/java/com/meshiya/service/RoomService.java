package com.meshiya.service;

import com.meshiya.model.Room;
import com.meshiya.dto.ChatMessage;
import com.meshiya.model.MessageType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class RoomService {
    
    private static final Logger logger = LoggerFactory.getLogger(RoomService.class);
    private static final String ROOM_KEY_PREFIX = "room:";
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    
    @Autowired
    private ConsumableService consumableService;
    
    private final ObjectMapper objectMapper;
    
    public RoomService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }
    
    /**
     * Initialize Room1 at startup - always ensures room exists
     */
    public void initializeRoom1() {
        String roomId = "room1";
        Room room = getRoom(roomId);
        
        if (room == null) {
            room = new Room(roomId, "Midnight Diner - Main Room");
            
            // Add Master welcome message
            ChatMessage welcomeMessage = new ChatMessage();
            welcomeMessage.setType(MessageType.AI_MESSAGE);
            welcomeMessage.setUserName("Master");
            welcomeMessage.setUserId("master");
            welcomeMessage.setContent("Welcome to my midnight diner. The night is long, and there's always time for a good conversation.");
            welcomeMessage.setTimestamp(LocalDateTime.now());
            
            room.addMessage(welcomeMessage);
            saveRoom(room);
            
            logger.info("Initialized Room1 with Master welcome message");
        } else {
            // Verify room is actually valid and accessible
            try {
                room.getMessages(); // Test access
                logger.info("Room1 already exists with {} messages and {} occupied seats", 
                           room.getMessages().size(), room.getSeatOccupancy().size());
            } catch (Exception e) {
                logger.warn("Room1 exists but is corrupted, reinitializing: {}", e.getMessage());
                // Reinitialize corrupted room
                room = new Room(roomId, "Midnight Diner - Main Room");
                ChatMessage welcomeMessage = new ChatMessage();
                welcomeMessage.setType(MessageType.AI_MESSAGE);
                welcomeMessage.setUserName("Master");
                welcomeMessage.setUserId("master");
                welcomeMessage.setContent("Welcome to my midnight diner. The night is long, and there's always time for a good conversation.");
                welcomeMessage.setTimestamp(LocalDateTime.now());
                room.addMessage(welcomeMessage);
                saveRoom(room);
                logger.info("Reinitialized corrupted Room1");
            }
        }
    }
    
    /**
     * Get room from Redis, return null if not found
     */
    public Room getRoom(String roomId) {
        try {
            Object roomData = redisTemplate.opsForValue().get(ROOM_KEY_PREFIX + roomId);
            if (roomData != null) {
                return objectMapper.readValue(roomData.toString(), Room.class);
            }
        } catch (JsonProcessingException e) {
            logger.error("Error deserializing room {} from Redis", roomId, e);
        }
        return null;
    }
    
    /**
     * Save room to Redis
     */
    public void saveRoom(Room room) {
        try {
            String roomJson = objectMapper.writeValueAsString(room);
            redisTemplate.opsForValue().set(ROOM_KEY_PREFIX + room.getRoomId(), roomJson);
            logger.debug("Saved room {} to Redis", room.getRoomId());
        } catch (JsonProcessingException e) {
            logger.error("Error serializing room {} to Redis", room.getRoomId(), e);
        }
    }
    
    /**
     * Add message to room and broadcast - creates room if it doesn't exist
     */
    public void addMessageToRoom(String roomId, ChatMessage message) {
        Room room = getRoom(roomId);
        if (room == null) {
            logger.warn("Room {} does not exist, initializing it", roomId);
            if ("room1".equals(roomId)) {
                initializeRoom1();
                room = getRoom(roomId);
            } else {
                logger.error("Cannot auto-create room other than room1: {}", roomId);
                return;
            }
        }
        
        if (room != null) {
            room.addMessage(message);
            saveRoom(room);
            
            // Broadcast to all users in the room
            messagingTemplate.convertAndSend("/topic/room/" + roomId, message);
            logger.info("Added message to {} and broadcast: {}", roomId, message.getContent());
        }
    }
    
    /**
     * Join seat in room - creates room if it doesn't exist
     */
    public boolean joinSeat(String roomId, Integer seatId, String userId) {
        Room room = getRoom(roomId);
        if (room == null) {
            logger.warn("Room {} does not exist for seat join, initializing it", roomId);
            if ("room1".equals(roomId)) {
                initializeRoom1();
                room = getRoom(roomId);
            } else {
                logger.error("Cannot auto-create room other than room1: {}", roomId);
                return false;
            }
        }
        
        if (room == null) {
            logger.error("Failed to initialize room {}", roomId);
            return false;
        }
        
        if (room.isSeatOccupied(seatId)) {
            logger.info("Seat {} is already occupied in room {}", seatId, roomId);
            return false;
        }
        
        // Remove user from previous seat if they had one
        Integer previousSeat = room.getUserSeat(userId);
        if (previousSeat != null) {
            logger.info("User {} moving from seat {} to seat {} in room {}", userId, previousSeat, seatId, roomId);
            room.freeSeat(previousSeat);
            
            // Transfer consumables to new seat (since consumables now follow user)
            consumableService.transferConsumablesOnSeatChange(userId, roomId, previousSeat, seatId);
        } else {
            // User joining for first time or rejoining after disconnect
            logger.info("User {} joining seat {} in room {} (new or reconnecting)", userId, seatId, roomId);
            
            // Restore user's consumables when they rejoin
            consumableService.restoreUserConsumables(userId, roomId, seatId);
        }
        
        room.occupySeat(seatId, userId);
        saveRoom(room);
        
        // Broadcast seat update
        broadcastSeatUpdate(roomId, room.getSeatOccupancy());
        logger.info("User {} joined seat {} in room {} (consumables restored)", userId, seatId, roomId);
        return true;
    }
    
    /**
     * Leave seat in room
     */
    public boolean leaveSeat(String roomId, String userId) {
        Room room = getRoom(roomId);
        if (room == null) {
            return false;
        }
        
        Integer seatId = room.getUserSeat(userId);
        if (seatId == null) {
            return false;
        }
        
        // Clear user's consumables when leaving seat
        consumableService.clearUserConsumables(userId, roomId, seatId);
        
        room.freeSeat(seatId);
        saveRoom(room);
        
        // Broadcast seat update
        broadcastSeatUpdate(roomId, room.getSeatOccupancy());
        logger.info("User {} left seat {} in room {}", userId, seatId, roomId);
        return true;
    }
    
    /**
     * Get all messages for a room (for new users)
     */
    public List<ChatMessage> getRoomMessages(String roomId) {
        Room room = getRoom(roomId);
        return room != null ? room.getMessages() : List.of();
    }
    
    /**
     * Get seat occupancy for a room
     */
    public Map<Integer, String> getRoomSeatOccupancy(String roomId) {
        Room room = getRoom(roomId);
        return room != null ? room.getSeatOccupancy() : Map.of();
    }
    
    /**
     * Broadcast seat occupancy update to all users in room
     */
    private void broadcastSeatUpdate(String roomId, Map<Integer, String> seatOccupancy) {
        Map<String, Object> seatUpdate = Map.of(
            "type", "SEAT_OCCUPANCY_UPDATE",
            "roomId", roomId,
            "occupancy", seatOccupancy
        );
        messagingTemplate.convertAndSend("/topic/room/" + roomId + "/seats", seatUpdate);
    }
    
    /**
     * Send initial room state to a user - creates room if it doesn't exist
     */
    public void sendInitialRoomState(String roomId, String userId) {
        Room room = getRoom(roomId);
        if (room == null) {
            logger.warn("Room {} does not exist for initial state, initializing it", roomId);
            if ("room1".equals(roomId)) {
                initializeRoom1();
                room = getRoom(roomId);
            } else {
                logger.error("Cannot auto-create room other than room1: {}", roomId);
                return;
            }
        }
        
        if (room == null) {
            logger.error("Failed to initialize room {} for initial state", roomId);
            return;
        }
        
        logger.info("Sending initial state for room {} to user {}: {} messages, {} occupied seats", 
                   roomId, userId, room.getMessages().size(), room.getSeatOccupancy().size());
        
        // Send all messages
        for (ChatMessage message : room.getMessages()) {
            messagingTemplate.convertAndSend("/topic/room/" + roomId, message);
        }
        
        // Send seat occupancy
        broadcastSeatUpdate(roomId, room.getSeatOccupancy());
    }
}