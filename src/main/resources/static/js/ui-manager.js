class UIManager {
    constructor() {
        this.elements = {};
        this.isLoggedIn = false;
        this.currentSeat = null;
        this.occupiedSeats = new Set();
        
        // Initialize TTS service
        this.ttsService = new TTSService();
        
        this.initializeElements();
        this.attachEventListeners();
        // Don't check for existing user here - wait for initialization to complete
    }
    
    /**
     * Initialize after WebSocket client is ready
     */
    initialize() {
        this.checkForExistingUser();
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
    checkForExistingUser() {
        try {
            // Check for sessionId in URL first
            const urlParams = new URLSearchParams(window.location.search);
            const sessionId = urlParams.get('sessionId');
            
            if (sessionId) {
                // Decode Base64 session ID
                try {
                    const decodedId = atob(sessionId);
                    console.log('Found session ID in URL:', decodedId);
                    
                    // Try to get username from localStorage based on sessionId
                    const userData = this.getUserDataForSession(decodedId);
                    
                    if (userData && userData.username) {
                        // User exists with this session - auto-connect
                        window.wsClient.userId = decodedId;
                        window.wsClient.username = userData.username;
                        
                        window.wsClient.connectWithExistingSession(decodedId, userData.username);
                        
                        this.isLoggedIn = true;
                        this.showMainInterface();
                        this.addSystemMessage(`Welcome back, ${userData.username}! Session restored.`);
                    } else {
                        // New session - prepare for username entry
                        this.prepareForNewSession(decodedId);
                    }
                    
                    // Keep the sessionId in URL (don't clean it)
                    // window.history.replaceState({}, document.title, window.location.pathname);
                    return;
                } catch (e) {
                    console.warn('Invalid session ID in URL');
                }
            }
            
            // Should not happen with new redirect logic, but fallback
            console.log('No sessionId found - this should not happen with new redirect logic');
            // Don't redirect - just log the issue
            
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


    enterDiner() {
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
                // Fallback - should not happen with new flow
                window.wsClient.connect(username);
                console.log('Connected with generated ID (fallback)');
            }
            
            this.isLoggedIn = true;
            this.showMainInterface();
            console.log('Diner entry successful');
        } catch (error) {
            console.error('Error entering diner:', error);
            alert('Failed to connect to diner. Please check if server is running.');
        }
    }

    showMainInterface() {
        this.elements.welcomeScreen.classList.add('hidden');
        this.elements.chatInterface.classList.remove('hidden');
        this.elements.seatControls.classList.remove('hidden');
        this.elements.statusDisplay.classList.remove('hidden');
        this.elements.ttsControls.classList.remove('hidden');
        // Don't show the HTML master status label anymore - use Three.js sprite instead
        // this.elements.masterStatusLabel.classList.remove('hidden');
        
        this.addSystemMessage('Welcome to Meshiya! Take a seat and enjoy your stay.');
        
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
    
    formatTimestamp(timestamp) {
        if (!timestamp) {
            return new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
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
                const [year, month, day, hour, minute, second, nano] = timestamp;
                date = new Date(year, month - 1, day, hour, minute, second, Math.floor(nano / 1000000));
            } else {
                // Assume it's already a Date object or timestamp
                date = new Date(timestamp);
            }
            
            // Check if date is valid
            if (isNaN(date.getTime())) {
                console.warn('Invalid timestamp received:', timestamp);
                return new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
            }
            
            return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        } catch (error) {
            console.warn('Error parsing timestamp:', timestamp, error);
            return new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
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
     * Convert Master's response to speech
     * @param {string} content - The Master's message content
     * @param {Element} messageEl - The message element for visual indicators
     */
    async speakMasterResponse(content, messageEl) {
        if (!this.ttsService || !this.ttsService.isAvailable()) {
            return;
        }

        try {
            // Find the TTS indicator in the message element
            const ttsIndicator = messageEl.querySelector('.tts-indicator');
            
            // Show loading/playing indicator
            if (ttsIndicator) {
                ttsIndicator.style.display = 'inline';
                ttsIndicator.textContent = '🔄'; // Loading indicator
            }

            // Speak the Master's response
            await this.ttsService.speak(content);

            // Hide indicator when done
            if (ttsIndicator) {
                ttsIndicator.style.display = 'none';
            }

        } catch (error) {
            console.error('Failed to speak Master response:', error);
            
            // Hide indicator on error
            const ttsIndicator = messageEl.querySelector('.tts-indicator');
            if (ttsIndicator) {
                ttsIndicator.style.display = 'none';
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