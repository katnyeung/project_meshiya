package com.meshiya.service;

import com.meshiya.model.Room;
import com.meshiya.dto.ChatMessage;
import com.meshiya.dto.UserProfile;
import com.meshiya.model.MessageType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
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
    @Lazy
    private UserService userService;
    
    @Autowired
    private ConsumableService consumableService;
    
    private final ObjectMapper objectMapper;
    
    public RoomService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }
    
    // Startup logic removed - now handled by scheduler
    
    /**
     * Initialize room with default setup - generic for any room
     */
    public void initializeRoom(String roomId, String roomName, String masterName, String welcomeMessage) {
        Room room = getRoom(roomId);
        
        if (room == null) {
            room = new Room(roomId, roomName);
            
            // Add Master welcome message
            ChatMessage welcome = new ChatMessage();
            welcome.setType(MessageType.AI_MESSAGE);
            welcome.setUserName(masterName);
            welcome.setUserId("master");
            welcome.setContent(welcomeMessage);
            welcome.setTimestamp(LocalDateTime.now());
            
            room.addMessage(welcome);
            saveRoom(room);
            
            logger.info("Initialized room {} ({}) with master welcome message", roomId, roomName);
        } else {
            // Verify room is actually valid and accessible
            try {
                room.getMessages(); // Test access
                logger.info("Room {} already exists with {} messages", roomId, room.getMessages().size());
            } catch (Exception e) {
                logger.warn("Room {} exists but is corrupted, reinitializing: {}", roomId, e.getMessage());
                // Reinitialize corrupted room
                room = new Room(roomId, roomName);
                ChatMessage welcome = new ChatMessage();
                welcome.setType(MessageType.AI_MESSAGE);
                welcome.setUserName(masterName);
                welcome.setUserId("master");
                welcome.setContent(welcomeMessage);
                welcome.setTimestamp(LocalDateTime.now());
                room.addMessage(welcome);
                saveRoom(room);
                logger.info("Reinitialized corrupted room {}", roomId);
            }
        }
    }
    
    /**
     * Initialize Room1 at startup - backward compatibility
     */
    public void initializeRoom1() {
        initializeRoom("room1", "Midnight Diner - Main Room", "Master", 
                      "Welcome to my midnight diner. The night is long, and there's always time for a good conversation.");
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
     * Add message to room and broadcast - room must exist
     */
    public void addMessageToRoom(String roomId, ChatMessage message) {
        Room room = getRoom(roomId);
        if (room == null) {
            logger.error("Room {} does not exist - cannot add message. Create room first.", roomId);
            return;
        }
        
        room.addMessage(message);
        saveRoom(room);
        
        // Broadcast to all users in the room
        messagingTemplate.convertAndSend("/topic/room/" + roomId, message);
        logger.info("Added message to {} and broadcast: {}", roomId, message.getContent());
    }
    
    // Seat operations removed - now handled by SeatService
    
    /**
     * Get all messages for a room (for new users)
     */
    public List<ChatMessage> getRoomMessages(String roomId) {
        Room room = getRoom(roomId);
        return room != null ? room.getMessages() : List.of();
    }
    
    // Seat occupancy removed - now handled by SeatService
    
    // Seat broadcasting removed - now handled by SeatService
    
    /**
     * Send initial room state to a user - room must exist
     */
    public void sendInitialRoomState(String roomId, String userId) {
        Room room = getRoom(roomId);
        if (room == null) {
            logger.error("Room {} does not exist - cannot send initial state. Create room first.", roomId);
            return;
        }
        
        logger.info("Sending initial state for room {} to user {}: {} messages", 
                   roomId, userId, room.getMessages().size());
        
        // Send all messages
        for (ChatMessage message : room.getMessages()) {
            messagingTemplate.convertAndSend("/topic/room/" + roomId, message);
        }
        
        // Note: Seat occupancy broadcasting now handled by SeatService
    }
}