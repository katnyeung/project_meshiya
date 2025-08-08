package com.meshiya.model;

public enum MasterStatus {
    IDLE("Idle - listening to conversations"),
    THINKING("Thinking - processing customer needs"),
    PREPARING_ORDER("Preparing - working on an order"),
    SERVING("Serving - delivering food or drinks"),
    BUSY("Busy - handling multiple tasks"),
    CLEANING("Cleaning - maintaining the diner"),
    CONVERSING("Conversing - engaging with customers");
    
    private final String description;
    
    MasterStatus(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
    
    public String getDisplayName() {
        return name().toLowerCase().replace('_', ' ');
    }
}