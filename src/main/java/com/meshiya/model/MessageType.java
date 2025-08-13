package com.meshiya.model;

public enum MessageType {
    JOIN,           // User joining the diner
    JOIN_SEAT,
    LEAVE_SEAT,
    CHAT_MESSAGE,
    ORDER_DRINK,
    ORDER_FOOD,
    EATING_ACTION,
    FOOD_SERVED,
    DRINK_SERVED,
    MOOD_UPDATE,
    AI_MESSAGE,
    SYSTEM_MESSAGE,
    USER_STATUS_REFRESH
}