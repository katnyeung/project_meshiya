package com.meshiya.dto;

import java.util.List;

/**
 * Debug information about a user's state
 */
public class DebugUserInfo {
    
    private String userId;
    private String userName;
    private String roomId;
    private String seatId;
    private long lastActivity;
    private List<DebugOrder> currentOrders;
    private boolean isActive;
    
    public DebugUserInfo() {}
    
    public DebugUserInfo(String userId, String userName, String roomId, String seatId, 
                        long lastActivity, List<DebugOrder> currentOrders, boolean isActive) {
        this.userId = userId;
        this.userName = userName;
        this.roomId = roomId;
        this.seatId = seatId;
        this.lastActivity = lastActivity;
        this.currentOrders = currentOrders;
        this.isActive = isActive;
    }

    // Getters and setters
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }

    public String getSeatId() { return seatId; }
    public void setSeatId(String seatId) { this.seatId = seatId; }

    public long getLastActivity() { return lastActivity; }
    public void setLastActivity(long lastActivity) { this.lastActivity = lastActivity; }

    public List<DebugOrder> getCurrentOrders() { return currentOrders; }
    public void setCurrentOrders(List<DebugOrder> currentOrders) { this.currentOrders = currentOrders; }

    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    
    public static class DebugOrder {
        private String orderId;
        private String itemName;
        private String status; // PREPARING, READY, SERVED
        private long orderTime;
        private long completionTime;
        
        public DebugOrder() {}
        
        public DebugOrder(String orderId, String itemName, String status, long orderTime, long completionTime) {
            this.orderId = orderId;
            this.itemName = itemName;
            this.status = status;
            this.orderTime = orderTime;
            this.completionTime = completionTime;
        }

        public String getOrderId() { return orderId; }
        public void setOrderId(String orderId) { this.orderId = orderId; }

        public String getItemName() { return itemName; }
        public void setItemName(String itemName) { this.itemName = itemName; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public long getOrderTime() { return orderTime; }
        public void setOrderTime(long orderTime) { this.orderTime = orderTime; }

        public long getCompletionTime() { return completionTime; }
        public void setCompletionTime(long completionTime) { this.completionTime = completionTime; }
    }
}