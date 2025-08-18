/**
 * UserStatusManager - Manages user status display boxes with consumable timers
 */
class UserStatusManager {
    constructor() {
        this.userStatuses = new Map(); // seatId -> status data
        this.statusBoxes = new Map(); // seatId -> DOM element
        this.isInitialized = false;
        this.animationFrameId = null;
        this.lastPositionUpdate = 0;
        this.updateDebounceMap = new Map(); // seatId -> timeout ID for debouncing
        this.lastUpdateContentMap = new Map(); // seatId -> last content hash
        this.clientTimerInterval = null; // Client-side timer for countdown
        this.lastClientUpdate = 0;
        this.preservedTimers = new Map(); // userId -> preserved timer data for seat swaps
    }

    /**
     * Initialize the status manager
     */
    init() {
        if (this.isInitialized) return;
        
        console.log('🚀 Initializing UserStatusManager');
        // No longer creating HTML status boxes - using Three.js sprites instead
        this.setupWebSocketListeners();
        
        // Start client-side timer for smooth countdown
        this.startClientTimer();
        
        this.isInitialized = true;
        
        console.log('📦 UserStatusManager initialized with client-side timers');
    }

    /**
     * Create status box elements for each seat
     */
    createStatusBoxes() {
        const gameContainer = document.getElementById('gameContainer');
        if (!gameContainer) {
            console.error('❌ Game container not found - trying document.body');
            // Fallback to body if gameContainer not found
            const bodyContainer = document.body;
            if (!bodyContainer) {
                console.error('❌ Document body not found either - cannot create status boxes');
                return;
            }
            this.createStatusBoxesInContainer(bodyContainer);
            return;
        }

        this.createStatusBoxesInContainer(gameContainer);
    }
    
    createStatusBoxesInContainer(container) {
        console.log(`📦 Creating status boxes in container:`, container);

        // Create status boxes for seats 1-8 (assuming 8 seats)
        for (let seatId = 1; seatId <= 8; seatId++) {
            const statusBox = this.createStatusBoxElement(seatId);
            container.appendChild(statusBox);
            this.statusBoxes.set(seatId, statusBox);
            
            console.log(`   ✅ Created status box for seat ${seatId}`);
        }
        
        console.log(`📦 Total status boxes created: ${this.statusBoxes.size}`);
        console.log(`📦 Status boxes are children of:`, container.id || container.tagName);
    }

    /**
     * Create a status box element for a specific seat
     */
    createStatusBoxElement(seatId) {
        const statusBox = document.createElement('div');
        statusBox.className = 'user-status-box';
        statusBox.id = `user-status-${seatId}`;
        statusBox.setAttribute('data-seat-id', seatId);
        
        // Position based on seat (you'll need to adjust these positions based on your 3D scene)
        const positions = this.getSeatPositions();
        const position = positions[seatId] || { x: 50, y: 50 };
        
        statusBox.style.cssText = `
            position: fixed;
            left: ${position.x}px;
            top: ${position.y}px;
            min-width: 80px;
            max-width: 200px;
            background: rgba(0, 0, 0, 0.9);
            border: 2px solid #ffd700;
            border-radius: 4px;
            padding: 4px 8px;
            color: #fff;
            font-family: Arial, sans-serif;
            font-size: 12px;
            font-weight: bold;
            text-align: center;
            white-space: nowrap;
            display: none;
            z-index: 10000;
            pointer-events: none;
            box-shadow: 0 2px 8px rgba(0, 0, 0, 0.5);
        `;

        statusBox.innerHTML = `
            <div class="consumables-list"></div>
        `;

        return statusBox;
    }

    /**
     * Get screen positions for each seat based on 3D scene coordinates
     */
    getSeatPositions() {
        // If dinerScene is available, calculate real positions
        if (window.dinerScene && window.dinerScene.camera && window.dinerScene.renderer) {
            return this.calculateScreenPositions();
        }
        
        // Fallback positions for testing (centered across screen width)
        const screenWidth = window.innerWidth;
        const screenHeight = window.innerHeight;
        const spacing = screenWidth / 9; // 8 seats + margins
        
        return {
            1: { x: spacing * 1, y: screenHeight * 0.3 },
            2: { x: spacing * 2, y: screenHeight * 0.3 },
            3: { x: spacing * 3, y: screenHeight * 0.3 },
            4: { x: spacing * 4, y: screenHeight * 0.3 },
            5: { x: spacing * 5, y: screenHeight * 0.3 },
            6: { x: spacing * 6, y: screenHeight * 0.3 },
            7: { x: spacing * 7, y: screenHeight * 0.3 },
            8: { x: spacing * 8, y: screenHeight * 0.3 }
        };
    }
    
    /**
     * Calculate actual screen positions from 3D world coordinates using real customer sprites
     */
    calculateScreenPositions() {
        const positions = {};
        
        // Get actual customer sprite positions from Three.js scene
        if (!window.dinerScene || !window.dinerScene.sprites || !window.dinerScene.sprites.customers) {
            console.warn('Diner scene or customer sprites not available, using fallback positions');
            return this.getFallbackPositions();
        }
        
        const customerSprites = window.dinerScene.sprites.customers;
        
        for (let i = 0; i < customerSprites.length; i++) {
            const customerSprite = customerSprites[i];
            if (!customerSprite) continue;
            
            // Use actual sprite position with offset above the head
            const worldPos = {
                x: customerSprite.position.x,
                y: customerSprite.position.y + 1.5, // Position above the customer's head
                z: customerSprite.position.z
            };
            
            const screenPos = this.worldToScreen(worldPos);
            
            // Position status box centered above the customer
            positions[i + 1] = {
                x: screenPos.x - 60, // Center the box (assuming ~120px width)
                y: screenPos.y - 20  // Small offset above the projected position
            };
        }
        
        return positions;
    }
    
    /**
     * Fallback positions when Three.js scene is not available
     */
    getFallbackPositions() {
        const positions = {};
        const seatWorldPositions = [
            { x: -7, y: -4, z: 2 },  // Seat 1 - updated z to match customer sprites
            { x: -5, y: -4, z: 2 },  // Seat 2
            { x: -3, y: -4, z: 2 },  // Seat 3
            { x: -1, y: -4, z: 2 },  // Seat 4
            { x: 1, y: -4, z: 2 },   // Seat 5
            { x: 3, y: -4, z: 2 },   // Seat 6
            { x: 5, y: -4, z: 2 },   // Seat 7
            { x: 7, y: -4, z: 2 }    // Seat 8
        ];
        
        for (let i = 0; i < seatWorldPositions.length; i++) {
            const worldPos = seatWorldPositions[i];
            const screenPos = this.worldToScreen(worldPos);
            
            positions[i + 1] = {
                x: screenPos.x - 60,
                y: screenPos.y - 80
            };
        }
        
        return positions;
    }
    
    /**
     * Convert 3D world coordinates to 2D screen coordinates
     */
    worldToScreen(worldPosition) {
        if (!window.dinerScene || !window.dinerScene.camera || !window.dinerScene.renderer) {
            return { x: 100, y: 100 };
        }
        
        const vector = new THREE.Vector3(worldPosition.x, worldPosition.y, worldPosition.z);
        vector.project(window.dinerScene.camera);
        
        const canvas = window.dinerScene.renderer.domElement;
        const canvasRect = canvas.getBoundingClientRect();
        
        // Convert to actual screen coordinates accounting for canvas position
        const x = (vector.x + 1) * canvasRect.width / 2 + canvasRect.left;
        const y = (-vector.y + 1) * canvasRect.height / 2 + canvasRect.top;
        
        return { x: Math.round(x), y: Math.round(y) };
    }
    
    /**
     * Start animation loop to continuously update textbox positions
     */
    startPositionUpdateLoop() {
        const updatePositions = (timestamp) => {
            // Update positions at 30 FPS to reduce CPU usage
            if (timestamp - this.lastPositionUpdate > 33) {
                this.updateAllPositions();
                this.lastPositionUpdate = timestamp;
            }
            
            this.animationFrameId = requestAnimationFrame(updatePositions);
        };
        
        this.animationFrameId = requestAnimationFrame(updatePositions);
    }
    
    /**
     * Stop the position update loop
     */
    stopPositionUpdateLoop() {
        if (this.animationFrameId) {
            cancelAnimationFrame(this.animationFrameId);
            this.animationFrameId = null;
        }
    }
    
    /**
     * Update positions for all visible status boxes
     */
    updateAllPositions() {
        if (!this.isInitialized) return;
        
        const positions = this.getSeatPositions();
        
        this.statusBoxes.forEach((statusBox, seatId) => {
            if (statusBox.style.display !== 'none' && positions[seatId]) {
                const pos = positions[seatId];
                statusBox.style.left = `${pos.x}px`;
                statusBox.style.top = `${pos.y}px`;
            }
        });
    }

    /**
     * Setup WebSocket listeners for user status updates
     */
    setupWebSocketListeners() {
        // WebSocket setup is now handled by main.js - just log that we're ready
        console.log('✅ WebSocket listeners setup (handled by main.js integration)');
    }

    /**
     * Handle user status update from WebSocket
     */
    handleUserStatusUpdate(statusUpdate) {
        console.log('🔔 User Status Update Received:', statusUpdate);
        
        const { userId, roomId, seatId, consumables } = statusUpdate;
        
        if (!seatId || seatId < 1 || seatId > 8) {
            console.warn('⚠️ Invalid seat ID in status update:', seatId);
            return;
        }

        console.log(`👤 User ${userId} in seat ${seatId} has ${consumables ? consumables.length : 0} consumables`);
        
        // If this user has no consumables, clear any old status for this user in other seats
        if (!consumables || consumables.length === 0) {
            this.clearUserFromOtherSeats(userId, seatId);
        } else {
            // If this user has consumables, clear their status from all other seats first
            this.clearUserFromOtherSeats(userId, seatId);
        }

        // Check if this is a seat transfer for the current user
        const isCurrentUser = userId === (window.wsClient?.userId || window.wsClient?.getUsername?.());
        const existingStatus = this.userStatuses.get(seatId);
        
        // Simple status data - use backend values directly without frontend timer logic
        const newStatusData = {
            userId,
            roomId,
            consumables: consumables || [],
            lastUpdate: Date.now()
        };
        
        this.userStatuses.set(seatId, newStatusData);
        
        console.log(`📊 Updated status data for seat ${seatId}:`, {
            consumableCount: newStatusData.consumables.length,
            items: newStatusData.consumables.map(c => `${c.itemName} ${c.serverRemainingSeconds}s`)
        });
        
        // Immediate visual update for new data from server
        this.updateStatusDisplay(seatId);
        
        // Log display update with more details
        const statusBox = this.statusBoxes.get(seatId);
        console.log(`📊 Status box update for seat ${seatId}:`, {
            statusBoxExists: !!statusBox,
            consumables: consumables ? consumables.length : 0,
            statusBoxDisplay: statusBox ? statusBox.style.display : 'N/A',
            statusBoxPosition: statusBox ? { left: statusBox.style.left, top: statusBox.style.top } : 'N/A',
            statusBoxContent: statusBox ? statusBox.innerHTML : 'N/A'
        });
        
        if (statusBox && consumables && consumables.length > 0) {
            console.log(`🎯 Status box for seat ${seatId} should be visible with:`, 
                       consumables.map(c => `${c.itemName} ${c.remainingSeconds}s`));
        }
    }

    /**
     * Create a content hash for deduplication
     */
    createContentHash(consumables) {
        if (!consumables || consumables.length === 0) return 'empty';
        
        // Create hash based on item names and significant time intervals (10-second buckets)
        return consumables.map(c => {
            const timeBucket = Math.floor((c.remainingSeconds || 0) / 10) * 10;
            return `${c.itemName}_${c.orderId || c.itemId || 'unknown'}_${timeBucket}`;
        }).sort().join('|');
    }
    
    /**
     * Debounced visual update to prevent flickering
     */
    debouncedUpdateStatusDisplay(seatId) {
        // Clear any existing debounce timer for this seat
        const existingTimeout = this.updateDebounceMap.get(seatId);
        if (existingTimeout) {
            clearTimeout(existingTimeout);
        }
        
        // Set new debounce timer (150ms delay)
        const timeoutId = setTimeout(() => {
            this.updateStatusDisplay(seatId);
            this.updateDebounceMap.delete(seatId);
        }, 150);
        
        this.updateDebounceMap.set(seatId, timeoutId);
    }

    /**
     * Update the visual display for a specific seat
     */
    updateStatusDisplay(seatId) {
        console.log(`🔄 updateStatusDisplay called for seat ${seatId}`);
        
        const statusData = this.userStatuses.get(seatId);
        
        if (!statusData) {
            console.warn(`❌ Missing statusData for seat ${seatId}`);
            // Hide the Three.js sprite
            if (window.meshiya && window.meshiya.dinerScene) {
                window.meshiya.dinerScene.hideUserStatusSprite(seatId);
            }
            return;
        }

        const { userId, consumables } = statusData;
        console.log(`   - consumables count: ${consumables ? consumables.length : 0}`);

        // Update Three.js sprite using current status data
        const currentStatusData = this.userStatuses.get(seatId);
        if (window.meshiya && window.meshiya.dinerScene && currentStatusData) {
            const { userId, consumables } = currentStatusData;
            
            if (consumables && consumables.length > 0) {
                // Check if this is the current user (for seat swapping)
                const isCurrentUser = userId === (window.wsClient?.userId || window.wsClient?.getUsername?.());
                
                if (isCurrentUser) {
                    console.log(`   - Current user updating to seat ${seatId}, clearing old sprites`);
                    // Clear only the current user's old status sprites before showing new one
                    this.clearCurrentUserOldSprites(seatId);
                }
                
                console.log(`   - Updating Three.js status sprite for seat ${seatId} with ${consumables.length} items`);
                window.meshiya.dinerScene.updateUserStatusSprite(seatId, consumables);
            } else {
                console.log(`   - Hiding Three.js status sprite for seat ${seatId} (no consumables)`);
                window.meshiya.dinerScene.hideUserStatusSprite(seatId);
            }
        }
    }
    
    /**
     * Update the consumables list display (simplified for above-head display)
     */
    updateConsumablesList(listElement, consumables) {
        console.log(`     - updateConsumablesList called with ${consumables ? consumables.length : 0} consumables`);
        
        listElement.innerHTML = '';

        if (!consumables || consumables.length === 0) {
            console.log(`     - No consumables to display`);
            return; // Don't show anything when no consumables
        }

        consumables.forEach((consumable, index) => {
            const item = document.createElement('div');
            item.className = 'consumable-item-simple';
            
            const remainingTime = this.formatTime(consumable.remainingSeconds || 0);
            const displayText = `${consumable.itemName} ${remainingTime}`;
            
            console.log(`     - Creating consumable ${index + 1}: "${displayText}"`);
            
            // Simple display: "Green Tea 4:32"
            item.innerHTML = `
                <span class="item-text">${displayText}</span>
            `;

            listElement.appendChild(item);
        });
        
        console.log(`     - Final innerHTML: ${listElement.innerHTML}`);
    }

    /**
     * Format seconds into MM:SS format
     */
    formatTime(seconds) {
        if (seconds <= 0) return '0:00';
        
        const minutes = Math.floor(seconds / 60);
        const secs = seconds % 60;
        return `${minutes}:${secs.toString().padStart(2, '0')}`;
    }

    /**
     * Clear status for a specific seat (when user leaves)
     */
    clearSeatStatus(seatId) {
        this.userStatuses.delete(seatId);
        // Hide the Three.js sprite
        if (window.meshiya && window.meshiya.dinerScene) {
            window.meshiya.dinerScene.hideUserStatusSprite(seatId);
        }
    }
    
    /**
     * Clear only the current user's old sprites when they change seats
     */
    clearCurrentUserOldSprites(newSeatId) {
        if (!window.wsClient?.userId) return;
        
        const currentUserId = window.wsClient.userId;
        
        // Find and clear only the current user's old seats (not the new one they're moving to)
        this.userStatuses.forEach((statusData, seatId) => {
            if (statusData.userId === currentUserId && seatId !== newSeatId) {
                console.log(`   - Clearing current user's old sprite from seat ${seatId}`);
                if (window.meshiya && window.meshiya.dinerScene) {
                    window.meshiya.dinerScene.hideUserStatusSprite(seatId);
                }
                // Don't delete the status data - let the server handle that
            }
        });
    }
    
    /**
     * Clear a specific user from all seats except the specified one
     */
    clearUserFromOtherSeats(userId, keepSeatId) {
        // Before clearing, preserve timer data for the current user (but only non-expired items)
        const isCurrentUser = userId === (window.wsClient?.userId || window.wsClient?.getUsername?.());
        
        this.userStatuses.forEach((statusData, seatId) => {
            if (statusData.userId === userId && seatId !== keepSeatId) {
                // Preserve timer data before clearing for current user, but filter out expired items
                if (isCurrentUser && statusData.consumables && statusData.consumables.length > 0) {
                    // Only preserve non-expired consumables
                    const validConsumables = statusData.consumables.filter(c => 
                        (c.remainingSeconds || c.serverRemainingSeconds || 0) > 0
                    );
                    
                    if (validConsumables.length > 0) {
                        console.log(`   - Preserving ${validConsumables.length} valid timer data for current user (filtered out ${statusData.consumables.length - validConsumables.length} expired)`);
                        this.preservedTimers.set(userId, validConsumables.map(c => ({
                            itemName: c.itemName,
                            itemId: c.itemId,
                            orderId: c.orderId,
                            localStartTime: c.localStartTime,
                            serverRemainingSeconds: c.serverRemainingSeconds,
                            durationSeconds: c.durationSeconds,
                            preservedAt: Date.now()
                        })));
                    } else {
                        console.log(`   - No valid consumables to preserve for current user (all expired)`);
                        this.preservedTimers.delete(userId);
                    }
                }
                
                console.log(`   - Clearing user ${userId} from old seat ${seatId} (ghost cleanup)`);
                this.userStatuses.delete(seatId);
                if (window.meshiya && window.meshiya.dinerScene) {
                    window.meshiya.dinerScene.hideUserStatusSprite(seatId);
                }
            }
        });
    }

    /**
     * Clear all user statuses
     */
    clearAllStatuses() {
        this.userStatuses.clear();
        this.preservedTimers.clear(); // Also clear preserved timers
        // Hide all Three.js sprites
        if (window.meshiya && window.meshiya.dinerScene) {
            for (let seatId = 1; seatId <= 8; seatId++) {
                window.meshiya.dinerScene.hideUserStatusSprite(seatId);
            }
        }
    }

    /**
     * Start client-side timer for smooth countdown updates
     */
    startClientTimer() {
        // Update every 10 seconds to reduce unnecessary refreshes
        this.clientTimerInterval = setInterval(() => {
            this.updateClientTimers();
        }, 10000);
    }
    
    /**
     * Update client-side timers - simplified to just refresh displays
     */
    updateClientTimers() {
        // No more client-side timer calculations - just refresh displays occasionally
        const now = Date.now();
        
        if (now - this.lastClientUpdate > 10000) { // Refresh every 10 seconds
            this.userStatuses.forEach((statusData, seatId) => {
                if (statusData.consumables && statusData.consumables.length > 0) {
                    this.updateStatusDisplay(seatId);
                }
            });
            this.lastClientUpdate = now;
        }
    }

    /**
     * Cleanup method to prevent memory leaks
     */
    cleanup() {
        // Clear client timer
        if (this.clientTimerInterval) {
            clearInterval(this.clientTimerInterval);
            this.clientTimerInterval = null;
        }
        
        // Clear all debounce timers
        this.updateDebounceMap.forEach(timeoutId => {
            clearTimeout(timeoutId);
        });
        this.updateDebounceMap.clear();
        this.lastUpdateContentMap.clear();
        
        // Clear user statuses and preserved timers
        this.userStatuses.clear();
        this.preservedTimers.clear();
        
        console.log('🧹 UserStatusManager cleaned up');
    }

    /**
     * Get current status for debugging
     */
    getDebugInfo() {
        return {
            userStatuses: Array.from(this.userStatuses.entries()),
            isInitialized: this.isInitialized,
            threeJsSprites: window.meshiya && window.meshiya.dinerScene ? 
                window.meshiya.dinerScene.sprites.userStatusBoxes.length : 0,
            activeDebounceTimers: this.updateDebounceMap.size,
            lastUpdateHashes: Array.from(this.lastUpdateContentMap.entries())
        };
    }
    
    /**
     * Test function - simulate a consumable for debugging
     */
    testStatusBox(seatId = 8) {
        console.log(`🧪 Testing status box for seat ${seatId}`);
        
        const testStatus = {
            type: 'USER_STATUS_UPDATE',
            userId: 'test_user',
            roomId: 'room1',
            seatId: seatId,
            consumables: [{
                itemName: 'Green Tea',
                itemType: 'DRINK',
                remainingSeconds: 240,
                durationSeconds: 300
            }]
        };
        
        this.handleUserStatusUpdate(testStatus);
        
        // Log Three.js sprite status
        if (window.meshiya && window.meshiya.dinerScene) {
            const sprites = window.meshiya.dinerScene.sprites.userStatusBoxes;
            console.log('📊 Three.js status sprites:', 
                sprites.map((sprite, index) => ({
                    seatId: index + 1,
                    visible: sprite.visible,
                    position: { x: sprite.position.x, y: sprite.position.y, z: sprite.position.z }
                }))
            );
        }
    }
    
    /**
     * Show all status boxes for testing (makes them all visible)
     */
    showAllStatusBoxes() {
        console.log('🎯 Making all status boxes visible for testing');
        Array.from(this.statusBoxes.values()).forEach((box, index) => {
            box.style.display = 'block';
            box.innerHTML = `<div class="consumables-list"><div class="consumable-item-simple"><span class="item-text">Test ${index + 1}</span></div></div>`;
        });
    }
    
    /**
     * Force show a specific status box with simple positioning
     */
    forceShowStatusBox(seatId = 6) {
        const statusBox = this.statusBoxes.get(seatId);
        if (!statusBox) {
            console.error(`No status box found for seat ${seatId}`);
            return;
        }
        
        // Force simple positioning in center of screen
        statusBox.style.position = 'fixed';
        statusBox.style.left = '50%';
        statusBox.style.top = '50%';
        statusBox.style.transform = 'translate(-50%, -50%)';
        statusBox.style.display = 'block';
        statusBox.style.zIndex = '99999';
        statusBox.style.background = 'red';  // Make it very obvious
        statusBox.style.border = '3px solid yellow';
        statusBox.style.padding = '10px';
        statusBox.innerHTML = '<div class="consumables-list"><div class="consumable-item-simple"><span class="item-text">FORCED TEST</span></div></div>';
        
        console.log('🔴 Force-showed status box in center of screen with red background');
    }
}

// Create global instance
window.userStatusManager = new UserStatusManager();

// Wait for both DOM and WebSocket to be ready
function initializeUserStatusManager() {
    const wsClient = window.wsClient || window.websocketClient;
    
    if (!wsClient || !wsClient.connected) {
        console.log('⏳ Waiting for WebSocket connection...', {
            wsClient: !!window.wsClient,
            websocketClient: !!window.websocketClient,
            connected: wsClient ? wsClient.connected : 'N/A'
        });
        setTimeout(initializeUserStatusManager, 1000);
        return;
    }
    
    console.log('🚀 WebSocket ready, initializing UserStatusManager');
    window.userStatusManager.init();
}

// Note: Initialization is now handled by main.js
// No auto-initialization needed here

console.log('UserStatusManager loaded');