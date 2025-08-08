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
    }

    connect(username) {
        this.username = username;
        this.userId = this.generateUserId();
        
        console.log('Creating WebSocket connection to /ws/cafe');
        console.log('Username:', username, 'UserID:', this.userId);
        
        // Store user data in localStorage
        this.storeUserDataLocally();
        
        this.establishConnection();
    }

    connectWithExistingSession(userId, username) {
        this.username = username;
        this.userId = userId;
        
        console.log('Creating WebSocket connection with existing session');
        console.log('Username:', username, 'UserID:', userId);
        
        // Store user data in localStorage
        this.storeUserDataLocally();
        
        this.establishConnection();
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
        
        // Join Room1
        this.stompClient.send("/app/room.join", {}, JSON.stringify({
            userId: this.userId,
            userName: this.username,
            roomId: 'room1'
        }));
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
                console.log('Attempting to reconnect...');
                this.connect(this.username);
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
            for (const [seatId, userId] of Object.entries(message.occupancy)) {
                const seatMessage = {
                    type: 'SEAT_STATE',
                    seatId: parseInt(seatId),
                    userId: userId,
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