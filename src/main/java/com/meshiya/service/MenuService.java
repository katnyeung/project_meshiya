package com.meshiya.service;

import com.meshiya.config.MenuConfiguration;
import com.meshiya.model.MenuItem;
import com.meshiya.model.MenuItemType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class MenuService {
    
    private static final Logger logger = LoggerFactory.getLogger(MenuService.class);
    
    @Autowired
    private MenuConfiguration.MenuConfig menuConfig;
    
    private final Map<String, MenuItem> menuItems = new HashMap<>();
    
    @PostConstruct
    public void initializeMenu() {
        // Load menu items from JSON configuration
        for (MenuConfiguration.MenuItemData itemData : menuConfig.getAllItems()) {
            MenuItemType type = MenuItemType.valueOf(itemData.getType());
            MenuItem menuItem = new MenuItem(
                itemData.getId(),
                itemData.getName(),
                itemData.getDescription(),
                type,
                itemData.getPreparationTimeSeconds(),
                itemData.getConsumptionTimeSeconds(),
                itemData.getMood(),
                itemData.getSeason()
            );
            addMenuItem(menuItem);
        }
        
        logger.info("Initialized menu with {} items loaded from JSON configuration", menuItems.size());
    }
    
    private void addMenuItem(MenuItem item) {
        menuItems.put(item.getId(), item);
    }
    
    /**
     * Gets all menu items
     */
    public List<MenuItem> getAllMenuItems() {
        return new ArrayList<>(menuItems.values());
    }
    
    /**
     * Gets menu items by type
     */
    public List<MenuItem> getMenuItemsByType(MenuItemType type) {
        return menuItems.values().stream()
                .filter(item -> item.getType() == type)
                .collect(Collectors.toList());
    }
    
    /**
     * Gets a menu item by ID
     */
    public Optional<MenuItem> getMenuItem(String itemId) {
        return Optional.ofNullable(menuItems.get(itemId));
    }
    
    /**
     * Gets menu items suitable for a mood
     */
    public List<MenuItem> getMenuItemsByMood(String mood) {
        return menuItems.values().stream()
                .filter(item -> item.getMood().equals(mood) || item.getMood().equals("all"))
                .collect(Collectors.toList());
    }
    
    /**
     * Gets a random suggestion based on type and mood
     */
    public Optional<MenuItem> getSuggestion(MenuItemType type, String mood) {
        List<MenuItem> candidates = menuItems.values().stream()
                .filter(item -> item.getType() == type)
                .filter(item -> item.getMood().equals(mood) || item.getMood().equals("all"))
                .collect(Collectors.toList());
        
        if (candidates.isEmpty()) {
            candidates = getMenuItemsByType(type);
        }
        
        if (!candidates.isEmpty()) {
            return Optional.of(candidates.get(new Random().nextInt(candidates.size())));
        }
        
        return Optional.empty();
    }
    
    /**
     * Formats menu items for display
     */
    public String formatMenuDisplay(List<MenuItem> items) {
        StringBuilder menu = new StringBuilder();
        
        Map<MenuItemType, List<MenuItem>> groupedItems = items.stream()
                .collect(Collectors.groupingBy(MenuItem::getType));
        
        for (MenuItemType type : MenuItemType.values()) {
            List<MenuItem> typeItems = groupedItems.get(type);
            if (typeItems != null && !typeItems.isEmpty()) {
                menu.append(type.name()).append(":\n");
                for (MenuItem item : typeItems) {
                    menu.append("- ").append(item.getName()).append(": ")
                         .append(item.getDescription()).append("\n");
                }
                menu.append("\n");
            }
        }
        
        return menu.toString().trim();
    }
}