package com.meshiya.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Abstract base class for LLM services providing common functionality
 */
public abstract class LLMService {
    
    protected static final Logger logger = LoggerFactory.getLogger(LLMService.class);
    protected final RestTemplate restTemplate = new RestTemplate();
    
    protected String apiUrl;
    protected String apiKey;
    protected String model;
    protected int timeoutMs;
    
    /**
     * Constructor to initialize common properties
     */
    protected LLMService(String apiUrl, String apiKey, String model, int timeoutMs) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutMs = timeoutMs;
    }
    
    /**
     * Main method to call LLM - must be implemented by subclasses
     */
    public abstract String callLlm(String systemPrompt, String userPrompt);
    
    /**
     * Get current provider information
     */
    public abstract String getCurrentProvider();
    
    /**
     * Get configuration as map
     */
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("apiUrl", apiUrl);
        config.put("model", model);
        config.put("timeoutMs", timeoutMs);
        config.put("hasApiKey", apiKey != null && !apiKey.isEmpty());
        return config;
    }
    
    /**
     * Common method to create HTTP headers
     */
    protected HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isEmpty()) {
            headers.set("Authorization", "Bearer " + apiKey);
        }
        return headers;
    }
    
    /**
     * Log the API call duration and result
     */
    protected void logApiCall(String type, long duration, String result) {
        logger.info("{} LLM response received in {}ms: {}", type, duration, 
                   result != null && result.length() > 100 ? result.substring(0, 100) + "..." : result);
    }
}