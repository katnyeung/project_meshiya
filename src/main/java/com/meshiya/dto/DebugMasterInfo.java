package com.meshiya.dto;

import java.util.List;
import java.util.Map;

/**
 * Debug information about the AI Master's state
 */
public class DebugMasterInfo {
    
    private String status;
    private long lastResponseTime;
    private long lastLlmCallTime;
    private int totalResponses;
    private List<String> recentResponses;
    private Map<String, Object> currentContext;
    private String llmProvider;
    private String llmModel;
    private boolean isLlmConnected;
    private int pendingOrders;
    private List<DebugSchedulerInfo> schedulerInfo;
    
    public DebugMasterInfo() {}

    // Getters and setters
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getLastResponseTime() { return lastResponseTime; }
    public void setLastResponseTime(long lastResponseTime) { this.lastResponseTime = lastResponseTime; }

    public long getLastLlmCallTime() { return lastLlmCallTime; }
    public void setLastLlmCallTime(long lastLlmCallTime) { this.lastLlmCallTime = lastLlmCallTime; }

    public int getTotalResponses() { return totalResponses; }
    public void setTotalResponses(int totalResponses) { this.totalResponses = totalResponses; }

    public List<String> getRecentResponses() { return recentResponses; }
    public void setRecentResponses(List<String> recentResponses) { this.recentResponses = recentResponses; }

    public Map<String, Object> getCurrentContext() { return currentContext; }
    public void setCurrentContext(Map<String, Object> currentContext) { this.currentContext = currentContext; }

    public String getLlmProvider() { return llmProvider; }
    public void setLlmProvider(String llmProvider) { this.llmProvider = llmProvider; }

    public String getLlmModel() { return llmModel; }
    public void setLlmModel(String llmModel) { this.llmModel = llmModel; }

    public boolean isLlmConnected() { return isLlmConnected; }
    public void setLlmConnected(boolean llmConnected) { isLlmConnected = llmConnected; }

    public int getPendingOrders() { return pendingOrders; }
    public void setPendingOrders(int pendingOrders) { this.pendingOrders = pendingOrders; }

    public List<DebugSchedulerInfo> getSchedulerInfo() { return schedulerInfo; }
    public void setSchedulerInfo(List<DebugSchedulerInfo> schedulerInfo) { this.schedulerInfo = schedulerInfo; }
    
    public static class DebugSchedulerInfo {
        private String schedulerName;
        private boolean isActive;
        private long nextExecution;
        private long lastExecution;
        private int executionCount;
        
        public DebugSchedulerInfo() {}
        
        public DebugSchedulerInfo(String schedulerName, boolean isActive, long nextExecution, long lastExecution, int executionCount) {
            this.schedulerName = schedulerName;
            this.isActive = isActive;
            this.nextExecution = nextExecution;
            this.lastExecution = lastExecution;
            this.executionCount = executionCount;
        }

        public String getSchedulerName() { return schedulerName; }
        public void setSchedulerName(String schedulerName) { this.schedulerName = schedulerName; }

        public boolean isActive() { return isActive; }
        public void setActive(boolean active) { isActive = active; }

        public long getNextExecution() { return nextExecution; }
        public void setNextExecution(long nextExecution) { this.nextExecution = nextExecution; }

        public long getLastExecution() { return lastExecution; }
        public void setLastExecution(long lastExecution) { this.lastExecution = lastExecution; }

        public int getExecutionCount() { return executionCount; }
        public void setExecutionCount(int executionCount) { this.executionCount = executionCount; }
    }
}