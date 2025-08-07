package com.meshiya.dto;

import java.util.List;
import java.util.Map;

/**
 * Debug information about a room's state
 */
public class DebugRoomInfo {
    
    private String roomId;
    private int messageCount;
    private List<DebugMessage> recentMessages;
    private Map<String, String> seatOccupancy; // seatId -> userId
    private int totalUsers;
    private long lastActivity;
    
    public DebugRoomInfo() {}
    
    public DebugRoomInfo(String roomId, int messageCount, List<DebugMessage> recentMessages, 
                        Map<String, String> seatOccupancy, int totalUsers, long lastActivity) {
        this.roomId = roomId;
        this.messageCount = messageCount;
        this.recentMessages = recentMessages;
        this.seatOccupancy = seatOccupancy;
        this.totalUsers = totalUsers;
        this.lastActivity = lastActivity;
    }

    // Getters and setters
    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }

    public int getMessageCount() { return messageCount; }
    public void setMessageCount(int messageCount) { this.messageCount = messageCount; }

    public List<DebugMessage> getRecentMessages() { return recentMessages; }
    public void setRecentMessages(List<DebugMessage> recentMessages) { this.recentMessages = recentMessages; }

    public Map<String, String> getSeatOccupancy() { return seatOccupancy; }
    public void setSeatOccupancy(Map<String, String> seatOccupancy) { this.seatOccupancy = seatOccupancy; }

    public int getTotalUsers() { return totalUsers; }
    public void setTotalUsers(int totalUsers) { this.totalUsers = totalUsers; }

    public long getLastActivity() { return lastActivity; }
    public void setLastActivity(long lastActivity) { this.lastActivity = lastActivity; }
    
    public static class DebugMessage {
        private String type;
        private String userId;
        private String userName;
        private String content;
        private long timestamp;
        
        public DebugMessage() {}
        
        public DebugMessage(String type, String userId, String userName, String content, long timestamp) {
            this.type = type;
            this.userId = userId;
            this.userName = userName;
            this.content = content;
            this.timestamp = timestamp;
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }

        public String getUserName() { return userName; }
        public void setUserName(String userName) { this.userName = userName; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    }
}