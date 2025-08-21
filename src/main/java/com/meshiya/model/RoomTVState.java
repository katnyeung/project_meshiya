package com.meshiya.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;

/**
 * Represents the persistent TV state for a room - like a real TV that stays on
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RoomTVState {
    
    private String roomId;
    private String videoId;
    private String videoUrl; 
    private String videoTitle;
    private LocalDateTime startTime;
    private int durationSeconds;
    private boolean isPlaying;
    private String initiatorUserId;
    private String initiatorUserName;
    
    public RoomTVState() {}
    
    public RoomTVState(String roomId, String videoId, String videoUrl, String videoTitle, 
                       int durationSeconds, String initiatorUserId, String initiatorUserName) {
        this.roomId = roomId;
        this.videoId = videoId;
        this.videoUrl = videoUrl;
        this.videoTitle = videoTitle;
        this.startTime = LocalDateTime.now();
        this.durationSeconds = durationSeconds;
        this.isPlaying = true;
        this.initiatorUserId = initiatorUserId;
        this.initiatorUserName = initiatorUserName;
    }
    
    /**
     * Get current playback time in seconds since start
     */
    public long getCurrentPlaybackSeconds() {
        if (startTime == null) return 0;
        
        LocalDateTime now = LocalDateTime.now();
        long elapsedSeconds = java.time.Duration.between(startTime, now).getSeconds();
        
        // Don't go beyond video duration
        return Math.min(elapsedSeconds, durationSeconds);
    }
    
    /**
     * Check if video has completed
     */
    public boolean isCompleted() {
        return getCurrentPlaybackSeconds() >= durationSeconds;
    }
    
    /**
     * Get progress percentage (0-100)
     */
    public double getProgressPercent() {
        if (durationSeconds <= 0) return 0;
        return (double) getCurrentPlaybackSeconds() / durationSeconds * 100.0;
    }
    
    // Getters and setters
    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }
    
    public String getVideoId() { return videoId; }
    public void setVideoId(String videoId) { this.videoId = videoId; }
    
    public String getVideoUrl() { return videoUrl; }
    public void setVideoUrl(String videoUrl) { this.videoUrl = videoUrl; }
    
    public String getVideoTitle() { return videoTitle; }
    public void setVideoTitle(String videoTitle) { this.videoTitle = videoTitle; }
    
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    
    public int getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(int durationSeconds) { this.durationSeconds = durationSeconds; }
    
    public boolean isPlaying() { return isPlaying; }
    public void setPlaying(boolean playing) { isPlaying = playing; }
    
    public String getInitiatorUserId() { return initiatorUserId; }
    public void setInitiatorUserId(String initiatorUserId) { this.initiatorUserId = initiatorUserId; }
    
    public String getInitiatorUserName() { return initiatorUserName; }
    public void setInitiatorUserName(String initiatorUserName) { this.initiatorUserName = initiatorUserName; }
    
    @Override
    public String toString() {
        return String.format("RoomTVState{roomId='%s', videoTitle='%s', playback=%ds/%ds, playing=%s, initiator='%s'}", 
                           roomId, videoTitle, getCurrentPlaybackSeconds(), durationSeconds, isPlaying, initiatorUserName);
    }
}