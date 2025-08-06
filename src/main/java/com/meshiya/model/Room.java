package com.meshiya.model;

import com.meshiya.dto.ChatMessage;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;

public class Room {
    private String roomId;
    private String roomName;
    private List<ChatMessage> messages;
    private Map<Integer, String> seatOccupancy; // seatId -> userId
    private LocalDateTime createdAt;
    private LocalDateTime lastActivity;
    
    public Room() {
        this.messages = new ArrayList<>();
        this.seatOccupancy = new ConcurrentHashMap<>();
        this.createdAt = LocalDateTime.now();
        this.lastActivity = LocalDateTime.now();
    }
    
    public Room(String roomId, String roomName) {
        this();
        this.roomId = roomId;
        this.roomName = roomName;
    }
    
    // Getters and setters
    public String getRoomId() {
        return roomId;
    }
    
    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }
    
    public String getRoomName() {
        return roomName;
    }
    
    public void setRoomName(String roomName) {
        this.roomName = roomName;
    }
    
    public List<ChatMessage> getMessages() {
        return messages;
    }
    
    public void setMessages(List<ChatMessage> messages) {
        this.messages = messages;
    }
    
    public Map<Integer, String> getSeatOccupancy() {
        return seatOccupancy;
    }
    
    public void setSeatOccupancy(Map<Integer, String> seatOccupancy) {
        this.seatOccupancy = seatOccupancy;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    
    public LocalDateTime getLastActivity() {
        return lastActivity;
    }
    
    public void setLastActivity(LocalDateTime lastActivity) {
        this.lastActivity = lastActivity;
    }
    
    // Helper methods
    public void addMessage(ChatMessage message) {
        this.messages.add(message);
        this.lastActivity = LocalDateTime.now();
        
        // Keep only last 50 messages
        if (this.messages.size() > 50) {
            this.messages.remove(0);
        }
    }
    
    public boolean isSeatOccupied(Integer seatId) {
        return seatOccupancy.containsKey(seatId);
    }
    
    public void occupySeat(Integer seatId, String userId) {
        seatOccupancy.put(seatId, userId);
        this.lastActivity = LocalDateTime.now();
    }
    
    public void freeSeat(Integer seatId) {
        seatOccupancy.remove(seatId);
        this.lastActivity = LocalDateTime.now();
    }
    
    public Integer getUserSeat(String userId) {
        return seatOccupancy.entrySet().stream()
            .filter(entry -> userId.equals(entry.getValue()))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
    }
}