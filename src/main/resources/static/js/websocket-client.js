class WebSocketClient {
    constructor() {
        this.stompClient = null;
        this.connected = false;
        this.username = null;
        this.userId = null;
        this.currentSeat = null;
        this.messageHandlers = [];
        this.seatHandlers = [];
        this.connectionHandlers = [];
        this.masterStatusHandlers = [];
        this.userStatusHandlers = [];
        this.orderNotificationHandlers = [];
        this.avatarStateHandlers = [];
        this.ttsReadyHandlers = [];
    }

    connect(username) {
        this.username = username;
        
        // IMPORTANT: Only use generateUserId() as absolute fallback
        // The normal flow should always use connectWithExistingSession()
        if (!this.userId) {
            console.warn('Fallback: Generating random userId - this should not happen in normal flow');
            this.userId = this.generateUserId();
        }
        
        console.log('Creating WebSocket connection to /ws/cafe');
        console.log('Username:', username, 'UserID:', this.userId);
        
        // Store user data in localStorage
        this.storeUserDataLocally();
        
        this.establishConnection();
    }

    connectWithExistingSession(userId, username, isRegisteredUser = false) {
        this.userId = userId;
        
        console.log('Creating WebSocket connection with existing session');
        console.log('Initial username:', username, 'UserID:', userId, 'Registered:', isRegisteredUser);
        
        if (isRegisteredUser) {
            // For registered users, use the verified username directly
            this.username = username;
            this.storeUserDataLocally();
            this.establishConnection();
        } else {
            // For guest users, fetch current username from backend as fallback
            this.fetchCurrentUsernameAndConnect(username);
        }
    }

    async fetchCurrentUsernameAndConnect(fallbackUsername) {
        try {
            const response = await fetch('/api/session/username');
            const data = await response.json();
            
            if (data.success && data.isRegistered) {
                // Use current username from database
                this.username = data.username;
                console.log('✅ Using current username from DB:', data.username);
            } else {
                // Not registered or no session, use fallback
                this.username = fallbackUsername;
                console.log('📝 Using fallback username:', fallbackUsername);
            }
            
            // Store user data in localStorage
            this.storeUserDataLocally();
            
            this.establishConnection();
            
        } catch (error) {
            console.warn('Failed to fetch current username, using fallback:', error);
            this.username = fallbackUsername;
            this.storeUserDataLocally();
            this.establishConnection();
        }
    }

    /**
     * Update username (called after username change in settings)
     */
    updateUsername(newUsername) {
        this.username = newUsername;
        console.log('Updated WebSocket client username to:', newUsername);
        
        // Update localStorage
        this.storeUserDataLocally();
    }

    establishConnection() {
        try {
            const socket = new SockJS('/ws/cafe');
            this.stompClient = Stomp.over(socket);
            
            // Enable debugging
            this.stompClient.debug = (str) => {
                console.log('STOMP Debug:', str);
            };
            
            this.stompClient.connect({}, 
                (frame) => this.onConnected(frame),
                (error) => this.onError(error)
            );
            
            console.log('WebSocket connection initiated');
        } catch (error) {
            console.error('Failed to create WebSocket connection:', error);
            this.onError(error);
        }
    }


    onConnected(frame) {
        console.log('Connected: ' + frame);
        this.connected = true;
        this.notifyConnectionHandlers('connected');
        
        // Subscribe to Room1 messages (includes chat history and new messages)
        this.stompClient.subscribe('/topic/room/room1', (messageOutput) => {
            console.log('Received room message:', messageOutput.body);
            this.handleMessage(JSON.parse(messageOutput.body));
        });
        
        // Subscribe to Room1 seat updates
        this.stompClient.subscribe('/topic/room/room1/seats', (messageOutput) => {
            console.log('Received seat update:', messageOutput.body);
            this.handleSeatUpdate(JSON.parse(messageOutput.body));
        });
        
        // Subscribe to master status updates
        this.stompClient.subscribe('/topic/master-status', (messageOutput) => {
            console.log('Received master status update:', messageOutput.body);
            this.handleMasterStatusUpdate(JSON.parse(messageOutput.body));
        });
        
        // Subscribe to user status updates
        this.stompClient.subscribe('/topic/room/room1/user-status', (messageOutput) => {
            console.log('Received user status update:', messageOutput.body);
            this.handleUserStatusUpdate(JSON.parse(messageOutput.body));
        });
        
        // Subscribe to avatar state updates
        this.stompClient.subscribe('/topic/room/room1/avatar-state', (messageOutput) => {
            console.log('🎭 [WEBSOCKET] Received avatar state update:', messageOutput.body);
            try {
                const message = JSON.parse(messageOutput.body);
                console.log('🎭 [WEBSOCKET] Parsed avatar state message:', message);
                this.handleAvatarStateUpdate(message);
            } catch (e) {
                console.error('🎭 [WEBSOCKET] Error parsing avatar state message:', e);
            }
        });
        
        // Subscribe to TTS ready notifications
        this.stompClient.subscribe('/topic/room/room1/tts', (messageOutput) => {
            console.log('🔊 [WEBSOCKET] Received TTS ready:', messageOutput.body);
            try {
                const message = JSON.parse(messageOutput.body);
                console.log('🔊 [WEBSOCKET] Parsed TTS message:', message);
                this.handleTTSReady(message);
            } catch (e) {
                console.error('🔊 [WEBSOCKET] Error parsing TTS message:', e);
            }
        });
        
        // Subscribe to personal order notifications
        this.stompClient.subscribe('/user/queue/orders', (messageOutput) => {
            console.log('Received personal order notification:', messageOutput.body);
            this.handleOrderNotification(JSON.parse(messageOutput.body));
        });
        
        // NOTE: TV/Video messages are now handled via room broadcasts (/topic/room/room1/video)
        // No need for personal video queue subscription since TV is a shared room experience
        
        // Join Room1
        this.stompClient.send("/app/room.join", {}, JSON.stringify({
            userId: this.userId,
            userName: this.username,
            roomId: 'room1'
        }));
        
        // Request current user status data after connection
        setTimeout(() => {
            this.requestUserStatusRefresh();
        }, 500);
    }

    onError(error) {
        console.error('WebSocket connection error:', error);
        this.connected = false;
        this.notifyConnectionHandlers('error');
        
        // Show user-friendly error message
        if (window.meshiya && window.meshiya.uiManager) {
            window.meshiya.uiManager.addSystemMessage('Connection failed. Please make sure the server is running on port 8080.');
        }
        
        // Try to reconnect after 5 seconds (longer delay)
        setTimeout(() => {
            if (!this.connected && this.username) {
                console.log('Attempting to reconnect with existing session...');
                // Use existing session if we have it
                if (this.userId) {
                    this.connectWithExistingSession(this.userId, this.username);
                } else {
                    this.connect(this.username);
                }
            }
        }, 5000);
    }

    sendMessage(content) {
        if (this.stompClient && this.connected) {
            const chatMessage = {
                userId: this.userId,
                userName: this.username,
                content: content,
                roomId: 'room1',
                seatId: this.currentSeat
            };
            
            this.stompClient.send("/app/room.sendMessage", {}, JSON.stringify(chatMessage));
            
        }
    }

    joinSeat(seatNumber) {
        if (this.stompClient && this.connected) {
            const message = {
                userId: this.userId,
                userName: this.username,
                seatId: seatNumber,
                roomId: 'room1'
            };
            
            this.stompClient.send("/app/room.joinSeat", {}, JSON.stringify(message));
        }
    }

    leaveSeat() {
        if (this.stompClient && this.connected && this.currentSeat) {
            const message = {
                userId: this.userId,
                userName: this.username,
                seatId: this.currentSeat,
                roomId: 'room1'
            };
            
            this.stompClient.send("/app/room.leaveSeat", {}, JSON.stringify(message));
        }
    }

    handleMessage(message) {
        this.notifyMessageHandlers(message);
    }

    handleSeatUpdate(message) {
        console.log('Handling seat update:', message);
        
        if (message.type === 'SEAT_OCCUPANCY_UPDATE' && message.occupancy) {
            // Handle full seat occupancy update
            for (const [seatId, seatInfo] of Object.entries(message.occupancy)) {
                // Handle both old format (userId string) and new format (object with userId/userName)
                const userId = typeof seatInfo === 'string' ? seatInfo : seatInfo.userId;
                const userName = typeof seatInfo === 'object' ? seatInfo.userName : undefined;
                
                const seatMessage = {
                    type: 'SEAT_STATE',
                    seatId: parseInt(seatId),
                    userId: userId,
                    userName: userName,
                    occupied: true
                };
                
                if (userId === this.userId) {
                    this.currentSeat = parseInt(seatId);
                }
                
                this.notifySeatHandlers(seatMessage);
            }
            
            // Clear seats that are no longer occupied
            for (let i = 1; i <= 8; i++) {
                if (!message.occupancy[i]) {
                    const seatMessage = {
                        type: 'SEAT_STATE',
                        seatId: i,
                        userId: null,
                        occupied: false
                    };
                    this.notifySeatHandlers(seatMessage);
                }
            }
        }
    }

    handleMasterStatusUpdate(message) {
        console.log('Handling master status update:', message);
        this.notifyMasterStatusHandlers(message);
    }

    handleUserStatusUpdate(message) {
        console.log('Handling user status update:', message);
        this.notifyUserStatusHandlers(message);
    }

    handleOrderNotification(message) {
        console.log('Handling order notification:', message);
        this.notifyOrderNotificationHandlers(message);
    }
    
    handleAvatarStateUpdate(message) {
        console.log('🎭 [HANDLER] Handling avatar state update:', message);
        console.log('🎭 [HANDLER] Avatar state handlers count:', this.avatarStateHandlers.length);
        this.notifyAvatarStateHandlers(message);
    }
    
    handleTTSReady(message) {
        console.log('🔊 [HANDLER] Handling TTS ready:', message);
        this.notifyTTSReadyHandlers(message);
    }

    // Event handler management
    onMessage(handler) {
        this.messageHandlers.push(handler);
    }

    onSeatUpdate(handler) {
        this.seatHandlers.push(handler);
    }

    onConnectionChange(handler) {
        this.connectionHandlers.push(handler);
    }

    onMasterStatusUpdate(handler) {
        this.masterStatusHandlers.push(handler);
    }

    onUserStatusUpdate(handler) {
        this.userStatusHandlers.push(handler);
    }

    onOrderNotification(handler) {
        this.orderNotificationHandlers.push(handler);
    }
    
    onAvatarStateUpdate(handler) {
        this.avatarStateHandlers.push(handler);
    }
    
    onTTSReady(handler) {
        this.ttsReadyHandlers.push(handler);
    }

    notifyMessageHandlers(message) {
        this.messageHandlers.forEach(handler => handler(message));
    }

    notifySeatHandlers(message) {
        this.seatHandlers.forEach(handler => handler(message));
    }

    notifyConnectionHandlers(status) {
        this.connectionHandlers.forEach(handler => handler(status));
    }

    notifyMasterStatusHandlers(message) {
        this.masterStatusHandlers.forEach(handler => handler(message));
    }

    notifyUserStatusHandlers(message) {
        this.userStatusHandlers.forEach(handler => handler(message));
    }

    notifyOrderNotificationHandlers(message) {
        this.orderNotificationHandlers.forEach(handler => handler(message));
    }
    
    notifyAvatarStateHandlers(message) {
        this.avatarStateHandlers.forEach(handler => handler(message));
    }
    
    notifyTTSReadyHandlers(message) {
        this.ttsReadyHandlers.forEach(handler => handler(message));
    }

    /**
     * Stores user data in localStorage
     */
    storeUserDataLocally() {
        try {
            const userData = {
                userId: this.userId,
                username: this.username,
                lastActivity: new Date().toISOString()
            };
            // Store both in session-specific and general locations
            localStorage.setItem('meshiya_user', JSON.stringify(userData));
            if (this.userId) {
                localStorage.setItem(`meshiya_session_${this.userId}`, JSON.stringify({
                    sessionId: this.userId,
                    username: this.username,
                    lastActivity: new Date().toISOString()
                }));
            }
        } catch (error) {
            console.warn('Failed to store user data locally:', error);
        }
    }
    
    /**
     * Retrieves user data from localStorage
     */
    getUserDataFromLocal() {
        try {
            const stored = localStorage.getItem('meshiya_user');
            if (stored) {
                return JSON.parse(stored);
            }
        } catch (error) {
            console.warn('Failed to retrieve user data from localStorage:', error);
        }
        return null;
    }

    generateUserId() {
        return 'user_' + Math.random().toString(36).substr(2, 9);
    }

    /**
     * Request a refresh of user status data from the server
     */
    requestUserStatusRefresh() {
        if (!this.isConnected()) {
            console.warn('Cannot request user status refresh - not connected');
            return;
        }
        
        console.log('🔄 Requesting user status refresh for all users');
        
        try {
            this.stompClient.send("/app/user-status.refresh", {}, JSON.stringify({
                roomId: 'room1',
                userId: this.userId,
                userName: this.username,
                type: 'USER_STATUS_REFRESH'
            }));
        } catch (error) {
            console.error('Failed to request user status refresh:', error);
        }
    }

    disconnect() {
        if (this.stompClient) {
            this.stompClient.disconnect();
            this.connected = false;
        }
    }

    isConnected() {
        return this.connected;
    }

    getCurrentSeat() {
        return this.currentSeat;
    }

    getUsername() {
        return this.username;
    }
}