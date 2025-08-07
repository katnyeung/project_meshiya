package com.meshiya.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class LlmApiService {
    
    private static final Logger logger = LoggerFactory.getLogger(LlmApiService.class);
    
    private final RestTemplate restTemplate;
    
    public LlmApiService() {
        // Create request factory with timeouts
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15000); // 15 seconds
        factory.setReadTimeout(30000);    // 30 seconds
        
        this.restTemplate = new RestTemplate(factory);
    }
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${ai.provider:ollama}")
    private String aiProvider;
    
    @Value("${ai.ollama.url:http://localhost:11434}")
    private String ollamaUrl;
    
    @Value("${ai.ollama.model:gpt-oss:20b}")
    private String ollamaModel;
    
    @Value("${ai.openai.api-key:}")
    private String openaiApiKey;
    
    @Value("${ai.openai.model:gpt-3.5-turbo}")
    private String openaiModel;
    
    @Value("${ai.anthropic.api-key:}")
    private String anthropicApiKey;
    
    @Value("${ai.anthropic.model:claude-3-haiku-20240307}")
    private String anthropicModel;
    
    @Value("${ai.timeout:30000}")
    private int timeoutMs;
    
    /**
     * Calls the configured LLM API with the given prompt
     */
    public String callLlm(String systemPrompt, String userPrompt) {
        logger.info("=== LLM API CALL START ===");
        logger.info("Provider: {}", aiProvider);
        logger.info("Model: {}", getCurrentModel());
        logger.info("System Prompt (length: {}): {}", systemPrompt.length(), 
                   systemPrompt.length() > 500 ? systemPrompt.substring(0, 500) + "..." : systemPrompt);
        logger.info("User Prompt (length: {}): {}", userPrompt.length(),
                   userPrompt.length() > 1000 ? userPrompt.substring(0, 1000) + "..." : userPrompt);
        
        try {
            String response;
            switch (aiProvider.toLowerCase()) {
                case "ollama":
                    response = callOllama(systemPrompt, userPrompt);
                    break;
                case "openai":
                    response = callOpenAi(systemPrompt, userPrompt);
                    break;
                case "anthropic":
                    response = callAnthropic(systemPrompt, userPrompt);
                    break;
                default:
                    logger.warn("Unknown AI provider: {}. Falling back to Ollama", aiProvider);
                    response = callOllama(systemPrompt, userPrompt);
                    break;
            }
            
            logger.info("LLM Response (length: {}): {}", 
                       response != null ? response.length() : 0,
                       response != null && response.length() > 500 ? response.substring(0, 500) + "..." : response);
            logger.info("=== LLM API CALL END ===");
            
            return response;
            
        } catch (Exception e) {
            logger.error("=== LLM API CALL FAILED ===");
            logger.error("Error calling LLM API ({}): {}", aiProvider, e.getMessage(), e);
            logger.error("=== LLM API CALL END ===");
            throw new RuntimeException("LLM API call failed", e);
        }
    }
    
    /**
     * Calls local Ollama API
     */
    private String callOllama(String systemPrompt, String userPrompt) {
        logger.info("Calling Ollama API with model: {} at URL: {}", ollamaModel, ollamaUrl);
        
        String fullPrompt = systemPrompt + "\n\n" + userPrompt;
        
        Map<String, Object> request = new HashMap<>();
        request.put("model", ollamaModel);
        request.put("prompt", fullPrompt);
        request.put("stream", false);
        request.put("options", Map.of(
            "temperature", 0.7,
            "top_p", 0.9,
            "max_tokens", 150,
            "timeout", 30  // 30 second timeout for Ollama
        ));
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);
        
        try {
            logger.info("Sending request to Ollama: {}/api/generate", ollamaUrl);
            logger.debug("Request payload: {}", objectMapper.writeValueAsString(request));
            
            ResponseEntity<String> response = restTemplate.exchange(
                ollamaUrl + "/api/generate",
                HttpMethod.POST,
                entity,
                String.class
            );
            
            logger.info("Ollama API response status: {}", response.getStatusCode());
            logger.debug("Raw response body: {}", response.getBody());
            
            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode jsonResponse = objectMapper.readTree(response.getBody());
                String responseText = jsonResponse.get("response").asText();
                logger.info("Ollama extracted response: {}", responseText);
                return responseText.trim();
            } else {
                logger.error("Ollama API returned non-success status: {} with body: {}", 
                           response.getStatusCode(), response.getBody());
                throw new RuntimeException("Ollama API returned status: " + response.getStatusCode());
            }
        } catch (Exception e) {
            logger.error("Failed to call Ollama API: {} ({})", e.getMessage(), e.getClass().getSimpleName(), e);
            if (e.getMessage().contains("timeout") || e.getMessage().contains("SocketTimeout")) {
                throw new RuntimeException("Ollama API timeout - check if service is running and responsive", e);
            }
            if (e.getMessage().contains("Connection refused")) {
                throw new RuntimeException("Cannot connect to Ollama at " + ollamaUrl + " - check if service is running", e);
            }
            throw new RuntimeException("Ollama API call failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Calls OpenAI API
     */
    private String callOpenAi(String systemPrompt, String userPrompt) {
        if (openaiApiKey.isEmpty()) {
            throw new RuntimeException("OpenAI API key not configured");
        }
        
        logger.info("Calling OpenAI API with model: {}", openaiModel);
        
        Map<String, Object> request = new HashMap<>();
        request.put("model", openaiModel);
        request.put("messages", Arrays.asList(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userPrompt)
        ));
        request.put("max_tokens", 200);
        request.put("temperature", 0.7);
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(openaiApiKey);
        
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);
        
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                "https://api.openai.com/v1/chat/completions",
                HttpMethod.POST,
                entity,
                String.class
            );
            
            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode jsonResponse = objectMapper.readTree(response.getBody());
                String responseText = jsonResponse.get("choices")
                    .get(0).get("message").get("content").asText();
                logger.debug("OpenAI response: {}", responseText);
                return responseText.trim();
            } else {
                throw new RuntimeException("OpenAI API returned status: " + response.getStatusCode());
            }
        } catch (Exception e) {
            logger.error("Failed to call OpenAI API: {}", e.getMessage());
            throw new RuntimeException("OpenAI API call failed", e);
        }
    }
    
    /**
     * Calls Anthropic Claude API
     */
    private String callAnthropic(String systemPrompt, String userPrompt) {
        if (anthropicApiKey.isEmpty()) {
            throw new RuntimeException("Anthropic API key not configured");
        }
        
        logger.info("Calling Anthropic API with model: {}", anthropicModel);
        
        Map<String, Object> request = new HashMap<>();
        request.put("model", anthropicModel);
        request.put("max_tokens", 200);
        request.put("system", systemPrompt);
        request.put("messages", Arrays.asList(
            Map.of("role", "user", "content", userPrompt)
        ));
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", anthropicApiKey);
        headers.set("anthropic-version", "2023-06-01");
        
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);
        
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                "https://api.anthropic.com/v1/messages",
                HttpMethod.POST,
                entity,
                String.class
            );
            
            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode jsonResponse = objectMapper.readTree(response.getBody());
                String responseText = jsonResponse.get("content")
                    .get(0).get("text").asText();
                logger.debug("Anthropic response: {}", responseText);
                return responseText.trim();
            } else {
                throw new RuntimeException("Anthropic API returned status: " + response.getStatusCode());
            }
        } catch (Exception e) {
            logger.error("Failed to call Anthropic API: {}", e.getMessage());
            throw new RuntimeException("Anthropic API call failed", e);
        }
    }
    
    /**
     * Gets the current AI provider configuration
     */
    public String getCurrentProvider() {
        return aiProvider;
    }
    
    /**
     * Gets the current model configuration
     */
    public String getCurrentModel() {
        switch (aiProvider.toLowerCase()) {
            case "ollama":
                return ollamaModel;
            case "openai":
                return openaiModel;
            case "anthropic":
                return anthropicModel;
            default:
                return "unknown";
        }
    }
}