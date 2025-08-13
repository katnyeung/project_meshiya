package com.meshiya.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat-focused LLM service for conversational responses
 * Supports multiple providers: Perplexity AI, OpenAI, etc.
 */
@Service("chatLLMService")
public class ChatLLMService extends LLMService {
    
    @Value("${llm.chat.provider:perplexity}")
    private String provider;
    
    public ChatLLMService(
            @Value("${llm.chat.api.url:https://api.perplexity.ai/chat/completions}") String apiUrl,
            @Value("${llm.chat.api.key:}") String apiKey,
            @Value("${llm.chat.model:sonar}") String model,
            @Value("${llm.chat.timeout:30000}") int timeoutMs) {
        super(apiUrl, apiKey, model, timeoutMs);
    }
    
    @Override
    public String callLlm(String systemPrompt, String userPrompt) {
        try {
            logger.debug("=== CHAT LLM CALL ===");
            logger.debug("Provider: {}, Model: {}, Timeout: {}ms", provider, model, timeoutMs);
            
            switch (provider.toLowerCase()) {
                case "perplexity":
                    return callPerplexityApi(systemPrompt, userPrompt);
                case "openai":
                    return callOpenAiApi(systemPrompt, userPrompt);
                case "ollama":
                    return callOllamaApi(systemPrompt, userPrompt);
                default:
                    logger.warn("Unknown chat provider: {}, defaulting to perplexity", provider);
                    return callPerplexityApi(systemPrompt, userPrompt);
            }
        } catch (Exception e) {
            logger.error("CHAT LLM API call failed: {} ({})", e.getMessage(), e.getClass().getSimpleName());
            return null;
        }
    }
    
    private String callPerplexityApi(String systemPrompt, String userPrompt) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userPrompt)
        ));
        requestBody.put("stream", false);
        requestBody.put("temperature", 0.7);
        requestBody.put("top_p", 0.9);
        requestBody.put("max_tokens", 500);
        
        return callOpenAiCompatibleApi(requestBody);
    }
    
    private String callOpenAiApi(String systemPrompt, String userPrompt) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userPrompt)
        ));
        requestBody.put("temperature", 0.7);
        requestBody.put("max_tokens", 500);
        
        return callOpenAiCompatibleApi(requestBody);
    }
    
    private String callOllamaApi(String systemPrompt, String userPrompt) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userPrompt)
        ));
        requestBody.put("stream", false);
        requestBody.put("options", Map.of(
            "temperature", 0.7,
            "top_p", 0.9
        ));
        
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, createHeaders());
        
        long startTime = System.currentTimeMillis();
        ResponseEntity<Map> response = restTemplate.postForEntity(apiUrl + "/api/chat", entity, Map.class);
        long duration = System.currentTimeMillis() - startTime;
        
        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
            Map<String, Object> body = response.getBody();
            Map<String, Object> message = (Map<String, Object>) body.get("message");
            String result = (String) message.get("content");
            
            logApiCall("CHAT", duration, result);
            return result;
        } else {
            logger.warn("CHAT LLM returned non-OK status: {}", response.getStatusCode());
        }
        return null;
    }
    
    private String callOpenAiCompatibleApi(Map<String, Object> requestBody) {
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, createHeaders());
        
        long startTime = System.currentTimeMillis();
        ResponseEntity<Map> response = restTemplate.postForEntity(apiUrl, entity, Map.class);
        long duration = System.currentTimeMillis() - startTime;
        
        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
            Map<String, Object> body = response.getBody();
            List<Map<String, Object>> choices = (List<Map<String, Object>>) body.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                String result = (String) message.get("content");
                
                logApiCall("CHAT", duration, result);
                return result;
            }
        } else {
            logger.warn("CHAT LLM returned non-OK status: {}", response.getStatusCode());
        }
        return null;
    }
    
    @Override
    public String getCurrentProvider() {
        return provider + "-" + model + "@" + apiUrl;
    }
    
    @Override
    public Map<String, Object> getConfig() {
        Map<String, Object> config = super.getConfig();
        config.put("provider", provider);
        config.put("type", "chat");
        return config;
    }
}