package com.meshiya.service;

import com.meshiya.model.MenuItem;
import com.meshiya.model.MenuItemType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class MenuService {
    
    private static final Logger logger = LoggerFactory.getLogger(MenuService.class);
    
    private final Map<String, MenuItem> menuItems = new HashMap<>();
    
    @PostConstruct
    public void initializeMenu() {
        // Initialize basic midnight diner menu
        addMenuItem(new MenuItem("tea_green", "Green Tea", "Warm and soothing green tea", 
                                MenuItemType.DRINK, 30, "contemplative", "all"));
        addMenuItem(new MenuItem("tea_oolong", "Oolong Tea", "Rich oolong tea for quiet moments", 
                                MenuItemType.DRINK, 30, "nostalgic", "all"));
        addMenuItem(new MenuItem("sake_warm", "Warm Sake", "Traditional warm sake", 
                                MenuItemType.DRINK, 45, "reflective", "winter"));
        addMenuItem(new MenuItem("beer", "Beer", "Cold beer for the evening", 
                                MenuItemType.DRINK, 15, "casual", "summer"));
        
        addMenuItem(new MenuItem("ramen_miso", "Miso Ramen", "Hearty miso ramen with soft-boiled egg", 
                                MenuItemType.FOOD, 180, "comforting", "winter"));
        addMenuItem(new MenuItem("tamagoyaki", "Tamagoyaki", "Sweet Japanese rolled omelet", 
                                MenuItemType.FOOD, 120, "nostalgic", "all"));
        addMenuItem(new MenuItem("onigiri", "Onigiri", "Rice ball with umeboshi", 
                                MenuItemType.FOOD, 60, "simple", "all"));
        addMenuItem(new MenuItem("yakitori", "Yakitori", "Grilled chicken skewers", 
                                MenuItemType.FOOD, 150, "social", "all"));
        addMenuItem(new MenuItem("gyoza", "Gyoza", "Pan-fried dumplings", 
                                MenuItemType.FOOD, 120, "comforting", "all"));
        
        addMenuItem(new MenuItem("mochi_ice", "Mochi Ice Cream", "Sweet mochi with ice cream", 
                                MenuItemType.DESSERT, 30, "sweet", "summer"));
        addMenuItem(new MenuItem("dorayaki", "Dorayaki", "Pancake sandwich with sweet filling", 
                                MenuItemType.DESSERT, 60, "childhood", "all"));
        
        logger.info("Initialized menu with {} items", menuItems.size());
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