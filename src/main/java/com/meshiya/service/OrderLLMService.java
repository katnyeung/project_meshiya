package com.meshiya.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Order-focused LLM service for fast, structured responses
 * Optimized for quick order processing with low latency
 */
@Service("orderLLMService")
public class OrderLLMService extends LLMService {
    
    @Value("${llm.order.provider:ollama}")
    private String provider;
    
    public OrderLLMService(
            @Value("${llm.order.api.url:http://localhost:11434/api/generate}") String apiUrl,
            @Value("${llm.order.api.key:}") String apiKey,
            @Value("${llm.order.model:deepseek-r1:1.5b}") String model,
            @Value("${llm.order.timeout:5000}") int timeoutMs) {
        super(apiUrl, apiKey, model, timeoutMs);
    }
    
    @Override
    public String callLlm(String systemPrompt, String userPrompt) {
        try {
            logger.debug("=== ORDER LLM CALL ===");
            logger.debug("Provider: {}, Model: {}, Timeout: {}ms", provider, model, timeoutMs);
            
            switch (provider.toLowerCase()) {
                case "ollama":
                    return callOllamaApi(systemPrompt, userPrompt);
                case "openai":
                    return callOpenAiApi(systemPrompt, userPrompt);
                case "perplexity":
                    return callPerplexityApi(systemPrompt, userPrompt);
                default:
                    logger.warn("Unknown order provider: {}, defaulting to ollama", provider);
                    return callOllamaApi(systemPrompt, userPrompt);
            }
        } catch (Exception e) {
            logger.error("ORDER LLM API call failed: {} ({})", e.getMessage(), e.getClass().getSimpleName());
            return null;
        }
    }
    
    private String callOllamaApi(String systemPrompt, String userPrompt) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("prompt", systemPrompt + "\n\n" + userPrompt + 
                        "\n\nIMPORTANT: Do NOT include <think> blocks or reasoning. Just provide the XML response directly.");
        requestBody.put("stream", false);
        requestBody.put("options", Map.of(
            "temperature", 0.1,  // Very low for consistent XML format
            "top_p", 0.7,
            "num_predict", 500   // Longer for full XML response
        ));
        
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, createHeaders());
        
        long startTime = System.currentTimeMillis();
        ResponseEntity<Map> response = restTemplate.postForEntity(apiUrl, entity, Map.class);
        long duration = System.currentTimeMillis() - startTime;
        
        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
            String result = (String) response.getBody().get("response");
            
            if (result != null) {
                result = cleanOrderResponse(result);
            }
            
            logApiCall("ORDER", duration, result);
            return result;
        } else {
            logger.warn("ORDER LLM returned non-OK status: {}", response.getStatusCode());
        }
        return null;
    }
    
    private String callOpenAiApi(String systemPrompt, String userPrompt) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userPrompt)
        ));
        requestBody.put("temperature", 0.1);  // Low for consistent format
        requestBody.put("max_tokens", 500);
        
        return callOpenAiCompatibleApi(requestBody);
    }
    
    private String callPerplexityApi(String systemPrompt, String userPrompt) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userPrompt)
        ));
        requestBody.put("stream", false);
        requestBody.put("temperature", 0.1);  // Low for consistent format
        requestBody.put("top_p", 0.7);
        requestBody.put("max_tokens", 500);
        
        return callOpenAiCompatibleApi(requestBody);
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
                
                if (result != null) {
                    result = cleanOrderResponse(result);
                }
                
                logApiCall("ORDER", duration, result);
                return result;
            }
        } else {
            logger.warn("ORDER LLM returned non-OK status: {}", response.getStatusCode());
        }
        return null;
    }
    
    /**
     * Cleans order response by removing thinking blocks and extracting XML
     */
    private String cleanOrderResponse(String response) {
        if (response == null || response.trim().isEmpty()) {
            return response;
        }
        
        logger.debug("Cleaning order response: {}", response);
        
        // Remove thinking blocks like <think>...</think>
        response = response.replaceAll("(?s)<think>.*?</think>", "").trim();
        
        // Remove other thinking patterns
        response = response.replaceAll("(?s)<thinking>.*?</thinking>", "").trim();
        
        // Extract XML tags if they exist, otherwise return cleaned response
        if (response.contains("<foodname>")) {
            // Find the start of XML content
            int xmlStart = response.indexOf("<foodname>");
            if (xmlStart >= 0) {
                response = response.substring(xmlStart).trim();
            }
        }
        
        logger.debug("Cleaned order response: {}", response);
        return response;
    }
    
    @Override
    public String getCurrentProvider() {
        return provider + "-" + model + "@" + apiUrl;
    }
    
    @Override
    public Map<String, Object> getConfig() {
        Map<String, Object> config = super.getConfig();
        config.put("provider", provider);
        config.put("type", "order");
        return config;
    }
}