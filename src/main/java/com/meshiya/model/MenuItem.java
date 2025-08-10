package com.meshiya.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class MenuItem {
    
    @JsonProperty("id")
    private String id;
    
    @JsonProperty("name")
    private String name;
    
    @JsonProperty("description")
    private String description;
    
    @JsonProperty("type")
    private MenuItemType type;
    
    @JsonProperty("preparationTime")
    private int preparationTimeSeconds;
    
    @JsonProperty("consumptionTime")
    private int consumptionTimeSeconds;
    
    @JsonProperty("mood")
    private String mood;
    
    @JsonProperty("season")
    private String season;
    
    // Constructors
    public MenuItem() {}
    
    public MenuItem(String id, String name, String description, MenuItemType type, 
                   int preparationTimeSeconds, int consumptionTimeSeconds, String mood, String season) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.type = type;
        this.preparationTimeSeconds = preparationTimeSeconds;
        this.consumptionTimeSeconds = consumptionTimeSeconds;
        this.mood = mood;
        this.season = season;
    }
    
    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    public MenuItemType getType() { return type; }
    public void setType(MenuItemType type) { this.type = type; }
    
    public int getPreparationTimeSeconds() { return preparationTimeSeconds; }
    public void setPreparationTimeSeconds(int preparationTimeSeconds) { 
        this.preparationTimeSeconds = preparationTimeSeconds; 
    }
    
    public int getConsumptionTimeSeconds() { return consumptionTimeSeconds; }
    public void setConsumptionTimeSeconds(int consumptionTimeSeconds) {
        this.consumptionTimeSeconds = consumptionTimeSeconds;
    }
    
    public String getMood() { return mood; }
    public void setMood(String mood) { this.mood = mood; }
    
    public String getSeason() { return season; }
    public void setSeason(String season) { this.season = season; }
    
    @Override
    public String toString() {
        return String.format("MenuItem{id='%s', name='%s', type=%s}", id, name, type);
    }
}