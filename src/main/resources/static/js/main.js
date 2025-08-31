// Main application entry point
class MeshiyaApp {
    constructor() {
        this.wsClient = null;
        this.dinerScene = null;
        this.uiManager = null;
        
        this.init();
    }

    init() {
        console.log('Initializing Meshiya Midnight Diner...');
        
        // Initialize WebSocket client
        this.wsClient = new WebSocketClient();
        
        // Initialize 2D scene
        const sceneContainer = document.getElementById('scene-container');
        this.dinerScene = new DinerScene(sceneContainer);
        
        // Initialize UI manager
        this.uiManager = new UIManager();
        
        // Connect components
        this.connectComponents();
        
        // Make WebSocket client globally accessible
        window.wsClient = this.wsClient;
        
        // Initialize UI manager after WebSocket client is available
        this.uiManager.initialize();
        
        // Initialize UserStatusManager after WebSocket is ready
        if (window.userStatusManager) {
            setTimeout(() => {
                window.userStatusManager.init();
                console.log('🎯 UserStatusManager initialized');
            }, 500);
        }
        
        // Initialize VideoDisplayManager - will be done when WebSocket connects
        
        console.log('Meshiya initialization complete');
    }

    connectComponents() {
        // Connect WebSocket events to UI
        this.wsClient.onMessage((message) => {
            this.uiManager.handleMessage(message);
        });

        this.wsClient.onSeatUpdate((message) => {
            this.uiManager.handleSeatUpdate(message);
            this.dinerScene.handleSeatUpdate(message);
        });

        this.wsClient.onConnectionChange((status) => {
            this.uiManager.handleConnectionChange(status);
            
            // Initialize VideoDisplayManager when WebSocket connects
            if (status === 'connected' && window.videoDisplayManager && !window.videoDisplayManager.isInitialized) {
                // Default to room1 for now - in future, get from current room context
                window.videoDisplayManager.init('room1');
                console.log('🎬 VideoDisplayManager initialized after WebSocket connection');
            }
        });

        this.wsClient.onMasterStatusUpdate((message) => {
            this.uiManager.handleMasterStatusUpdate(message);
        });

        // Connect user status updates to UserStatusManager
        this.wsClient.onUserStatusUpdate((message) => {
            if (window.userStatusManager && window.userStatusManager.isInitialized) {
                window.userStatusManager.handleUserStatusUpdate(message);
            }
        });

        // Connect order notifications to UI
        this.wsClient.onOrderNotification((message) => {
            this.uiManager.handleOrderNotification(message);
        });
        
        // Connect avatar state updates to diner scene
        this.wsClient.onAvatarStateUpdate((message) => {
            this.handleAvatarStateUpdate(message);
        });
        
        // Connect TTS ready notifications to UI manager
        this.wsClient.onTTSReady((message) => {
            this.uiManager.handleTTSReady(message);
        });
    }
    
    handleAvatarStateUpdate(message) {
        console.log('🎭 [MAIN] Avatar state update received:', message);
        
        const { userId, roomId, seatId, avatarState } = message;
        
        console.log(`🎭 [MAIN] Processing avatar state: userId=${userId}, seatId=${seatId}, state=${avatarState}`);
        
        if (!seatId || seatId < 1 || seatId > 8) {
            console.warn('⚠️ [MAIN] Invalid seat ID in avatar state update:', seatId);
            return;
        }
        
        if (!this.dinerScene) {
            console.warn('⚠️ [MAIN] Diner scene not available for avatar state update');
            return;
        }
        
        // Update the user's avatar image state
        console.log(`🎭 [MAIN] Calling dinerScene.updateUserImageState(${seatId}, '${avatarState}')`);
        this.dinerScene.updateUserImageState(seatId, avatarState);
        console.log('🎭 [MAIN] Avatar state update call completed');
    }

    // Public API for debugging/external access
    getWebSocketClient() {
        return this.wsClient;
    }

    getDinerScene() {
        return this.dinerScene;
    }

    getUIManager() {
        return this.uiManager;
    }

    // Utility methods
    log(message) {
        console.log(`[Meshiya] ${message}`);
    }

    error(message, error = null) {
        console.error(`[Meshiya Error] ${message}`, error || '');
    }
}

// Initialize application when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
    // Ensure all classes are loaded before initialization
    if (typeof DinerScene === 'undefined' || typeof UIManager === 'undefined' || typeof WebSocketClient === 'undefined') {
        console.error('Required classes not loaded. Retrying in 100ms...');
        setTimeout(() => {
            if (typeof DinerScene !== 'undefined' && typeof UIManager !== 'undefined' && typeof WebSocketClient !== 'undefined') {
                window.meshiya = new MeshiyaApp();
            } else {
                console.error('Classes still not loaded after retry:', {
                    DinerScene: typeof DinerScene,
                    UIManager: typeof UIManager, 
                    WebSocketClient: typeof WebSocketClient
                });
            }
        }, 100);
        return;
    }
    
    // Create global app instance
    window.meshiya = new MeshiyaApp();
});

// Handle page unload
window.addEventListener('beforeunload', () => {
    if (window.meshiya && window.meshiya.wsClient) {
        window.meshiya.wsClient.disconnect();
    }
    if (window.userStatusManager) {
        window.userStatusManager.cleanup();
    }
});

// Global error handler
window.addEventListener('error', (event) => {
    console.error('Global error:', event.error);
    
    // Show user-friendly error message
    const errorMsg = 'An error occurred. Please refresh the page if problems persist.';
    if (window.meshiya && window.meshiya.uiManager) {
        window.meshiya.uiManager.addSystemMessage(errorMsg);
    } else {
        alert(errorMsg);
    }
});

// Debug utilities (available in browser console)
window.debugMeshiya = {
    getSeats: () => {
        return window.meshiya?.dinerScene?.getSeatStates();
    },
    
    sendTestMessage: (message = 'Test message') => {
        if (window.wsClient?.isConnected()) {
            window.wsClient.sendMessage(message);
        } else {
            console.log('Not connected to server');
        }
    },
    
    joinSeat: (seatNumber) => {
        if (window.wsClient?.isConnected()) {
            window.wsClient.joinSeat(seatNumber);
        } else {
            console.log('Not connected to server');
        }
    },
    
    leaveSeat: () => {
        if (window.wsClient?.isConnected()) {
            window.wsClient.leaveSeat();
        } else {
            console.log('Not connected to server');
        }
    },
    
    getConnectionStatus: () => {
        return {
            connected: window.wsClient?.isConnected() || false,
            username: window.wsClient?.getUsername() || null,
            currentSeat: window.wsClient?.getCurrentSeat() || null
        };
    }
};