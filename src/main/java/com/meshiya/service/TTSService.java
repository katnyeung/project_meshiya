package com.meshiya.service;

import com.meshiya.dto.ChatMessage;
import com.meshiya.model.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.batch.model.JobDetail;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class TTSService {
    
    private static final Logger logger = LoggerFactory.getLogger(TTSService.class);
    
    @Value("${tts.mode:aws-batch}")
    private String mode;
    
    @Value("${tts.voice.default:am_michael}")
    private String defaultVoice;
    
    @Value("${tts.enabled:true}")
    private boolean enabled;
    
    @Value("${tts.timeout:120000}")
    private int timeoutMs;
    
    @Value("${tts.storage.ttl-hours:2}")
    private int ttlHours;
    
    @Value("${tts.format:mp3}")
    private String audioFormat;
    
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    
    @Autowired
    private AWSBatchService awsBatchService;
    
    @Autowired
    private RunPodService runPodService;
    
    @Autowired
    private TTSServiceLocal ttsServiceLocal;
    
    // Cache for storing TTS audio URLs by message content hash
    private final ConcurrentHashMap<String, TTSCacheEntry> ttsCache = new ConcurrentHashMap<>();
    
    // Track processing to prevent duplicate requests
    private final ConcurrentHashMap<String, CompletableFuture<String>> processingRequests = new ConcurrentHashMap<>();
    
    // Scheduler for cache cleanup
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    private static class TTSCacheEntry {
        final String audioUrl;
        final String jobId;
        final LocalDateTime timestamp;
        
        TTSCacheEntry(String audioUrl, String jobId) {
            this.audioUrl = audioUrl;
            this.jobId = jobId;
            this.timestamp = LocalDateTime.now();
        }
        
        boolean isExpired(int ttlHours) {
            return timestamp.isBefore(LocalDateTime.now().minusHours(ttlHours));
        }
    }
    
    public TTSService() {
        // Start cache cleanup scheduler - run every 30 minutes
        scheduler.scheduleAtFixedRate(this::cleanupExpiredCache, 30, 30, TimeUnit.MINUTES);
    }
    
    /**
     * Generate speech using AWS Batch
     */
    public byte[] generateSpeech(String text, String voice) {
        if (!enabled) {
            logger.warn("TTS service is disabled");
            return new byte[0];
        }
        
        if (!"runpod".equals(mode) && !"aws-batch".equals(mode) && !"localhost".equals(mode)) {
            logger.warn("TTS mode is not set to runpod, aws-batch, or localhost (current: {})", mode);
            return new byte[0];
        }
        
        if (text == null || text.trim().isEmpty()) {
            logger.warn("Empty text provided to TTS service");
            return new byte[0];
        }
        
        try {
            String cleanText = cleanTextForTTS(text);
            if (voice == null || voice.trim().isEmpty()) {
                voice = defaultVoice;
            }
            
            logger.info("Submitting TTS batch job for text: '{}'", 
                       cleanText.length() > 50 ? cleanText.substring(0, 50) + "..." : cleanText);
            
            String filename = generateMessageKey(cleanText, voice);
            String jobId;
            
            if ("runpod".equals(mode)) {
                jobId = runPodService.submitTTSJob(cleanText, filename, voice);
            } else if ("localhost".equals(mode)) {
                // For localhost mode, directly use TTSServiceLocal
                return ttsServiceLocal.generateSpeech(cleanText, voice);
            } else {
                jobId = awsBatchService.submitTTSJob(cleanText, filename, voice);
            }
            
            if (jobId == null) {
                logger.error("Failed to submit TTS job");
                return new byte[0];
            }
            
            // Wait for job completion
            String audioUrl = waitForTTSJobCompletion(jobId, filename);
            if (audioUrl != null) {
                // In a real implementation, you'd download the audio from S3
                // For now, return empty bytes as the audio is stored in S3
                logger.info("TTS job completed, audio available at: {}", audioUrl);
                return new byte[0]; // Audio is in S3, not returned as bytes
            }
            
            return new byte[0];
            
        } catch (Exception e) {
            logger.error("Error calling TTS AWS Batch: {}", e.getMessage(), e);
            return new byte[0];
        }
    }
    
    /**
     * Process AI messages for centralized TTS generation with caching using AWS Batch
     */
    public void processAIMessageForTTS(ChatMessage message) {
        processAIMessageForTTSWithCallback(message, null);
    }
    
    /**
     * Process AI messages for TTS with callback when ready
     */
    public void processAIMessageForTTSWithCallback(ChatMessage message, Runnable onTTSReady) {
        if (!enabled || message.getType() != MessageType.AI_MESSAGE) {
            return;
        }
        
        if (!"runpod".equals(mode) && !"aws-batch".equals(mode) && !"localhost".equals(mode)) {
            logger.warn("TTS mode is not set to runpod, aws-batch, or localhost (current: {}), skipping TTS processing", mode);
            return;
        }
        
        // For localhost mode, delegate entirely to TTSServiceLocal
        if ("localhost".equals(mode)) {
            logger.info("Delegating TTS processing to TTSServiceLocal for mode: {}", mode);
            ttsServiceLocal.processAIMessageForTTSWithCallback(message, onTTSReady);
            return;
        }
        
        String text = message.getContent();
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        
        // Generate unique key for this message content + voice combination
        String messageKey = generateMessageKey(text, defaultVoice);
        
        logger.info("Processing AI message for TTS: messageKey={}, roomId={}", messageKey, message.getRoomId());
        
        // Check cache first
        TTSCacheEntry cachedEntry = ttsCache.get(messageKey);
        if (cachedEntry != null && !cachedEntry.isExpired(ttlHours)) {
            logger.info("TTS cache hit for messageKey={}, broadcasting existing audio", messageKey);
            broadcastTTSReady(message.getRoomId(), messageKey, cachedEntry.audioUrl);
            if (onTTSReady != null) {
                onTTSReady.run();
            }
            return;
        }
        
        // Check if already processing
        CompletableFuture<String> existingRequest = processingRequests.get(messageKey);
        if (existingRequest != null) {
            logger.info("TTS already processing for messageKey={}, waiting for completion", messageKey);
            existingRequest.thenAccept(audioUrl -> {
                if (audioUrl != null) {
                    broadcastTTSReady(message.getRoomId(), messageKey, audioUrl);
                    if (onTTSReady != null) {
                        onTTSReady.run();
                    }
                }
            });
            return;
        }
        
        // Start new TTS generation
        CompletableFuture<String> ttsTask = CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Generating TTS via {} for messageKey={}", mode, messageKey);
                String cleanText = cleanTextForTTS(text);
                
                String jobId;
                if ("runpod".equals(mode)) {
                    jobId = runPodService.submitTTSJob(cleanText, messageKey, defaultVoice);
                } else {
                    jobId = awsBatchService.submitTTSJob(cleanText, messageKey, defaultVoice);
                }
                // Note: localhost mode is handled earlier by delegation to TTSServiceLocal
                if (jobId != null) {
                    String audioUrl = waitForTTSJobCompletion(jobId, messageKey);
                    if (audioUrl != null) {
                        // Cache the result
                        ttsCache.put(messageKey, new TTSCacheEntry(audioUrl, jobId));
                        logger.info("TTS generated and cached via AWS Batch: messageKey={}, audioUrl={}", messageKey, audioUrl);
                        return audioUrl;
                    }
                }
                
                logger.warn("TTS generation failed via {} for messageKey={}", mode, messageKey);
                return null;
                
            } catch (Exception e) {
                logger.error("Error in TTS generation task for messageKey={}: {}", messageKey, e.getMessage(), e);
                return null;
            }
        });
        
        // Store the processing task
        processingRequests.put(messageKey, ttsTask);
        
        // Handle completion
        ttsTask.thenAccept(audioUrl -> {
            try {
                processingRequests.remove(messageKey);
                if (audioUrl != null) {
                    broadcastTTSReady(message.getRoomId(), messageKey, audioUrl);
                    if (onTTSReady != null) {
                        onTTSReady.run();
                    }
                }
            } catch (Exception e) {
                logger.error("Error broadcasting TTS result for messageKey={}: {}", messageKey, e.getMessage(), e);
            }
        });
    }
    
    /**
     * Wait for TTS job completion and extract audio URL
     */
    private String waitForTTSJobCompletion(String jobId, String filename) {
        try {
            long startTime = System.currentTimeMillis();
            long maxWaitTime = timeoutMs;
            
            while (System.currentTimeMillis() - startTime < maxWaitTime) {
                String status;
                String audioUrl = null;
                
                if ("runpod".equals(mode)) {
                    status = runPodService.getTTSJobStatus(jobId);
                    
                    if ("COMPLETED".equals(status)) {
                        // Get job result from RunPod
                        com.fasterxml.jackson.databind.JsonNode result = runPodService.getTTSJobResult(jobId);
                        if (result != null && result.has("audio_url")) {
                            audioUrl = result.get("audio_url").asText();
                        }
                    }
                } else {
                    status = awsBatchService.getJobStatus(jobId);
                    
                    if ("SUCCEEDED".equals(status)) {
                        JobDetail jobDetail = awsBatchService.getJobDetails(jobId);
                        audioUrl = extractAudioUrlFromJob(jobDetail, filename);
                    }
                }
                
                if (audioUrl != null && ("SUCCEEDED".equals(status) || "COMPLETED".equals(status))) {
                    long duration = System.currentTimeMillis() - startTime;
                    logger.info("TTS generation completed in {}ms - URL: {}", duration, audioUrl);
                    return audioUrl;
                }
                else if ("FAILED".equals(status) || "ERROR".equals(status)) {
                    logger.error("TTS job failed (jobId: {})", jobId);
                    return null;
                }
                
                Thread.sleep(3000); // 3 seconds
            }
            
            logger.warn("TTS job timed out (jobId: {})", jobId);
            return null;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("TTS job wait interrupted");
            return null;
        } catch (Exception e) {
            logger.error("Error waiting for TTS job: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Extract audio URL from job result
     */
    private String extractAudioUrlFromJob(JobDetail jobDetail, String filename) {
        try {
            if (jobDetail != null && jobDetail.attempts() != null && !jobDetail.attempts().isEmpty()) {
                // Job completed (we'll assume success if we got here and status was SUCCEEDED)
                String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
                return String.format("https://meshiya-tts-audio.s3.eu-west-2.amazonaws.com/tts/%s_%s.%s", 
                                   filename, timestamp, audioFormat);
            }
            return null;
        } catch (Exception e) {
            logger.error("Error extracting audio URL from job result: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Generate a unique key for message content and voice combination
     */
    private String generateMessageKey(String text, String voice) {
        try {
            String input = text.trim() + ":" + voice;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString().substring(0, 32);
        } catch (Exception e) {
            logger.error("Error generating message key: {}", e.getMessage(), e);
            return String.valueOf(text.hashCode() + voice.hashCode());
        }
    }
    
    /**
     * Broadcast TTS ready message to all users in room
     */
    private void broadcastTTSReady(String roomId, String messageKey, String audioUrl) {
        try {
            Map<String, Object> ttsMessage = new HashMap<>();
            ttsMessage.put("type", "TTS_READY");
            ttsMessage.put("messageKey", messageKey);
            ttsMessage.put("audioUrl", audioUrl);
            ttsMessage.put("roomId", roomId);
            
            String destination = "/topic/room/" + roomId + "/tts";
            messagingTemplate.convertAndSend(destination, ttsMessage);
            
            logger.info("Broadcasted TTS ready to room {}: messageKey={}, audioUrl={}", roomId, messageKey, audioUrl);
        } catch (Exception e) {
            logger.error("Error broadcasting TTS ready for room={}, messageKey={}: {}", roomId, messageKey, e.getMessage(), e);
        }
    }
    
    /**
     * Clean text for better TTS pronunciation
     */
    private String cleanTextForTTS(String text) {
        return text
            .replaceAll("\\*\\*", "")
            .replaceAll("\\*", "")
            .replaceAll("`", "")
            .replaceAll("_", " ")
            .replaceAll("\\*([^*]+)\\*", "$1")
            .replaceAll("\\s+", " ")
            .trim()
            .replaceAll("([.!?])\\s*$", "$1");
    }
    
    /**
     * Check if TTS service is enabled
     */
    public boolean isEnabled() {
        return enabled && "aws-batch".equals(mode);
    }
    
    /**
     * Get default voice
     */
    public String getDefaultVoice() {
        return defaultVoice;
    }
    
    /**
     * Get TTS configuration
     */
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("enabled", enabled);
        config.put("mode", mode);
        config.put("defaultVoice", defaultVoice);
        config.put("timeout", timeoutMs);
        config.put("awsBatchAvailable", awsBatchService.isAvailable());
        return config;
    }
    
    /**
     * Cleanup expired cache entries
     */
    private void cleanupExpiredCache() {
        if (!enabled) {
            return;
        }
        
        logger.info("Starting TTS cache cleanup");
        int removedEntries = 0;
        
        try {
            var expiredKeys = ttsCache.entrySet().stream()
                .filter(entry -> entry.getValue().isExpired(ttlHours))
                .map(Map.Entry::getKey)
                .toList();
            
            for (String messageKey : expiredKeys) {
                TTSCacheEntry entry = ttsCache.remove(messageKey);
                if (entry != null) {
                    removedEntries++;
                    logger.debug("Removed expired TTS cache entry: {}", messageKey);
                }
            }
            
            logger.info("TTS cache cleanup completed: removed {} cache entries", removedEntries);
            
        } catch (Exception e) {
            logger.error("Error during TTS cache cleanup: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Shutdown the service and cleanup resources
     */
    public void shutdown() {
        logger.info("Shutting down TTS service");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}