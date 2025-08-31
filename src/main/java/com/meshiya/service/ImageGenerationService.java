package com.meshiya.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;
import io.minio.MinioClient;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;

import java.io.ByteArrayInputStream;
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
    
    @Value("${minio.bucket.food-images:meshiya-food-images}")
    private String bucketName;
    
    @Value("${minio.endpoint:http://localhost:9000}")
    private String minioEndpoint;
    
    @Autowired
    private MinioClient minioClient;
    
    /**
     * Generate an image for a food/drink item and store it in MinIO
     * @return MinIO URL of the stored image, or null if generation failed
     */
    public String generateFoodImage(String itemName, String itemDescription, String itemType) {
        if (!enabled) {
            logger.debug("Image generation disabled, returning null");
            return null;
        }
        
        try {
            long startTime = System.currentTimeMillis();
            logger.info("Generating image for item: {} ({})", itemName, itemType);
            
            // Create a unique filename based on item details
            String fileName = createImageFileName(itemName, itemType);
            
            // Check if image already exists in MinIO
            String existingUrl = checkExistingImage(fileName);
            if (existingUrl != null) {
                logger.info("Using existing image for {}: {}", itemName, existingUrl);
                return existingUrl;
            }
            
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
                // Store image in MinIO
                String imageUrl = storeImageInMinIO(fileName, response.getBody());
                logger.info("Image generated and stored successfully for {} in {}ms (size: {}KB) - URL: {}", 
                           itemName, duration, response.getBody().length / 1024, imageUrl);
                return imageUrl;
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
     * Create a unique filename for the food image with timestamp for uniqueness
     */
    private String createImageFileName(String itemName, String itemType) {
        // Normalize item name for filename
        String normalizedName = itemName.toLowerCase()
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
        
        // Add timestamp to make it unique (prevents overwrites and allows variations)
        String timestamp = String.valueOf(System.currentTimeMillis());
        
        return String.format("food/%s/%s_%s.png", itemType.toLowerCase(), timestamp, normalizedName);
    }
    
    /**
     * Check if image already exists in MinIO (disabled for unique images)
     * Since we use timestamps, each image is unique, so skip existing check
     */
    private String checkExistingImage(String fileName) {
        // Skip existing check since we want unique images with timestamps
        // This prevents reusing images and allows for food variations
        return null;
    }
    
    /**
     * Store image bytes in MinIO and return the proxy URL
     */
    private String storeImageInMinIO(String fileName, byte[] imageBytes) throws Exception {
        initializeBucket();
        
        // Store image in MinIO
        minioClient.putObject(
            PutObjectArgs.builder()
                .bucket(bucketName)
                .object(fileName)
                .stream(new ByteArrayInputStream(imageBytes), imageBytes.length, -1)
                .contentType("image/png")
                .build()
        );
        
        // Parse the filename to create Spring Boot proxy URL
        // fileName format: food/{type}/{timestamp}_{name}.png
        String[] parts = fileName.split("/");
        if (parts.length == 3) {
            String type = parts[1];
            String fileNameOnly = parts[2];
            
            // Return Spring Boot proxy URL instead of direct MinIO URL
            return String.format("/api/images/food/%s/%s", type, fileNameOnly);
        } else {
            logger.warn("Unexpected fileName format: {}", fileName);
            return String.format("/api/images/food/unknown/%s", fileName);
        }
    }
    
    /**
     * Initialize MinIO bucket if it doesn't exist
     */
    private void initializeBucket() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                logger.info("Created MinIO bucket for food images: {}", bucketName);
            }
        } catch (Exception e) {
            logger.error("Failed to initialize MinIO bucket for food images: {}", e.getMessage());
            throw new RuntimeException("MinIO bucket initialization failed", e);
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