package com.meshiya.scheduler;

import com.meshiya.service.RoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Application initialization scheduler - triggers service initialization after Spring Boot is ready
 */
@Component
public class ApplicationInitializationScheduler {
    
    private static final Logger logger = LoggerFactory.getLogger(ApplicationInitializationScheduler.class);
    
    @Autowired
    private RoomService roomService;
    
    /**
     * Initialize application components after Spring Boot is fully ready
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        logger.info("Application ready - initializing Midnight Diner components...");
        
        try {
            // Initialize default room (room1) with Midnight Diner theme
            roomService.initializeRoom("room1", "Midnight Diner - Main Room", "Master", 
                                      "Welcome to my midnight diner. The night is long, and there's always time for a good conversation.");
            
            logger.info("✅ Default room (room1) initialized successfully");
            logger.info("🍜 Midnight Diner is ready - Master is present in room1");
            logger.info("🏠 Additional rooms can be created via /api/debug/rooms/{roomId}/initialize");
            
        } catch (Exception e) {
            logger.error("❌ Failed to initialize default room: {}", e.getMessage(), e);
        }
    }
}