package com.meshiya.controller;

import com.meshiya.service.TTSService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/tts")
public class TTSController {
    
    private static final Logger logger = LoggerFactory.getLogger(TTSController.class);
    
    @Autowired
    private TTSService ttsService;
    
    /**
     * Convert text to speech via POST request
     * This endpoint acts as a proxy to the TTS service
     */
    @PostMapping
    public ResponseEntity<byte[]> generateSpeech(@RequestBody Map<String, String> request) {
        try {
            String text = request.get("text");
            String voice = request.get("voice");
            
            if (text == null || text.trim().isEmpty()) {
                return ResponseEntity.badRequest().build();
            }
            
            logger.info("TTS request received: text='{}', voice='{}'", 
                       text.length() > 50 ? text.substring(0, 50) + "..." : text, voice);
            
            // Generate speech using TTS service
            byte[] audioData = ttsService.generateSpeech(text, voice);
            
            if (audioData.length == 0) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }
            
            // Return audio with appropriate headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("audio/wav"));
            headers.setContentLength(audioData.length);
            headers.set("Content-Disposition", "attachment; filename=speech.wav");
            
            return new ResponseEntity<>(audioData, headers, HttpStatus.OK);
            
        } catch (Exception e) {
            logger.error("Error in TTS controller: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * Get TTS service status and configuration
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> config = ttsService.getConfig();
        return ResponseEntity.ok(config);
    }
    
    /**
     * Health check for TTS service
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> status = Map.of(
            "status", ttsService.isEnabled() ? "UP" : "DISABLED",
            "service", "TTS Proxy",
            "defaultVoice", ttsService.getDefaultVoice()
        );
        return ResponseEntity.ok(status);
    }
}