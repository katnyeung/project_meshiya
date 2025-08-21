package com.meshiya.controller;

import com.meshiya.dto.ChatMessage;
import com.meshiya.model.MessageType;
import com.meshiya.service.ChatService;
import com.meshiya.service.RoomTVService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

@Controller
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    @Autowired
    private ChatService chatService;
    
    @Autowired
    private RoomTVService roomTVService;

    @MessageMapping("/chat.sendMessage")
    @SendTo("/topic/public")
    public ChatMessage sendMessage(@Payload ChatMessage chatMessage) {
        logger.info("Received chat message: userId={}, userName={}, type={}, content={}", 
                    chatMessage.getUserId(), chatMessage.getUserName(), 
                    chatMessage.getType(), chatMessage.getContent());
        
        ChatMessage response = chatService.processMessage(chatMessage);
        
        logger.info("Sending chat response: type={}, content={}", 
                    response.getType(), response.getContent());
        
        return response;
    }

    @MessageMapping("/chat.requestInitialState")
    public void requestInitialState(@Payload ChatMessage message) {
        logger.info("Received initial state request from user: {}", message.getUserId());
        chatService.sendInitialStateToUser(message.getUserId());
    }

    @MessageMapping("/chat.addUser")
    @SendTo("/topic/public")
    public ChatMessage addUser(@Payload ChatMessage chatMessage,
                               SimpMessageHeaderAccessor headerAccessor) {
        logger.info("Received addUser request: userId={}, userName={}, type={}", 
                    chatMessage.getUserId(), chatMessage.getUserName(), chatMessage.getType());
        
        headerAccessor.getSessionAttributes().put("username", chatMessage.getUserName());
        headerAccessor.getSessionAttributes().put("userId", chatMessage.getUserId());
        
        // Schedule initial state sending with a delay to ensure subscriptions are ready
        String userId = chatMessage.getUserId();
        new Thread(() -> {
            try {
                Thread.sleep(1000); // Wait 1s for frontend subscriptions to be established
                chatService.sendInitialStateToUser(userId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Initial state sending interrupted for user {}", userId);
            }
        }).start();
        
        // Override type to SYSTEM_MESSAGE regardless of what was sent
        MessageType originalType = chatMessage.getType();
        chatMessage.setType(MessageType.SYSTEM_MESSAGE);
        chatMessage.setContent(chatMessage.getUserName() + " entered the diner");
        
        logger.info("User added to diner: userId={}, userName={}, originalType={}, newType={}, content={}", 
                    chatMessage.getUserId(), chatMessage.getUserName(), 
                    originalType, chatMessage.getType(), chatMessage.getContent());
        
        // Add system message to chat history
        chatService.addMessageToHistory(chatMessage);
        
        return chatMessage;
    }

    @MessageMapping("/seat.join")
    @SendTo("/topic/seats")
    public ChatMessage joinSeat(@Payload ChatMessage message) {
        logger.info("Received seat join request: userId={}, userName={}, seatId={}, type={}", 
                    message.getUserId(), message.getUserName(), message.getSeatId(), message.getType());
        
        ChatMessage response = chatService.joinSeat(message);
        
        logger.info("Seat join response: userId={}, userName={}, seatId={}, type={}, content={}", 
                    response.getUserId(), response.getUserName(), response.getSeatId(), 
                    response.getType(), response.getContent());
        
        // Add seat join message to chat history if successful
        if (response.getType() == MessageType.JOIN_SEAT) {
            ChatMessage historyMessage = new ChatMessage();
            historyMessage.setType(MessageType.SYSTEM_MESSAGE);
            historyMessage.setContent(response.getContent());
            historyMessage.setUserName("System");
            historyMessage.setUserId("system");
            chatService.addMessageToHistory(historyMessage);
        }
        
        return response;
    }

    @MessageMapping("/seat.leave")
    @SendTo("/topic/seats")
    public ChatMessage leaveSeat(@Payload ChatMessage message) {
        logger.info("Received seat leave request: userId={}, userName={}, seatId={}, type={}", 
                    message.getUserId(), message.getUserName(), message.getSeatId(), message.getType());
        
        ChatMessage response = chatService.leaveSeat(message);
        
        logger.info("Seat leave response: userId={}, userName={}, seatId={}, type={}, content={}", 
                    response.getUserId(), response.getUserName(), response.getSeatId(), 
                    response.getType(), response.getContent());
        
        // Add seat leave message to chat history if successful
        if (response.getType() == MessageType.LEAVE_SEAT) {
            ChatMessage historyMessage = new ChatMessage();
            historyMessage.setType(MessageType.SYSTEM_MESSAGE);
            historyMessage.setContent(response.getContent());
            historyMessage.setUserName("System");
            historyMessage.setUserId("system");
            chatService.addMessageToHistory(historyMessage);
        }
        
        return response;
    }

    @MessageMapping("/video.control")
    public void handleVideoControl(@Payload ChatMessage message) {
        logger.info("Received video control: userId={}, roomId={}, type={}, action={}", 
                    message.getUserId(), message.getRoomId(), message.getType(), message.getContent());
        
        String roomId = message.getRoomId();
        String userId = message.getUserId();
        
        if (roomId == null || userId == null) {
            logger.warn("Invalid video control message - missing roomId or userId");
            return;
        }
        
        // Handle video control types for room TV
        switch (message.getType()) {
            case VIDEO_STOP:
                // Stop the room TV (like turning off TV)
                roomTVService.stopRoomTV(roomId, userId, message.getUserName());
                logger.info("User {} stopped room TV in {}", message.getUserName(), roomId);
                break;
            case VIDEO_PAUSE:
            case VIDEO_PLAY_REQUEST:
                // These are now handled locally by frontend - room TV keeps playing
                logger.debug("Personal video control from {} in room {}: {}", message.getUserName(), roomId, message.getType());
                break;
            default:
                logger.warn("Unhandled video control type: {}", message.getType());
        }
    }

    @MessageMapping("/video.join")
    public void joinVideoSession(@Payload ChatMessage message) {
        logger.debug("User {} requesting video sync in room {}", message.getUserId(), message.getRoomId());
        
        String roomId = message.getRoomId();
        String userId = message.getUserId();
        
        if (roomId != null && userId != null) {
            // Send current room TV state to user (like looking at the TV when entering)
            roomTVService.sendTVStateToUser(userId, roomId);
        }
    }

    @MessageMapping("/video.leave")
    public void leaveVideoSession(@Payload ChatMessage message) {
        // No action needed - room TV continues playing regardless of who's watching
        logger.debug("User {} no longer watching room TV in {}", message.getUserId(), message.getRoomId());
    }
}