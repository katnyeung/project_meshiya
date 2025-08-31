package com.meshiya.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meshiya.dto.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class RedisService {
    
    private static final Logger logger = LoggerFactory.getLogger(RedisService.class);
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    // Redis keys
    private static final String SEAT_KEY = "cafe:seats";
    private static final String MESSAGES_KEY = "cafe:messages";
    private static final String USER_KEY_PREFIX = "cafe:users:";
    private static final String AI_CONTEXT_KEY = "cafe:ai:context";
    
    /**
     * Seat Management
     */
    public void setSeatOccupancy(Integer seatId, String userId) {
        redisTemplate.opsForHash().put(SEAT_KEY, seatId.toString(), userId);
        logger.debug("Set seat {} occupied by {}", seatId, userId);
    }
    
    public void clearSeat(Integer seatId) {
        redisTemplate.opsForHash().delete(SEAT_KEY, seatId.toString());
        logger.debug("Cleared seat {}", seatId);
    }
    
    public Map<Integer, String> getAllSeatOccupancy() {
        Map<Object, Object> rawSeats = redisTemplate.opsForHash().entries(SEAT_KEY);
        Map<Integer, String> seats = new HashMap<>();
        
        for (Map.Entry<Object, Object> entry : rawSeats.entrySet()) {
            try {
                Integer seatId = Integer.valueOf(entry.getKey().toString());
                String userId = entry.getValue().toString();
                seats.put(seatId, userId);
            } catch (NumberFormatException e) {
                logger.warn("Invalid seat ID in Redis: {}", entry.getKey());
            }
        }
        
        return seats;
    }
    
    public String getSeatOccupant(Integer seatId) {
        Object occupant = redisTemplate.opsForHash().get(SEAT_KEY, seatId.toString());
        return occupant != null ? occupant.toString() : null;
    }
    
    /**
     * Gets the count of active users (seated users)
     */
    public int getActiveUserCount() {
        Map<Object, Object> rawSeats = redisTemplate.opsForHash().entries(SEAT_KEY);
        return rawSeats.size();
    }
    
    /**
     * Gets list of active user IDs
     */
    public List<String> getActiveUserIds() {
        Map<Object, Object> rawSeats = redisTemplate.opsForHash().entries(SEAT_KEY);
        List<String> userIds = new ArrayList<>();
        
        for (Object userId : rawSeats.values()) {
            if (userId != null) {
                userIds.add(userId.toString());
            }
        }
        
        return userIds;
    }
    
    /**
     * Message History Management
     */
    public void addMessage(ChatMessage message) {
        try {
            String messageJson = objectMapper.writeValueAsString(message);
            redisTemplate.opsForList().leftPush(MESSAGES_KEY, messageJson);
            
            // Keep only last 50 messages
            redisTemplate.opsForList().trim(MESSAGES_KEY, 0, 49);
            
            logger.debug("Added message to Redis: {}", message.getContent());
        } catch (JsonProcessingException e) {
            logger.error("Error serializing message", e);
        }
    }
    
    public List<ChatMessage> getRecentMessages(int count) {
        List<Object> rawMessages = redisTemplate.opsForList().range(MESSAGES_KEY, 0, count - 1);
        List<ChatMessage> messages = new ArrayList<>();
        
        if (rawMessages != null) {
            for (Object rawMessage : rawMessages) {
                try {
                    ChatMessage message = objectMapper.readValue(rawMessage.toString(), ChatMessage.class);
                    messages.add(message);
                } catch (JsonProcessingException e) {
                    logger.warn("Error deserializing message from Redis", e);
                }
            }
        }
        
        // Reverse to get chronological order (newest last)
        Collections.reverse(messages);
        return messages;
    }
    
    public List<ChatMessage> getAllRecentMessages() {
        return getRecentMessages(50);
    }
    
    /**
     * User State Management
     */
    public void setUserSeat(String userId, Integer seatId) {
        Map<String, Object> userData = new HashMap<>();
        userData.put("seatId", seatId);
        userData.put("lastActivity", System.currentTimeMillis());
        
        try {
            String userDataJson = objectMapper.writeValueAsString(userData);
            redisTemplate.opsForValue().set(USER_KEY_PREFIX + userId, userDataJson, 24, TimeUnit.HOURS);
        } catch (JsonProcessingException e) {
            logger.error("Error serializing user data", e);
        }
    }
    
    public Integer getUserSeat(String userId) {
        Object userData = redisTemplate.opsForValue().get(USER_KEY_PREFIX + userId);
        if (userData != null) {
            try {
                Map<String, Object> userMap = objectMapper.readValue(userData.toString(), Map.class);
                Object seatId = userMap.get("seatId");
                return seatId != null ? Integer.valueOf(seatId.toString()) : null;
            } catch (Exception e) {
                logger.warn("Error deserializing user data for {}", userId, e);
            }
        }
        return null;
    }
    
    public void removeUser(String userId) {
        redisTemplate.delete(USER_KEY_PREFIX + userId);
    }
    
    /**
     * AI Context Management
     */
    public void updateAIContext(List<ChatMessage> contextMessages) {
        try {
            String contextJson = objectMapper.writeValueAsString(contextMessages);
            redisTemplate.opsForValue().set(AI_CONTEXT_KEY, contextJson, 1, TimeUnit.HOURS);
        } catch (JsonProcessingException e) {
            logger.error("Error serializing AI context", e);
        }
    }
    
    public List<ChatMessage> getAIContext() {
        Object context = redisTemplate.opsForValue().get(AI_CONTEXT_KEY);
        if (context != null) {
            try {
                return objectMapper.readValue(context.toString(), 
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ChatMessage.class));
            } catch (JsonProcessingException e) {
                logger.warn("Error deserializing AI context", e);
            }
        }
        return new ArrayList<>();
    }
    
    /**
     * Initialize AI Master presence
     */
    public void initializeAIMaster() {
        ChatMessage masterWelcome = new ChatMessage();
        masterWelcome.setType(com.meshiya.model.MessageType.AI_MESSAGE);
        masterWelcome.setContent("*The Master quietly prepares the diner for the night, wiping down glasses and checking the menu. The lanterns cast a warm glow as the midnight diner opens its doors.*");
        masterWelcome.setUserName("Master");
        masterWelcome.setUserId("ai_master");
        masterWelcome.setSeatId(null);
        
        addMessage(masterWelcome);
        logger.info("AI Master initialized in the diner");
    }
    
    /**
     * Clear all Redis cache (use with caution!)
     */
    public Map<String, Object> clearAllCache() {
        Map<String, Object> result = new HashMap<>();
        int deletedKeys = 0;
        
        try {
            // Get all keys to count them before deletion
            Set<String> allKeys = redisTemplate.keys("*");
            if (allKeys != null) {
                deletedKeys = allKeys.size();
            }
            
            // Flush all Redis data
            redisTemplate.getConnectionFactory().getConnection().flushAll();
            
            logger.warn("CLEARED ALL REDIS CACHE - {} keys deleted", deletedKeys);
            
            result.put("status", "SUCCESS");
            result.put("message", "All Redis cache cleared successfully");
            result.put("deletedKeys", deletedKeys);
            result.put("timestamp", System.currentTimeMillis());
            
        } catch (Exception e) {
            logger.error("Error clearing all Redis cache: {}", e.getMessage(), e);
            result.put("status", "ERROR");
            result.put("message", "Failed to clear cache: " + e.getMessage());
            result.put("deletedKeys", 0);
        }
        
        return result;
    }
    
    /**
     * Get Redis cache statistics
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        
        try {
            Set<String> allKeys = redisTemplate.keys("*");
            int totalKeys = allKeys != null ? allKeys.size() : 0;
            
            // Count keys by prefix
            Map<String, Integer> keysByPrefix = new HashMap<>();
            keysByPrefix.put("cafe:seats", 0);
            keysByPrefix.put("cafe:messages", 0);  
            keysByPrefix.put("cafe:users:", 0);
            keysByPrefix.put("cafe:ai:context", 0);
            keysByPrefix.put("user_profile:", 0);
            keysByPrefix.put("room:", 0);
            keysByPrefix.put("other", 0);
            
            if (allKeys != null) {
                for (String key : allKeys) {
                    boolean matched = false;
                    for (String prefix : keysByPrefix.keySet()) {
                        if (key.startsWith(prefix)) {
                            keysByPrefix.put(prefix, keysByPrefix.get(prefix) + 1);
                            matched = true;
                            break;
                        }
                    }
                    if (!matched) {
                        keysByPrefix.put("other", keysByPrefix.get("other") + 1);
                    }
                }
            }
            
            stats.put("totalKeys", totalKeys);
            stats.put("keysByPrefix", keysByPrefix);
            stats.put("timestamp", System.currentTimeMillis());
            
        } catch (Exception e) {
            logger.error("Error getting cache stats: {}", e.getMessage());
            stats.put("error", e.getMessage());
        }
        
        return stats;
    }
}