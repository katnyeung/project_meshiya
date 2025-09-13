class UIManager {
    constructor() {
        this.elements = {};
        this.isLoggedIn = false;
        this.currentSeat = null;
        this.occupiedSeats = new Set();
        
        // Initialize TTS service
        this.ttsService = new TTSService();
        
        // Track pending TTS messages
        this.pendingTTSMessages = new Map(); // messageKey -> messageElement
        
        // Track TTS ready messages that arrived before chat messages
        this.readyTTSMessages = new Map(); // messageKey -> {audioUrl, timestamp}
        
        this.initializeElements();
        this.attachEventListeners();
        // Don't check for existing user here - wait for initialization to complete
    }
    
    /**
     * Initialize after WebSocket client is ready
     */
    initialize() {
        this.checkForExistingUser().catch(error => {
            console.error('Error checking existing user:', error);
        });
        
        // Start periodic cleanup of ready TTS messages
        setInterval(() => {
            this.cleanupReadyTTSMessages();
        }, 2 * 60 * 1000); // Clean up every 2 minutes
    }

    initializeElements() {
        // Get all UI elements
        this.elements = {
            welcomeScreen: document.getElementById('welcome-screen'),
            usernameInput: document.getElementById('username-input'),
            enterDinerBtn: document.getElementById('enter-diner-btn'),
            
            chatInterface: document.getElementById('chat-interface'),
            chatMessages: document.getElementById('chat-messages'),
            chatInput: document.getElementById('chat-input'),
            sendBtn: document.getElementById('send-btn'),
            
            seatControls: document.getElementById('seat-controls'),
            seatButtons: document.querySelectorAll('.seat-btn'),
            leaveSeatBtn: document.getElementById('leave-seat-btn'),
            
            statusDisplay: document.getElementById('status-display'),
            currentSeat: document.getElementById('current-seat'),
            connectionStatus: document.getElementById('connection-status'),
            
            masterStatusLabel: document.getElementById('master-status-label'),
            masterStatusText: document.getElementById('master-status-text'),
            
            // TTS controls
            ttsControls: document.getElementById('tts-controls'),
            ttsToggleBtn: document.getElementById('tts-toggle-btn'),
            ttsTestBtn: document.getElementById('tts-test-btn'),
            ttsSkipBtn: document.getElementById('tts-skip-btn'),
            ttsClearBtn: document.getElementById('tts-clear-btn'),
            ttsQueueStatus: document.getElementById('tts-queue-status'),
            ttsCurrentText: document.getElementById('tts-current-text'),
            ttsCurrent: document.getElementById('tts-current')
        };
    }

    attachEventListeners() {
        // Welcome screen
        this.elements.enterDinerBtn.addEventListener('click', () => this.enterDiner());
        this.elements.usernameInput.addEventListener('keypress', (e) => {
            if (e.key === 'Enter') this.enterDiner();
        });

        // Chat
        this.elements.sendBtn.addEventListener('click', () => this.sendMessage());
        this.elements.chatInput.addEventListener('keypress', (e) => {
            if (e.key === 'Enter') this.sendMessage();
        });

        // Seats
        this.elements.seatButtons.forEach(btn => {
            btn.addEventListener('click', (e) => {
                const seatNumber = parseInt(e.target.dataset.seat);
                this.requestSeat(seatNumber);
            });
        });

        this.elements.leaveSeatBtn.addEventListener('click', () => this.leaveSeat());
        
        // TTS controls
        if (this.elements.ttsToggleBtn) {
            this.elements.ttsToggleBtn.addEventListener('click', () => this.toggleTTS());
        }
        if (this.elements.ttsTestBtn) {
            this.elements.ttsTestBtn.addEventListener('click', () => this.testTTS());
        }
        if (this.elements.ttsSkipBtn) {
            this.elements.ttsSkipBtn.addEventListener('click', () => this.skipCurrentTTS());
        }
        if (this.elements.ttsClearBtn) {
            this.elements.ttsClearBtn.addEventListener('click', () => this.clearTTSQueue());
        }
        
        // Listen for TTS queue updates
        document.addEventListener('tts-queue-update', (event) => this.handleTTSQueueUpdate(event.detail));
    }

    /**
     * Simple check for existing user data
     */
    async checkForExistingUser() {
        try {
            // Check for sessionId in URL first
            const urlParams = new URLSearchParams(window.location.search);
            const sessionId = urlParams.get('sessionId');
            
            if (sessionId) {
                // Decode Base64 session ID
                try {
                    const decodedId = atob(sessionId);
                    console.log('Found session ID in URL:', decodedId);
                    
                    // Check if user has valid login cookie
                    const loginCookie = this.getLoginCookie();
                    if (loginCookie) {
                        console.log('Found login cookie, verifying registered user session...');
                        // Verify login cookie is still valid
                        const isValidLogin = await this.verifyLoginCookie();
                        if (isValidLogin) {
                            console.log('✅ Valid registered user session restored');
                            // Connect as registered user
                            window.wsClient.userId = decodedId;
                            window.wsClient.connectWithExistingSession(decodedId, loginCookie, true);
                            
                            this.isLoggedIn = true;
                            this.showMainInterface(false); // Don't show welcome for session restore
                            // Remove session restored message to maintain proper message order
                            return;
                        } else {
                            console.log('❌ Login cookie expired, clearing cookies...');
                            this.clearAllCookies();
                        }
                    }
                    
                    // No valid login cookie - restore as guest session
                    const userData = this.getUserDataForSession(decodedId);
                    const fallbackUsername = userData ? userData.username : 'Guest';
                    
                    console.log('Restoring as guest session with username:', fallbackUsername);
                    window.wsClient.userId = decodedId;
                    window.wsClient.connectWithExistingSession(decodedId, fallbackUsername, false);
                    
                    this.isLoggedIn = true;
                    this.showMainInterface(false); // Don't show welcome for session restore
                    // Remove session restored message to maintain proper message order
                    
                    return;
                } catch (e) {
                    console.warn('Invalid session ID in URL');
                }
            }
            
            // No sessionId found - this is expected for fresh access, show welcome screen
            console.log('No sessionId found - showing welcome screen for authentication');
            // Don't automatically generate or connect - let user choose how to proceed
            
        } catch (error) {
            console.warn('Error checking existing user:', error);
        }
    }
    
    /**
     * Get user data for a specific session ID
     */
    getUserDataForSession(sessionId) {
        try {
            const stored = localStorage.getItem(`meshiya_session_${sessionId}`);
            if (stored) {
                return JSON.parse(stored);
            }
        } catch (error) {
            console.warn('Failed to retrieve session data:', error);
        }
        return null;
    }
    
    /**
     * Get login cookie value
     */
    getLoginCookie() {
        const cookies = document.cookie.split(';');
        for (let cookie of cookies) {
            const [name, value] = cookie.trim().split('=');
            if (name === 'meshiyaLoggedIn') {
                return decodeURIComponent(value);
            }
        }
        return null;
    }
    
    /**
     * Verify login cookie is still valid
     */
    async verifyLoginCookie() {
        try {
            const response = await fetch('/api/profile');
            const data = await response.json();
            return data.success && data.isLoggedIn;
        } catch (error) {
            console.warn('Failed to verify login cookie:', error);
            return false;
        }
    }
    
    /**
     * Clear all Meshiya cookies
     */
    clearAllCookies() {
        const cookiesToClear = ['meshiyaLoggedIn', 'meshiyaRegistered', 'meshiyaSessionId'];
        for (let cookieName of cookiesToClear) {
            document.cookie = `${cookieName}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`;
        }
        console.log('All Meshiya cookies cleared');
    }
    
    /**
     * Store user data for a specific session ID
     */
    storeUserDataForSession(sessionId, username) {
        try {
            const userData = {
                sessionId: sessionId,
                username: username,
                lastActivity: new Date().toISOString()
            };
            localStorage.setItem(`meshiya_session_${sessionId}`, JSON.stringify(userData));
            console.log('Stored user data for session:', sessionId);
        } catch (error) {
            console.warn('Failed to store session data:', error);
        }
    }
    
    /**
     * Create a new session and connect to the server
     */
    async createNewSessionAndConnect(username) {
        try {
            // Generate a new session ID (similar to backend logic)
            const newSessionId = 'session_' + Date.now() + '_' + Math.random().toString(36).substring(2, 10);
            
            console.log('Generated new session ID for guest:', newSessionId);
            
            // Set up the session
            window.wsClient.userId = newSessionId;
            window.wsClient.username = username;
            
            // Store the session data
            this.storeUserDataForSession(newSessionId, username);
            
            // Connect as new guest user
            window.wsClient.connect(username);
            
            // Redirect to include session ID in URL for future refreshes
            const encodedSessionId = btoa(newSessionId);
            const newUrl = `${window.location.origin}/?sessionId=${encodedSessionId}`;
            window.history.replaceState({}, '', newUrl);
            
            console.log('Connected with new session ID:', newSessionId);
        } catch (error) {
            console.error('Failed to create new session:', error);
            throw error;
        }
    }
    
    /**
     * Prepare for a new session - show username input with sessionId ready
     */
    prepareForNewSession(sessionId) {
        this.pendingSessionId = sessionId;
        console.log('Prepared for new session:', sessionId);
        
        // Check if we have a previous username for convenience
        const allSessions = this.getAllStoredSessions();
        if (allSessions.length > 0) {
            const lastSession = allSessions[allSessions.length - 1];
            this.elements.usernameInput.value = lastSession.username;
            console.log('Pre-filled with previous username:', lastSession.username);
        }
    }
    
    /**
     * Get all stored sessions for username auto-fill
     */
    getAllStoredSessions() {
        const sessions = [];
        try {
            for (let i = 0; i < localStorage.length; i++) {
                const key = localStorage.key(i);
                if (key && key.startsWith('meshiya_session_')) {
                    const data = JSON.parse(localStorage.getItem(key));
                    sessions.push(data);
                }
            }
            return sessions.sort((a, b) => new Date(a.lastActivity) - new Date(b.lastActivity));
        } catch (error) {
            console.warn('Error retrieving stored sessions:', error);
            return [];
        }
    }


    async enterDiner() {
        const username = this.elements.usernameInput.value.trim();
        if (!username) {
            alert('Please enter your name');
            return;
        }

        if (username.length > 20) {
            alert('Name must be 20 characters or less');
            return;
        }

        // Add debugging
        console.log('Attempting to enter diner with username:', username);
        console.log('WebSocket client available:', window.wsClient ? 'yes' : 'no');
        console.log('Pending session ID:', this.pendingSessionId);

        try {
            if (this.pendingSessionId) {
                // Use the session ID as the user ID
                window.wsClient.userId = this.pendingSessionId;
                window.wsClient.username = username;
                
                // Store the session data
                this.storeUserDataForSession(this.pendingSessionId, username);
                
                // Connect with existing session method
                window.wsClient.connectWithExistingSession(this.pendingSessionId, username);
                
                console.log('Connected with session ID:', this.pendingSessionId);
            } else {
                // Generate new session for fresh user
                console.log('No session ID available, generating new session...');
                await this.createNewSessionAndConnect(username);
            }
            
            this.isLoggedIn = true;
            this.showMainInterface();
            console.log('Diner entry successful');
        } catch (error) {
            console.error('Error entering diner:', error);
            alert('Failed to connect to diner. Please check if server is running.');
        }
    }

    showMainInterface(showWelcomeMessage = true) {
        this.elements.welcomeScreen.classList.add('hidden');
        this.elements.chatInterface.classList.remove('hidden');
        this.elements.seatControls.classList.remove('hidden');
        this.elements.statusDisplay.classList.remove('hidden');
        this.elements.ttsControls.classList.remove('hidden');
        // Don't show the HTML master status label anymore - use Three.js sprite instead
        // this.elements.masterStatusLabel.classList.remove('hidden');
        
        if (showWelcomeMessage) {
            this.addSystemMessage('Welcome to Meshiya! Take a seat and enjoy your stay.');
        }
        
        // Initialize TTS toggle button state
        this.updateTTSToggleButton();
    }


    sendMessage() {
        const message = this.elements.chatInput.value.trim();
        if (!message) return;

        if (!window.wsClient.isConnected()) {
            this.addSystemMessage('Not connected to server. Please wait...');
            return;
        }

        window.wsClient.sendMessage(message);
        this.elements.chatInput.value = '';
    }

    requestSeat(seatNumber) {
        if (!window.wsClient.isConnected()) {
            this.addSystemMessage('Not connected to server');
            return;
        }

        if (this.occupiedSeats.has(seatNumber)) {
            this.addSystemMessage(`Seat ${seatNumber} is already occupied`);
            return;
        }

        window.wsClient.joinSeat(seatNumber);
    }

    leaveSeat() {
        if (!window.wsClient.isConnected()) {
            this.addSystemMessage('Not connected to server');
            return;
        }

        if (!this.currentSeat) {
            this.addSystemMessage('You are not seated');
            return;
        }

        window.wsClient.leaveSeat();
    }

    // Helper method to trigger user image state changes
    triggerUserImageState(userName, userId, imageType, duration = 3000) {
        if (!userName || !window.dinerScene) return;
        
        // Find which seat the user is in
        const seatStates = window.dinerScene.getSeatStates();
        let userSeat = null;
        
        for (const [seatNumber, seatData] of seatStates) {
            if (seatData.userId === userId) {
                userSeat = seatNumber;
                break;
            }
        }
        
        if (userSeat) {
            console.log(`🎭 Changing ${userName} to ${imageType} state for ${duration}ms`);
            
            // Change to the specified state
            window.dinerScene.updateUserImageState(userSeat, imageType);
            
            // Return to normal state after duration
            setTimeout(() => {
                window.dinerScene.updateUserImageState(userSeat, 'normal');
                console.log(`🎭 ${userName} returned to normal state`);
            }, duration);
        }
    }
    
    // Message handling
    handleMessage(message) {
        console.log('UI Manager handling message:', message);
        console.log('Message timestamp:', message.timestamp, 'Type:', typeof message.timestamp);
        switch (message.type) {
            case 'CHAT_MESSAGE':
                this.addChatMessage(message.userName, message.content, message.timestamp);
                // Record user activity for idle tracking
                if (window.userStatusManager && message.userId) {
                    window.userStatusManager.recordUserActivity(message.userId);
                }
                // Trigger chatting image state for the user who sent the message
                this.triggerUserImageState(message.userName, message.userId, 'chatting');
                break;
                
            case 'SYSTEM_MESSAGE':
                this.addSystemMessage(message.content, message.timestamp);
                break;
                
            case 'AI_MESSAGE':
                this.addAIMessage(message.content, message.timestamp);
                break;
                
            default:
                this.addSystemMessage(message.content || 'Unknown message type', message.timestamp);
        }
    }

    handleSeatUpdate(message) {
        const seatNumber = message.seatId;
        const isCurrentUser = message.userId === window.wsClient.userId;
        
        if (message.type === 'JOIN_SEAT') {
            this.occupiedSeats.add(seatNumber);
            
            if (isCurrentUser) {
                this.currentSeat = seatNumber;
                this.updateSeatDisplay();
                this.elements.leaveSeatBtn.classList.remove('hidden');
            }
            
            this.updateSeatButton(seatNumber, true, isCurrentUser);
            this.addSystemMessage(message.content);
            
        } else if (message.type === 'LEAVE_SEAT') {
            this.occupiedSeats.delete(seatNumber);
            
            if (isCurrentUser) {
                this.currentSeat = null;
                this.updateSeatDisplay();
                this.elements.leaveSeatBtn.classList.add('hidden');
            }
            
            this.updateSeatButton(seatNumber, false, false);
            this.addSystemMessage(message.content);
            
        } else if (message.type === 'SEAT_STATE') {
            // Handle initial seat state loading
            if (message.occupied) {
                this.occupiedSeats.add(seatNumber);
                this.updateSeatButton(seatNumber, true, isCurrentUser);
                if (isCurrentUser) {
                    this.currentSeat = seatNumber;
                    this.updateSeatDisplay();
                    this.elements.leaveSeatBtn.classList.remove('hidden');
                }
            } else {
                this.occupiedSeats.delete(seatNumber);
                this.updateSeatButton(seatNumber, false, false);
            }
        }
    }

    handleConnectionChange(status) {
        const statusEl = this.elements.connectionStatus;
        
        switch (status) {
            case 'connected':
                statusEl.textContent = 'Connected';
                statusEl.classList.remove('disconnected');
                break;
                
            case 'error':
                statusEl.textContent = 'Connection Error - Retrying...';
                statusEl.classList.add('disconnected');
                break;
                
            default:
                statusEl.textContent = 'Connecting...';
                statusEl.classList.remove('disconnected');
        }
    }

    updateSeatDisplay() {
        const seatText = this.currentSeat ? `Seated at: ${this.currentSeat}` : 'Not seated';
        this.elements.currentSeat.textContent = seatText;
    }

    updateSeatButton(seatNumber, occupied, isCurrentUser) {
        const button = document.querySelector(`[data-seat="${seatNumber}"]`);
        if (!button) return;

        // Reset classes
        button.classList.remove('occupied', 'current');
        
        if (isCurrentUser) {
            button.classList.add('current');
            button.textContent = `Seat ${seatNumber} (You)`;
        } else if (occupied) {
            button.classList.add('occupied');
            button.textContent = `Seat ${seatNumber} (Occupied)`;
        } else {
            button.textContent = `Seat ${seatNumber}`;
        }
    }

    // Message display methods
    addChatMessage(sender, content, timestamp) {
        const messageEl = document.createElement('div');
        messageEl.className = 'message user';
        
        const timestampStr = this.formatTimestamp(timestamp);
        messageEl.innerHTML = `
            <div class="message-header">
                <span class="message-sender">${sender}:</span>
                <span class="message-timestamp">${timestampStr}</span>
            </div>
            <div class="message-content">${this.escapeHtml(content)}</div>
        `;
        
        this.appendMessage(messageEl, timestamp);
    }

    addSystemMessage(content, timestamp) {
        const messageEl = document.createElement('div');
        messageEl.className = 'message system';
        
        const timestampStr = this.formatTimestamp(timestamp);
        messageEl.innerHTML = `
            <span class="system-content">${this.escapeHtml(content)}</span>
            <span class="message-timestamp">${timestampStr}</span>
        `;
        
        this.appendMessage(messageEl, timestamp);
    }

    addAIMessage(content, timestamp) {
        const messageEl = document.createElement('div');
        messageEl.className = 'message ai';
        
        const timestampStr = this.formatTimestamp(timestamp);
        messageEl.innerHTML = `
            <div class="message-header">
                <span class="message-sender">Master:</span>
                <span class="message-timestamp">${timestampStr}</span>
                <span class="tts-indicator" style="display: none;">🔊</span>
            </div>
            <div class="message-content">${this.escapeHtml(content)}</div>
        `;
        
        this.appendMessage(messageEl, timestamp);
        
        // Also trigger chef speech bubble animation
        if (window.meshiya && window.meshiya.dinerScene) {
            window.meshiya.dinerScene.showChefSpeechBubble(content);
        }
        
        // Convert Master's response to speech
        this.speakMasterResponse(content, messageEl);
    }

    appendMessage(messageEl, timestamp) {
        // Store timestamp in ISO format for consistent sorting
        let normalizedTimestamp;
        if (timestamp) {
            try {
                const date = this.parseTimestampToDate(timestamp);
                normalizedTimestamp = date.toISOString();
            } catch (error) {
                console.warn('Error normalizing timestamp:', timestamp, error);
                normalizedTimestamp = new Date().toISOString();
            }
        } else {
            normalizedTimestamp = new Date().toISOString();
        }
        
        messageEl.dataset.timestamp = normalizedTimestamp;
        
        // Insert message in chronological order
        const messages = Array.from(this.elements.chatMessages.children);
        const insertIndex = this.findInsertPosition(messages, normalizedTimestamp);
        
        if (insertIndex === messages.length) {
            this.elements.chatMessages.appendChild(messageEl);
        } else {
            this.elements.chatMessages.insertBefore(messageEl, messages[insertIndex]);
        }
        
        // Only scroll to bottom if we're inserting at the end (newest message)
        if (insertIndex === messages.length) {
            this.elements.chatMessages.scrollTop = this.elements.chatMessages.scrollHeight;
        }
        
        // Limit message history (keep last 100 messages)
        const updatedMessages = Array.from(this.elements.chatMessages.children);
        if (updatedMessages.length > 100) {
            updatedMessages[0].remove();
        }
    }

    escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }
    
    getServerTimeString() {
        // Get current UTC time and format it consistently with server timezone
        // Since the server appears to be running in UTC/GMT, use UTC time
        const now = new Date();
        const utcTime = new Date(now.getTime() + (now.getTimezoneOffset() * 60000));
        return utcTime.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    }

    formatTimestamp(timestamp) {
        if (!timestamp) {
            // Use server time equivalent - UTC time formatted to match server timezone
            return this.getServerTimeString();
        }
        
        try {
            // Handle different timestamp formats
            let date;
            
            if (typeof timestamp === 'string') {
                // Handle Java LocalDateTime format (e.g., "2024-01-01T15:30:45" or array format)
                if (timestamp.includes('T')) {
                    date = new Date(timestamp);
                } else {
                    // If it's not ISO format, try direct parsing
                    date = new Date(timestamp);
                }
            } else if (Array.isArray(timestamp)) {
                // Handle Java LocalDateTime array format [2024, 1, 1, 15, 30, 45, 123456789]
                // Note: Month is 0-based in JS but 1-based in Java
                // Server timestamps are in local time, so we create local dates
                const [year, month, day, hour, minute, second, nano] = timestamp;
                date = new Date(year, month - 1, day, hour, minute, second, Math.floor(nano / 1000000));
            } else {
                // Assume it's already a Date object or timestamp
                date = new Date(timestamp);
            }
            
            // Check if date is valid
            if (isNaN(date.getTime())) {
                console.warn('Invalid timestamp received:', timestamp);
                return this.getServerTimeString();
            }
            
            return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        } catch (error) {
            console.warn('Error parsing timestamp:', timestamp, error);
            return this.getServerTimeString();
        }
    }
    
    findInsertPosition(messages, newTimestamp) {
        // Convert timestamp to Date object for proper comparison
        const newDate = this.parseTimestampToDate(newTimestamp);
        
        for (let i = 0; i < messages.length; i++) {
            const messageTimestamp = messages[i].dataset.timestamp;
            const messageDate = this.parseTimestampToDate(messageTimestamp);
            
            // If new message is older than current message, insert here
            if (newDate < messageDate) {
                return i;
            }
        }
        return messages.length; // Insert at end (newest)
    }
    
    parseTimestampToDate(timestamp) {
        if (!timestamp) {
            return new Date();
        }
        
        try {
            // Handle different timestamp formats
            if (typeof timestamp === 'string') {
                // If it's already an ISO string, use it directly
                if (timestamp.includes('T')) {
                    return new Date(timestamp);
                } else {
                    // If it's our stored timestamp format, parse it
                    return new Date(timestamp);
                }
            } else if (Array.isArray(timestamp)) {
                // Handle Java LocalDateTime array format
                // Server timestamps are in local time, so we create local dates
                const [year, month, day, hour, minute, second, nano] = timestamp;
                return new Date(year, month - 1, day, hour, minute, second, Math.floor(nano / 1000000));
            } else {
                return new Date(timestamp);
            }
        } catch (error) {
            console.warn('Error parsing timestamp for sorting:', timestamp, error);
            return new Date(); // Fallback to current time
        }
    }

    handleMasterStatusUpdate(message) {
        console.log('UI Manager handling master status update:', message);
        
        if (message.type === 'MASTER_STATUS_UPDATE') {
            this.updateMasterStatus(message.status, message.displayName);
            // Also update the Three.js sprite
            if (window.meshiya && window.meshiya.dinerScene) {
                window.meshiya.dinerScene.updateMasterStatusSprite(message.status, message.displayName);
                
                // Update chef image based on status
                const chefState = this.getChefStateFromStatus(message.status);
                window.meshiya.dinerScene.updateChefImage(chefState);
            }
        }
    }

    getChefStateFromStatus(status) {
        // Map master status to chef image states
        switch (status?.toLowerCase()) {
            case 'thinking':
                return 'thinking';
            case 'preparing_order':
            case 'preparing order':
            case 'busy':
            case 'serving':
                return 'prepare';
            case 'idle':
            case 'conversing':
            case 'cleaning':
            default:
                return 'normal';
        }
    }

    handleOrderNotification(message) {
        console.log('UI Manager handling order notification:', message);
        
        if (message.type === 'FOOD_SERVED') {
            // Show notification that order is ready
            this.addSystemMessage(`🍽️ Your ${message.content} is ready!`);
            
            // Play a notification sound if available
            this.playNotificationSound();
        }
    }

    updateMasterStatus(status, displayName) {
        if (!this.elements.masterStatusLabel || !this.elements.masterStatusText) {
            return;
        }

        // Remove all status classes
        const statusClasses = ['status-idle', 'status-thinking', 'status-preparing_order', 
                              'status-serving', 'status-busy', 'status-cleaning', 'status-conversing'];
        statusClasses.forEach(cls => this.elements.masterStatusLabel.classList.remove(cls));

        // Add new status class
        this.elements.masterStatusLabel.classList.add(`status-${status.toLowerCase()}`);
        
        // Update text
        this.elements.masterStatusText.textContent = displayName || status.replace('_', ' ');
    }

    playNotificationSound() {
        try {
            // Create a simple notification beep
            const audioContext = new (window.AudioContext || window.webkitAudioContext)();
            const oscillator = audioContext.createOscillator();
            const gainNode = audioContext.createGain();
            
            oscillator.connect(gainNode);
            gainNode.connect(audioContext.destination);
            
            oscillator.frequency.setValueAtTime(800, audioContext.currentTime);
            gainNode.gain.setValueAtTime(0.1, audioContext.currentTime);
            gainNode.gain.exponentialRampToValueAtTime(0.01, audioContext.currentTime + 0.3);
            
            oscillator.start(audioContext.currentTime);
            oscillator.stop(audioContext.currentTime + 0.3);
        } catch (error) {
            console.log('Could not play notification sound:', error);
        }
    }

    // Public methods for external access
    isUserLoggedIn() {
        return this.isLoggedIn;
    }

    getCurrentSeat() {
        return this.currentSeat;
    }

    getOccupiedSeats() {
        return new Set(this.occupiedSeats);
    }

    /**
     * Generate message key for TTS caching (matches server-side MD5 logic)
     * @param {string} text - The message text
     * @param {string} voice - The voice to use
     * @returns {string} - MD5 hash of text:voice
     */
    generateMessageKey(text, voice = 'am_michael') {
        const input = text.trim() + ':' + voice;
        return this.md5(input);
    }

    /**
     * MD5 hash implementation in JavaScript
     * @param {string} string - Input string to hash
     * @returns {string} - MD5 hash as hex string
     */
    md5(string) {
        function rotateLeft(lValue, iShiftBits) {
            return (lValue << iShiftBits) | (lValue >>> (32 - iShiftBits));
        }

        function addUnsigned(lX, lY) {
            var lX4, lY4, lX8, lY8, lResult;
            lX8 = (lX & 0x80000000);
            lY8 = (lY & 0x80000000);
            lX4 = (lX & 0x40000000);
            lY4 = (lY & 0x40000000);
            lResult = (lX & 0x3FFFFFFF) + (lY & 0x3FFFFFFF);
            if (lX4 & lY4) {
                return (lResult ^ 0x80000000 ^ lX8 ^ lY8);
            }
            if (lX4 | lY4) {
                if (lResult & 0x40000000) {
                    return (lResult ^ 0xC0000000 ^ lX8 ^ lY8);
                } else {
                    return (lResult ^ 0x40000000 ^ lX8 ^ lY8);
                }
            } else {
                return (lResult ^ lX8 ^ lY8);
            }
        }

        function F(x, y, z) { return (x & y) | ((~x) & z); }
        function G(x, y, z) { return (x & z) | (y & (~z)); }
        function H(x, y, z) { return (x ^ y ^ z); }
        function I(x, y, z) { return (y ^ (x | (~z))); }

        function FF(a, b, c, d, x, s, ac) {
            a = addUnsigned(a, addUnsigned(addUnsigned(F(b, c, d), x), ac));
            return addUnsigned(rotateLeft(a, s), b);
        }

        function GG(a, b, c, d, x, s, ac) {
            a = addUnsigned(a, addUnsigned(addUnsigned(G(b, c, d), x), ac));
            return addUnsigned(rotateLeft(a, s), b);
        }

        function HH(a, b, c, d, x, s, ac) {
            a = addUnsigned(a, addUnsigned(addUnsigned(H(b, c, d), x), ac));
            return addUnsigned(rotateLeft(a, s), b);
        }

        function II(a, b, c, d, x, s, ac) {
            a = addUnsigned(a, addUnsigned(addUnsigned(I(b, c, d), x), ac));
            return addUnsigned(rotateLeft(a, s), b);
        }

        function convertToWordArray(string) {
            var lWordCount;
            var lMessageLength = string.length;
            var lNumberOfWords_temp1 = lMessageLength + 8;
            var lNumberOfWords_temp2 = (lNumberOfWords_temp1 - (lNumberOfWords_temp1 % 64)) / 64;
            var lNumberOfWords = (lNumberOfWords_temp2 + 1) * 16;
            var lWordArray = Array(lNumberOfWords - 1);
            var lBytePosition = 0;
            var lByteCount = 0;
            while (lByteCount < lMessageLength) {
                lWordCount = (lByteCount - (lByteCount % 4)) / 4;
                lBytePosition = (lByteCount % 4) * 8;
                lWordArray[lWordCount] = (lWordArray[lWordCount] | (string.charCodeAt(lByteCount) << lBytePosition));
                lByteCount++;
            }
            lWordCount = (lByteCount - (lByteCount % 4)) / 4;
            lBytePosition = (lByteCount % 4) * 8;
            lWordArray[lWordCount] = lWordArray[lWordCount] | (0x80 << lBytePosition);
            lWordArray[lNumberOfWords - 2] = lMessageLength << 3;
            lWordArray[lNumberOfWords - 1] = lMessageLength >>> 29;
            return lWordArray;
        }

        function wordToHex(lValue) {
            var wordToHexValue = "", wordToHexValue_temp = "", lByte, lCount;
            for (lCount = 0; lCount <= 3; lCount++) {
                lByte = (lValue >>> (lCount * 8)) & 255;
                wordToHexValue_temp = "0" + lByte.toString(16);
                wordToHexValue = wordToHexValue + wordToHexValue_temp.substr(wordToHexValue_temp.length - 2, 2);
            }
            return wordToHexValue;
        }

        var x = Array();
        var k, AA, BB, CC, DD, a, b, c, d;
        var S11 = 7, S12 = 12, S13 = 17, S14 = 22;
        var S21 = 5, S22 = 9, S23 = 14, S24 = 20;
        var S31 = 4, S32 = 11, S33 = 16, S34 = 23;
        var S41 = 6, S42 = 10, S43 = 15, S44 = 21;

        string = this.utf8Encode(string);
        x = convertToWordArray(string);
        a = 0x67452301; b = 0xEFCDAB89; c = 0x98BADCFE; d = 0x10325476;

        for (k = 0; k < x.length; k += 16) {
            AA = a; BB = b; CC = c; DD = d;
            a = FF(a, b, c, d, x[k + 0], S11, 0xD76AA478);
            d = FF(d, a, b, c, x[k + 1], S12, 0xE8C7B756);
            c = FF(c, d, a, b, x[k + 2], S13, 0x242070DB);
            b = FF(b, c, d, a, x[k + 3], S14, 0xC1BDCEEE);
            a = FF(a, b, c, d, x[k + 4], S11, 0xF57C0FAF);
            d = FF(d, a, b, c, x[k + 5], S12, 0x4787C62A);
            c = FF(c, d, a, b, x[k + 6], S13, 0xA8304613);
            b = FF(b, c, d, a, x[k + 7], S14, 0xFD469501);
            a = FF(a, b, c, d, x[k + 8], S11, 0x698098D8);
            d = FF(d, a, b, c, x[k + 9], S12, 0x8B44F7AF);
            c = FF(c, d, a, b, x[k + 10], S13, 0xFFFF5BB1);
            b = FF(b, c, d, a, x[k + 11], S14, 0x895CD7BE);
            a = FF(a, b, c, d, x[k + 12], S11, 0x6B901122);
            d = FF(d, a, b, c, x[k + 13], S12, 0xFD987193);
            c = FF(c, d, a, b, x[k + 14], S13, 0xA679438E);
            b = FF(b, c, d, a, x[k + 15], S14, 0x49B40821);
            a = GG(a, b, c, d, x[k + 1], S21, 0xF61E2562);
            d = GG(d, a, b, c, x[k + 6], S22, 0xC040B340);
            c = GG(c, d, a, b, x[k + 11], S23, 0x265E5A51);
            b = GG(b, c, d, a, x[k + 0], S24, 0xE9B6C7AA);
            a = GG(a, b, c, d, x[k + 5], S21, 0xD62F105D);
            d = GG(d, a, b, c, x[k + 10], S22, 0x2441453);
            c = GG(c, d, a, b, x[k + 15], S23, 0xD8A1E681);
            b = GG(b, c, d, a, x[k + 4], S24, 0xE7D3FBC8);
            a = GG(a, b, c, d, x[k + 9], S21, 0x21E1CDE6);
            d = GG(d, a, b, c, x[k + 14], S22, 0xC33707D6);
            c = GG(c, d, a, b, x[k + 3], S23, 0xF4D50D87);
            b = GG(b, c, d, a, x[k + 8], S24, 0x455A14ED);
            a = GG(a, b, c, d, x[k + 13], S21, 0xA9E3E905);
            d = GG(d, a, b, c, x[k + 2], S22, 0xFCEFA3F8);
            c = GG(c, d, a, b, x[k + 7], S23, 0x676F02D9);
            b = GG(b, c, d, a, x[k + 12], S24, 0x8D2A4C8A);
            a = HH(a, b, c, d, x[k + 5], S31, 0xFFFA3942);
            d = HH(d, a, b, c, x[k + 8], S32, 0x8771F681);
            c = HH(c, d, a, b, x[k + 11], S33, 0x6D9D6122);
            b = HH(b, c, d, a, x[k + 14], S34, 0xFDE5380C);
            a = HH(a, b, c, d, x[k + 1], S31, 0xA4BEEA44);
            d = HH(d, a, b, c, x[k + 4], S32, 0x4BDECFA9);
            c = HH(c, d, a, b, x[k + 7], S33, 0xF6BB4B60);
            b = HH(b, c, d, a, x[k + 10], S34, 0xBEBFBC70);
            a = HH(a, b, c, d, x[k + 13], S31, 0x289B7EC6);
            d = HH(d, a, b, c, x[k + 0], S32, 0xEAA127FA);
            c = HH(c, d, a, b, x[k + 3], S33, 0xD4EF3085);
            b = HH(b, c, d, a, x[k + 6], S34, 0x4881D05);
            a = HH(a, b, c, d, x[k + 9], S31, 0xD9D4D039);
            d = HH(d, a, b, c, x[k + 12], S32, 0xE6DB99E5);
            c = HH(c, d, a, b, x[k + 15], S33, 0x1FA27CF8);
            b = HH(b, c, d, a, x[k + 2], S34, 0xC4AC5665);
            a = II(a, b, c, d, x[k + 0], S41, 0xF4292244);
            d = II(d, a, b, c, x[k + 7], S42, 0x432AFF97);
            c = II(c, d, a, b, x[k + 14], S43, 0xAB9423A7);
            b = II(b, c, d, a, x[k + 5], S44, 0xFC93A039);
            a = II(a, b, c, d, x[k + 12], S41, 0x655B59C3);
            d = II(d, a, b, c, x[k + 3], S42, 0x8F0CCC92);
            c = II(c, d, a, b, x[k + 10], S43, 0xFFEFF47D);
            b = II(b, c, d, a, x[k + 1], S44, 0x85845DD1);
            a = II(a, b, c, d, x[k + 8], S41, 0x6FA87E4F);
            d = II(d, a, b, c, x[k + 15], S42, 0xFE2CE6E0);
            c = II(c, d, a, b, x[k + 6], S43, 0xA3014314);
            b = II(b, c, d, a, x[k + 13], S44, 0x4E0811A1);
            a = II(a, b, c, d, x[k + 4], S41, 0xF7537E82);
            d = II(d, a, b, c, x[k + 11], S42, 0xBD3AF235);
            c = II(c, d, a, b, x[k + 2], S43, 0x2AD7D2BB);
            b = II(b, c, d, a, x[k + 9], S44, 0xEB86D391);
            a = addUnsigned(a, AA);
            b = addUnsigned(b, BB);
            c = addUnsigned(c, CC);
            d = addUnsigned(d, DD);
        }

        return (wordToHex(a) + wordToHex(b) + wordToHex(c) + wordToHex(d)).toLowerCase();
    }

    /**
     * UTF-8 encode string
     */
    utf8Encode(string) {
        string = string.replace(/\r\n/g, "\n");
        var utftext = "";
        for (var n = 0; n < string.length; n++) {
            var c = string.charCodeAt(n);
            if (c < 128) {
                utftext += String.fromCharCode(c);
            } else if ((c > 127) && (c < 2048)) {
                utftext += String.fromCharCode((c >> 6) | 192);
                utftext += String.fromCharCode((c & 63) | 128);
            } else {
                utftext += String.fromCharCode((c >> 12) | 224);
                utftext += String.fromCharCode(((c >> 6) & 63) | 128);
                utftext += String.fromCharCode((c & 63) | 128);
            }
        }
        return utftext;
    }

    /**
     * Convert Master's response to speech using server-side TTS
     * @param {string} content - The Master's message content
     * @param {Element} messageEl - The message element for visual indicators
     */
    async speakMasterResponse(content, messageEl) {
        if (!this.ttsService || !this.ttsService.isAvailable()) {
            return;
        }

        // Store client-side arrival time to avoid timezone issues
        const clientArrivalTime = Date.now();
        messageEl.setAttribute('data-client-arrival', clientArrivalTime);
        
        // Check if this is an old message based on client arrival time
        const arrivalTime = parseInt(messageEl.getAttribute('data-client-arrival'));
        if (arrivalTime) {
            const ageInMinutes = (Date.now() - arrivalTime) / (1000 * 60);
            
            if (ageInMinutes > 5) {
                console.log(`🔊 TTS: Skipping old message (${ageInMinutes.toFixed(1)} minutes old): ${content.substring(0, 50)}...`);
                return;
            }
        }

        try {
            console.log(`🔊 TTS: Preparing TTS for message: ${content.substring(0, 50)}...`);
            
            // Check if there's already a ready TTS waiting
            if (this.readyTTSMessages.size > 0) {
                // Use the first available TTS (FIFO approach)
                const firstEntry = this.readyTTSMessages.entries().next().value;
                if (firstEntry) {
                    const [messageKey, ttsData] = firstEntry;
                    console.log(`🔊 TTS: Using available TTS for messageKey: ${messageKey}`);
                    this.readyTTSMessages.delete(messageKey);
                    await this.playTTSAudio(messageEl, messageKey, ttsData.audioUrl);
                    return;
                }
            }
            
            // Show loading indicator - TTS will be matched when it arrives
            const ttsIndicator = messageEl.querySelector('.tts-indicator');
            if (ttsIndicator) {
                ttsIndicator.style.display = 'inline';
                ttsIndicator.textContent = '🔄'; // Loading indicator
            }
            
            console.log(`🔊 TTS: Waiting for server TTS generation to complete...`);

        } catch (error) {
            console.error('Failed to prepare TTS for Master response:', error);
            
            // Hide indicator on error
            const ttsIndicator = messageEl.querySelector('.tts-indicator');
            if (ttsIndicator) {
                ttsIndicator.style.display = 'none';
            }
        }
    }

    /**
     * Handle TTS ready notification from server
     * @param {Object} message - TTS ready message with messageKey and audioUrl
     */
    async handleTTSReady(message) {
        console.log('🔊 TTS: Server-side TTS ready:', message);
        
        const { messageKey, audioUrl, roomId } = message;
        
        // Simple approach: find the most recent AI message that doesn't have TTS yet
        const aiMessages = Array.from(this.elements.chatMessages.querySelectorAll('.message.ai'));
        let targetMessage = null;
        
        // Look for the most recent AI message that has a loading TTS indicator
        for (let i = aiMessages.length - 1; i >= 0; i--) {
            const msg = aiMessages[i];
            const indicator = msg.querySelector('.tts-indicator');
            if (indicator && indicator.textContent === '🔄') {
                targetMessage = msg;
                break;
            }
        }
        
        if (targetMessage) {
            console.log(`🔊 TTS: Found target message for TTS key: ${messageKey}`);
            await this.playTTSAudio(targetMessage, messageKey, audioUrl);
        } else {
            console.log(`🔊 TTS: No target message found for TTS key: ${messageKey}`);
            // Store for later - maybe the message will arrive soon
            this.readyTTSMessages.set(messageKey, {
                audioUrl,
                timestamp: Date.now()
            });
        }
    }

    /**
     * Play TTS audio for a message
     * @param {Element} messageEl - The message element for visual indicators
     * @param {string} messageKey - The message key for tracking
     * @param {string} audioUrl - The audio URL to play
     */
    async playTTSAudio(messageEl, messageKey, audioUrl) {
        try {
            // Update indicator to show queued
            const ttsIndicator = messageEl.querySelector('.tts-indicator');
            if (ttsIndicator) {
                ttsIndicator.style.display = 'inline';
                ttsIndicator.textContent = '⏳'; // Queued indicator
            }
            
            console.log(`🔊 TTS: Adding server-generated audio to queue for messageKey: ${messageKey}`);
            
            // Add to TTS service queue instead of playing directly
            if (this.ttsService) {
                await this.ttsService.addServerAudioToQueue(messageKey, audioUrl, ttsIndicator, () => {
                    // Cleanup callback when audio finishes
                    this.pendingTTSMessages.delete(messageKey);
                });
            } else {
                console.warn('🔊 TTS: TTS service not available, playing directly');
                // Fallback to direct play if TTS service unavailable
                const audio = new Audio(audioUrl);
                
                audio.onended = () => {
                    if (ttsIndicator) {
                        ttsIndicator.style.display = 'none';
                    }
                    this.pendingTTSMessages.delete(messageKey);
                };
                
                audio.onerror = () => {
                    if (ttsIndicator) {
                        ttsIndicator.style.display = 'none';
                    }
                    this.pendingTTSMessages.delete(messageKey);
                };
                
                await audio.play();
            }
            
        } catch (error) {
            console.error(`🔊 TTS: Failed to play audio for messageKey: ${messageKey}`, error);
            
            // Hide indicator on error
            const ttsIndicator = messageEl.querySelector('.tts-indicator');
            if (ttsIndicator) {
                ttsIndicator.style.display = 'none';
            }
            
            // Remove from pending messages if it was there
            this.pendingTTSMessages.delete(messageKey);
        }
    }

    /**
     * Clean up old ready TTS messages to prevent memory leaks
     * Called periodically to remove expired entries
     */
    cleanupReadyTTSMessages() {
        const now = Date.now();
        const maxAge = 5 * 60 * 1000; // 5 minutes in milliseconds
        
        for (const [messageKey, readyTTS] of this.readyTTSMessages.entries()) {
            if (now - readyTTS.timestamp > maxAge) {
                console.log(`🔊 TTS: Cleaning up expired ready TTS for messageKey: ${messageKey}`);
                this.readyTTSMessages.delete(messageKey);
            }
        }
    }

    /**
     * Toggle TTS on/off
     * @param {boolean} enabled - Whether TTS should be enabled
     */
    setTTSEnabled(enabled) {
        if (this.ttsService) {
            this.ttsService.setEnabled(enabled);
        }
    }

    /**
     * Get current TTS status
     * @returns {boolean} - Whether TTS is enabled
     */
    isTTSEnabled() {
        return this.ttsService ? this.ttsService.isAvailable() : false;
    }

    /**
     * Stop any currently playing TTS audio
     */
    stopTTS() {
        if (this.ttsService) {
            this.ttsService.stop();
        }
    }

    /**
     * Test TTS functionality
     */
    testTTS() {
        if (this.ttsService) {
            this.ttsService.test();
        }
    }

    /**
     * Toggle TTS on/off
     */
    toggleTTS() {
        if (!this.ttsService) return;
        
        const currentState = this.ttsService.isAvailable();
        this.ttsService.setEnabled(!currentState);
        this.updateTTSToggleButton();
        
        if (!currentState) {
            this.addSystemMessage('Master voice enabled 🔊');
        } else {
            this.addSystemMessage('Master voice muted 🔇');
        }
    }

    /**
     * Update the TTS toggle button appearance
     */
    updateTTSToggleButton() {
        if (!this.elements.ttsToggleBtn || !this.ttsService) return;
        
        const isEnabled = this.ttsService.isAvailable();
        this.elements.ttsToggleBtn.textContent = isEnabled ? '🔇 Mute' : '🔊 Enable';
        this.elements.ttsToggleBtn.classList.toggle('tts-enabled', isEnabled);
        this.elements.ttsToggleBtn.classList.toggle('tts-disabled', !isEnabled);
    }

    /**
     * Skip current TTS audio
     */
    skipCurrentTTS() {
        if (this.ttsService) {
            this.ttsService.skipCurrent();
        }
    }

    /**
     * Clear TTS queue
     */
    clearTTSQueue() {
        if (this.ttsService) {
            this.ttsService.stop(); // This clears queue and stops current
            this.addSystemMessage('🔇 TTS queue cleared');
        }
    }

    /**
     * Handle TTS queue updates
     */
    handleTTSQueueUpdate(status) {
        // Update queue status display
        if (this.elements.ttsQueueStatus) {
            this.elements.ttsQueueStatus.textContent = `Queue: ${status.queueLength}`;
        }

        // Show/hide current playing text
        if (this.elements.ttsCurrent && this.elements.ttsCurrentText) {
            if (status.isPlaying && status.currentItem) {
                this.elements.ttsCurrentText.textContent = status.currentItem;
                this.elements.ttsCurrent.classList.remove('hidden');
            } else {
                this.elements.ttsCurrent.classList.add('hidden');
            }
        }

        // Enable/disable controls based on queue state
        if (this.elements.ttsSkipBtn) {
            this.elements.ttsSkipBtn.disabled = !status.isPlaying;
        }
        if (this.elements.ttsClearBtn) {
            this.elements.ttsClearBtn.disabled = status.queueLength === 0 && !status.isPlaying;
        }
    }

}