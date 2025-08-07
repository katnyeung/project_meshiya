package com.meshiya.controller;

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
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Base64;

/**
 * Session management controller for handling user sessions and redirects
 */
@Controller
@Tag(name = "Session", description = "User session management and redirects")
public class SessionController {
    
    private static final Logger logger = LoggerFactory.getLogger(SessionController.class);
    
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
}