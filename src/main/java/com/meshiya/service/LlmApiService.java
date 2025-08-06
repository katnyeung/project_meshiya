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
        try {
            switch (aiProvider.toLowerCase()) {
                case "ollama":
                    return callOllama(systemPrompt, userPrompt);
                case "openai":
                    return callOpenAi(systemPrompt, userPrompt);
                case "anthropic":
                    return callAnthropic(systemPrompt, userPrompt);
                default:
                    logger.warn("Unknown AI provider: {}. Falling back to Ollama", aiProvider);
                    return callOllama(systemPrompt, userPrompt);
            }
        } catch (Exception e) {
            logger.error("Error calling LLM API ({}): {}", aiProvider, e.getMessage());
            throw new RuntimeException("LLM API call failed", e);
        }
    }
    
    /**
     * Calls local Ollama API
     */
    private String callOllama(String systemPrompt, String userPrompt) {
        logger.info("Calling Ollama API with model: {}", ollamaModel);
        
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
            ResponseEntity<String> response = restTemplate.exchange(
                ollamaUrl + "/api/generate",
                HttpMethod.POST,
                entity,
                String.class
            );
            
            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode jsonResponse = objectMapper.readTree(response.getBody());
                String responseText = jsonResponse.get("response").asText();
                logger.debug("Ollama response: {}", responseText);
                return responseText.trim();
            } else {
                throw new RuntimeException("Ollama API returned status: " + response.getStatusCode());
            }
        } catch (Exception e) {
            logger.error("Failed to call Ollama API: {}", e.getMessage());
            if (e.getMessage().contains("timeout") || e.getMessage().contains("SocketTimeout")) {
                throw new RuntimeException("Ollama API timeout - check if service is running and responsive", e);
            }
            throw new RuntimeException("Ollama API call failed", e);
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