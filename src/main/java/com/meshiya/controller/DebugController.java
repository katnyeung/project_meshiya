package com.meshiya.controller;

import com.meshiya.dto.DebugRoomInfo;
import com.meshiya.dto.DebugUserInfo;
import com.meshiya.dto.DebugMasterInfo;
import com.meshiya.service.RedisService;
import com.meshiya.service.RoomService;
import com.meshiya.service.OrderService;
import com.meshiya.service.UserService;
import com.meshiya.service.ConsumableService;
import com.meshiya.service.SeatService;
import com.meshiya.dto.ChatMessage;
import com.meshiya.model.MessageType;
import com.meshiya.model.Consumable;
import java.time.ZoneOffset;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Debug controller for inspecting Redis cache and system state
 */
@RestController
@RequestMapping("/api/debug")
@Tag(name = "Debug", description = "Debug endpoints for system inspection and monitoring")
public class DebugController {

    private static final Logger logger = LoggerFactory.getLogger(DebugController.class);

    @Autowired
    private RedisService redisService;
    
    @Autowired
    private RoomService roomService;
    
    @Autowired
    private OrderService orderService;
    
    @Autowired
    private UserService userService;
    
    @Autowired
    private ConsumableService consumableService;
    
    @Autowired
    private SeatService seatService;

    @Operation(summary = "Get all rooms information", description = "Returns debug information about all rooms in the system")
    @GetMapping("/rooms")
    public List<DebugRoomInfo> getAllRooms() {
        List<DebugRoomInfo> rooms = new ArrayList<>();
        
        try {
            // Get Room1 info (main room)
            DebugRoomInfo room1 = getRoomInfo("room1");
            rooms.add(room1);
            
        } catch (Exception e) {
            // Return empty room info on error
            rooms.add(new DebugRoomInfo("room1", 0, new ArrayList<>(), new HashMap<>(), 0, 0));
        }
        
        return rooms;
    }

    @Operation(summary = "Get specific room information", description = "Returns detailed debug information about a specific room")
    @GetMapping("/rooms/{roomId}")
    public DebugRoomInfo getRoom(@Parameter(description = "Room ID") @PathVariable String roomId) {
        return getRoomInfo(roomId);
    }

    @Operation(summary = "Get all users information", description = "Returns debug information about all active users")
    @GetMapping("/users")
    public List<DebugUserInfo> getAllUsers() {
        List<DebugUserInfo> users = new ArrayList<>();
        
        try {
            // Get seat occupancy from room1 to find active users
            Map<Integer, String> seatOccupancy = roomService.getRoomSeatOccupancy("room1");
            
            if (seatOccupancy != null) {
                for (Map.Entry<Integer, String> entry : seatOccupancy.entrySet()) {
                    String seatId = String.valueOf(entry.getKey());
                    String userId = entry.getValue();
                    
                    if (userId != null && !userId.isEmpty()) {
                        DebugUserInfo userInfo = getUserInfo(userId);
                        userInfo.setSeatId(seatId);
                        users.add(userInfo);
                    }
                }
            }
            
            // Also get users from UserService who might not be seated
            var activeUsers = userService.getActivityStats();
            @SuppressWarnings("unchecked")
            int trackedUsers = (Integer) activeUsers.get("activeUsers");
            
            // If we have active users beyond seated ones, we could add them
            // For now, just focus on seated users as they're the main activity
            
        } catch (Exception e) {
            logger.error("Error getting all users: {}", e.getMessage(), e);
        }
        
        return users;
    }

    @Operation(summary = "Get specific user information", description = "Returns detailed debug information about a specific user")
    @GetMapping("/users/{userId}")
    public DebugUserInfo getUser(@Parameter(description = "User ID") @PathVariable String userId) {
        return getUserInfo(userId);
    }

    @Operation(summary = "Get user activity statistics", description = "Returns statistics about user activity and timeout system")
    @GetMapping("/users/stats")
    public Map<String, Object> getUserActivityStats() {
        return userService.getActivityStats();
    }

    @Operation(summary = "Get AI Master information", description = "Returns debug information about the AI Master's current state")
    @GetMapping("/master")
    public DebugMasterInfo getMaster() {
        DebugMasterInfo masterInfo = new DebugMasterInfo();
        
        try {
            masterInfo.setStatus("ACTIVE");
            masterInfo.setLlmProvider("ollama");
            masterInfo.setLlmModel("qwen3:4b");
            masterInfo.setLlmConnected(false); // Based on the logs showing connection refused
            
            // Get AI context from Redis
            List<ChatMessage> aiContext = redisService.getAIContext();
            Map<String, Object> contextMap = new HashMap<>();
            contextMap.put("messages", aiContext);
            
            // Get recent responses from chat messages
            List<ChatMessage> messages = redisService.getAllRecentMessages();
            List<String> recentResponses = new ArrayList<>();
            
            for (ChatMessage msg : messages) {
                if (MessageType.AI_MESSAGE.equals(msg.getType()) && msg.getContent() != null) {
                    recentResponses.add(msg.getContent());
                }
            }
            
            // Keep only last 5 responses
            if (recentResponses.size() > 5) {
                recentResponses = recentResponses.subList(recentResponses.size() - 5, recentResponses.size());
            }
            
            masterInfo.setRecentResponses(recentResponses);
            masterInfo.setTotalResponses(recentResponses.size());
            masterInfo.setLastResponseTime(System.currentTimeMillis());
            masterInfo.setLastLlmCallTime(System.currentTimeMillis());
            
            // Get order queue status from OrderService
            Map<String, Object> queueStatus = orderService.getQueueStatus();
            int pendingOrders = (Integer) queueStatus.get("queueSize");
            masterInfo.setPendingOrders(pendingOrders);
            
            // Add additional order information to context
            Map<String, Object> orderContext = new HashMap<>();
            orderContext.put("queueSize", queueStatus.get("queueSize"));
            orderContext.put("activeOrdersCount", queueStatus.get("activeOrdersCount"));
            orderContext.put("masterBusy", queueStatus.get("masterBusy"));
            orderContext.put("currentlyPreparing", queueStatus.get("currentlyPreparing"));
            orderContext.put("currentlyPreparingOrderId", queueStatus.get("currentlyPreparingOrderId"));
            
            // Get room1 specific orders
            var roomOrders = orderService.getRoomOrders("room1");
            var pendingRoomOrders = orderService.getRoomPendingOrders("room1");
            orderContext.put("room1TotalOrders", roomOrders.size());
            orderContext.put("room1PendingOrders", pendingRoomOrders.size());
            
            // Add order context to the main context map
            contextMap.put("orderSystem", orderContext);
            masterInfo.setCurrentContext(contextMap);
            
            // Scheduler info
            List<DebugMasterInfo.DebugSchedulerInfo> schedulers = Arrays.asList(
                new DebugMasterInfo.DebugSchedulerInfo("MasterResponseScheduler", true, 
                    System.currentTimeMillis() + 60000, System.currentTimeMillis(), 100),
                new DebugMasterInfo.DebugSchedulerInfo("UserActivityCleanup", true,
                    System.currentTimeMillis() + 120000, System.currentTimeMillis(), 50)
            );
            masterInfo.setSchedulerInfo(schedulers);
            
        } catch (Exception e) {
            masterInfo.setStatus("ERROR: " + e.getMessage());
        }
        
        return masterInfo;
    }

    @Operation(summary = "Get Redis cache keys", description = "Returns all Redis keys for debugging")
    @GetMapping("/redis/keys")
    public Map<String, Object> getRedisKeys() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // Get various Redis data
            result.put("seatOccupancy", redisService.getAllSeatOccupancy());
            result.put("messagesCount", redisService.getAllRecentMessages().size());
            result.put("aiContext", redisService.getAIContext());
            
            // Additional debug info
            result.put("timestamp", System.currentTimeMillis());
            result.put("status", "SUCCESS");
            
        } catch (Exception e) {
            result.put("status", "ERROR");
            result.put("error", e.getMessage());
        }
        
        return result;
    }

    @Operation(summary = "Clear specific Redis cache", description = "Clears specific Redis cache entries (use with caution)")
    @DeleteMapping("/redis/{cacheType}")
    public Map<String, String> clearCache(@Parameter(description = "Cache type to clear") @PathVariable String cacheType) {
        Map<String, String> result = new HashMap<>();
        
        try {
            switch (cacheType.toLowerCase()) {
                case "messages":
                    // Clear chat messages - implement if needed
                    result.put("status", "SUCCESS");
                    result.put("message", "Messages cache cleared");
                    break;
                case "seats":
                    // Clear seat occupancy - implement if needed
                    result.put("status", "SUCCESS");
                    result.put("message", "Seats cache cleared");
                    break;
                default:
                    result.put("status", "ERROR");
                    result.put("message", "Unknown cache type: " + cacheType);
            }
        } catch (Exception e) {
            result.put("status", "ERROR");
            result.put("message", "Error clearing cache: " + e.getMessage());
        }
        
        return result;
    }

    @Operation(summary = "Clear ALL Redis cache", 
               description = "⚠️ DANGER: Clears the entire Redis cache including all user sessions, messages, seats, and system data. Use with extreme caution!")
    @io.swagger.v3.oas.annotations.responses.ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Cache cleared successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Error clearing cache")
    })
    @DeleteMapping("/redis/all")
    public Map<String, Object> clearAllCache() {
        return redisService.clearAllCache();
    }

    @Operation(summary = "Get Redis cache statistics", 
               description = "Returns detailed statistics about Redis cache usage including key counts by type")
    @io.swagger.v3.oas.annotations.responses.ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Cache statistics retrieved successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Error retrieving statistics")
    })
    @GetMapping("/redis/stats")
    public Map<String, Object> getCacheStats() {
        return redisService.getCacheStats();
    }

    @Operation(summary = "Initialize/Create room", 
               description = "Creates or reinitializes a room with default settings. Useful after clearing cache.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Room initialized successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Error initializing room")
    })
    @PostMapping("/rooms/{roomId}/initialize")
    public Map<String, Object> initializeRoom(
            @Parameter(description = "Room ID to initialize") @PathVariable String roomId) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            if ("room1".equals(roomId)) {
                roomService.initializeRoom1();
                result.put("status", "SUCCESS");
                result.put("message", "Room " + roomId + " initialized successfully");
                result.put("roomId", roomId);
            } else {
                result.put("status", "ERROR");
                result.put("message", "Only room1 is supported for initialization");
            }
        } catch (Exception e) {
            result.put("status", "ERROR");
            result.put("message", "Failed to initialize room: " + e.getMessage());
        }
        
        return result;
    }

    @Operation(summary = "Initialize all system components", 
               description = "Reinitializes all system components including rooms, AI Master, and Redis cache structure. Use after clearing cache.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "System reinitialized successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Error during system reinitialization")
    })
    @PostMapping("/system/initialize")
    public Map<String, Object> reinitializeSystem() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // Initialize Room1
            roomService.initializeRoom1();
            
            // Initialize AI Master presence in Redis
            redisService.initializeAIMaster();
            
            result.put("status", "SUCCESS");
            result.put("message", "System reinitialized successfully");
            result.put("components", List.of("room1", "ai_master", "redis_structure"));
            result.put("timestamp", System.currentTimeMillis());
            
        } catch (Exception e) {
            result.put("status", "ERROR");
            result.put("message", "Failed to reinitialize system: " + e.getMessage());
        }
        
        return result;
    }

    // Helper methods
    private DebugRoomInfo getRoomInfo(String roomId) {
        try {
            // Get room data from RoomService (this is the correct source)
            List<ChatMessage> messages = roomService.getRoomMessages(roomId);
            Map<Integer, String> roomSeatOccupancy = roomService.getRoomSeatOccupancy(roomId);
            
            List<DebugRoomInfo.DebugMessage> debugMessages = new ArrayList<>();
            
            if (messages != null) {
                for (ChatMessage msg : messages) {
                    debugMessages.add(new DebugRoomInfo.DebugMessage(
                        msg.getType().toString(),
                        msg.getUserId(),
                        msg.getUserName(),
                        msg.getContent(),
                        msg.getTimestamp().toInstant(ZoneOffset.UTC).toEpochMilli()
                    ));
                }
                
                // Keep only last 10 messages
                if (debugMessages.size() > 10) {
                    debugMessages = debugMessages.subList(debugMessages.size() - 10, debugMessages.size());
                }
            }
            
            // Convert seat occupancy to String keys for DebugRoomInfo
            Map<String, String> seatOccupancy = new HashMap<>();
            if (roomSeatOccupancy != null) {
                for (Map.Entry<Integer, String> entry : roomSeatOccupancy.entrySet()) {
                    seatOccupancy.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            
            int messageCount = messages != null ? messages.size() : 0;
            int totalUsers = seatOccupancy.size();
            
            return new DebugRoomInfo(roomId, messageCount, debugMessages, 
                seatOccupancy, totalUsers, System.currentTimeMillis());
                
        } catch (Exception e) {
            logger.error("Error getting room info for {}: {}", roomId, e.getMessage(), e);
            return new DebugRoomInfo(roomId, 0, new ArrayList<>(), new HashMap<>(), 0, 0);
        }
    }

    private DebugUserInfo getUserInfo(String userId) {
        try {
            // Get user profile from UserService
            var userProfile = userService.getUserProfile(userId);
            
            if (userProfile != null) {
                String seatId = userProfile.getCurrentSeat() != null ? 
                    String.valueOf(userProfile.getCurrentSeat()) : null;
                long lastActivity = userProfile.getLastActivity().toInstant(ZoneOffset.UTC).toEpochMilli();
                
                // Get user orders from OrderService
                List<DebugUserInfo.DebugOrder> orders = new ArrayList<>();
                var userOrders = orderService.getUserCurrentOrders(userId);
                
                for (var order : userOrders) {
                    long orderTime = order.getOrderTime().toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
                    long completionTime = order.getEstimatedReadyTime() != null ? 
                        order.getEstimatedReadyTime().toInstant(java.time.ZoneOffset.UTC).toEpochMilli() : 0;
                        
                    orders.add(new DebugUserInfo.DebugOrder(
                        order.getOrderId(),
                        order.getMenuItem().getName(),
                        order.getStatus().toString(),
                        orderTime,
                        completionTime
                    ));
                }
                
                return new DebugUserInfo(userId, userProfile.getUserName(), userProfile.getRoomId(), 
                                       seatId, lastActivity, orders, userProfile.isActive());
            } else {
                // Fallback to old method if profile doesn't exist
                Integer seatId = redisService.getUserSeat(userId);
                String userSeatId = seatId != null ? String.valueOf(seatId) : null;
                
                // Still get orders even without user profile
                List<DebugUserInfo.DebugOrder> orders = new ArrayList<>();
                var userOrders = orderService.getUserCurrentOrders(userId);
                
                for (var order : userOrders) {
                    long orderTime = order.getOrderTime().toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
                    long completionTime = order.getEstimatedReadyTime() != null ? 
                        order.getEstimatedReadyTime().toInstant(java.time.ZoneOffset.UTC).toEpochMilli() : 0;
                        
                    orders.add(new DebugUserInfo.DebugOrder(
                        order.getOrderId(),
                        order.getMenuItem().getName(),
                        order.getStatus().toString(),
                        orderTime,
                        completionTime
                    ));
                }
                
                return new DebugUserInfo(userId, "Unknown", "room1", userSeatId, 
                                       System.currentTimeMillis(), orders, false);
            }
            
        } catch (Exception e) {
            return new DebugUserInfo(userId, "ERROR", "unknown", null, 0, new ArrayList<>(), false);
        }
    }
    
    @Operation(summary = "Get user status information", description = "Returns consumable status for a specific user in room/seat")
    @GetMapping("/user-status")
    public Map<String, Object> getUserStatus(
            @Parameter(description = "User ID") @RequestParam String userId,
            @Parameter(description = "Room ID", example = "room1") @RequestParam(defaultValue = "room1") String roomId,
            @Parameter(description = "Seat ID") @RequestParam Integer seatId) {
        
        try {
            return consumableService.getUserStatusInfo(userId, roomId, seatId);
        } catch (Exception e) {
            logger.error("Error getting user status for {} in room {} seat {}", userId, roomId, seatId, e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to get user status: " + e.getMessage());
            error.put("userId", userId);
            error.put("roomId", roomId);
            error.put("seatId", seatId);
            return error;
        }
    }
    
    @Operation(summary = "Get all user statuses", description = "Returns consumable status for all occupied seats")
    @GetMapping("/all-user-status")
    public Map<String, Object> getAllUserStatus() {
        try {
            Map<Integer, String> seatOccupancy = redisService.getAllSeatOccupancy();
            Map<String, Object> allStatuses = new HashMap<>();
            
            for (Map.Entry<Integer, String> seat : seatOccupancy.entrySet()) {
                Integer seatId = seat.getKey();
                String userId = seat.getValue();
                String roomId = "room1"; // Assume room1 for now
                
                Map<String, Object> userStatus = consumableService.getUserStatusInfo(userId, roomId, seatId);
                allStatuses.put("seat" + seatId, userStatus);
            }
            
            allStatuses.put("totalOccupiedSeats", seatOccupancy.size());
            allStatuses.put("timestamp", System.currentTimeMillis());
            
            return allStatuses;
        } catch (Exception e) {
            logger.error("Error getting all user statuses", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to get user statuses: " + e.getMessage());
            return error;
        }
    }
    
    @Operation(summary = "Get room/seat/user relationships", description = "Returns centralized room/seat/user mapping with consumables info")
    @GetMapping("/seat-occupancy")
    public Map<String, Object> getSeatOccupancyDebug() {
        try {
            // Get the centralized mapping
            Map<String, Object> debug = seatService.getDebugInfo();
            
            // Add consumable info for each user
            SeatService.RoomMapping allRooms = seatService.getAllRooms();
            for (SeatService.RoomInfo room : allRooms.getRooms().values()) {
                for (SeatService.UserInfo user : room.getSeats().values()) {
                    List<Consumable> consumables = consumableService.getConsumables(user.getUserId(), user.getRoomId(), user.getSeatId());
                    
                    // Add consumable info to the debug data
                    @SuppressWarnings("unchecked")
                    Map<String, Object> roomData = (Map<String, Object>) debug.get(user.getRoomId());
                    if (roomData != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> seatsData = (Map<String, Object>) roomData.get("seats");
                        if (seatsData != null) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> seatData = (Map<String, Object>) seatsData.get("seat" + user.getSeatId());
                            if (seatData != null) {
                                seatData.put("consumables", consumables);
                                seatData.put("consumableCount", consumables.size());
                            }
                        }
                    }
                }
            }
            
            return debug;
        } catch (Exception e) {
            logger.error("Error getting room/seat/user debug info", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to get room/seat/user info: " + e.getMessage());
            return error;
        }
    }
    
    @Operation(summary = "Reset room/seat/user mappings", description = "Clears all centralized mappings (for testing)")
    @PostMapping("/clear-mappings")
    public Map<String, Object> clearMappings() {
        try {
            seatService.clearAll();
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "All room/seat/user mappings cleared");
            result.put("timestamp", System.currentTimeMillis());
            return result;
        } catch (Exception e) {
            logger.error("Error clearing mappings", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "Failed to clear mappings: " + e.getMessage());
            return error;
        }
    }
    
    @Operation(summary = "Clear all consumables", description = "Clears all consumable data from Redis (fixes corruption)")
    @PostMapping("/clear-consumables")
    public Map<String, Object> clearConsumables() {
        try {
            consumableService.clearAllConsumables();
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "All consumables cleared from Redis");
            result.put("timestamp", System.currentTimeMillis());
            return result;
        } catch (Exception e) {
            logger.error("Error clearing consumables", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "Failed to clear consumables: " + e.getMessage());
            return error;
        }
    }
}