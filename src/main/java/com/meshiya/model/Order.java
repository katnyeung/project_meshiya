package com.meshiya.model;

import java.time.LocalDateTime;

public class Order {
    
    private String orderId;
    private String userId;
    private String userName;
    private String roomId;
    private MenuItem menuItem;
    private OrderStatus status;
    private LocalDateTime orderTime;
    private LocalDateTime estimatedReadyTime;
    private LocalDateTime servedTime;
    private Integer seatId;
    private String imageUrl; // MinIO URL for generated food images
    
    // Constructors
    public Order() {}
    
    public Order(String orderId, String userId, String userName, String roomId, 
                 MenuItem menuItem, Integer seatId) {
        this.orderId = orderId;
        this.userId = userId;
        this.userName = userName;
        this.roomId = roomId;
        this.menuItem = menuItem;
        this.seatId = seatId;
        this.status = OrderStatus.ORDERED;
        this.orderTime = LocalDateTime.now();
        this.estimatedReadyTime = orderTime.plusSeconds(menuItem.getPreparationTimeSeconds());
    }
    
    // Getters and Setters
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    
    public MenuItem getMenuItem() { return menuItem; }
    public void setMenuItem(MenuItem menuItem) { this.menuItem = menuItem; }
    
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    
    public LocalDateTime getOrderTime() { return orderTime; }
    public void setOrderTime(LocalDateTime orderTime) { this.orderTime = orderTime; }
    
    public LocalDateTime getEstimatedReadyTime() { return estimatedReadyTime; }
    public void setEstimatedReadyTime(LocalDateTime estimatedReadyTime) { 
        this.estimatedReadyTime = estimatedReadyTime; 
    }
    
    public LocalDateTime getServedTime() { return servedTime; }
    public void setServedTime(LocalDateTime servedTime) { this.servedTime = servedTime; }
    
    public Integer getSeatId() { return seatId; }
    public void setSeatId(Integer seatId) { this.seatId = seatId; }
    
    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }
    
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    
    @Override
    public String toString() {
        return String.format("Order{orderId='%s', userName='%s', item='%s', status=%s}", 
                           orderId, userName, menuItem.getName(), status);
    }
}