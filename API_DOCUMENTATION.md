# Meshiya AI Midnight Diner API Documentation

## Swagger UI Access

Once the application is running, you can access the interactive API documentation at:

- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **API Docs JSON**: http://localhost:8080/api-docs

## Available API Endpoints

### Orders (`/api/orders`)  
- `GET /api/orders/status` - Get order queue status
- `POST /api/orders/complete/{userId}` - Complete an order for a user

### Debug (`/api/debug`)
- `GET /api/debug/rooms` - Get all rooms information  
- `GET /api/debug/rooms/{roomId}` - Get specific room information
- `GET /api/debug/users` - Get all users information
- `GET /api/debug/users/{userId}` - Get specific user information  
- `GET /api/debug/users/stats` - Get user activity statistics
- `GET /api/debug/master` - Get AI Master status and debug information
- `GET /api/debug/redis/keys` - Get Redis cache keys for debugging
- `GET /api/debug/redis/stats` - Get detailed Redis cache statistics
- `DELETE /api/debug/redis/{cacheType}` - Clear specific Redis cache
- `DELETE /api/debug/redis/all` - ⚠️ Clear ALL Redis cache (DANGEROUS - use with caution!)
- `POST /api/debug/rooms/{roomId}/initialize` - Initialize/recreate a specific room
- `POST /api/debug/system/initialize` - Reinitialize all system components after cache clear

### WebSocket Endpoints (Not in REST API)
- `/room.join` - Join a room
- `/room.sendMessage` - Send a chat message
- `/room.joinSeat` - Join a specific seat
- `/room.leaveSeat` - Leave current seat
- `/chat.*` - Various chat operations

## Quick Start

1. Start the application: `./run.sh`
2. Open Swagger UI: http://localhost:8080/swagger-ui.html  
3. Explore and test API endpoints directly in the browser

## Key Features

- **Interactive Testing**: Try out API endpoints directly from Swagger UI
- **Comprehensive Documentation**: Each endpoint includes detailed descriptions, parameters, and response examples
- **Real-time Monitoring**: Debug endpoints provide system status and Redis cache inspection
- **User Activity Tracking**: Monitor user sessions, timeouts, and seat occupancy
- **Order Management**: Track food/drink orders and completion status

## Authentication

Currently, the API does not require authentication for MVP. User identification is handled through `userId` parameters and WebSocket sessions.