// User Profile Manager for the diner scene
class UserProfileManager {
    constructor() {
        this.isOpen = false;
        this.init();
    }

    init() {
        console.log('Initializing User Profile Manager...');
        this.setupEventListeners();
    }

    setupEventListeners() {
        // Profile button in status display
        const profileBtn = document.getElementById('user-profile-btn');
        if (profileBtn) {
            profileBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                this.toggleProfilePanel();
            });
        }

        // Close button in profile panel
        const closeBtn = document.getElementById('close-profile-btn');
        if (closeBtn) {
            closeBtn.addEventListener('click', () => {
                this.closeProfilePanel();
            });
        }

        // Logout button in profile panel
        const profileLogoutBtn = document.getElementById('profile-logout-btn');
        if (profileLogoutBtn) {
            profileLogoutBtn.addEventListener('click', () => {
                this.handleLogout();
            });
        }

        // Login button for guests in profile panel
        const guestLoginBtn = document.getElementById('guest-login-btn');
        if (guestLoginBtn) {
            guestLoginBtn.addEventListener('click', () => {
                this.handleGuestLogin();
            });
        }

        // Close panel when clicking outside
        document.addEventListener('click', (e) => {
            const panel = document.getElementById('user-profile-panel');
            if (this.isOpen && panel && !panel.contains(e.target) && !e.target.matches('#user-profile-btn')) {
                this.closeProfilePanel();
            }
        });

        // Close panel with Escape key
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape' && this.isOpen) {
                this.closeProfilePanel();
            }
        });
    }

    toggleProfilePanel() {
        if (this.isOpen) {
            this.closeProfilePanel();
        } else {
            this.openProfilePanel();
        }
    }

    openProfilePanel() {
        const panel = document.getElementById('user-profile-panel');
        if (!panel) return;

        // Update panel content based on user status
        this.updateProfileContent();

        // Show panel
        panel.classList.remove('hidden');
        this.isOpen = true;

        console.log('User profile panel opened');
    }

    closeProfilePanel() {
        const panel = document.getElementById('user-profile-panel');
        if (!panel) return;

        panel.classList.add('hidden');
        this.isOpen = false;

        console.log('User profile panel closed');
    }

    updateProfileContent() {
        const registeredProfile = document.getElementById('registered-user-profile');
        const guestProfile = document.getElementById('guest-user-profile');
        const profileUsername = document.getElementById('profile-username');
        const guestUsername = document.getElementById('guest-username');

        // Check if user is logged in via auth manager
        const isLoggedIn = window.authManager?.isUserLoggedIn() || false;
        const currentUser = window.authManager?.getLoggedInUser();

        if (isLoggedIn && currentUser) {
            // Show registered user profile
            if (registeredProfile) registeredProfile.classList.remove('hidden');
            if (guestProfile) guestProfile.classList.add('hidden');
            
            // Update username
            if (profileUsername) {
                profileUsername.textContent = currentUser.username;
            }

            console.log(`Showing registered user profile for: ${currentUser.username}`);
            
        } else {
            // Show guest profile
            if (registeredProfile) registeredProfile.classList.add('hidden');
            if (guestProfile) guestProfile.classList.remove('hidden');

            // Get current username from WebSocket client or username input
            const currentUsername = this.getCurrentUsername();
            if (guestUsername) {
                guestUsername.textContent = currentUsername || 'Guest User';
            }

            console.log(`Showing guest profile for: ${currentUsername || 'Guest User'}`);
        }
    }

    getCurrentUsername() {
        // Try to get username from WebSocket client
        if (window.wsClient && window.wsClient.username) {
            return window.wsClient.username;
        }

        // Try to get from username input field
        const usernameInput = document.getElementById('username-input');
        if (usernameInput && usernameInput.value.trim()) {
            return usernameInput.value.trim();
        }

        return null;
    }

    async handleLogout() {
        if (!confirm('Are you sure you want to logout?')) {
            return;
        }

        // Close profile panel first
        this.closeProfilePanel();

        // Use existing auth manager logout functionality
        if (window.authManager) {
            await window.authManager.logout();
        } else {
            console.error('Auth manager not available');
        }
    }

    handleGuestLogin() {
        // Close profile panel
        this.closeProfilePanel();

        // Redirect to welcome screen with login form visible
        // First disconnect from chat if connected
        if (window.wsClient && window.wsClient.isConnected()) {
            window.wsClient.disconnect();
        }

        // Show welcome screen and hide chat interface
        const welcomeScreen = document.getElementById('welcome-screen');
        const chatInterface = document.getElementById('chat-interface');
        
        if (welcomeScreen) welcomeScreen.classList.remove('hidden');
        if (chatInterface) chatInterface.classList.add('hidden');

        // Focus on login email field
        setTimeout(() => {
            const loginEmail = document.getElementById('login-email');
            if (loginEmail) {
                loginEmail.focus();
            }
        }, 100);
    }

    // Public methods for external use
    refreshProfileContent() {
        if (this.isOpen) {
            this.updateProfileContent();
        }
    }

    isPanelOpen() {
        return this.isOpen;
    }
}

// Create global instance
window.userProfileManager = new UserProfileManager();