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
    USER_STATUS_REFRESH,
    VIDEO_PLAY_REQUEST,  // User requests to play video
    VIDEO_START,         // Video starts playing
    VIDEO_SYNC,          // Periodic sync broadcast
    VIDEO_PAUSE,         // User pauses locally
    VIDEO_STOP,          // User stops locally
    VIDEO_COMPLETE,      // Video ends
    AVATAR_STATE_UPDATE  // Avatar state change (idle, chatting, eating, normal)
}