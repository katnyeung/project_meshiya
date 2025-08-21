package com.meshiya.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Consumable {
    
    private String itemId;
    private String itemName;
    private String itemType; // DRINK, FOOD, DESSERT
    private LocalDateTime startTime;
    private int durationSeconds; // Total consumption duration
    private int remainingSeconds; // Remaining time
    private String roomId;
    private Integer seatId;
    private String userId;
    private String imageData; // Base64 encoded image data for generated food images
    
    // Default constructor
    public Consumable() {}
    
    // Constructor for creating new consumable
    public Consumable(String itemId, String itemName, String itemType, int durationSeconds, 
                     String roomId, Integer seatId, String userId) {
        this.itemId = itemId;
        this.itemName = itemName;
        this.itemType = itemType;
        this.startTime = LocalDateTime.now();
        this.durationSeconds = durationSeconds;
        this.remainingSeconds = durationSeconds;
        this.roomId = roomId;
        this.seatId = seatId;
        this.userId = userId;
    }
    
    // Getters and setters
    public String getItemId() {
        return itemId;
    }
    
    public void setItemId(String itemId) {
        this.itemId = itemId;
    }
    
    public String getItemName() {
        return itemName;
    }
    
    public void setItemName(String itemName) {
        this.itemName = itemName;
    }
    
    public String getItemType() {
        return itemType;
    }
    
    public void setItemType(String itemType) {
        this.itemType = itemType;
    }
    
    public LocalDateTime getStartTime() {
        return startTime;
    }
    
    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }
    
    public int getDurationSeconds() {
        return durationSeconds;
    }
    
    public void setDurationSeconds(int durationSeconds) {
        this.durationSeconds = durationSeconds;
    }
    
    public int getRemainingSeconds() {
        return remainingSeconds;
    }
    
    public void setRemainingSeconds(int remainingSeconds) {
        this.remainingSeconds = remainingSeconds;
    }
    
    public String getRoomId() {
        return roomId;
    }
    
    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }
    
    public Integer getSeatId() {
        return seatId;
    }
    
    public void setSeatId(Integer seatId) {
        this.seatId = seatId;
    }
    
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    public String getImageData() {
        return imageData;
    }
    
    public void setImageData(String imageData) {
        this.imageData = imageData;
    }
    
    /**
     * Check if consumable has expired
     */
    public boolean isExpired() {
        return remainingSeconds <= 0;
    }
    
    /**
     * Get progress percentage (0-100)
     */
    public int getProgressPercent() {
        if (durationSeconds <= 0) return 100;
        return Math.max(0, Math.min(100, (int) ((double) (durationSeconds - remainingSeconds) / durationSeconds * 100)));
    }
    
    /**
     * Decrease remaining time by 1 second
     */
    public void tick() {
        if (remainingSeconds > 0) {
            remainingSeconds--;
        }
    }
    
    @Override
    public String toString() {
        return String.format("Consumable{item='%s', remaining=%ds, room='%s', seat=%d}", 
                           itemName, remainingSeconds, roomId, seatId);
    }
}