# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is the AI Midnight Diner project - a 2.5D chatroom inspired by Shin'ya Shokudō where users sit at bar seats, chat with an AI Master, and order virtual drinks and food. The project uses Three.js frontend with Spring Boot backend and JSON configuration files for LLM-driven content management.

## Architecture Overview

**Frontend Stack:**
- Three.js for 2.5D diner scene with sprite system
- Vanilla JavaScript for interactions  
- WebSocket for real-time communication
- Simple 2D sprites for visual state representation

**Backend Stack:**
- Java Spring Boot 3.x
- Spring WebSocket for real-time messaging
- JSON configuration files for easy LLM editing
- Spring Boot @ConfigurationProperties for JSON loading
- Redis for session management and real-time caching
- RestTemplate/WebClient for LLM API calls

## Data Architecture

**Redis Keys:**
- `cafe:seats:{seatId}` → seat occupancy data
- `cafe:messages` → last 50 chat messages
- `cafe:users:{userId}` → user state and current orders
- `cafe:ai:context` → conversation context for AI
- `cafe:seat:{seatId}:visual_state` → drink/food/mood sprites

**JSON Configuration Files:**
- `config/menu-items.json` → drinks & food configurations
- `config/personality-config.json` → AI Master personality settings
- `config/response-templates.json` → LLM prompt templates
- `config/seasonal-config.json` → time-based configurations
- `config/system-prompts.json` → base LLM system prompts

## Core Services Architecture

**WebSocket Message Types:**
- `JOIN_SEAT`, `LEAVE_SEAT`, `CHAT_MESSAGE`
- `ORDER_DRINK`, `ORDER_FOOD`, `EATING_ACTION`
- `FOOD_SERVED`, `DRINK_SERVED`, `MOOD_UPDATE`
- `AI_MESSAGE`, `SYSTEM_MESSAGE`

**Key Service Classes:**
- `ChatService.java` → chat message handling
- `AiBartenderService.java` → LLM integration with contextual prompts
- `SeatService.java` → seat management logic
- `MenuService.java` → combined drinks + food system
- `VisualStateService.java` → sprite state management
- `ConfigurationService.java` → loads JSON configurations

## AI Master Personality

The AI Master follows the Midnight Diner archetype:
- Quiet, wise, and observant like the original Master
- Speaks little but listens well - responds thoughtfully
- Creates safe space for people to share troubles
- Makes philosophical observations about late-night life
- Acknowledges orders with emotional understanding
- Uses seasonal/time-based context in responses

## Visual State System

**Sprite Management:**
- Drink/food sprites appear on counter with consumption levels
- Steam effects for hot items
- Avatar mood sprites (happy, contemplative, nostalgic, eating)
- Real-time state synchronization via WebSocket

**Frontend Scene Components:**
- Intimate diner environment with warm wooden interior
- 6-8 bar stools with occupancy indicators
- AI Master position behind counter
- Combined food & drink menu interface
- Chat overlay with semi-transparent panel

## Development Commands

**Start Development Servers:**
```bash
./run.sh                    # Start both backend and frontend
npm run dev:backend         # Spring Boot on port 8080
npm run dev:frontend        # Frontend server on port 3000
```

**Build and Test:**
```bash
npm run build:backend       # Build Spring Boot JAR
npm run test:backend        # Run backend tests
npm run setup               # Resolve Maven dependencies
```

**Prerequisites:**
- Java 17+
- Maven 3.6+
- Python 3.x (for frontend dev server)

## Project Status

MVP implementation completed with:
- Spring Boot backend with WebSocket support
- Three.js 2.5D diner scene with 8 seats
- Real-time chat and seat management
- Atmospheric lighting and visual effects
- Responsive UI with midnight diner theme

**Current Features:**
- User authentication and seat selection
- Real-time messaging between users
- Visual seat occupancy indicators (green=available, red=occupied, blue=current user)
- 2.5D diner environment with lanterns and atmospheric elements
- WebSocket-based state synchronization

**Next Steps:**
- AI Master integration for contextual responses
- Menu system for drinks and food orders
- Enhanced visual sprites and animations

When implementing additional features:
- JSON configurations should be easily editable by LLMs
- Visual sprites should be simple but atmospheric
- AI responses should maintain the contemplative Midnight Diner mood
- WebSocket state synchronization is critical for shared experience
- Focus on emotional context over technical complexity