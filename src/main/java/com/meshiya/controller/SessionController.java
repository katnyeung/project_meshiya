package com.meshiya.controller;

import com.meshiya.model.RegisteredUser;
import com.meshiya.service.UserService;
import com.meshiya.service.SeatService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.Map;
import java.util.HashMap;

/**
 * Session management controller for handling user sessions and redirects
 */
@Controller
@Tag(name = "Session", description = "User session management and redirects")
public class SessionController {
    
    private static final Logger logger = LoggerFactory.getLogger(SessionController.class);
    
    @Autowired
    private UserService userService;
    
    @Autowired
    private SeatService seatService;
    
    /**
     * Handle root path requests - redirect to session URL if user exists
     */
    @GetMapping("/")
    public String handleRootPath(HttpServletRequest request, HttpServletResponse response) {
        String sessionId = request.getParameter("sessionId");
        
        // If already has sessionId parameter, serve the page normally
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            logger.info("Serving page with session ID: {}", sessionId);
            // Store sessionId in cookie for future visits
            Cookie sessionCookie = new Cookie("meshiyaSessionId", sessionId);
            sessionCookie.setMaxAge(30 * 24 * 60 * 60); // 30 days
            sessionCookie.setPath("/");
            response.addCookie(sessionCookie);
            return "forward:/index.html";
        }
        
        // Check if user has existing session cookie
        Cookie[] cookies = request.getCookies();
        String existingSessionId = null;
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("meshiyaSessionId".equals(cookie.getName())) {
                    existingSessionId = cookie.getValue();
                    logger.info("Found existing session ID in cookie: {}", existingSessionId);
                    break;
                }
            }
        }
        
        // If we have an existing session, redirect to it
        if (existingSessionId != null && !existingSessionId.trim().isEmpty()) {
            logger.info("Redirecting to existing session: {}", existingSessionId);
            return "redirect:/?sessionId=" + existingSessionId;
        }
        
        // Generate new session ID and redirect
        String newSessionId = generateSessionId();
        String encodedSessionId = Base64.getEncoder().encodeToString(newSessionId.getBytes());
        
        logger.info("Generated new session ID: {} (encoded: {})", newSessionId, encodedSessionId);
        return "redirect:/?sessionId=" + encodedSessionId;
    }
    
    /**
     * Generate a unique session ID
     */
    private String generateSessionId() {
        return "session_" + System.currentTimeMillis() + "_" + 
               java.util.UUID.randomUUID().toString().substring(0, 8);
    }
    
    // =====================================================
    // REGISTRATION AND AUTHENTICATION ENDPOINTS
    // =====================================================
    
    /**
     * Register a new user
     */
    @PostMapping("/api/register")
    @Operation(summary = "Register new user", description = "Create a new registered user account")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "User registered successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid input or user already exists"),
        @ApiResponse(responseCode = "500", description = "Registration failed")
    })
    @ResponseBody
    public ResponseEntity<Map<String, Object>> registerUser(
            @Parameter(description = "Registration data") @RequestBody Map<String, String> registrationData,
            HttpServletResponse response) {
        
        Map<String, Object> responseBody = new HashMap<>();
        
        try {
            String username = registrationData.get("username");
            String email = registrationData.get("email");
            String password = registrationData.get("password");
            
            // Validate input
            if (username == null || username.trim().isEmpty()) {
                responseBody.put("success", false);
                responseBody.put("message", "Username is required");
                return ResponseEntity.badRequest().body(responseBody);
            }
            
            if (email == null || email.trim().isEmpty()) {
                responseBody.put("success", false);
                responseBody.put("message", "Email is required");
                return ResponseEntity.badRequest().body(responseBody);
            }
            
            if (password == null || password.length() < 6) {
                responseBody.put("success", false);
                responseBody.put("message", "Password must be at least 6 characters");
                return ResponseEntity.badRequest().body(responseBody);
            }
            
            // Register user
            RegisteredUser newUser = userService.registerUser(username.trim(), email.trim(), password);
            
            // Create a registration success cookie
            Cookie registrationCookie = new Cookie("meshiyaRegistered", "true");
            registrationCookie.setMaxAge(30 * 24 * 60 * 60); // 30 days
            registrationCookie.setPath("/");
            response.addCookie(registrationCookie);
            
            responseBody.put("success", true);
            responseBody.put("message", "User registered successfully");
            responseBody.put("username", newUser.getUsername());
            responseBody.put("email", newUser.getEmail());
            
            logger.info("New user registered: {}", newUser.getUsername());
            return ResponseEntity.ok(responseBody);
            
        } catch (RuntimeException e) {
            logger.warn("Registration failed: {}", e.getMessage());
            responseBody.put("success", false);
            responseBody.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(responseBody);
            
        } catch (Exception e) {
            logger.error("Registration error: {}", e.getMessage());
            responseBody.put("success", false);
            responseBody.put("message", "Registration failed. Please try again.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(responseBody);
        }
    }
    
    /**
     * User login
     */
    @PostMapping("/api/login")
    @Operation(summary = "User login", description = "Authenticate registered user")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Login successful"),
        @ApiResponse(responseCode = "400", description = "Invalid credentials"),
        @ApiResponse(responseCode = "401", description = "Authentication failed")
    })
    @ResponseBody
    public ResponseEntity<Map<String, Object>> loginUser(
            @Parameter(description = "Login credentials") @RequestBody Map<String, String> credentials,
            HttpServletRequest request,
            HttpServletResponse response) {
        
        Map<String, Object> responseBody = new HashMap<>();
        
        try {
            String username = credentials.get("username");
            String password = credentials.get("password");
            
            if (username == null || username.trim().isEmpty()) {
                responseBody.put("success", false);
                responseBody.put("message", "Username is required");
                return ResponseEntity.badRequest().body(responseBody);
            }
            
            if (password == null || password.isEmpty()) {
                responseBody.put("success", false);
                responseBody.put("message", "Password is required");
                return ResponseEntity.badRequest().body(responseBody);
            }
            
            // Authenticate user
            RegisteredUser user = userService.authenticateUser(username.trim(), password);
            
            if (user != null) {
                // Create login success cookie
                Cookie loginCookie = new Cookie("meshiyaLoggedIn", user.getUsername());
                loginCookie.setMaxAge(24 * 60 * 60); // 24 hours
                loginCookie.setPath("/");
                response.addCookie(loginCookie);
                
                // Update user profile with registered username and refresh seat data
                String sessionId = getSessionIdFromRequest(request);
                if (sessionId != null) {
                    logger.info("Updating profile and refreshing seat data for logged in user: {} (session: {})", user.getUsername(), sessionId);
                    userService.updateUserProfileUsername(sessionId, user.getUsername());
                    seatService.refreshUserSeatData(sessionId);
                }
                
                responseBody.put("success", true);
                responseBody.put("message", "Login successful");
                responseBody.put("username", user.getUsername());
                responseBody.put("email", user.getEmail());
                responseBody.put("lastLogin", user.getLastLogin());
                
                logger.info("User logged in: {}", user.getUsername());
                return ResponseEntity.ok(responseBody);
                
            } else {
                responseBody.put("success", false);
                responseBody.put("message", "Invalid username or password");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(responseBody);
            }
            
        } catch (Exception e) {
            logger.error("Login error: {}", e.getMessage());
            responseBody.put("success", false);
            responseBody.put("message", "Login failed. Please try again.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(responseBody);
        }
    }
    
    /**
     * User logout
     */
    @PostMapping("/api/logout")
    @Operation(summary = "User logout", description = "Logout registered user")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Logout successful")
    })
    @ResponseBody
    public ResponseEntity<Map<String, Object>> logoutUser(HttpServletResponse response) {
        
        Map<String, Object> responseBody = new HashMap<>();
        
        try {
            // Clear login cookies
            Cookie loginCookie = new Cookie("meshiyaLoggedIn", "");
            loginCookie.setMaxAge(0);
            loginCookie.setPath("/");
            response.addCookie(loginCookie);
            
            responseBody.put("success", true);
            responseBody.put("message", "Logout successful");
            
            logger.info("User logged out");
            return ResponseEntity.ok(responseBody);
            
        } catch (Exception e) {
            logger.error("Logout error: {}", e.getMessage());
            responseBody.put("success", false);
            responseBody.put("message", "Logout failed");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(responseBody);
        }
    }
    
    /**
     * Get current user profile
     */
    @GetMapping("/api/profile")
    @Operation(summary = "Get user profile", description = "Get current user profile information")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getUserProfile(HttpServletRequest request) {
        
        Map<String, Object> responseBody = new HashMap<>();
        
        try {
            String username = getLoggedInUsername(request);
            
            if (username == null) {
                responseBody.put("success", false);
                responseBody.put("message", "No active login session");
                responseBody.put("isLoggedIn", false);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(responseBody);
            }
            
            if (userService.isUserRegistered(username)) {
                // Get user images
                Map<String, String> images = new HashMap<>();
                String[] imageTypes = {"idle", "chatting", "eating", "normal"};
                
                for (String imageType : imageTypes) {
                    String imageUrl = userService.getImageUrl(username, imageType);
                    if (imageUrl != null) {
                        images.put(imageType, imageUrl);
                    }
                }
                
                responseBody.put("success", true);
                responseBody.put("isLoggedIn", true);
                responseBody.put("username", username);
                responseBody.put("isRegistered", true);
                responseBody.put("images", images);
                
                return ResponseEntity.ok(responseBody);
            } else {
                responseBody.put("success", false);
                responseBody.put("message", "User not found");
                responseBody.put("isLoggedIn", false);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(responseBody);
            }
            
        } catch (Exception e) {
            logger.error("Profile fetch error: {}", e.getMessage());
            responseBody.put("success", false);
            responseBody.put("message", "Failed to get profile");
            responseBody.put("isLoggedIn", false);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(responseBody);
        }
    }
    
    // Helper method to get logged in username
    private String getLoggedInUsername(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("meshiyaLoggedIn".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
    
    /**
     * Extract session ID from request (parameter or cookie)
     */
    private String getSessionIdFromRequest(HttpServletRequest request) {
        // First try parameter
        String sessionId = request.getParameter("sessionId");
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            return decodeSessionId(sessionId);
        }
        
        // Then try cookies
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("meshiyaSessionId".equals(cookie.getName())) {
                    return decodeSessionId(cookie.getValue());
                }
            }
        }
        
        return null;
    }
    
    /**
     * Decode base64 encoded session ID
     */
    private String decodeSessionId(String encodedSessionId) {
        try {
            String decoded = new String(java.util.Base64.getDecoder().decode(encodedSessionId));
            return decoded;
        } catch (Exception e) {
            logger.warn("Failed to decode session ID: {}", e.getMessage());
            return encodedSessionId; // Return as-is if decoding fails
        }
    }
}