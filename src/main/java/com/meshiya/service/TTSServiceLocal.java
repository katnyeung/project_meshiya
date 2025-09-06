package com.meshiya.service;

import com.meshiya.dto.ChatMessage;
import com.meshiya.model.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import io.minio.MinioClient;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.http.Method;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.io.ByteArrayInputStream;

@Service
public class TTSServiceLocal {
    
    private static final Logger logger = LoggerFactory.getLogger(TTSServiceLocal.class);
    
    @Value("${tts.api.url:http://localhost:8000/tts}")
    private String ttsApiUrl;
    
    @Value("${tts.voice.default:am_michael}")
    private String defaultVoice;
    
    @Value("${tts.enabled:true}")
    private boolean enabled;
    
    @Value("${tts.api.timeout:10000}")
    private int timeoutMs;
    
    @Value("${tts.storage.type:file}")
    private String storageType;
    
    @Value("${tts.storage.ttl-hours:2}")
    private int ttlHours;
    
    @Value("${tts.api.format:mp3}")
    private String audioFormat;
    
    @Value("${minio.bucket.tts:meshiya-tts-audio}")
    private String ttsBucket;
    
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    
    @Autowired
    private MinioClient minioClient;
    
    private final RestTemplate restTemplate;
    
    // Cache for storing TTS audio URLs by message content hash
    private final ConcurrentHashMap<String, TTSCacheEntry> ttsCache = new ConcurrentHashMap<>();
    
    // Track processing to prevent duplicate requests
    private final ConcurrentHashMap<String, CompletableFuture<String>> processingRequests = new ConcurrentHashMap<>();
    
    // Scheduler for cache cleanup
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    private static class TTSCacheEntry {
        final String audioUrl;
        final String objectKey; // MinIO object key for cleanup
        final LocalDateTime timestamp;
        
        TTSCacheEntry(String audioUrl, String objectKey) {
            this.audioUrl = audioUrl;
            this.objectKey = objectKey;
            this.timestamp = LocalDateTime.now();
        }
        
        boolean isExpired(int ttlHours) {
            return timestamp.isBefore(LocalDateTime.now().minusHours(ttlHours));
        }
    }
    
    public TTSServiceLocal() {
        this.restTemplate = new RestTemplate();
        // Set timeout for requests
        this.restTemplate.setRequestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory());
        ((org.springframework.http.client.SimpleClientHttpRequestFactory) restTemplate.getRequestFactory())
                .setConnectTimeout(timeoutMs);
        ((org.springframework.http.client.SimpleClientHttpRequestFactory) restTemplate.getRequestFactory())
                .setReadTimeout(timeoutMs);
                
        // Start cache cleanup scheduler - run every 30 minutes
        scheduler.scheduleAtFixedRate(this::cleanupExpiredCache, 30, 30, TimeUnit.MINUTES);
    }
    
    /**
     * Initialize MinIO bucket after all dependencies are injected
     */
    @EventListener(ContextRefreshedEvent.class)
    public void initializeStorage() {
        if ("minio".equals(storageType)) {
            try {
                initializeBucket();
                logger.info("TTS MinIO storage initialized successfully");
            } catch (Exception e) {
                logger.error("Failed to initialize TTS MinIO storage: {}", e.getMessage(), e);
            }
        }
    }
    
    /**
     * Initialize TTS bucket if it doesn't exist
     */
    private void initializeBucket() {
        try {
            boolean bucketExists = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(ttsBucket)
                    .build());
                    
            if (!bucketExists) {
                minioClient.makeBucket(MakeBucketArgs.builder()
                        .bucket(ttsBucket)
                        .build());
                logger.info("Created TTS bucket: {}", ttsBucket);
            } else {
                logger.info("TTS bucket already exists: {}", ttsBucket);
            }
        } catch (Exception e) {
            logger.error("Failed to initialize TTS bucket: {}", e.getMessage(), e);
            throw new RuntimeException("TTS MinIO initialization failed", e);
        }
    }
    
    /**
     * Convert text to speech using the configured TTS API
     * @param text The text to convert
     * @param voice The voice to use (optional, uses default if null)
     * @return The audio data as byte array
     */
    public byte[] generateSpeech(String text, String voice) {
        if (!enabled) {
            logger.warn("TTS service is disabled");
            return new byte[0];
        }
        
        if (text == null || text.trim().isEmpty()) {
            logger.warn("Empty text provided to TTS service");
            return new byte[0];
        }
        
        try {
            // Clean text for TTS
            String cleanText = cleanTextForTTS(text);
            
            // Use default voice if none provided
            if (voice == null || voice.trim().isEmpty()) {
                voice = defaultVoice;
            }
            
            logger.info("Generating TTS for text: '{}' with voice: {}", 
                       cleanText.length() > 50 ? cleanText.substring(0, 50) + "..." : cleanText, voice);
            
            // Prepare request
            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("text", cleanText);
            requestBody.put("voice", voice);
            requestBody.put("format", audioFormat); // Request MP3 format
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(requestBody, headers);
            
            // Make request to TTS API
            long startTime = System.currentTimeMillis();
            ResponseEntity<byte[]> response = restTemplate.exchange(
                ttsApiUrl, 
                HttpMethod.POST, 
                entity, 
                byte[].class
            );
            long duration = System.currentTimeMillis() - startTime;
            
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                byte[] audioData = response.getBody();
                logger.info("TTS generation successful: {} bytes in {}ms", audioData.length, duration);
                return audioData;
            } else {
                logger.warn("TTS API returned status: {}", response.getStatusCode());
                return new byte[0];
            }
            
        } catch (Exception e) {
            logger.error("Error calling TTS API: {}", e.getMessage(), e);
            return new byte[0];
        }
    }
    
    /**
     * Process AI messages for centralized TTS generation with caching
     * This prevents duplicate TTS requests from multiple users
     */
    public void processAIMessageForTTS(ChatMessage message) {
        processAIMessageForTTSWithCallback(message, null);
    }
    
    /**
     * Process AI messages for TTS with callback when ready
     * @param message The AI message to process
     * @param onTTSReady Callback to execute when TTS is ready (can be null)
     */
    public void processAIMessageForTTSWithCallback(ChatMessage message, Runnable onTTSReady) {
        if (!enabled || message.getType() != MessageType.AI_MESSAGE) {
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
            // Execute callback if provided (for synchronized message broadcasting)
            if (onTTSReady != null) {
                onTTSReady.run();
            }
            return;
        }
        
        // Check if already processing to prevent duplicate requests
        CompletableFuture<String> existingRequest = processingRequests.get(messageKey);
        if (existingRequest != null) {
            logger.info("TTS already processing for messageKey={}, waiting for completion", messageKey);
            existingRequest.thenAccept(audioUrl -> {
                if (audioUrl != null) {
                    broadcastTTSReady(message.getRoomId(), messageKey, audioUrl);
                    // Execute callback if provided (for synchronized message broadcasting)
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
                logger.info("Generating TTS for messageKey={}", messageKey);
                byte[] audioData = generateSpeech(text, defaultVoice);
                
                if (audioData.length > 0) {
                    String[] urlAndKey = saveAudioFile(messageKey, audioData);
                    String audioUrl = urlAndKey[0];
                    String objectKey = urlAndKey[1];
                    
                    // Cache the result
                    ttsCache.put(messageKey, new TTSCacheEntry(audioUrl, objectKey));
                    logger.info("TTS generated and cached: messageKey={}, audioUrl={}", messageKey, audioUrl);
                    
                    return audioUrl;
                } else {
                    logger.warn("TTS generation failed for messageKey={}", messageKey);
                    return null;
                }
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
                    // Execute callback if provided (for synchronized message broadcasting)
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
            // Take first 32 characters for consistent length with frontend
            return sb.toString().substring(0, 32);
        } catch (Exception e) {
            logger.error("Error generating message key: {}", e.getMessage(), e);
            return String.valueOf(text.hashCode() + voice.hashCode());
        }
    }
    
    /**
     * Save audio data and return [URL, objectKey]
     */
    private String[] saveAudioFile(String messageKey, byte[] audioData) {
        if ("minio".equals(storageType)) {
            return saveToMinIO(messageKey, audioData);
        } else {
            return saveToLocal(messageKey, audioData);
        }
    }
    
    /**
     * Save audio data to MinIO and return [presignedURL, objectKey]
     */
    private String[] saveToMinIO(String messageKey, byte[] audioData) {
        try {
            String objectKey = "tts_" + messageKey + "." + audioFormat;
            
            // Upload to MinIO
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(ttsBucket)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(audioData), audioData.length, -1)
                    .contentType("audio/mpeg")
                    .build());
            
            // Get presigned URL (valid for 1 hour)
            String presignedUrl = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(ttsBucket)
                    .object(objectKey)
                    .expiry(1, TimeUnit.HOURS)
                    .build());
            
            logger.info("Audio saved to MinIO: {} ({} bytes)", objectKey, audioData.length);
            return new String[]{presignedUrl, objectKey};
            
        } catch (Exception e) {
            logger.error("Error saving audio to MinIO for messageKey={}: {}", messageKey, e.getMessage(), e);
            return new String[]{null, null};
        }
    }
    
    /**
     * Save audio data to local file and return [URL, fileName] (fallback)
     */
    private String[] saveToLocal(String messageKey, byte[] audioData) {
        try {
            // Create local cache directory if needed
            Path cacheDir = Paths.get("./tts-cache");
            Files.createDirectories(cacheDir);
            
            // Save audio file
            String fileName = "tts_" + messageKey + "." + audioFormat;
            Path filePath = cacheDir.resolve(fileName);
            Files.write(filePath, audioData);
            
            // Return URL for serving the file
            String audioUrl = "/api/tts/audio/" + fileName;
            logger.info("Audio file saved locally: {} ({} bytes)", filePath, audioData.length);
            
            return new String[]{audioUrl, fileName};
        } catch (Exception e) {
            logger.error("Error saving audio file locally for messageKey={}: {}", messageKey, e.getMessage(), e);
            return new String[]{null, null};
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
     * @param text Raw text from Master response
     * @return Cleaned text suitable for TTS
     */
    private String cleanTextForTTS(String text) {
        return text
            // Remove markdown formatting
            .replaceAll("\\*\\*", "") // Remove bold
            .replaceAll("\\*", "")    // Remove italic/actions
            .replaceAll("`", "")      // Remove code formatting
            .replaceAll("_", " ")     // Replace underscores with spaces
            
            // Clean up action descriptions in asterisks
            .replaceAll("\\*([^*]+)\\*", "$1") // Keep content, remove asterisks
            
            // Normalize whitespace
            .replaceAll("\\s+", " ")
            .trim()
            
            // Ensure proper sentence endings for natural speech
            .replaceAll("([.!?])\\s*$", "$1");
    }
    
    /**
     * Check if TTS service is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Get default voice
     */
    public String getDefaultVoice() {
        return defaultVoice;
    }
    
    /**
     * Get TTS API configuration
     */
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("enabled", enabled);
        config.put("defaultVoice", defaultVoice);
        config.put("apiUrl", ttsApiUrl);
        config.put("timeout", timeoutMs);
        return config;
    }
    
    /**
     * Cleanup expired cache entries and delete associated files
     */
    private void cleanupExpiredCache() {
        if (!enabled) {
            return;
        }
        
        logger.info("Starting TTS cache cleanup");
        int removedEntries = 0;
        int removedFiles = 0;
        
        try {
            // Find expired entries
            var expiredKeys = ttsCache.entrySet().stream()
                .filter(entry -> entry.getValue().isExpired(ttlHours))
                .map(Map.Entry::getKey)
                .toList();
            
            for (String messageKey : expiredKeys) {
                TTSCacheEntry entry = ttsCache.remove(messageKey);
                if (entry != null) {
                    removedEntries++;
                    
                    // Try to delete the associated file/object
                    try {
                        if ("minio".equals(storageType) && entry.objectKey != null) {
                            // Delete from MinIO
                            minioClient.removeObject(RemoveObjectArgs.builder()
                                    .bucket(ttsBucket)
                                    .object(entry.objectKey)
                                    .build());
                            removedFiles++;
                            logger.debug("Deleted expired TTS object from MinIO: {}", entry.objectKey);
                        } else {
                            // Delete local file (fallback)
                            String fileName = "tts_" + messageKey + "." + audioFormat;
                            Path filePath = Paths.get("./tts-cache").resolve(fileName);
                            
                            if (Files.exists(filePath)) {
                                Files.delete(filePath);
                                removedFiles++;
                                logger.debug("Deleted expired TTS file: {}", fileName);
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to delete TTS file/object for messageKey {}: {}", messageKey, e.getMessage());
                    }
                }
            }
            
            logger.info("TTS cache cleanup completed: removed {} cache entries, deleted {} files", 
                       removedEntries, removedFiles);
            
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