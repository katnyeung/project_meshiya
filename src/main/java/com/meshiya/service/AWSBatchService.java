package com.meshiya.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.batch.BatchClient;
import software.amazon.awssdk.services.batch.model.*;

import java.util.HashMap;
import java.util.Map;

@Service
public class AWSBatchService {

    private static final Logger logger = LoggerFactory.getLogger(AWSBatchService.class);
    
    private final BatchClient batchClient;

    @Value("${aws.region:eu-west-2}")
    private String awsRegion;

    @Value("${aws.batch.image-queue:meshiya-image-queue}")
    private String imageQueue;

    @Value("${aws.batch.tts-queue:meshiya-tts-queue}")
    private String ttsQueue;

    @Value("${minio.bucket.food-images:meshiya-food-images}")
    private String imageBucket;

    @Value("${minio.bucket.tts:meshiya-tts-audio}")
    private String ttsBucket;

    public AWSBatchService() {
        this.batchClient = BatchClient.builder()
                .region(Region.EU_WEST_2)
                .build();
    }

    /**
     * Submit image generation job to AWS Batch
     */
    public String submitImageGenerationJob(String prompt, String itemName, String negativePrompt, 
                                         Integer width, Integer height, Integer steps, 
                                         Float guidanceScale, String seed, String modelName) {
        try {
            logger.info("Submitting image generation job for item: {}", itemName);
            
            // Prepare environment variables for the batch job
            Map<String, String> environment = new HashMap<>();
            environment.put("PROMPT", prompt != null ? prompt : "");
            environment.put("ITEM_NAME", itemName != null ? itemName : "unknown");
            environment.put("NEGATIVE_PROMPT", negativePrompt != null ? negativePrompt : "blurry, low quality, text, watermark");
            environment.put("WIDTH", width != null ? width.toString() : "512");
            environment.put("HEIGHT", height != null ? height.toString() : "512");
            environment.put("STEPS", steps != null ? steps.toString() : "25");
            environment.put("GUIDANCE_SCALE", guidanceScale != null ? guidanceScale.toString() : "7.0");
            environment.put("SEED", seed != null ? seed : "");
            environment.put("MODEL_NAME", modelName != null ? modelName : "realvis");
            environment.put("OUTPUT_BUCKET", imageBucket);

            // Create job parameters
            Map<String, String> parameters = new HashMap<>();
            parameters.put("inputData", itemName);

            // Submit job request
            SubmitJobRequest request = SubmitJobRequest.builder()
                    .jobName("image-gen-" + System.currentTimeMillis())
                    .jobQueue(imageQueue)
                    .jobDefinition("meshiya-image-generator")
                    .parameters(parameters)
                    .containerOverrides(ContainerOverrides.builder()
                            .environment(environment.entrySet().stream()
                                    .map(entry -> KeyValuePair.builder()
                                            .name(entry.getKey())
                                            .value(entry.getValue())
                                            .build())
                                    .toList())
                            .build())
                    .build();

            SubmitJobResponse response = batchClient.submitJob(request);
            String jobId = response.jobId();
            
            logger.info("Image generation job submitted successfully: {}", jobId);
            return jobId;
            
        } catch (Exception e) {
            logger.error("Error submitting image generation job for item {}: {}", itemName, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Submit TTS job to AWS Batch
     */
    public String submitTTSJob(String text, String filename, String voice) {
        try {
            logger.info("Submitting TTS job for filename: {}", filename);
            
            // Prepare environment variables for the batch job
            Map<String, String> environment = new HashMap<>();
            environment.put("TEXT", text != null ? text : "");
            environment.put("FILENAME", filename != null ? filename : "unknown");
            environment.put("VOICE", voice != null ? voice : "am_michael");
            environment.put("OUTPUT_BUCKET", ttsBucket);

            // Create job parameters
            Map<String, String> parameters = new HashMap<>();
            parameters.put("inputText", text);

            // Submit job request
            SubmitJobRequest request = SubmitJobRequest.builder()
                    .jobName("tts-" + System.currentTimeMillis())
                    .jobQueue(ttsQueue)
                    .jobDefinition("meshiya-tts")
                    .parameters(parameters)
                    .containerOverrides(ContainerOverrides.builder()
                            .environment(environment.entrySet().stream()
                                    .map(entry -> KeyValuePair.builder()
                                            .name(entry.getKey())
                                            .value(entry.getValue())
                                            .build())
                                    .toList())
                            .build())
                    .build();

            SubmitJobResponse response = batchClient.submitJob(request);
            String jobId = response.jobId();
            
            logger.info("TTS job submitted successfully: {}", jobId);
            return jobId;
            
        } catch (Exception e) {
            logger.error("Error submitting TTS job for filename {}: {}", filename, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Get job status
     */
    public String getJobStatus(String jobId) {
        try {
            DescribeJobsRequest request = DescribeJobsRequest.builder()
                    .jobs(jobId)
                    .build();

            DescribeJobsResponse response = batchClient.describeJobs(request);
            if (!response.jobs().isEmpty()) {
                return response.jobs().get(0).status().toString();
            }
            return "UNKNOWN";
            
        } catch (Exception e) {
            logger.error("Error getting job status for {}: {}", jobId, e.getMessage());
            return "ERROR";
        }
    }

    /**
     * Get detailed job information
     */
    public JobDetail getJobDetails(String jobId) {
        try {
            DescribeJobsRequest request = DescribeJobsRequest.builder()
                    .jobs(jobId)
                    .build();

            DescribeJobsResponse response = batchClient.describeJobs(request);
            if (!response.jobs().isEmpty()) {
                return response.jobs().get(0);
            }
            return null;
            
        } catch (Exception e) {
            logger.error("Error getting job details for {}: {}", jobId, e.getMessage());
            return null;
        }
    }
    
    /**
     * Check if AWS Batch is available
     */
    public boolean isAvailable() {
        try {
            // Simple health check by listing job queues
            batchClient.describeJobQueues(DescribeJobQueuesRequest.builder().build());
            return true;
        } catch (Exception e) {
            logger.debug("AWS Batch service not available: {}", e.getMessage());
            return false;
        }
    }
}