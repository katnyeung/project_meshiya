package com.meshiya.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class TTSService {
    
    private static final Logger logger = LoggerFactory.getLogger(TTSService.class);
    
    @Value("${tts.api.url:http://localhost:8000/tts}")
    private String ttsApiUrl;
    
    @Value("${tts.voice.default:am_michael}")
    private String defaultVoice;
    
    @Value("${tts.enabled:true}")
    private boolean enabled;
    
    @Value("${tts.api.timeout:10000}")
    private int timeoutMs;
    
    private final RestTemplate restTemplate;
    
    public TTSService() {
        this.restTemplate = new RestTemplate();
        // Set timeout for requests
        this.restTemplate.setRequestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory());
        ((org.springframework.http.client.SimpleClientHttpRequestFactory) restTemplate.getRequestFactory())
                .setConnectTimeout(timeoutMs);
        ((org.springframework.http.client.SimpleClientHttpRequestFactory) restTemplate.getRequestFactory())
                .setReadTimeout(timeoutMs);
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
}