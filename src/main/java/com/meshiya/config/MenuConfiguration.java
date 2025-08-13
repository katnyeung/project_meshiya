package com.meshiya.config;

import com.meshiya.model.MenuItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Configuration
public class MenuConfiguration {
    
    private static final Logger logger = LoggerFactory.getLogger(MenuConfiguration.class);
    
    @Bean
    public MenuConfig menuConfig() {
        ObjectMapper mapper = new ObjectMapper();
        try {
            ClassPathResource resource = new ClassPathResource("settings/menu.json");
            MenuConfig config = mapper.readValue(resource.getInputStream(), MenuConfig.class);
            logger.info("Successfully loaded menu configuration with {} total items", 
                       config.getAllItems().size());
            return config;
        } catch (IOException e) {
            logger.error("Failed to load menu configuration, using empty config", e);
            return new MenuConfig();
        }
    }
    
    public static class MenuConfig {
        private List<MenuItemData> drinks = new ArrayList<>();
        private List<MenuItemData> food = new ArrayList<>();
        private List<MenuItemData> desserts = new ArrayList<>();
        
        public List<MenuItemData> getDrinks() { return drinks; }
        public void setDrinks(List<MenuItemData> drinks) { this.drinks = drinks; }
        
        public List<MenuItemData> getFood() { return food; }
        public void setFood(List<MenuItemData> food) { this.food = food; }
        
        public List<MenuItemData> getDesserts() { return desserts; }
        public void setDesserts(List<MenuItemData> desserts) { this.desserts = desserts; }
        
        public List<MenuItemData> getAllItems() {
            List<MenuItemData> all = new ArrayList<>();
            all.addAll(drinks);
            all.addAll(food);
            all.addAll(desserts);
            return all;
        }
    }
    
    public static class MenuItemData {
        private String id;
        private String name;
        private String description;
        private String type;
        private int preparationTimeSeconds;
        private int consumptionTimeSeconds;
        private String mood;
        private String season;
        
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        
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
    }
}