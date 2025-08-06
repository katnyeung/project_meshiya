class UIManager {
    constructor() {
        this.elements = {};
        this.isLoggedIn = false;
        this.currentSeat = null;
        this.occupiedSeats = new Set();
        
        this.initializeElements();
        this.attachEventListeners();
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
            connectionStatus: document.getElementById('connection-status')
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

        try {
            // Connect to WebSocket
            window.wsClient.connect(username);
            
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
        
        this.addSystemMessage('Welcome to Meshiya! Take a seat and enjoy your stay.');
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

    // Message handling
    handleMessage(message) {
        console.log('UI Manager handling message:', message);
        switch (message.type) {
            case 'CHAT_MESSAGE':
                this.addChatMessage(message.userName, message.content);
                break;
                
            case 'SYSTEM_MESSAGE':
                this.addSystemMessage(message.content);
                break;
                
            case 'AI_MESSAGE':
                this.addAIMessage(message.content);
                break;
                
            default:
                this.addSystemMessage(message.content || 'Unknown message type');
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
    addChatMessage(sender, content) {
        const messageEl = document.createElement('div');
        messageEl.className = 'message user';
        messageEl.innerHTML = `<span class="message-sender">${sender}:</span> ${this.escapeHtml(content)}`;
        
        this.appendMessage(messageEl);
    }

    addSystemMessage(content) {
        const messageEl = document.createElement('div');
        messageEl.className = 'message system';
        messageEl.textContent = content;
        
        this.appendMessage(messageEl);
    }

    addAIMessage(content) {
        const messageEl = document.createElement('div');
        messageEl.className = 'message ai';
        messageEl.innerHTML = `<span class="message-sender">Master:</span> ${this.escapeHtml(content)}`;
        
        this.appendMessage(messageEl);
    }

    appendMessage(messageEl) {
        this.elements.chatMessages.appendChild(messageEl);
        this.elements.chatMessages.scrollTop = this.elements.chatMessages.scrollHeight;
        
        // Limit message history (keep last 100 messages)
        const messages = this.elements.chatMessages.children;
        if (messages.length > 100) {
            messages[0].remove();
        }
    }

    escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
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
}