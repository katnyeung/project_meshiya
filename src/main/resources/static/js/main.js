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
        });

        this.wsClient.onMasterStatusUpdate((message) => {
            this.uiManager.handleMasterStatusUpdate(message);
        });
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
    // Create global app instance
    window.meshiya = new MeshiyaApp();
});

// Handle page unload
window.addEventListener('beforeunload', () => {
    if (window.meshiya && window.meshiya.wsClient) {
        window.meshiya.wsClient.disconnect();
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