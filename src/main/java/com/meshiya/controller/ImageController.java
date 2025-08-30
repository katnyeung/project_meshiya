package com.meshiya.controller;

import com.meshiya.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.HashMap;

/**
 * Controller for handling user image upload/download operations
 */
@RestController
@RequestMapping("/api/images")
@Tag(name = "Images", description = "User image management for registered users")
public class ImageController {
    
    private static final Logger logger = LoggerFactory.getLogger(ImageController.class);
    
    @Autowired
    private UserService userService;
    
    /**
     * Upload user image
     */
    @PostMapping("/upload/{imageType}")
    @Operation(summary = "Upload user image", description = "Upload a custom image for registered user (idle, chatting, eating, normal)")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Image uploaded successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid image type or file"),
        @ApiResponse(responseCode = "403", description = "User not registered"),
        @ApiResponse(responseCode = "500", description = "Upload failed")
    })
    public ResponseEntity<Map<String, Object>> uploadImage(
            @Parameter(description = "Image type (idle, chatting, eating, normal)") @PathVariable String imageType,
            @Parameter(description = "Image file (PNG recommended)") @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            // Get username from session (simple approach for MVP)
            String username = getUsernameFromSession(request);
            if (username == null) {
                response.put("success", false);
                response.put("message", "No active session found");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }
            
            // Check if user is registered
            if (!userService.isUserRegistered(username)) {
                response.put("success", false);
                response.put("message", "User not registered");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
            }
            
            // Validate image type
            if (!isValidImageType(imageType)) {
                response.put("success", false);
                response.put("message", "Invalid image type. Allowed: idle, chatting, eating, normal");
                return ResponseEntity.badRequest().body(response);
            }
            
            // Validate file
            if (file.isEmpty()) {
                response.put("success", false);
                response.put("message", "No file uploaded");
                return ResponseEntity.badRequest().body(response);
            }
            
            // Check file size (max 10MB)
            if (file.getSize() > 10 * 1024 * 1024) {
                response.put("success", false);
                response.put("message", "File too large. Maximum size is 10MB");
                return ResponseEntity.badRequest().body(response);
            }
            
            // Check file type
            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                response.put("success", false);
                response.put("message", "Invalid file type. Only images are allowed");
                return ResponseEntity.badRequest().body(response);
            }
            
            // Upload image
            String imageUrl = userService.uploadUserImage(username, imageType, file);
            
            response.put("success", true);
            response.put("message", "Image uploaded successfully");
            response.put("imageUrl", imageUrl);
            response.put("imageType", imageType);
            response.put("username", username);
            
            logger.info("Image uploaded for user {} - type: {}", username, imageType);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Image upload failed: {}", e.getMessage());
            response.put("success", false);
            response.put("message", "Upload failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
    
    /**
     * Get user image URL
     */
    @GetMapping("/{username}/{imageType}")
    @Operation(summary = "Get user image URL", description = "Get the URL for a specific user image")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Image URL retrieved successfully"),
        @ApiResponse(responseCode = "404", description = "Image not found")
    })
    public ResponseEntity<Map<String, Object>> getImage(
            @Parameter(description = "Username") @PathVariable String username,
            @Parameter(description = "Image type") @PathVariable String imageType) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            if (!isValidImageType(imageType)) {
                response.put("success", false);
                response.put("message", "Invalid image type");
                return ResponseEntity.badRequest().body(response);
            }
            
            String imageUrl = userService.getImageUrl(username, imageType);
            
            if (imageUrl != null) {
                response.put("success", true);
                response.put("imageUrl", imageUrl);
                response.put("username", username);
                response.put("imageType", imageType);
                return ResponseEntity.ok(response);
            } else {
                response.put("success", false);
                response.put("message", "Image not found");
                return ResponseEntity.notFound().build();
            }
            
        } catch (Exception e) {
            logger.error("Failed to get image URL for {} - {}: {}", username, imageType, e.getMessage());
            response.put("success", false);
            response.put("message", "Failed to get image: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
    
    /**
     * Delete user image
     */
    @DeleteMapping("/{imageType}")
    @Operation(summary = "Delete user image", description = "Delete a user's custom image")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Image deleted successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid image type"),
        @ApiResponse(responseCode = "403", description = "User not registered"),
        @ApiResponse(responseCode = "404", description = "Image not found")
    })
    public ResponseEntity<Map<String, Object>> deleteImage(
            @Parameter(description = "Image type to delete") @PathVariable String imageType,
            HttpServletRequest request) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            String username = getUsernameFromSession(request);
            if (username == null) {
                response.put("success", false);
                response.put("message", "No active session found");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }
            
            if (!userService.isUserRegistered(username)) {
                response.put("success", false);
                response.put("message", "User not registered");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
            }
            
            if (!isValidImageType(imageType)) {
                response.put("success", false);
                response.put("message", "Invalid image type");
                return ResponseEntity.badRequest().body(response);
            }
            
            userService.deleteUserImage(username, imageType);
            
            response.put("success", true);
            response.put("message", "Image deleted successfully");
            response.put("imageType", imageType);
            response.put("username", username);
            
            logger.info("Image deleted for user {} - type: {}", username, imageType);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Image deletion failed: {}", e.getMessage());
            response.put("success", false);
            response.put("message", "Deletion failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
    
    /**
     * Get all user images
     */
    @GetMapping("/user/{username}")
    @Operation(summary = "Get all user images", description = "Get URLs for all custom images of a user")
    public ResponseEntity<Map<String, Object>> getAllUserImages(
            @Parameter(description = "Username") @PathVariable String username) {
        
        Map<String, Object> response = new HashMap<>();
        Map<String, String> imageUrls = new HashMap<>();
        
        try {
            String[] imageTypes = {"idle", "chatting", "eating", "normal"};
            
            for (String imageType : imageTypes) {
                String imageUrl = userService.getImageUrl(username, imageType);
                if (imageUrl != null) {
                    imageUrls.put(imageType, imageUrl);
                }
            }
            
            response.put("success", true);
            response.put("username", username);
            response.put("images", imageUrls);
            response.put("isRegistered", userService.isUserRegistered(username));
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Failed to get user images for {}: {}", username, e.getMessage());
            response.put("success", false);
            response.put("message", "Failed to get images: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
    
    // Helper methods
    
    /**
     * Extract username from session (simple approach for MVP)
     */
    private String getUsernameFromSession(HttpServletRequest request) {
        // For MVP, we can extract from session attribute or cookie
        // This is a simplified approach - in production, use proper JWT tokens
        String sessionId = request.getParameter("sessionId");
        if (sessionId == null) {
            // Try to get from cookies
            if (request.getCookies() != null) {
                for (var cookie : request.getCookies()) {
                    if ("meshiyaSessionId".equals(cookie.getName())) {
                        sessionId = cookie.getValue();
                        break;
                    }
                }
            }
        }
        
        // For now, decode the session ID to get username (very basic approach)
        // In production, this should be properly managed with session stores
        if (sessionId != null) {
            try {
                String decoded = new String(java.util.Base64.getDecoder().decode(sessionId));
                // Extract username from decoded session (this is very basic for MVP)
                if (decoded.startsWith("session_")) {
                    // This is just for demo - real implementation would store username in session
                    // For now, assume username is passed as a header for registered users
                    String username = request.getHeader("X-Username");
                    return username;
                }
            } catch (Exception e) {
                logger.warn("Failed to decode session ID: {}", e.getMessage());
            }
        }
        
        return null;
    }
    
    /**
     * Validate image type
     */
    private boolean isValidImageType(String imageType) {
        return imageType != null && 
               (imageType.equals("idle") || imageType.equals("chatting") || 
                imageType.equals("eating") || imageType.equals("normal"));
    }
}