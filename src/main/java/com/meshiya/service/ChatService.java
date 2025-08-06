package com.meshiya.service;

import com.meshiya.dto.ChatMessage;
import com.meshiya.model.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import com.meshiya.event.ChatMessageEvent;
import java.util.concurrent.ConcurrentHashMap;
import java.util.*;
import java.util.Map;

@Service
public class ChatService {
    
    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);
    
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    
    @Autowired
    private RedisService redisService;

    public ChatMessage processMessage(ChatMessage message) {
        if (message.getType() == MessageType.CHAT_MESSAGE) {
            logger.info("Processing chat message from {}: {}", message.getUserName(), message.getContent());
            
            // Store the message in Redis
            redisService.addMessage(message);
            
            // Publish event for Master response analysis
            eventPublisher.publishEvent(new ChatMessageEvent(message));
            
            return message;
        }
        return message;
    }
    
    /**
     * Adds any message type to recent history (for chat display)
     */
    public void addMessageToHistory(ChatMessage message) {
        // Store all message types in Redis for display purposes
        if (message.getType() == MessageType.CHAT_MESSAGE || 
            message.getType() == MessageType.AI_MESSAGE || 
            message.getType() == MessageType.SYSTEM_MESSAGE) {
            redisService.addMessage(message);
        }
    }

    public ChatMessage joinSeat(ChatMessage message) {
        Integer seatId = message.getSeatId();
        String userId = message.getUserId();
        
        if (seatId == null || seatId < 1 || seatId > 8) {
            message.setType(MessageType.SYSTEM_MESSAGE);
            message.setContent("Invalid seat number");
            return message;
        }
        
        String existingOccupant = redisService.getSeatOccupant(seatId);
        if (existingOccupant != null) {
            message.setType(MessageType.SYSTEM_MESSAGE);
            message.setContent("Seat " + seatId + " is already occupied");
            return message;
        }
        
        // Remove user from previous seat if they had one
        Integer previousSeat = redisService.getUserSeat(userId);
        if (previousSeat != null) {
            redisService.clearSeat(previousSeat);
        }
        
        redisService.setSeatOccupancy(seatId, userId);
        redisService.setUserSeat(userId, seatId);
        
        message.setType(MessageType.JOIN_SEAT);
        message.setContent(message.getUserName() + " took seat " + seatId);
        
        return message;
    }

    public ChatMessage leaveSeat(ChatMessage message) {
        String userId = message.getUserId();
        
        Integer seatId = redisService.getUserSeat(userId);
        if (seatId == null) {
            message.setType(MessageType.SYSTEM_MESSAGE);
            message.setContent("You are not seated");
            return message;
        }
        
        redisService.clearSeat(seatId);
        redisService.removeUser(userId);
        
        message.setType(MessageType.LEAVE_SEAT);
        message.setSeatId(seatId);
        message.setContent(message.getUserName() + " left seat " + seatId);
        
        return message;
    }

    public Map<Integer, String> getSeatOccupancy() {
        return redisService.getAllSeatOccupancy();
    }
    
    /**
     * Gets recent chat messages from Redis
     */
    public List<ChatMessage> getRecentMessages() {
        return redisService.getAllRecentMessages();
    }
    
    /**
     * Sends initial state to a newly connected user
     */
    public void sendInitialStateToUser(String userId) {
        logger.info("Sending initial state to user: {}", userId);
        
        // Send chat history
        List<ChatMessage> messages = getRecentMessages();
        logger.info("Sending {} messages to user {}", messages.size(), userId);
        
        for (ChatMessage msg : messages) {
            logger.info("Sending message to user {}: type={}, sender={}, content={}", 
                       userId, msg.getType(), msg.getUserName(), msg.getContent());
            messagingTemplate.convertAndSendToUser(userId, "/queue/history", msg);
        }
        
        // Send current seat occupancy
        Map<String, Object> seatState = new HashMap<>();
        seatState.put("type", "SEAT_STATE");
        seatState.put("occupancy", getSeatOccupancy());
        messagingTemplate.convertAndSendToUser(userId, "/queue/seats", seatState);
        
        logger.info("Completed sending {} messages and seat occupancy to user {}", messages.size(), userId);
    }
}