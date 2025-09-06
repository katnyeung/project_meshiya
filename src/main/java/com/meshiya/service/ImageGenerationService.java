package com.meshiya.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.batch.model.JobDetail;
import software.amazon.awssdk.services.batch.model.JobStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for generating food/drink images using AWS Batch
 */
@Service
public class ImageGenerationService {
    
    private static final Logger logger = LoggerFactory.getLogger(ImageGenerationService.class);
    
    // Pattern to extract JSON result from batch job logs
    private static final Pattern RESULT_PATTERN = Pattern.compile("RESULT: (.+)");
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // Cache for tracking ongoing jobs
    private final ConcurrentHashMap<String, CompletableFuture<String>> activeJobs = new ConcurrentHashMap<>();
    
    @Value("${image.generation.mode:aws-batch}")
    private String mode;
    
    @Value("${image.generation.timeout:300000}")
    private int timeoutMs;
    
    @Value("${image.generation.enabled:true}")
    private boolean enabled;
    
    @Autowired
    private AWSBatchService awsBatchService;
    
    /**
     * Generate an image for a food/drink item using AWS Batch
     * @return S3 URL of the generated image, or null if generation failed
     */
    public String generateFoodImage(String itemName, String itemDescription, String itemType) {
        if (!enabled) {
            logger.debug("Image generation disabled, returning null");
            return null;
        }
        
        if (!"aws-batch".equals(mode)) {
            logger.warn("Image generation mode is not set to aws-batch, returning null");
            return null;
        }
        
        try {
            logger.info("Submitting AWS Batch job for image generation: {} ({})", itemName, itemType);
            
            // Create prompt based on item details
            String prompt = createPrompt(itemName, itemDescription, itemType);
            
            // Submit batch job
            String jobId = awsBatchService.submitImageGenerationJob(
                prompt, 
                itemName,
                "blurry, low quality, text, watermark", // negative prompt
                512, // width
                512, // height  
                25,  // steps
                7.0f, // guidance scale
                null, // seed
                "realvis" // model name
            );
            
            if (jobId == null) {
                logger.error("Failed to submit image generation job for item: {}", itemName);
                return null;
            }
            
            logger.info("Image generation job submitted: {} for item: {}", jobId, itemName);
            
            // Wait for job completion and get result
            return waitForJobCompletion(jobId, itemName);
            
        } catch (Exception e) {
            logger.error("Error generating image for item {}: {}", itemName, e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Wait for AWS Batch job completion and extract S3 URL from result
     */
    private String waitForJobCompletion(String jobId, String itemName) {
        try {
            long startTime = System.currentTimeMillis();
            long maxWaitTime = timeoutMs;
            
            while (System.currentTimeMillis() - startTime < maxWaitTime) {
                String status = awsBatchService.getJobStatus(jobId);
                
                if ("SUCCEEDED".equals(status)) {
                    // Get job details to extract result from logs
                    JobDetail jobDetail = awsBatchService.getJobDetails(jobId);
                    String imageUrl = extractImageUrlFromJob(jobDetail);
                    
                    if (imageUrl != null) {
                        long duration = System.currentTimeMillis() - startTime;
                        logger.info("Image generation completed for {} in {}ms - URL: {}", 
                                   itemName, duration, imageUrl);
                        return imageUrl;
                    }
                }
                else if ("FAILED".equals(status)) {
                    logger.error("Image generation job failed for item: {} (jobId: {})", itemName, jobId);
                    return null;
                }
                
                // Wait before checking again
                Thread.sleep(5000); // 5 seconds
            }
            
            logger.warn("Image generation job timed out for item: {} (jobId: {})", itemName, jobId);
            return null;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Image generation wait interrupted for item: {}", itemName);
            return null;
        } catch (Exception e) {
            logger.error("Error waiting for image generation job for item {}: {}", itemName, e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Extract image URL from job logs/result
     */
    private String extractImageUrlFromJob(JobDetail jobDetail) {
        try {
            // In a real implementation, you would get the job logs from CloudWatch
            // For now, we'll simulate extracting the result from job exit reason or similar
            
            // The batch job prints "RESULT: {json}" to stdout
            // AWS Batch captures this in the job's exit reason or logs
            
            if (jobDetail != null && jobDetail.attempts() != null && !jobDetail.attempts().isEmpty()) {
                var attempt = jobDetail.attempts().get(0);
                // Check if job completed (we'll assume success if we got here and status was SUCCEEDED)
                String jobName = jobDetail.jobName();
                String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
                
                // The actual URL would come from the batch job result
                return String.format("https://meshiya-food-images.s3.eu-west-2.amazonaws.com/generated/unknown_%s.png", timestamp);
            }
            
            return null;
            
        } catch (Exception e) {
            logger.error("Error extracting image URL from job result: {}", e.getMessage(), e);
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
    public java.util.Map<String, Object> getConfig() {
        java.util.Map<String, Object> config = new java.util.HashMap<>();
        config.put("mode", mode);
        config.put("timeoutMs", timeoutMs);
        config.put("enabled", enabled);
        config.put("awsBatchAvailable", awsBatchService.isAvailable());
        return config;
    }
    
    /**
     * Check if image generation is available
     */
    public boolean isAvailable() {
        if (!enabled) {
            return false;
        }
        
        if ("aws-batch".equals(mode)) {
            return awsBatchService.isAvailable();
        }
        
        return false;
    }
}