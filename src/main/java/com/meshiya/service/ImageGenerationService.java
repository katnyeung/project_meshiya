package com.meshiya.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.util.HashMap;
import java.util.Map;
import java.util.Base64;

/**
 * Service for generating food/drink images using the local image generation API
 */
@Service
public class ImageGenerationService {
    
    private static final Logger logger = LoggerFactory.getLogger(ImageGenerationService.class);
    private final RestTemplate restTemplate = new RestTemplate();
    
    @Value("${image.generation.api.url:http://localhost:5000/generate}")
    private String apiUrl;
    
    @Value("${image.generation.timeout:30000}")
    private int timeoutMs;
    
    @Value("${image.generation.enabled:true}")
    private boolean enabled;
    
    /**
     * Generate an image for a food/drink item
     */
    public String generateFoodImage(String itemName, String itemDescription, String itemType) {
        if (!enabled) {
            logger.debug("Image generation disabled, returning null");
            return null;
        }
        
        try {
            long startTime = System.currentTimeMillis();
            logger.info("Generating image for item: {} ({})", itemName, itemType);
            
            // Create prompt based on item details
            String prompt = createPrompt(itemName, itemDescription, itemType);
            
            // Prepare request payload
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("prompt", prompt);
            requestBody.put("negative_prompt", "blurry, low quality, text, watermark");
            requestBody.put("width", 512);
            requestBody.put("height", 512);
            
            // Create headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            
            // Make API call
            ResponseEntity<byte[]> response = restTemplate.exchange(
                apiUrl,
                HttpMethod.POST,
                request,
                byte[].class
            );
            
            long duration = System.currentTimeMillis() - startTime;
            
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                // Convert image bytes to base64 for easy transmission
                String base64Image = Base64.getEncoder().encodeToString(response.getBody());
                logger.info("Image generated successfully for {} in {}ms (size: {}KB)", 
                           itemName, duration, response.getBody().length / 1024);
                return base64Image;
            } else {
                logger.warn("Image generation API returned status: {} for item: {}", 
                           response.getStatusCode(), itemName);
                return null;
            }
            
        } catch (ResourceAccessException e) {
            logger.warn("Image generation service unavailable for item {}: {}", itemName, e.getMessage());
            return null;
        } catch (Exception e) {
            logger.error("Error generating image for item {}: {}", itemName, e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Create a descriptive prompt for image generation
     */
    private String createPrompt(String itemName, String itemDescription, String itemType) {
        StringBuilder prompt = new StringBuilder();
        
        // Start with 3D shape and angle prefix - full view not closeup
        prompt.append("Displayed at a 45-degree angle (three-quarter view), placed centrally, professional lighting, no background (transparent background), studio-style photography, highly detailed, soft shadows. A realistic 3D dish of ").append(itemName.toLowerCase());
        
        // Add type-specific styling
        switch (itemType.toUpperCase()) {
            case "DRINK":
                prompt.append(", served in a glass cup");
                break;
            case "FOOD":
                prompt.append(", woodern bowl or plaste");
                break;
            case "DESSERT":
                prompt.append(", artistically presented dessert");
                break;
            default:
                prompt.append(", beautifully prepared");
        }
        
        // Add description if available and meaningful
        if (itemDescription != null && !itemDescription.trim().isEmpty() && 
            !itemDescription.equals("A special creation inspired by your request")) {
            prompt.append(", ").append(itemDescription.toLowerCase());
        }
        
        // Add style qualifiers for better results with 3D emphasis and complete view
        prompt.append(", anime style, high quality, detailed, 3D rendered, dimensional depth, warm lighting, restaurant setting, floating in space, clean background, not closeup, full item visible, entire dish in frame, medium distance shot");
        
        String finalPrompt = prompt.toString();
        logger.debug("Generated image prompt for {}: {}", itemName, finalPrompt);
        
        return finalPrompt;
    }
    
    /**
     * Get service configuration for debugging
     */
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("apiUrl", apiUrl);
        config.put("timeoutMs", timeoutMs);
        config.put("enabled", enabled);
        return config;
    }
    
    /**
     * Check if image generation is available
     */
    public boolean isAvailable() {
        if (!enabled) {
            return false;
        }
        
        try {
            // Simple health check - just check if the endpoint is reachable
            restTemplate.optionsForAllow(apiUrl);
            return true;
        } catch (Exception e) {
            logger.debug("Image generation service not available: {}", e.getMessage());
            return false;
        }
    }
}