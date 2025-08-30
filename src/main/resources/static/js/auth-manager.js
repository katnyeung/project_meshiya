// Authentication Manager for Meshiya
class AuthManager {
    constructor() {
        this.isLoggedIn = false;
        this.currentUser = null;
        this.init();
    }

    init() {
        console.log('Initializing Auth Manager...');
        this.setupEventListeners();
        this.checkLoginStatus();
    }

    setupEventListeners() {
        // Logout link
        const logoutLink = document.getElementById('logout-link');
        if (logoutLink) {
            logoutLink.addEventListener('click', (e) => {
                e.preventDefault();
                this.logout();
            });
        }

        // Login form submission
        const loginForm = document.getElementById('login-form');
        if (loginForm) {
            loginForm.addEventListener('submit', (e) => {
                e.preventDefault();
                this.handleLogin();
            });
        }
        
        // Enter diner button for logged-in users
        const enterLoggedInBtn = document.getElementById('enter-diner-logged-in-btn');
        if (enterLoggedInBtn) {
            enterLoggedInBtn.addEventListener('click', () => {
                this.enterDinerAsLoggedInUser();
            });
        }
        
        // Check for new user parameter (from registration page)
        const urlParams = new URLSearchParams(window.location.search);
        const newUser = urlParams.get('newUser');
        if (newUser) {
            // Pre-fill login email with new user's username
            const loginEmailInput = document.getElementById('login-email');
            if (loginEmailInput) {
                loginEmailInput.value = newUser;
            }
            // Show success message
            this.showMessage('login-message', 'Account created successfully! Please login below.', 'success');
        }
    }

    async checkLoginStatus() {
        try {
            const response = await fetch('/api/profile');
            const data = await response.json();

            if (data.success && data.isLoggedIn) {
                this.isLoggedIn = true;
                this.currentUser = {
                    username: data.username,
                    isRegistered: data.isRegistered
                };
                this.updateUIForLoggedInUser();
            } else {
                this.isLoggedIn = false;
                this.currentUser = null;
                this.updateUIForGuestUser();
            }

        } catch (error) {
            console.log('Not logged in or error checking status:', error);
            this.isLoggedIn = false;
            this.currentUser = null;
            this.updateUIForGuestUser();
        }
    }

    updateUIForLoggedInUser() {
        const guestEntry = document.getElementById('guest-entry');
        const loginSection = document.getElementById('login-section');
        const loggedInUser = document.getElementById('logged-in-user');
        const currentUsername = document.getElementById('current-username');

        if (guestEntry) guestEntry.classList.add('hidden');
        if (loginSection) loginSection.classList.add('hidden');
        if (loggedInUser) loggedInUser.classList.remove('hidden');
        if (currentUsername) currentUsername.textContent = this.currentUser.username;

        console.log(`Logged in as: ${this.currentUser.username}`);
    }

    updateUIForGuestUser() {
        const guestEntry = document.getElementById('guest-entry');
        const loginSection = document.getElementById('login-section');
        const loggedInUser = document.getElementById('logged-in-user');

        if (guestEntry) guestEntry.classList.remove('hidden');
        if (loginSection) loginSection.classList.remove('hidden');
        if (loggedInUser) loggedInUser.classList.add('hidden');

        console.log('Not logged in - showing guest and login options');
    }

    clearLoginForm() {
        const form = document.getElementById('login-form');
        if (form) {
            form.reset();
        }
    }

    showMessage(elementId, message, type) {
        const messageEl = document.getElementById(elementId);
        if (messageEl) {
            messageEl.textContent = message;
            messageEl.className = `message ${type}`;
            messageEl.classList.remove('hidden');
        }
    }

    hideMessage(elementId) {
        const messageEl = document.getElementById(elementId);
        if (messageEl) {
            messageEl.classList.add('hidden');
        }
    }

    async handleLogin() {
        const email = document.getElementById('login-email').value.trim();
        const password = document.getElementById('login-password').value;
        const submitBtn = document.getElementById('login-btn');

        if (!email || !password) {
            this.showMessage('login-message', 'Please fill in all fields', 'error');
            return;
        }

        // Disable submit button
        submitBtn.disabled = true;
        submitBtn.textContent = 'Logging in...';

        try {
            const response = await fetch('/api/login', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({ username: email, password })
            });

            const result = await response.json();

            if (result.success) {
                this.showMessage('login-message', 'Login successful!', 'success');
                
                // Update auth state
                this.isLoggedIn = true;
                this.currentUser = {
                    username: result.username,
                    isRegistered: true
                };
                
                // Clear form and update UI
                this.clearLoginForm();
                this.updateUIForLoggedInUser();
                
                // Refresh profile panel if open
                if (window.userProfileManager) {
                    window.userProfileManager.refreshProfileContent();
                }

            } else {
                this.showMessage('login-message', result.message, 'error');
            }

        } catch (error) {
            console.error('Login error:', error);
            this.showMessage('login-message', 'Login failed. Please try again.', 'error');
        } finally {
            submitBtn.disabled = false;
            submitBtn.textContent = 'Login';
        }
    }


    async logout() {
        if (!confirm('Are you sure you want to logout?')) {
            return;
        }

        try {
            const response = await fetch('/api/logout', {
                method: 'POST'
            });

            const result = await response.json();

            if (result.success) {
                this.isLoggedIn = false;
                this.currentUser = null;
                this.clearLoginForm();
                this.updateUIForGuestUser();
                
                // Refresh profile panel if open
                if (window.userProfileManager) {
                    window.userProfileManager.refreshProfileContent();
                }
                
                console.log('Logged out successfully');
            } else {
                console.error('Logout failed:', result.message);
            }

        } catch (error) {
            console.error('Logout error:', error);
        }
    }
    
    enterDinerAsLoggedInUser() {
        if (!this.isLoggedIn || !this.currentUser) {
            console.error('User not logged in');
            return;
        }
        
        // Use the existing UI manager to enter the diner with the registered username
        if (window.meshiya && window.meshiya.uiManager) {
            // Set the username in the input field and trigger entry
            const usernameInput = document.getElementById('username-input');
            if (usernameInput) {
                usernameInput.value = this.currentUser.username;
            }
            
            // Call the existing enter diner functionality
            window.meshiya.uiManager.enterDiner();
        }
    }

    // Public API methods
    getLoggedInUser() {
        return this.currentUser;
    }

    isUserLoggedIn() {
        return this.isLoggedIn;
    }
}

// Create global auth manager instance
window.authManager = new AuthManager();