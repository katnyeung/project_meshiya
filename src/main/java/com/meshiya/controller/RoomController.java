package com.meshiya.controller;

import com.meshiya.dto.ChatMessage;
import com.meshiya.model.MessageType;
import com.meshiya.service.RoomService;
import com.meshiya.service.UserService;
import com.meshiya.service.SeatService;
import com.meshiya.service.OrderService;
import com.meshiya.service.ConsumableService;
import com.meshiya.service.AvatarStateService;
import com.meshiya.service.RoomTVService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Controller
public class RoomController {

    private static final Logger logger = LoggerFactory.getLogger(RoomController.class);
    private static final String DEFAULT_ROOM = "room1";

    @Autowired
    private RoomService roomService;
    
    @Autowired
    private UserService userService;
    
    @Autowired
    private SeatService seatService;
    
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    
    @Autowired
    private OrderService orderService;
    
    @Autowired
    private ConsumableService consumableService;
    
    @Autowired
    private RoomTVService roomTVService;
    
    @Autowired
    private AvatarStateService avatarStateService;
    
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/room.join")
    public void joinRoom(@Payload ChatMessage message, SimpMessageHeaderAccessor headerAccessor) {
        String roomId = message.getRoomId() != null ? message.getRoomId() : DEFAULT_ROOM;
        String userId = message.getUserId();
        String userName = message.getUserName();
        
        logger.info("User {} ({}) joining room {}", userName, userId, roomId);
        
        // Clean up any ghost users for this username before joining
        seatService.cleanupGhostUsersForUserName(userName, userId);
        
        // Track user activity and create/update profile
        userService.updateUserActivity(userId, userName, roomId);
        
        // Store user info in session
        headerAccessor.getSessionAttributes().put("username", userName);
        headerAccessor.getSessionAttributes().put("userId", userId);
        headerAccessor.getSessionAttributes().put("roomId", roomId);
        
        // Send initial room state to the user
        roomService.sendInitialRoomState(roomId, userId);
        
        // Send initial seat occupancy state
        sendInitialSeatState(roomId);
        
        // Restore TV state for users joining/rejoining the room (for page refreshes)
        roomTVService.sendTVStateToUser(userId, roomId);
        logger.debug("TV state restoration sent for user {} joining room {}", userName, roomId);
        
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
        
        // Trigger chatting avatar state when user sends a message
        logger.info("Chat message details: userId={}, roomId={}, seatId={}", 
                   message.getUserId(), message.getRoomId(), message.getSeatId());
        
        if (message.getUserId() != null) {
            String actualRoomId = message.getRoomId() != null ? message.getRoomId() : roomId;
            Integer seatId = message.getSeatId();
            
            // If seatId is not in message, try to find user's current seat
            if (seatId == null) {
                // Get from UserService
                seatId = userService.getUserSeat(message.getUserId());
                logger.info("Found user {} current seat: {}", message.getUserId(), seatId);
            }
            
            if (seatId != null) {
                logger.info("Triggering avatar state for user {} in room {} seat {}", 
                           message.getUserId(), actualRoomId, seatId);
                avatarStateService.recordUserActivity(message.getUserId(), actualRoomId, seatId);
                avatarStateService.triggerChattingState(message.getUserId(), actualRoomId, seatId);
            } else {
                logger.warn("Cannot trigger avatar state - user {} not in a seat", message.getUserId());
            }
        }
        
        // Check for video commands (following order system pattern)
        String content = message.getContent();
        if (content != null) {
            String trimmedContent = content.trim();
            
            if (trimmedContent.startsWith("/play ")) {
                logger.info("Processing TV play command from {}: {}", message.getUserName(), content);
                ChatMessage tvResponse = roomTVService.processPlayCommand(message);
                
                // Add TV response to room and broadcast it
                roomService.addMessageToRoom(roomId, tvResponse);
                return; // Don't process as regular chat message
            } else if (trimmedContent.equals("/tv stop") || trimmedContent.equals("/stop")) {
                logger.info("Processing TV stop command from {}: {}", message.getUserName(), content);
                
                // Stop the room TV
                roomTVService.stopRoomTV(roomId, message.getUserId(), message.getUserName());
                
                // Create chat message to show who stopped the TV
                ChatMessage stopChatMessage = new ChatMessage();
                stopChatMessage.setType(MessageType.SYSTEM_MESSAGE);
                stopChatMessage.setUserId("system");
                stopChatMessage.setUserName("System");
                stopChatMessage.setRoomId(roomId);
                stopChatMessage.setContent(message.getUserName() + " turned off the TV");
                stopChatMessage.setTimestamp(java.time.LocalDateTime.now());
                
                // Add to room chat
                roomService.addMessageToRoom(roomId, stopChatMessage);
                
                return; // Don't process as regular chat message
            }
        }
        
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
        
        // Check if user was in another seat (for seat swapping)
        Integer oldSeatId = userService.getUserSeat(userId);
        
        // Use SeatService as single source of truth for seat operations
        boolean success = seatService.joinSeat(roomId, seatId, userId, userName);
        
        // Update user profile if successful
        if (success) {
            userService.updateUserSeat(userId, seatId);
            
            // Handle consumables transfer for seat swapping
            if (oldSeatId != null && !oldSeatId.equals(seatId)) {
                // User is swapping seats - transfer consumables with suppressed broadcast
                logger.info("User {} swapping from seat {} to seat {}, transferring consumables", userName, oldSeatId, seatId);
                consumableService.transferConsumablesOnSeatChange(userId, roomId, oldSeatId, seatId, true);
                // Final broadcast will be handled after all seat operations are complete
            } else if (!consumableService.hasExistingConsumables(userId, roomId, seatId)) {
                // Fresh join - restore orders only if no active consumables exist
                // This prevents overwriting consumables that are still ticking down
                orderService.restoreUserOrders(userId, roomId, seatId);
                logger.info("Orders and consumables restored for user {} joining seat {} (fresh restore)", userName, seatId);
            } else {
                // User has existing consumables - just restore their status without recreating
                logger.info("User {} joining seat {} - restoring existing consumables without reset", userName, seatId);
                consumableService.restoreUserConsumables(userId, roomId, seatId);
            }
            
            // Always broadcast final user status after all consumable operations are complete
            // This ensures only one USER_STATUS_UPDATE message is sent per seat join
            if (oldSeatId != null && !oldSeatId.equals(seatId)) {
                // For seat swaps, first clear old seat, then broadcast new seat status
                consumableService.broadcastEmptyUserStatus(userId, roomId, oldSeatId);
                // Small delay to ensure message ordering
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            consumableService.broadcastUserStatusUpdate(userId, roomId, seatId);
            
            // Always restore TV state for users joining seats (like turning on TV when entering)
            roomTVService.sendTVStateToUser(userId, roomId);
            logger.debug("TV state restoration sent for user {} joining seat {}", userName, seatId);
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
        
        // NOTE: Don't update user activity when leaving seat - this prevents 
        // proper cleanup of inactive/timed-out users
        
        // Use SeatService as single source of truth for seat operations
        boolean success = seatService.leaveSeat(roomId, userId);
        
        // Update user profile if successful
        if (success) {
            userService.removeUserSeat(userId);
            
            // Note: Unlike consumables, we DON'T remove users from video viewers
            // Video is a shared room experience that continues even when users leave seats
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
        
        // NOTE: Status refresh is automatic/passive - don't update user activity timestamp
        // This prevents ghost users from staying alive due to automated frontend requests
        
        // Send current user status for all users in the room
        consumableService.broadcastAllUserStatuses(roomId);
    }
    
    /**
     * Send initial seat occupancy state to all users in room
     */
    private void sendInitialSeatState(String roomId) {
        try {
            SeatService.RoomMapping allRooms = seatService.getAllRooms();
            SeatService.RoomInfo room = allRooms.getRooms().get(roomId);
            
            if (room != null && !room.getSeats().isEmpty()) {
                // Create enhanced seat occupancy with username information
                Map<String, Object> enhancedOccupancy = new HashMap<>();
                
                for (Map.Entry<Integer, SeatService.UserInfo> entry : room.getSeats().entrySet()) {
                    SeatService.UserInfo userInfo = entry.getValue();
                    
                    // Get additional user profile information if available
                    String userName = userInfo.getUserName();
                    var profile = userService.getUserProfile(userInfo.getUserId());
                    if (profile != null) {
                        userName = profile.getUserName();
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
                logger.debug("Sent initial seat state for room {}: {} occupied seats", roomId, enhancedOccupancy.size());
            } else {
                // Send empty occupancy if no seats are occupied
                Map<String, Object> seatUpdate = Map.of(
                    "type", "SEAT_OCCUPANCY_UPDATE",
                    "roomId", roomId,
                    "occupancy", Map.of()
                );
                
                messagingTemplate.convertAndSend("/topic/room/" + roomId + "/seats", seatUpdate);
                logger.debug("Sent initial seat state for room {} (no occupied seats)", roomId);
            }
            
        } catch (Exception e) {
            logger.error("Error sending initial seat state for room {}: {}", roomId, e.getMessage());
        }
    }
}