package com.meshiya.event;

import com.meshiya.dto.ChatMessage;
import org.springframework.context.ApplicationEvent;

public class ChatMessageEvent extends ApplicationEvent {
    private final ChatMessage chatMessage;
    
    public ChatMessageEvent(ChatMessage chatMessage) {
        super(chatMessage);
        this.chatMessage = chatMessage;
    }
    
    public ChatMessage getChatMessage() {
        return chatMessage;
    }
}