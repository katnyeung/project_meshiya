package com.meshiya.dto;

import com.meshiya.model.MessageType;
import java.time.LocalDateTime;

public class ChatMessage {
    private MessageType type;
    private String userId;
    private String userName;
    private String content;
    private Integer seatId;
    private String roomId;
    private LocalDateTime timestamp;
    
    // Video-related fields
    private String videoId;
    private String videoUrl;
    private String videoTitle;
    private Long playbackTime; // Current playback position in seconds
    private Boolean isPlaying;

    public ChatMessage() {
        this.timestamp = LocalDateTime.now();
    }

    public ChatMessage(MessageType type, String userId, String userName, String content) {
        this();
        this.type = type;
        this.userId = userId;
        this.userName = userName;
        this.content = content;
    }

    // Getters and setters
    public MessageType getType() { return type; }
    public void setType(MessageType type) { this.type = type; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Integer getSeatId() { return seatId; }
    public void setSeatId(Integer seatId) { this.seatId = seatId; }

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    // Video getters and setters
    public String getVideoId() { return videoId; }
    public void setVideoId(String videoId) { this.videoId = videoId; }

    public String getVideoUrl() { return videoUrl; }
    public void setVideoUrl(String videoUrl) { this.videoUrl = videoUrl; }

    public String getVideoTitle() { return videoTitle; }
    public void setVideoTitle(String videoTitle) { this.videoTitle = videoTitle; }

    public Long getPlaybackTime() { return playbackTime; }
    public void setPlaybackTime(Long playbackTime) { this.playbackTime = playbackTime; }

    public Boolean getIsPlaying() { return isPlaying; }
    public void setIsPlaying(Boolean isPlaying) { this.isPlaying = isPlaying; }
}