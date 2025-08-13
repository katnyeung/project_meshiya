# AI Midnight Diner - Project Specification

## Project Overview
Build an AI Midnight Diner chatroom inspired by Shin'ya Shokudō, where users can sit at bar seats, chat with an AI Master, and order virtual drinks and food. The project uses a 2.5D Three.js frontend with simple sprite-based visual states, Spring Boot backend, and JSON configuration files for easy LLM-driven content management.

## Phase 1: MVP Requirements

### Frontend Architecture
**Technology Stack:**
- Three.js for 2.5D diner scene with simple sprite system
- Vanilla JavaScript for interactions
- WebSocket for real-time communication
- JSON configuration files for drinks/food/personality
- Simple 2D sprites for visual state representation

**Scene Components:**
1. **Intimate Diner Environment (Midnight Diner Style)**
   - Warm wooden interior with soft ambient lighting
   - Traditional Japanese lanterns and hanging lights
   - Wooden counter with worn, lived-in texture
   - Small decorative elements: sake bottles, traditional fans, simple flower arrangements
   - 6-8 bar stools arranged in traditional counter style
   - AI "Master" bartender position behind counter (inspired by the enigmatic Master character)

2. **Visual State System (Simple Sprites)**
   - **Drink Sprites:** Cup/mug sprites on counter with liquid levels, steam effects
   - **Food Sprites:** Plate/bowl sprites with food items, chopsticks, consumption states
   - **Avatar Sprites:** Simple mood representations (happy, sad, contemplative, relaxed)
   - **State Indicators:** Visual feedback for eating/drinking activities

3. **Interactive Elements**
   - Clickable bar seats (visual feedback on hover)
   - Seat states: empty (green glow), occupied (red glow), current user (blue glow)
   - Chat overlay UI (semi-transparent panel)
   - Combined food & drink menu panel (toggleable)

4. **UI Components**
   - Chat input field and send button
   - Message display area with user names
   - Food & drink menu with items from Shin'ya Shokudō
   - "Order Item" button
   - "Leave Seat" button
   - Visual state displays for each seat

### Backend Architecture
**Technology Stack:**
- Java Spring Boot 3.x
- Spring WebSocket for real-time messaging
- JSON configuration files for easy editing
- Spring Boot @ConfigurationProperties for JSON loading
- Redis for session management and real-time caching
- RestTemplate/WebClient for LLM API calls

**Data Storage Architecture:**
```
Redis (Real-time data):
cafe:seats:{seatId} → {userId, userName, timestamp}
cafe:messages → List<Message> (last 50 messages)
cafe:users:{userId} → {seatId, lastActivity, currentDrink, currentFood}
cafe:ai:context → Recent conversation context for AI
cafe:seat:{seatId}:visual_state → {drink, food, avatar mood states}

JSON Files (Configuration data):
- config/menu-items.json → Drinks & food configurations
- config/personality-config.json → AI Master personality settings  
- config/response-templates.json → LLM prompt templates and triggers
- config/seasonal-config.json → Time-based configurations
- config/system-prompts.json → Base LLM system prompts
```

**Core Services:**
1. **WebSocket Configuration**
   - Endpoint: `/ws/cafe`
   - Message types: JOIN_SEAT, LEAVE_SEAT, CHAT_MESSAGE, ORDER_DRINK, ORDER_FOOD, EATING_ACTION, AI_MESSAGE

2. **Message Types**
   ```java
   enum MessageType {
       JOIN_SEAT, LEAVE_SEAT, CHAT_MESSAGE, 
       ORDER_DRINK, ORDER_FOOD, EATING_ACTION,
       FOOD_SERVED, DRINK_SERVED, MOOD_UPDATE,
       AI_MESSAGE, SYSTEM_MESSAGE
   }
   ```

### AI Master Features (LLM-Driven with JSON Specifications)

1. **Personality Traits (Midnight Diner Master Style)**
   - Quiet, wise, and observant like the original Master
   - Speaks little but listens well - responds thoughtfully
   - Has mysterious background but focuses on customers' stories
   - Makes simple but meaningful dishes/drinks
   - Creates safe space for people to share their troubles
   - Uses gentle, philosophical language

2. **Behavioral Patterns (Late-Night Intimacy)**
   - Welcomes newcomers with quiet acknowledgment: "Welcome to my diner"
   - Asks gentle questions: "Rough day?" or "What brings you here tonight?"
   - Shares philosophical observations about late-night life
   - Creates moments of comfortable silence
   - Responds to emotional cues with appropriate empathy
   - Uses seasonal or time-based observations (midnight thoughts, dawn approaching)

3. **Food & Drink Integration**
   - Acknowledges orders with understanding of emotional subtext
   - Comments on why someone might choose particular items at this hour
   - Shares wisdom through food and drink metaphors
   - References memories and stories associated with dishes

### Menu System (Shin'ya Shokudō Inspired)

#### **Drinks (Simple & Soulful):**
- Hot Coffee - "For those starting their night"
- Warm Milk - "When you need comfort"
- Green Tea - "For quiet contemplation"  
- Sake (virtual) - "To ease the heart"
- Hot Chocolate - "Sweet memories"
- Ginger Tea - "Warming from within"
- Barley Tea - "Simple and honest"
- Water - "Pure and essential"

#### **Foods (Comfort & Stories):**
- Tamago Kake Gohan - "Rice with raw egg - childhood comfort"
- Instant Ramen - "For those burning the midnight oil"
- Onigiri - "Rice ball - portable comfort"
- Yakitori - "Grilled chicken skewers - simple pleasures"
- Potato Salad - "Creamy nostalgia"
- Tamagoyaki - "Sweet rolled egg - mother's love"
- Miso Soup - "Warm embrace in a bowl"
- Pickled Vegetables - "Sharp clarity for clouded thoughts"

### Technical Implementation Details

#### Frontend File Structure
```
/frontend
├── index.html
├── css/
│   └── style.css (warm, dim lighting theme)
├── js/
│   ├── main.js (Three.js scene setup)
│   ├── midnight-diner-scene.js (intimate diner environment)
│   ├── sprite-state-manager.js (visual state system)
│   ├── websocket-client.js (WebSocket handling)
│   ├── ui-manager.js (Minimal, non-intrusive UI)
│   └── seat-manager.js (Counter stool interactions)
├── assets/
│   ├── images/
│   │   ├── diner-interior-warm.jpg
│   │   ├── wooden-counter-worn.jpg  
│   │   ├── traditional-stool.png
│   │   ├── master-silhouette.png
│   │   ├── lantern-glow.png
│   │   ├── drinks/
│   │   │   ├── coffee-mug.png
│   │   │   ├── tea-cup.png
│   │   │   ├── milk-glass.png
│   │   │   └── steam-effect.png
│   │   ├── food/
│   │   │   ├── plates/
│   │   │   │   ├── ceramic-bowl.png
│   │   │   │   └── wooden-plate.png
│   │   │   ├── dishes/
│   │   │   │   ├── tamago-kake-gohan.png
│   │   │   │   ├── instant-ramen.png
│   │   │   │   ├── onigiri.png
│   │   │   │   └── yakitori.png
│   │   │   └── utensils/
│   │   │       ├── chopsticks.png
│   │   │       └── spoon.png
│   │   └── avatars/
│   │       ├── avatar-neutral.png
│   │       ├── avatar-happy.png
│   │       ├── avatar-contemplative.png
│   │       ├── avatar-nostalgic.png
│   │       └── avatar-eating.png
│   └── sounds/ 
│       ├── ambient-night-sounds.mp3
│       ├── gentle-ceramic-clinks.mp3
│       └── chopsticks-click.mp3
```

#### Backend File Structure
```
/backend
├── src/main/java/com/aimidnightdiner/
│   ├── AiMidnightDinerApplication.java
│   ├── config/
│   │   ├── WebSocketConfig.java
│   │   ├── RedisConfig.java
│   │   ├── CafeConfigProperties.java (@ConfigurationProperties)
│   │   ├── MenuConfig.java
│   │   ├── PersonalityConfig.java
│   │   └── ResponseTemplateConfig.java
│   ├── controller/
│   │   ├── WebSocketController.java
│   │   └── CafeController.java
│   ├── service/
│   │   ├── ChatService.java
│   │   ├── AiBartenderService.java (LLM integration)
│   │   ├── SeatService.java
│   │   ├── MenuService.java (drinks + food)
│   │   ├── VisualStateService.java (sprite state management)
│   │   └── ConfigurationService.java (loads from JSON files)
│   ├── model/
│   │   ├── Message.java
│   │   ├── User.java
│   │   ├── Seat.java
│   │   ├── DrinkOrder.java
│   │   ├── FoodOrder.java
│   │   └── VisualState.java
│   └── dto/
│       ├── ChatMessage.java
│       ├── SeatStatus.java
│       ├── OrderRequest.java
│       └── StateUpdateMessage.java
├── src/main/resources/
│   ├── application.yml
│   └── config/
│       ├── menu-items.json (drinks & food)
│       ├── personality-config.json
│       ├── response-templates.json
│       ├── seasonal-config.json
│       └── system-prompts.json
```

### Core Features Implementation

#### 1. Visual State Management (Simple Sprite System)
```javascript
class SpriteStateManager {
  // Serve drink/food with visual representation
  serveItem(seatId, itemType, itemData) {
    const seatPos = this.getSeatPosition(seatId);
    const sprite = this.createItemSprite(itemType, itemData);
    sprite.position.set(seatPos.x, seatPos.y + 0.1, seatPos.z);
    
    // Add effects (steam for hot items)
    if (itemData.temperature === "hot") {
      this.addSteamEffect(sprite);
    }
    
    this.scene.add(sprite);
    this.updateSeatState(seatId, { [itemType]: itemData });
  }
  
  // Update consumption levels
  updateConsumption(seatId, itemType, newLevel) {
    const sprite = this.getItemSprite(seatId, itemType);
    this.updateSpriteLevel(sprite, newLevel);
    
    // Update avatar mood based on consumption
    if (newLevel < 0.5) {
      this.updateAvatarMood(seatId, "satisfied");
    }
  }
}
```

#### 2. Enhanced Menu System
- Combined drinks and food in single menu interface
- Emotional tags and Master's comments for each item
- Visual icons for easy selection
- Category-based organization (comfort, energy, contemplative)

#### 3. LLM Integration with JSON Specifications
```java
@Service
public class AiBartenderService {
    
    public String generateResponse(ChatContext context) {
        String systemPrompt = buildSystemPrompt();
        String contextPrompt = buildContextPrompt(context);
        
        return callLLM(systemPrompt, contextPrompt, context.getUserMessage());
    }
    
    private String buildContextPrompt(ChatContext context) {
        StringBuilder prompt = new StringBuilder();
        
        // Current diner state
        prompt.append("CURRENT SITUATION:\n");
        prompt.append(String.format("- Time: %s\n", getCurrentTimeDescription()));
        prompt.append(String.format("- Customers present: %d\n", context.getActiveUsers()));
        
        // User's current orders
        if (context.getCurrentDrink() != null) {
            DrinkConfig drink = configService.getDrinkConfig(context.getCurrentDrink());
            prompt.append(String.format("- Customer's drink: %s (%s)\n", 
                drink.getName(), drink.getEmotionalContext()));
        }
        
        if (context.getCurrentFood() != null) {
            FoodConfig food = configService.getFoodConfig(context.getCurrentFood());
            prompt.append(String.format("- Customer's food: %s (%s)\n", 
                food.getName(), food.getEmotionalContext()));
        }
        
        return prompt.toString();
    }
}
```

#### 4. Real-time State Synchronization
- WebSocket broadcasts for all visual state changes
- Eating/drinking animations triggered by backend events
- Mood changes based on food/drink choices and conversation

### JSON Configuration Examples

#### Menu Items (config/menu-items.json)
```json
{
  "drinks": [
    {
      "id": "warm_milk",
      "name": "Warm Milk",
      "description": "When you need comfort", 
      "category": "comfort",
      "emotionalTags": ["comfort", "nostalgia", "healing"],
      "masterComment": "Sometimes we all need to return to simpler times.",
      "temperature": "warm",
      "servingTime": "2 minutes"
    }
  ],
  "foods": [
    {
      "id": "tamago_kake_gohan",
      "name": "Tamago Kake Gohan",
      "description": "Rice with raw egg - childhood comfort",
      "category": "comfort",
      "emotionalTags": ["nostalgia", "simplicity", "maternal"],
      "masterComment": "The taste of home, no matter how far you've traveled.",
      "servingTime": "5 minutes",
      "temperature": "warm",
      "utensils": ["chopsticks"]
    }
  ]
}
```

### WebSocket Message Format
```json
{
  "type": "FOOD_SERVED",
  "userId": "user123",
  "seatId": 3,
  "userName": "Guest_3",
  "item": {
    "id": "tamago_kake_gohan",
    "name": "Tamago Kake Gohan",
    "plate": "ceramic_bowl",
    "utensils": ["chopsticks"],
    "temperature": "warm"
  },
  "visualState": {
    "steamEffect": true,
    "consumptionLevel": 1.0,
    "avatarMood": "nostalgic"
  },
  "timestamp": "2025-08-03T10:30:00Z"
}
```

### Environment Variables
```yaml
# Backend
OPENAI_API_KEY=your_api_key
REDIS_HOST=localhost
REDIS_PORT=6379
SERVER_PORT=8080

# Configuration files
MENU_CONFIG_FILE=classpath:config/menu-items.json
PERSONALITY_CONFIG_FILE=classpath:config/personality-config.json

# AI Configuration
AI_RESPONSE_DELAY_MS=3000
AI_AMBIENT_INTERVAL_MS=60000
AI_MAX_CONTEXT_MESSAGES=20

# Visual state settings
SPRITE_UPDATE_INTERVAL_MS=5000
CONSUMPTION_SIMULATION_ENABLED=true
```

### Phase 1 Success Criteria
- [ ] Users can join/leave seats successfully
- [ ] Real-time chat works between multiple users
- [ ] AI Master welcomes new users with Midnight Diner personality
- [ ] AI Master responds to direct messages with contextual wisdom
- [ ] AI Master generates ambient philosophical observations
- [ ] Combined food & drink menu displays correctly
- [ ] Users can order both drinks and food
- [ ] AI Master acknowledges orders with emotional understanding
- [ ] Visual sprites appear on counter for drinks and food
- [ ] Consumption simulation shows eating/drinking progress
- [ ] Avatar mood sprites reflect customer emotional states
- [ ] Basic 2.5D diner scene renders with warm atmosphere
- [ ] WebSocket connections handle disconnects gracefully
- [ ] JSON configurations can be hot-reloaded

### Development Notes
- Use simple geometric shapes or basic sprites for MVP visual elements
- Focus on functionality and atmosphere over visual polish in Phase 1
- Implement proper error handling for WebSocket connections
- JSON configuration files should be easily editable by LLMs
- Add comprehensive logging for debugging AI responses
- Implement graceful degradation if AI service is unavailable
- Ensure sprite animations are smooth but not performance-intensive

### Future Phases
**Phase 2:** Enhanced visual effects, 3D models, advanced AI memory
**Phase 3:** Multi-room expansion, voice integration, advanced social features
**Phase 4:** Mobile app, AR integration, community features

### Testing Requirements
- Unit tests for core services and configuration loading
- Integration tests for WebSocket functionality and AI responses
- Manual testing with multiple browser tabs (6+ concurrent users)
- Visual state synchronization testing
- AI response quality validation with various emotional contexts
- JSON configuration hot-reload testing
- Performance testing with sprite animations and state updates