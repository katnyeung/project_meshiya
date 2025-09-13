package com.meshiya.model;

import java.time.LocalDateTime;
import java.util.UUID;

// POJO version - JPA annotations removed for DynamoDB migration
public class RegisteredUser {
    
    private Long id;
    private String username;
    private String email;
    private String password;
    private String userKey;
    private LocalDateTime createdAt;
    private LocalDateTime lastLogin;
    
    public RegisteredUser() {
        this.createdAt = LocalDateTime.now();
        this.userKey = UUID.randomUUID().toString();
    }
    
    public RegisteredUser(String username, String email, String password) {
        this();
        this.username = username;
        this.email = email;
        this.password = password;
    }
    
    // Getters and setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getUsername() {
        return username;
    }
    
    public void setUsername(String username) {
        this.username = username;
    }
    
    public String getEmail() {
        return email;
    }
    
    public void setEmail(String email) {
        this.email = email;
    }
    
    public String getPassword() {
        return password;
    }
    
    public void setPassword(String password) {
        this.password = password;
    }
    
    public String getUserKey() {
        return userKey;
    }
    
    public void setUserKey(String userKey) {
        this.userKey = userKey;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    
    public LocalDateTime getLastLogin() {
        return lastLogin;
    }
    
    public void setLastLogin(LocalDateTime lastLogin) {
        this.lastLogin = lastLogin;
    }
    
    public void updateLastLogin() {
        this.lastLogin = LocalDateTime.now();
    }
    
    @Override
    public String toString() {
        return String.format("RegisteredUser{id=%d, username='%s', email='%s', userKey='%s', createdAt=%s, lastLogin=%s}", 
                           id, username, email, userKey, createdAt, lastLogin);
    }
}