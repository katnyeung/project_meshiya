package com.meshiya.dto;

import java.time.LocalDateTime;

/**
 * Comprehensive user profile data stored in Redis
 */
public class UserProfile {
    
    private String userId;
    private String userName;
    private String roomId;
    private Integer currentSeat;
    private LocalDateTime lastActivity;
    private LocalDateTime joinTime;
    private boolean isActive;
    private String status; // "ONLINE", "INACTIVE", "DISCONNECTED"
    
    public UserProfile() {}
    
    public UserProfile(String userId, String userName, String roomId) {
        this.userId = userId;
        this.userName = userName;
        this.roomId = roomId;
        this.lastActivity = LocalDateTime.now();
        this.joinTime = LocalDateTime.now();
        this.isActive = true;
        this.status = "ONLINE";
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }

    public Integer getCurrentSeat() { return currentSeat; }
    public void setCurrentSeat(Integer currentSeat) { this.currentSeat = currentSeat; }

    public LocalDateTime getLastActivity() { return lastActivity; }
    public void setLastActivity(LocalDateTime lastActivity) { this.lastActivity = lastActivity; }

    public LocalDateTime getJoinTime() { return joinTime; }
    public void setJoinTime(LocalDateTime joinTime) { this.joinTime = joinTime; }

    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { this.isActive = active; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    /**
     * Update user activity timestamp and set as active
     */
    public void updateActivity() {
        this.lastActivity = LocalDateTime.now();
        this.isActive = true;
        this.status = "ONLINE";
    }

    /**
     * Mark user as inactive
     */
    public void markInactive() {
        this.isActive = false;
        this.status = "INACTIVE";
    }

    @Override
    public String toString() {
        return String.format("UserProfile{userId='%s', userName='%s', roomId='%s', currentSeat=%s, lastActivity=%s, isActive=%s, status='%s'}", 
                           userId, userName, roomId, currentSeat, lastActivity, isActive, status);
    }
}