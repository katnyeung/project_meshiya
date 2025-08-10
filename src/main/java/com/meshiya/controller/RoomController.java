package com.meshiya.controller;

import com.meshiya.dto.ChatMessage;
import com.meshiya.model.MessageType;
import com.meshiya.service.RoomService;
import com.meshiya.service.UserService;
import com.meshiya.service.RoomSeatUserManager;
import com.meshiya.service.OrderService;
import com.meshiya.service.UserStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.time.LocalDateTime;

@Controller
public class RoomController {

    private static final Logger logger = LoggerFactory.getLogger(RoomController.class);
    private static final String DEFAULT_ROOM = "room1";

    @Autowired
    private RoomService roomService;
    
    @Autowired
    private UserService userService;
    
    @Autowired
    private RoomSeatUserManager roomSeatUserManager;
    
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    
    @Autowired
    private OrderService orderService;
    
    @Autowired
    private UserStatusService userStatusService;

    @MessageMapping("/room.join")
    public void joinRoom(@Payload ChatMessage message, SimpMessageHeaderAccessor headerAccessor) {
        String roomId = message.getRoomId() != null ? message.getRoomId() : DEFAULT_ROOM;
        String userId = message.getUserId();
        String userName = message.getUserName();
        
        logger.info("User {} ({}) joining room {}", userName, userId, roomId);
        
        // Track user activity and create/update profile
        userService.updateUserActivity(userId, userName, roomId);
        
        // Store user info in session
        headerAccessor.getSessionAttributes().put("username", userName);
        headerAccessor.getSessionAttributes().put("userId", userId);
        headerAccessor.getSessionAttributes().put("roomId", roomId);
        
        // Send initial room state to the user
        roomService.sendInitialRoomState(roomId, userId);
        
        // Create and broadcast join message
        ChatMessage joinMessage = new ChatMessage();
        joinMessage.setType(MessageType.SYSTEM_MESSAGE);
        joinMessage.setUserName("System");
        joinMessage.setUserId("system");
        joinMessage.setContent(userName + " entered the diner");
        joinMessage.setRoomId(roomId);
        joinMessage.setTimestamp(LocalDateTime.now());
        
        roomService.addMessageToRoom(roomId, joinMessage);
    }

    @MessageMapping("/room.sendMessage")
    public void sendMessage(@Payload ChatMessage message) {
        String roomId = message.getRoomId() != null ? message.getRoomId() : DEFAULT_ROOM;
        message.setRoomId(roomId);
        message.setType(MessageType.CHAT_MESSAGE);
        message.setTimestamp(LocalDateTime.now());
        
        logger.info("User {} sent message in room {}: {}", 
                   message.getUserName(), roomId, message.getContent());
        
        // Track user activity and create/update profile
        userService.updateUserActivity(message.getUserId(), message.getUserName(), roomId);
        
        roomService.addMessageToRoom(roomId, message);
        
        // Publish event for Master analysis
        eventPublisher.publishEvent(new com.meshiya.event.ChatMessageEvent(message));
    }

    @MessageMapping("/room.joinSeat")
    public void joinSeat(@Payload ChatMessage message) {
        String roomId = message.getRoomId() != null ? message.getRoomId() : DEFAULT_ROOM;
        Integer seatId = message.getSeatId();
        String userId = message.getUserId();
        String userName = message.getUserName();
        
        logger.info("User {} ({}) requesting seat {} in room {}", userName, userId, seatId, roomId);
        
        // Track user activity and create/update profile
        userService.updateUserActivity(userId, userName, roomId);
        
        boolean success = roomService.joinSeat(roomId, seatId, userId);
        
        // Update centralized mapping and user profile if successful
        if (success) {
            // Check if user was in another seat (for seat swapping)
            Integer oldSeatId = userService.getUserSeat(userId);
            
            roomSeatUserManager.joinSeat(roomId, seatId, userId, userName);
            userService.updateUserSeat(userId, seatId);
            
            // Handle consumables transfer for seat swapping
            if (oldSeatId != null && !oldSeatId.equals(seatId)) {
                // User is swapping seats - transfer consumables
                logger.info("User {} swapping from seat {} to seat {}, transferring consumables", userName, oldSeatId, seatId);
                userStatusService.transferConsumablesOnSeatChange(userId, roomId, oldSeatId, seatId);
            } else if (!userStatusService.hasExistingConsumables(userId, roomId, seatId)) {
                // Fresh join - restore orders only if no active consumables exist
                // This prevents overwriting consumables that are still ticking down
                orderService.restoreUserOrders(userId, roomId, seatId);
                logger.info("Orders and consumables restored for user {} joining seat {} (fresh restore)", userName, seatId);
            } else {
                // User has existing consumables - just restore their status without recreating
                logger.info("User {} joining seat {} - restoring existing consumables without reset", userName, seatId);
                userStatusService.restoreUserConsumables(userId, roomId, seatId);
            }
        }
        
        ChatMessage responseMessage = new ChatMessage();
        responseMessage.setType(MessageType.SYSTEM_MESSAGE);
        responseMessage.setUserName("System");
        responseMessage.setUserId("system");
        responseMessage.setRoomId(roomId);
        responseMessage.setTimestamp(LocalDateTime.now());
        
        if (success) {
            responseMessage.setContent(userName + " took seat " + seatId);
        } else {
            responseMessage.setContent("Seat " + seatId + " is already occupied");
        }
        
        roomService.addMessageToRoom(roomId, responseMessage);
    }

    @MessageMapping("/room.leaveSeat")
    public void leaveSeat(@Payload ChatMessage message) {
        String roomId = message.getRoomId() != null ? message.getRoomId() : DEFAULT_ROOM;
        String userId = message.getUserId();
        String userName = message.getUserName();
        
        logger.info("User {} ({}) leaving seat in room {}", userName, userId, roomId);
        
        // Track user activity and create/update profile
        userService.updateUserActivity(userId, userName, roomId);
        
        boolean success = roomService.leaveSeat(roomId, userId);
        
        // Update centralized mapping and user profile
        if (success) {
            roomSeatUserManager.leaveSeat(roomId, userId);
            userService.removeUserSeat(userId);
        }
        
        if (success) {
            ChatMessage responseMessage = new ChatMessage();
            responseMessage.setType(MessageType.SYSTEM_MESSAGE);
            responseMessage.setUserName("System");
            responseMessage.setUserId("system");
            responseMessage.setContent(userName + " left their seat");
            responseMessage.setRoomId(roomId);
            responseMessage.setTimestamp(LocalDateTime.now());
            
            roomService.addMessageToRoom(roomId, responseMessage);
        }
    }
    
    @MessageMapping("/user-status.refresh")
    public void refreshUserStatus(@Payload ChatMessage message) {
        String roomId = message.getRoomId() != null ? message.getRoomId() : DEFAULT_ROOM;
        String userId = message.getUserId();
        
        logger.info("User {} requesting user status refresh for room {}", userId, roomId);
        
        // Send current user status for all users in the room
        userStatusService.broadcastAllUserStatuses(roomId);
    }
}