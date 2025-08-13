package com.meshiya.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class MasterConfiguration {
    
    private static final Logger logger = LoggerFactory.getLogger(MasterConfiguration.class);
    
    @Bean
    public MasterConfig masterConfig() {
        ObjectMapper mapper = new ObjectMapper();
        try {
            ClassPathResource resource = new ClassPathResource("settings/master.json");
            MasterConfig config = mapper.readValue(resource.getInputStream(), MasterConfig.class);
            logger.info("Successfully loaded master configuration");
            return config;
        } catch (IOException e) {
            logger.error("Failed to load master configuration, using default config", e);
            return createDefaultMasterConfig();
        }
    }
    
    private MasterConfig createDefaultMasterConfig() {
        MasterConfig config = new MasterConfig();
        PersonalityConfig personality = new PersonalityConfig();
        personality.setSystemPrompt("You are a wise diner master.");
        config.setPersonality(personality);
        return config;
    }
    
    public static class MasterConfig {
        private PersonalityConfig personality;
        private ConversationPromptsConfig conversationPrompts;
        private OrderPromptsConfig orderPrompts;
        private SettingsConfig settings;
        
        public PersonalityConfig getPersonality() { return personality; }
        public void setPersonality(PersonalityConfig personality) { this.personality = personality; }
        
        public ConversationPromptsConfig getConversationPrompts() { return conversationPrompts; }
        public void setConversationPrompts(ConversationPromptsConfig conversationPrompts) { this.conversationPrompts = conversationPrompts; }
        
        public OrderPromptsConfig getOrderPrompts() { return orderPrompts; }
        public void setOrderPrompts(OrderPromptsConfig orderPrompts) { this.orderPrompts = orderPrompts; }
        
        public SettingsConfig getSettings() { return settings; }
        public void setSettings(SettingsConfig settings) { this.settings = settings; }
    }
    
    public static class PersonalityConfig {
        private String systemPrompt;
        
        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    }
    
    public static class ConversationPromptsConfig {
        private String instructionsTemplate;
        private String contextHeader;
        private String instructionsHeader;
        private String responsePrefix;
        
        public String getInstructionsTemplate() { return instructionsTemplate; }
        public void setInstructionsTemplate(String instructionsTemplate) { this.instructionsTemplate = instructionsTemplate; }
        
        public String getContextHeader() { return contextHeader; }
        public void setContextHeader(String contextHeader) { this.contextHeader = contextHeader; }
        
        public String getInstructionsHeader() { return instructionsHeader; }
        public void setInstructionsHeader(String instructionsHeader) { this.instructionsHeader = instructionsHeader; }
        
        public String getResponsePrefix() { return responsePrefix; }
        public void setResponsePrefix(String responsePrefix) { this.responsePrefix = responsePrefix; }
    }
    
    public static class OrderPromptsConfig {
        private String systemPrompt;
        private String userPromptTemplate;
        private List<PromptExample> examples = new ArrayList<>();
        
        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
        
        public String getUserPromptTemplate() { return userPromptTemplate; }
        public void setUserPromptTemplate(String userPromptTemplate) { this.userPromptTemplate = userPromptTemplate; }
        
        public List<PromptExample> getExamples() { return examples; }
        public void setExamples(List<PromptExample> examples) { this.examples = examples; }
    }
    
    public static class PromptExample {
        private String input;
        private String output;
        
        public String getInput() { return input; }
        public void setInput(String input) { this.input = input; }
        
        public String getOutput() { return output; }
        public void setOutput(String output) { this.output = output; }
    }
    
    public static class SettingsConfig {
        private BehaviorConfig behavior;
        private Map<String, Integer> autoReturnTimings = new HashMap<>();
        private ConversationContextConfig conversationContext;
        private ResponseStyleConfig responseStyle;
        private LlmParametersConfig llmParameters;
        
        public BehaviorConfig getBehavior() { return behavior; }
        public void setBehavior(BehaviorConfig behavior) { this.behavior = behavior; }
        
        public Map<String, Integer> getAutoReturnTimings() { return autoReturnTimings; }
        public void setAutoReturnTimings(Map<String, Integer> autoReturnTimings) { 
            this.autoReturnTimings = autoReturnTimings; 
        }
        
        public ConversationContextConfig getConversationContext() { return conversationContext; }
        public void setConversationContext(ConversationContextConfig conversationContext) { this.conversationContext = conversationContext; }
        
        public ResponseStyleConfig getResponseStyle() { return responseStyle; }
        public void setResponseStyle(ResponseStyleConfig responseStyle) { this.responseStyle = responseStyle; }
        
        public LlmParametersConfig getLlmParameters() { return llmParameters; }
        public void setLlmParameters(LlmParametersConfig llmParameters) { this.llmParameters = llmParameters; }
        
        // Backward compatibility
        public double getSilenceProbability() { 
            return behavior != null ? behavior.getSilenceProbability() : 0.05; 
        }
    }
    
    public static class BehaviorConfig {
        private double silenceProbability = 0.05;
        private int maxResponseLength = 300;
        private int minResponseLength = 20;
        private int preferredSentenceCount = 3;
        private double emotionalEngagement = 0.8;
        private double philosophicalTendency = 0.6;
        private double foodSuggestionFrequency = 0.3;
        
        public double getSilenceProbability() { return silenceProbability; }
        public void setSilenceProbability(double silenceProbability) { this.silenceProbability = silenceProbability; }
        
        public int getMaxResponseLength() { return maxResponseLength; }
        public void setMaxResponseLength(int maxResponseLength) { this.maxResponseLength = maxResponseLength; }
        
        public int getMinResponseLength() { return minResponseLength; }
        public void setMinResponseLength(int minResponseLength) { this.minResponseLength = minResponseLength; }
        
        public int getPreferredSentenceCount() { return preferredSentenceCount; }
        public void setPreferredSentenceCount(int preferredSentenceCount) { this.preferredSentenceCount = preferredSentenceCount; }
        
        public double getEmotionalEngagement() { return emotionalEngagement; }
        public void setEmotionalEngagement(double emotionalEngagement) { this.emotionalEngagement = emotionalEngagement; }
        
        public double getPhilosophicalTendency() { return philosophicalTendency; }
        public void setPhilosophicalTendency(double philosophicalTendency) { this.philosophicalTendency = philosophicalTendency; }
        
        public double getFoodSuggestionFrequency() { return foodSuggestionFrequency; }
        public void setFoodSuggestionFrequency(double foodSuggestionFrequency) { this.foodSuggestionFrequency = foodSuggestionFrequency; }
    }
    
    public static class ConversationContextConfig {
        private int maxMessagesInContext = 20;
        private boolean includeTimestamps = true;
        private boolean includeMoodIndicators = true;
        private boolean prioritizeRecentMessages = true;
        
        public int getMaxMessagesInContext() { return maxMessagesInContext; }
        public void setMaxMessagesInContext(int maxMessagesInContext) { this.maxMessagesInContext = maxMessagesInContext; }
        
        public boolean isIncludeTimestamps() { return includeTimestamps; }
        public void setIncludeTimestamps(boolean includeTimestamps) { this.includeTimestamps = includeTimestamps; }
        
        public boolean isIncludeMoodIndicators() { return includeMoodIndicators; }
        public void setIncludeMoodIndicators(boolean includeMoodIndicators) { this.includeMoodIndicators = includeMoodIndicators; }
        
        public boolean isPrioritizeRecentMessages() { return prioritizeRecentMessages; }
        public void setPrioritizeRecentMessages(boolean prioritizeRecentMessages) { this.prioritizeRecentMessages = prioritizeRecentMessages; }
    }
    
    public static class ResponseStyleConfig {
        private boolean useNaturalPauses = true;
        private boolean includeActionDescriptions = true;
        private String responsePersonality = "wise-empathetic";
        private String culturalFlavor = "japanese-traditional";
        
        public boolean isUseNaturalPauses() { return useNaturalPauses; }
        public void setUseNaturalPauses(boolean useNaturalPauses) { this.useNaturalPauses = useNaturalPauses; }
        
        public boolean isIncludeActionDescriptions() { return includeActionDescriptions; }
        public void setIncludeActionDescriptions(boolean includeActionDescriptions) { this.includeActionDescriptions = includeActionDescriptions; }
        
        public String getResponsePersonality() { return responsePersonality; }
        public void setResponsePersonality(String responsePersonality) { this.responsePersonality = responsePersonality; }
        
        public String getCulturalFlavor() { return culturalFlavor; }
        public void setCulturalFlavor(String culturalFlavor) { this.culturalFlavor = culturalFlavor; }
    }
    
    public static class LlmParametersConfig {
        private double temperature = 0.7;
        private double topP = 0.9;
        private int maxTokens = 150;
        private double presencePenalty = 0.1;
        private double frequencyPenalty = 0.2;
        
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        
        public double getTopP() { return topP; }
        public void setTopP(double topP) { this.topP = topP; }
        
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        
        public double getPresencePenalty() { return presencePenalty; }
        public void setPresencePenalty(double presencePenalty) { this.presencePenalty = presencePenalty; }
        
        public double getFrequencyPenalty() { return frequencyPenalty; }
        public void setFrequencyPenalty(double frequencyPenalty) { this.frequencyPenalty = frequencyPenalty; }
    }
}