package com.meshiya.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

@Service
public class StartupService implements ApplicationRunner {
    
    private static final Logger logger = LoggerFactory.getLogger(StartupService.class);
    
    @Autowired
    private RoomService roomService;
    
    @Override
    public void run(ApplicationArguments args) throws Exception {
        logger.info("Initializing Midnight Diner with Room-based Architecture...");
        
        // Initialize Room1 with Master
        roomService.initializeRoom1();
        
        logger.info("Room1 initialized with persistent Redis storage");
        logger.info("Midnight Diner is ready - Master is present in Room1");
    }
}