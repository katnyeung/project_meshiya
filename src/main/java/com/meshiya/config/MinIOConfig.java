package com.meshiya.config;

import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinIOConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(MinIOConfig.class);
    
    @Value("${minio.endpoint:http://localhost:9000}")
    private String endpoint;
    
    @Value("${minio.access-key:minioadmin}")
    private String accessKey;
    
    @Value("${minio.secret-key:minioadmin}")
    private String secretKey;
    
    @Bean
    public MinioClient minioClient() {
        try {
            MinioClient client = MinioClient.builder()
                    .endpoint(endpoint)
                    .credentials(accessKey, secretKey)
                    .build();
            
            logger.info("MinIO client configured successfully with endpoint: {}", endpoint);
            return client;
            
        } catch (Exception e) {
            logger.error("Failed to configure MinIO client: {}", e.getMessage());
            throw new RuntimeException("MinIO configuration failed", e);
        }
    }
}