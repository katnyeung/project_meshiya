class DinerScene {
    constructor(container) {
        this.container = container;
        this.scene = null;
        this.camera = null;
        this.renderer = null;
        this.seats = [];
        this.seatStates = new Map(); // Track seat occupancy
        this.sprites = {
            background: null,
            counter: null,
            master: null,
            seats: [],
            customers: [],
            userStatusBoxes: [],
            userImageBoxes: [], // Separate image display boxes
            usernameBoxes: [], // Username display boxes below avatars
            masterStatusLabel: null,
            chefSpeechBubble: null, // Speech bubble above chef's head
            tvDisplay: null // TV sprite for video sharing
        };
        this.raycaster = new THREE.Raycaster();
        this.mouse = new THREE.Vector2();
        
        // Speech bubble animation properties
        this.speechBubble = {
            isAnimating: false,
            currentMessage: '',
            sentences: [],
            currentSentenceIndex: 0,
            currentWordInSentence: 0,
            displayedSentences: [], // Track displayed sentences for 2-line limit
            animationTimer: null
        };
        
        this.init();
    }

    init() {
        // Create Three.js scene
        this.scene = new THREE.Scene();
        this.scene.background = new THREE.Color(0x1a1a2e);

        // Create orthographic camera for 2D feel
        const aspect = window.innerWidth / window.innerHeight;
        const frustumSize = 10;
        this.camera = new THREE.OrthographicCamera(
            frustumSize * aspect / -2,
            frustumSize * aspect / 2,
            frustumSize / 2,
            frustumSize / -2,
            0.1,
            1000
        );
        this.camera.position.set(0, 0, 10);

        // Create renderer
        this.renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
        this.renderer.setSize(window.innerWidth, window.innerHeight);
        this.container.appendChild(this.renderer.domElement);

        // Create layered sprites
        this.createSprites();
        
        // Create seats
        this.createSeats();
        
        // Create UI elements as sprites
        this.createUISprites();
        
        // Add click handler for seat interaction
        this.renderer.domElement.addEventListener('click', (e) => this.handleCanvasClick(e));
        
        // Start render loop
        this.animate();
        
        // Handle window resize
        window.addEventListener('resize', () => this.onWindowResize(), false);
    }

    createSprites() {
        // Create background sprite (back wall + floor)
        this.createBackgroundSprite();
        
        // Create counter sprite
        this.createCounterSprite();
        
        // Create master sprite
        this.createMasterSprite();
        
        // Create ambient lighting sprites
        this.createLightingSprites();
    }
    
    createBackgroundSprite() {
        // Load background image
        const loader = new THREE.TextureLoader();
        loader.load('/assets/images/background.png', (texture) => {
            const material = new THREE.SpriteMaterial({ map: texture });
            this.sprites.background = new THREE.Sprite(material);
            this.sprites.background.position.set(0, 0, -5);
            this.sprites.background.scale.set(20, 10, 1);
            this.scene.add(this.sprites.background);
        }, undefined, (error) => {
            console.warn('Could not load background.png, falling back to canvas background');
            this.createFallbackBackground();
        });
    }
    
    createFallbackBackground() {
        // Fallback canvas background if image fails to load
        const canvas = document.createElement('canvas');
        canvas.width = 1024;
        canvas.height = 512;
        const ctx = canvas.getContext('2d');
        
        // Draw back wall
        ctx.fillStyle = '#2c1810';
        ctx.fillRect(0, 0, 1024, 358);
        
        // Draw wooden floor with grain
        ctx.fillStyle = '#8b4513';
        ctx.fillRect(0, 358, 1024, 154);
        
        // Wood grain effect
        ctx.strokeStyle = '#654321';
        ctx.lineWidth = 2;
        for (let i = 358; i < 512; i += 16) {
            ctx.beginPath();
            ctx.moveTo(0, i);
            ctx.lineTo(1024, i);
            ctx.stroke();
        }
        
        // Create texture and sprite
        const texture = new THREE.CanvasTexture(canvas);
        const material = new THREE.SpriteMaterial({ map: texture });
        this.sprites.background = new THREE.Sprite(material);
        this.sprites.background.position.set(0, 0, -5);
        this.sprites.background.scale.set(20, 10, 1);
        this.scene.add(this.sprites.background);
    }
    
    createCounterSprite() {
        const canvas = document.createElement('canvas');
        canvas.width = 1024;
        canvas.height = 128;
        const ctx = canvas.getContext('2d');
        
        // Counter main
        ctx.fillStyle = '#654321';
        ctx.fillRect(0, 32, 1024, 64);
        
        // Counter top highlight
        ctx.fillStyle = '#8b5a2b';
        ctx.fillRect(0, 32, 1024, 16);
        
        // Counter shadow
        ctx.fillStyle = '#4a2c17';
        ctx.fillRect(0, 80, 1024, 16);
        
        const texture = new THREE.CanvasTexture(canvas);
        const material = new THREE.SpriteMaterial({ map: texture });
        this.sprites.counter = new THREE.Sprite(material);
        this.sprites.counter.position.set(0, -3, -1);
        this.sprites.counter.scale.set(20, 2.5, 1);
        this.scene.add(this.sprites.counter);
    }
    
    createMasterSprite() {
        // Initialize chef state management
        this.chefState = 'normal'; // normal, prepare, thinking
        
        // Try to load external chef image first with new structure
        this.loadChefImage('normal');
    }

    loadChefImage(state) {
        const loader = new THREE.TextureLoader();
        const imagePath = `/assets/images/chef/chef_${state}.png`;
        
        loader.load(imagePath, (texture) => {
            const material = new THREE.SpriteMaterial({ map: texture, transparent: true });
            
            if (this.sprites.master) {
                // Update existing sprite material
                this.sprites.master.material = material;
                this.sprites.master.material.needsUpdate = true;
            } else {
                // Create new sprite
                this.sprites.master = new THREE.Sprite(material);
                // Position chef at Y: -1 and behind counter (z = -2)
                this.sprites.master.position.set(0, -1, -2);
                this.scene.add(this.sprites.master);
            }
            
            // Handle dynamic sizing for 1280px height with variable width
            this.updateChefScale(texture);
            this.chefState = state;
            
        }, undefined, (error) => {
            console.warn(`Could not load chef_${state}.png, falling back to canvas master`);
            this.createFallbackMaster();
        });
    }

    updateChefScale(texture) {
        if (!this.sprites.master || !texture.image) return;
        
        const imageWidth = texture.image.width;
        const imageHeight = texture.image.height;
        
        // Target height is fixed at 8 units (4 * 2x multiplier for full body)
        const baseHeight = 4;
        const sizeMultiplier = 2;
        const targetHeight = baseHeight * sizeMultiplier;
        
        // Calculate width scale based on image aspect ratio
        // Base calculation: if image was 1024x1024, scale would be 4x4
        // Now: if image is 1280px height, maintain same visual height, adjust width proportionally
        const aspectRatio = imageWidth / imageHeight;
        const targetWidth = targetHeight * aspectRatio;
        
        this.sprites.master.scale.set(targetWidth, targetHeight, 1);
    }

    updateChefImage(newState) {
        if (newState !== this.chefState) {
            console.log(`Updating chef image from ${this.chefState} to ${newState}`);
            this.loadChefImage(newState);
        }
    }
    
    createFallbackMaster() {
        // Fallback canvas master if image fails to load
        const canvas = document.createElement('canvas');
        canvas.width = 256;
        canvas.height = 256;
        const ctx = canvas.getContext('2d');
        
        const centerX = 128;
        const centerY = 128;
        
        // Master's silhouette
        ctx.fillStyle = 'rgba(0, 0, 0, 0.8)';
        
        // Head
        ctx.beginPath();
        ctx.arc(centerX, centerY - 60, 40, 0, Math.PI * 2);
        ctx.fill();
        
        // Body
        ctx.fillRect(centerX - 50, centerY - 20, 100, 120);
        
        // Arms
        ctx.fillRect(centerX - 75, centerY - 10, 25, 60);
        ctx.fillRect(centerX + 50, centerY - 10, 25, 60);
        
        // Eyes
        ctx.fillStyle = 'rgba(255, 255, 255, 0.4)';
        ctx.beginPath();
        ctx.arc(centerX - 15, centerY - 68, 3, 0, Math.PI * 2);
        ctx.arc(centerX + 15, centerY - 68, 3, 0, Math.PI * 2);
        ctx.fill();
        
        const texture = new THREE.CanvasTexture(canvas);
        const material = new THREE.SpriteMaterial({ map: texture, transparent: true });
        this.sprites.master = new THREE.Sprite(material);
        // Position chef at Y: -1 and behind counter (z = -2)
        this.sprites.master.position.set(0, -1, -2);
        this.sprites.master.scale.set(8, 8, 1); // Increased by 2x from 4,4
        this.scene.add(this.sprites.master);
    }
    
    createLightingSprites() {
        // Create warm ambient lighting sprites
        for (let i = 0; i < 3; i++) {
            const canvas = document.createElement('canvas');
            canvas.width = 256;
            canvas.height = 256;
            const ctx = canvas.getContext('2d');
            
            // Warm light glow
            const gradient = ctx.createRadialGradient(128, 128, 0, 128, 128, 128);
            gradient.addColorStop(0, 'rgba(255, 215, 0, 0.2)');
            gradient.addColorStop(1, 'rgba(255, 215, 0, 0)');
            ctx.fillStyle = gradient;
            ctx.fillRect(0, 0, 256, 256);
            
            const texture = new THREE.CanvasTexture(canvas);
            const material = new THREE.SpriteMaterial({ 
                map: texture, 
                transparent: true,
                blending: THREE.AdditiveBlending
            });
            const light = new THREE.Sprite(material);
            light.position.set((i - 1) * 6, 3, -2);
            light.scale.set(8, 8, 1);
            this.scene.add(light);
        }
    }

    createSeats() {
        // Define seat positions in 3D space (front-facing perspective)
        const seatPositions = [
            { x: -7, y: -4 },
            { x: -5, y: -4 },
            { x: -3, y: -4 },
            { x: -1, y: -4 },
            { x: 1, y: -4 },
            { x: 3, y: -4 },
            { x: 5, y: -4 },
            { x: 7, y: -4 }
        ];

        this.seats = [];
        this.sprites.seats = [];
        this.sprites.customers = new Array(8).fill(null);
        
        seatPositions.forEach((pos, index) => {
            const seatNumber = index + 1;
            const seatSprite = this.createSeatSprite(seatNumber);
            seatSprite.position.set(pos.x, pos.y, 1);
            seatSprite.scale.set(1.5, 1, 1); // Flattened for front perspective
            
            const seat = {
                sprite: seatSprite,
                seatNumber: seatNumber,
                occupied: false,
                isCurrentUser: false,
                position: { x: pos.x, y: pos.y }
            };
            
            this.seats.push(seat);
            this.sprites.seats.push(seatSprite);
            this.scene.add(seatSprite);
            this.seatStates.set(seatNumber, { occupied: false, userId: null });
            
            // Ensure seat shows as available (green) initially
            this.updateSeatSprite(seat);
        });
    }
    
    createSeatSprite(seatNumber) {
        // Try to load external stool image first
        const loader = new THREE.TextureLoader();
        const sprite = new THREE.Sprite();
        
        loader.load('/assets/images/stool.png', (texture) => {
            const material = new THREE.SpriteMaterial({ map: texture, transparent: true });
            sprite.material = material;
            // Update sprite appearance after texture loads
            const seat = this.seats.find(s => s.sprite === sprite);
            if (seat) this.updateSeatSprite(seat);
        }, undefined, (error) => {
            console.warn('Could not load stool.png, falling back to canvas stool');
            sprite.material = this.createFallbackSeatMaterial(seatNumber);
            // Update sprite appearance after fallback material is set
            const seat = this.seats.find(s => s.sprite === sprite);
            if (seat) this.updateSeatSprite(seat);
        });
        
        return sprite;
    }
    
    createFallbackSeatMaterial(seatNumber) {
        const canvas = document.createElement('canvas');
        canvas.width = 128;
        canvas.height = 128;
        const ctx = canvas.getContext('2d');
        
        const centerX = 64;
        const centerY = 64;
        
        // Stool base (elliptical for front perspective)
        ctx.fillStyle = '#654321';
        ctx.save();
        ctx.translate(centerX, centerY);
        ctx.scale(1, 0.4);
        ctx.beginPath();
        ctx.arc(0, 0, 40, 0, Math.PI * 2);
        ctx.fill();
        ctx.restore();
        
        // Seat cushion
        ctx.fillStyle = '#8b0000';
        ctx.save();
        ctx.translate(centerX, centerY);
        ctx.scale(1, 0.4);
        ctx.beginPath();
        ctx.arc(0, 0, 32, 0, Math.PI * 2);
        ctx.fill();
        ctx.restore();
        
        // Seat number
        ctx.fillStyle = '#ffffff';
        ctx.font = '16px Arial';
        ctx.textAlign = 'center';
        ctx.fillText(seatNumber.toString(), centerX, centerY + 6);
        
        const texture = new THREE.CanvasTexture(canvas);
        return new THREE.SpriteMaterial({ map: texture, transparent: true });
    }
    
    updateSeatSprite(seat) {
        console.log(`🪑 Updating seat ${seat.seatNumber} sprite - occupied: ${seat.occupied}, isCurrentUser: ${seat.isCurrentUser}`);
        
        // Check if we're using external stool image or fallback
        const hasExternalTexture = seat.sprite.material.map && 
                                  seat.sprite.material.map.image && 
                                  seat.sprite.material.map.image.src &&
                                  seat.sprite.material.map.image.src.includes('stool.png');
        
        if (hasExternalTexture) {
            // For external images, add glow effect by tinting the material
            let color;
            if (seat.isCurrentUser) {
                color = new THREE.Color(0.3, 0.3, 1.0); // Blue tint
            } else if (seat.occupied) {
                color = new THREE.Color(1.0, 0.3, 0.3); // Red tint
            } else {
                color = new THREE.Color(0.3, 1.0, 0.3); // Green tint
            }
            seat.sprite.material.color = color;
        } else {
            // Fallback to canvas-based sprite with glow
            this.updateFallbackSeatSprite(seat);
        }
        
        seat.sprite.material.needsUpdate = true;
    }
    
    updateFallbackSeatSprite(seat) {
        const canvas = document.createElement('canvas');
        canvas.width = 128;
        canvas.height = 128;
        const ctx = canvas.getContext('2d');
        
        const centerX = 64;
        const centerY = 64;
        
        // Stool base
        ctx.fillStyle = '#654321';
        ctx.save();
        ctx.translate(centerX, centerY);
        ctx.scale(1, 0.4);
        ctx.beginPath();
        ctx.arc(0, 0, 40, 0, Math.PI * 2);
        ctx.fill();
        ctx.restore();
        
        // Seat cushion
        ctx.fillStyle = '#8b0000';
        ctx.save();
        ctx.translate(centerX, centerY);
        ctx.scale(1, 0.4);
        ctx.beginPath();
        ctx.arc(0, 0, 32, 0, Math.PI * 2);
        ctx.fill();
        ctx.restore();
        
        // Glow effect based on state
        let glowColor;
        if (seat.isCurrentUser) {
            glowColor = 'rgba(0, 0, 255, 0.8)';
        } else if (seat.occupied) {
            glowColor = 'rgba(255, 0, 0, 0.6)';
        } else {
            glowColor = 'rgba(0, 255, 0, 0.5)';
        }
        
        ctx.strokeStyle = glowColor;
        ctx.lineWidth = 6;
        ctx.save();
        ctx.translate(centerX, centerY);
        ctx.scale(1, 0.4);
        ctx.beginPath();
        ctx.arc(0, 0, 50, 0, Math.PI * 2);
        ctx.stroke();
        ctx.restore();
        
        // Seat number
        ctx.fillStyle = '#ffffff';
        ctx.font = '16px Arial';
        ctx.textAlign = 'center';
        ctx.fillText(seat.seatNumber.toString(), centerX, centerY + 6);
        
        // Update texture
        seat.sprite.material.map = new THREE.CanvasTexture(canvas);
    }
    
    updateSeatState(seatNumber, occupied, isCurrentUser = false) {
        const seat = this.seats[seatNumber - 1];
        if (!seat) return;

        seat.occupied = occupied;
        seat.isCurrentUser = isCurrentUser;
        this.updateSeatSprite(seat);
    }
    
    handleCanvasClick(event) {
        const rect = this.renderer.domElement.getBoundingClientRect();
        this.mouse.x = ((event.clientX - rect.left) / rect.width) * 2 - 1;
        this.mouse.y = -((event.clientY - rect.top) / rect.height) * 2 + 1;
        
        this.raycaster.setFromCamera(this.mouse, this.camera);
        const intersects = this.raycaster.intersectObjects(this.sprites.seats);
        
        if (intersects.length > 0) {
            // Find which seat was clicked
            const clickedSprite = intersects[0].object;
            const seat = this.seats.find(s => s.sprite === clickedSprite);
            
            if (seat && window.wsClient && window.wsClient.isConnected()) {
                const currentSeat = window.wsClient.getCurrentSeat();
                
                if (currentSeat === seat.seatNumber) {
                    // Leave current seat
                    window.wsClient.leaveSeat();
                } else if (!seat.occupied) {
                    // Join new seat if available
                    if (currentSeat) {
                        window.wsClient.leaveSeat();
                    }
                    setTimeout(() => {
                        window.wsClient.joinSeat(seat.seatNumber);
                    }, 100);
                }
            }
        }
    }

    createCustomerSprite(seatNumber, isCurrentUser = false, userId = null, userName = null) {
        console.log(`🎭 [CREATE SPRITE] Creating customer sprite for seat ${seatNumber}`);
        console.log(`📝 [USER DATA] userId="${userId}", userName="${userName}", isCurrentUser=${isCurrentUser}`);
        console.log(`🔍 [CHECK] userId truthy: ${!!userId}, userName truthy: ${!!userName}`);
        console.log(`🔍 [TYPES] userId type: ${typeof userId}, userName type: ${typeof userName}`);
        
        const loader = new THREE.TextureLoader();
        const sprite = new THREE.Sprite();
        
        // Check if we should load custom user image for registered users
        const shouldLoad = this.shouldLoadCustomImage(userId, userName);
        console.log(`🤔 [SHOULD LOAD] Result: ${shouldLoad} (userId && userName && shouldLoadCustomImage)`);
        console.log(`🧮 [LOGIC] userId: ${!!userId}, userName: ${!!userName}, shouldLoadCustomImage: ${shouldLoad}`);
        
        if (userId && userName && shouldLoad) {
            console.log(`✅ [CUSTOM] Loading custom image for ${userName} at seat ${seatNumber}`);
            this.loadCustomUserImage(sprite, userName, 'normal', isCurrentUser);
        } else {
            console.log(`🖼️ [DEFAULT] Loading default image for seat ${seatNumber} - Reason: userId=${!!userId}, userName=${!!userName}, shouldLoad=${shouldLoad}`);
            
            // Fallback to default images
            const imageName = isCurrentUser ? 'customer-self.png' : 'customer.png';
            
            loader.load(`/assets/images/${imageName}`, (texture) => {
                const material = new THREE.SpriteMaterial({ map: texture, transparent: true });
                sprite.material = material;
                console.log(`✅ [DEFAULT LOADED] Default image ${imageName} loaded for seat ${seatNumber}`);
            }, undefined, (error) => {
                console.warn(`❌ [DEFAULT FAILED] Could not load ${imageName}, falling back to canvas customer`);
                sprite.material = this.createFallbackCustomerMaterial(isCurrentUser);
            });
        }
        
        // Position customer slightly behind and above the seat
        const seat = this.seats[seatNumber - 1];
        if (seat) {
            sprite.position.set(seat.position.x, seat.position.y + 0.5, 2);
            sprite.scale.set(2, 2, 1);
        }
        
        // Store user info for future image updates
        sprite.userData = {
            userId: userId,
            userName: userName,
            isCurrentUser: isCurrentUser,
            seatNumber: seatNumber
        };
        
        return sprite;
    }
    
    shouldLoadCustomImage(userId, userName) {
        // For MVP, always try to load custom images for any user
        // The API will return 404 if no custom image exists, and we'll fall back to defaults
        const shouldLoad = userName && userName.trim().length > 0;
        console.log(`🤔 [DECISION] Should load custom image for ${userName} (ID: ${userId})? ${shouldLoad}`);
        return shouldLoad;
    }
    
    async loadCustomUserImage(sprite, userName, imageType = 'normal', isCurrentUser = false) {
        console.log(`🖼️ [IMAGE LOADING] Starting custom image load for user: ${userName}, type: ${imageType}, isCurrentUser: ${isCurrentUser}`);
        
        try {
            // Simple approach: always use the username provided by the seat data
            // The backend should ensure seat data has the correct username for image lookup
            const headers = {};
            
            if (window.authManager && window.authManager.isUserLoggedIn()) {
                const currentUser = window.authManager.getLoggedInUser();
                if (currentUser) {
                    headers['X-Username'] = currentUser.username;
                    console.log(`🔑 [AUTH] Adding X-Username header: ${currentUser.username}`);
                }
            }
            
            const apiUrl = `/api/images/${userName}/${imageType}`;
            console.log(`🌐 [API] Fetching: ${apiUrl}`);
            console.log(`📋 [API] Headers:`, headers);
            
            // Try to load custom image from MinIO
            const response = await fetch(apiUrl, {
                method: 'GET',
                headers: headers
            });
            
            console.log(`📡 [RESPONSE] Status: ${response.status} ${response.statusText}`);
            console.log(`📡 [RESPONSE] Headers:`, [...response.headers.entries()]);
            
            if (response.ok) {
                const data = await response.json();
                console.log(`📝 [DATA] Response data:`, data);
                
                if (data.success && data.imageUrl) {
                    console.log(`🎯 [SUCCESS] Loading texture from: ${data.imageUrl}`);
                    const loader = new THREE.TextureLoader();
                    
                    loader.load(data.imageUrl, (texture) => {
                        const material = new THREE.SpriteMaterial({ map: texture, transparent: true });
                        sprite.material = material;
                        console.log(`✅ [COMPLETE] Successfully loaded custom ${imageType} image for user: ${userName}`);
                        console.log(`📊 [TEXTURE] Size: ${texture.image.width}x${texture.image.height}`);
                    }, (progress) => {
                        console.log(`⏳ [LOADING] Progress: ${Math.round((progress.loaded / progress.total) * 100)}%`);
                    }, (error) => {
                        console.error(`❌ [TEXTURE ERROR] Failed to load texture from ${data.imageUrl}:`, error);
                        console.log(`🔄 [FALLBACK] Using fallback image for ${userName}`);
                        this.loadFallbackImage(sprite, isCurrentUser);
                    });
                } else {
                    console.log(`⚠️ [API RESPONSE] API returned success=false or no imageUrl for ${userName}/${imageType}`);
                    console.log(`📝 [API RESPONSE] Data:`, data);
                    this.loadFallbackImage(sprite, isCurrentUser);
                }
            } else {
                const errorText = await response.text().catch(() => 'Unable to read response text');
                console.log(`⚠️ [HTTP ERROR] ${response.status} ${response.statusText} for ${userName}/${imageType}`);
                console.log(`📝 [ERROR BODY] ${errorText}`);
                this.loadFallbackImage(sprite, isCurrentUser);
            }
            
        } catch (error) {
            console.error(`❌ [EXCEPTION] Error loading custom image for ${userName}/${imageType}:`, error);
            console.log(`🔄 [FALLBACK] Using fallback image due to exception`);
            this.loadFallbackImage(sprite, isCurrentUser);
        }
    }
    
    loadFallbackImage(sprite, isCurrentUser) {
        const loader = new THREE.TextureLoader();
        const imageName = isCurrentUser ? 'customer-self.png' : 'customer.png';
        
        loader.load(`/assets/images/${imageName}`, (texture) => {
            const material = new THREE.SpriteMaterial({ map: texture, transparent: true });
            sprite.material = material;
        }, undefined, (error) => {
            console.warn(`Could not load ${imageName}, falling back to canvas customer`);
            sprite.material = this.createFallbackCustomerMaterial(isCurrentUser);
        });
    }
    
    // Method to update user image based on activity state
    updateUserImageState(seatNumber, imageType = 'normal') {
        console.log(`🎭 [STATE CHANGE] Updating user image state for seat ${seatNumber} to '${imageType}'`);
        
        if (seatNumber < 1 || seatNumber > 8) {
            console.warn(`⚠️ [INVALID SEAT] Seat number ${seatNumber} is out of range (1-8)`);
            return;
        }
        
        const customerSprite = this.sprites.customers[seatNumber - 1];
        if (!customerSprite) {
            console.log(`🚫 [NO SPRITE] No customer sprite found for seat ${seatNumber}`);
            return;
        }
        
        if (!customerSprite.userData) {
            console.warn(`⚠️ [NO USER DATA] Customer sprite at seat ${seatNumber} has no userData`);
            return;
        }
        
        const { userId, userName, isCurrentUser } = customerSprite.userData;
        console.log(`📝 [USER INFO] Seat ${seatNumber}: userId=${userId}, userName=${userName}, isCurrentUser=${isCurrentUser}`);
        
        if (userId && userName && this.shouldLoadCustomImage(userId, userName)) {
            console.log(`✅ [LOADING] Loading custom ${imageType} image for ${userName} at seat ${seatNumber}`);
            this.loadCustomUserImage(customerSprite, userName, imageType, isCurrentUser);
        } else {
            console.log(`⚠️ [SKIP] Skipping custom image load for seat ${seatNumber} (missing userId/userName or shouldLoadCustomImage returned false)`);
        }
        
        // Always update username box if we have a userName
        if (userName) {
            this.updateUsernameBox(seatNumber, userName);
        }
    }
    
    updateUsernameBox(seatNumber, username) {
        console.log(`👤 [USERNAME] Updating username box for seat ${seatNumber}: ${username}`);
        
        if (seatNumber < 1 || seatNumber > 8) {
            console.warn(`⚠️ [INVALID SEAT] Seat number ${seatNumber} is out of range (1-8)`);
            return;
        }
        
        const seatIndex = seatNumber - 1;
        if (seatIndex >= 0 && seatIndex < this.sprites.usernameBoxes.length) {
            const usernameSprite = this.sprites.usernameBoxes[seatIndex];
            
            // Update the canvas with new username
            const canvas = this.createUsernameCanvas(username);
            const texture = new THREE.CanvasTexture(canvas);
            usernameSprite.material.map = texture;
            usernameSprite.material.needsUpdate = true;
            
            // Make it visible
            usernameSprite.visible = true;
            
            console.log(`✅ [USERNAME] Updated username box for seat ${seatNumber} with "${username}"`);
        } else {
            console.warn(`⚠️ [NO USERNAME SPRITE] No username sprite found for seat ${seatNumber}`);
        }
    }
    
    createFallbackCustomerMaterial(isCurrentUser = false) {
        const canvas = document.createElement('canvas');
        canvas.width = 128;
        canvas.height = 128;
        const ctx = canvas.getContext('2d');
        
        const centerX = 64;
        const centerY = 64;
        
        // Customer silhouette
        const customerColor = isCurrentUser ? 'rgba(0, 100, 200, 0.9)' : 'rgba(100, 100, 100, 0.8)';
        ctx.fillStyle = customerColor;
        
        // Head
        ctx.beginPath();
        ctx.arc(centerX, centerY - 30, 20, 0, Math.PI * 2);
        ctx.fill();
        
        // Body
        ctx.fillRect(centerX - 25, centerY - 10, 50, 60);
        
        // Arms
        ctx.fillRect(centerX - 40, centerY, 15, 30);
        ctx.fillRect(centerX + 25, centerY, 15, 30);
        
        // Simple face features for current user
        if (isCurrentUser) {
            ctx.fillStyle = 'rgba(255, 255, 255, 0.8)';
            ctx.beginPath();
            ctx.arc(centerX - 8, centerY - 35, 2, 0, Math.PI * 2);
            ctx.arc(centerX + 8, centerY - 35, 2, 0, Math.PI * 2);
            ctx.fill();
        }
        
        const texture = new THREE.CanvasTexture(canvas);
        return new THREE.SpriteMaterial({ map: texture, transparent: true });
    }
    
    addCustomerToSeat(seatNumber, isCurrentUser = false, userId = null, userName = null) {
        // Remove any existing customer sprite for this seat
        this.removeCustomerFromSeat(seatNumber);
        // Also remove any existing username box
        this.removeUsernameBox(seatNumber);
        
        // Create new customer sprite with user info
        const customerSprite = this.createCustomerSprite(seatNumber, isCurrentUser, userId, userName);
        this.sprites.customers[seatNumber - 1] = customerSprite;
        this.scene.add(customerSprite);
    }
    
    removeCustomerFromSeat(seatNumber) {
        const existingCustomer = this.sprites.customers[seatNumber - 1];
        if (existingCustomer) {
            this.scene.remove(existingCustomer);
            this.sprites.customers[seatNumber - 1] = null;
        }
    }
    
    removeUsernameBox(seatNumber) {
        const seatIndex = seatNumber - 1;
        if (seatIndex >= 0 && seatIndex < this.sprites.usernameBoxes.length) {
            const usernameSprite = this.sprites.usernameBoxes[seatIndex];
            if (usernameSprite) {
                usernameSprite.visible = false;
            }
        }
    }

    handleSeatUpdate(message) {
        const seatNumber = message.seatId;
        if (!seatNumber || seatNumber < 1 || seatNumber > 8) return;

        if (message.type === 'JOIN_SEAT') {
            this.seatStates.set(seatNumber, { 
                occupied: true, 
                userId: message.userId 
            });
            
            // Update visual state
            const isCurrentUser = message.userId === window.wsClient?.userId;
            this.updateSeatState(seatNumber, true, isCurrentUser);
            
            // If this is the current user moving seats, clear old user status sprites
            if (isCurrentUser) {
                console.log(`Current user joining seat ${seatNumber}, clearing old status sprites`);
                this.clearUserStatusSpritesForCurrentUser();
            }
            
            // Add customer avatar
            console.log(`🔌 [JOIN_SEAT] Calling addCustomerToSeat with: seatNumber=${seatNumber}, isCurrentUser=${isCurrentUser}, userId="${message.userId}", userName="${message.userName}"`);
            this.addCustomerToSeat(seatNumber, isCurrentUser, message.userId, message.userName);
            
        } else if (message.type === 'LEAVE_SEAT') {
            const leavingUserId = message.userId;
            this.seatStates.set(seatNumber, { 
                occupied: false, 
                userId: null 
            });
            
            // Update visual state
            this.updateSeatState(seatNumber, false, false);
            
            // Remove customer avatar
            this.removeCustomerFromSeat(seatNumber);
            // Also remove username box
            this.removeUsernameBox(seatNumber);
            
            // Clear user status sprite for this seat when user leaves
            this.hideUserStatusSprite(seatNumber);
            
        } else if (message.type === 'SEAT_STATE') {
            // Handle initial seat state loading
            this.seatStates.set(seatNumber, { 
                occupied: message.occupied, 
                userId: message.userId 
            });
            
            if (message.occupied) {
                const isCurrentUser = message.userId === window.wsClient?.userId;
                this.updateSeatState(seatNumber, true, isCurrentUser);
                console.log(`🔌 [SEAT_STATUS] Calling addCustomerToSeat with: seatNumber=${seatNumber}, isCurrentUser=${isCurrentUser}, userId="${message.userId}", userName="${message.userName}"`);
                this.addCustomerToSeat(seatNumber, isCurrentUser, message.userId, message.userName);
            } else {
                this.updateSeatState(seatNumber, false, false);
                this.removeCustomerFromSeat(seatNumber);
                this.removeUsernameBox(seatNumber);
            }
        }
    }

    animate() {
        requestAnimationFrame(() => this.animate());
        
        // Add subtle animations to lighting sprites
        const time = Date.now() * 0.001;
        this.scene.children.forEach(child => {
            if (child.material && child.material.blending === THREE.AdditiveBlending) {
                child.material.opacity = 0.15 + Math.sin(time + child.position.x) * 0.05;
            }
        });
        
        this.renderer.render(this.scene, this.camera);
    }

    onWindowResize() {
        const aspect = window.innerWidth / window.innerHeight;
        const frustumSize = 10;
        this.camera.left = frustumSize * aspect / -2;
        this.camera.right = frustumSize * aspect / 2;
        this.camera.top = frustumSize / 2;
        this.camera.bottom = frustumSize / -2;
        this.camera.updateProjectionMatrix();
        this.renderer.setSize(window.innerWidth, window.innerHeight);
    }

    createUISprites() {
        // Create master status label sprite
        this.createMasterStatusSprite();
        
        // Create user status box sprites for each seat
        this.createUserStatusSprites();
        
        // Create chef speech bubble sprite
        this.createChefSpeechBubble();
    }
    
    createMasterStatusSprite() {
        const canvas = this.createMasterStatusCanvas('Idle', '#90ee90');
        const texture = new THREE.CanvasTexture(canvas);
        const material = new THREE.SpriteMaterial({ map: texture, transparent: true });
        this.sprites.masterStatusLabel = new THREE.Sprite(material);
        this.sprites.masterStatusLabel.position.set(0, 4.5, 5);
        this.sprites.masterStatusLabel.scale.set(3, 0.8, 1);
        this.sprites.masterStatusLabel.visible = false; // Initially hidden
        this.scene.add(this.sprites.masterStatusLabel);
    }
    
    createMasterStatusCanvas(text, color) {
        const canvas = document.createElement('canvas');
        canvas.width = 240;
        canvas.height = 60;
        const ctx = canvas.getContext('2d');
        
        // Background with rounded corners
        ctx.fillStyle = 'rgba(0, 0, 0, 0.9)';
        ctx.strokeStyle = color;
        ctx.lineWidth = 2;
        this.drawRoundedRect(ctx, 5, 5, 230, 50, 10);
        ctx.fill();
        ctx.stroke();
        
        // Text
        ctx.fillStyle = color;
        ctx.font = 'bold 16px Arial';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(text, 120, 30);
        
        return canvas;
    }
    
    createUserStatusSprites() {
        // Create status box sprites for seats 1-8
        for (let i = 0; i < 8; i++) {
            const canvas = this.createUserStatusCanvas('');
            const texture = new THREE.CanvasTexture(canvas);
            const material = new THREE.SpriteMaterial({ map: texture, transparent: true });
            const statusSprite = new THREE.Sprite(material);
            
            // Position above each seat with increased spacing from food image
            const seat = this.seats[i];
            if (seat) {
                statusSprite.position.set(seat.position.x, seat.position.y + 2.8, 3);
                statusSprite.scale.set(2.5, 1, 1);
            }
            statusSprite.visible = false; // Initially hidden
            
            this.sprites.userStatusBoxes.push(statusSprite);
            this.scene.add(statusSprite);
            
            // Create separate image display sprite
            const imageCanvas = this.createUserImageCanvas('');
            const imageTexture = new THREE.CanvasTexture(imageCanvas);
            const imageMaterial = new THREE.SpriteMaterial({ map: imageTexture, transparent: true });
            const imageSprite = new THREE.Sprite(imageMaterial);
            
            // Position image box with increased spacing from user
            if (seat) {
                imageSprite.position.set(seat.position.x, seat.position.y + 2.0, 3);
                imageSprite.scale.set(1.2, 1.2, 1);
            }
            imageSprite.visible = false; // Initially hidden
            
            this.sprites.userImageBoxes.push(imageSprite);
            this.scene.add(imageSprite);
            
            // Create username display box below image
            const usernameCanvas = this.createUsernameCanvas('');
            const usernameTexture = new THREE.CanvasTexture(usernameCanvas);
            const usernameMaterial = new THREE.SpriteMaterial({ map: usernameTexture, transparent: true });
            const usernameSprite = new THREE.Sprite(usernameMaterial);
            
            // Position username box below avatar
            if (seat) {
                usernameSprite.position.set(seat.position.x, seat.position.y - 0.5, 3);
                usernameSprite.scale.set(1.5, 0.4, 1);
            }
            usernameSprite.visible = false; // Initially hidden
            
            this.sprites.usernameBoxes.push(usernameSprite);
            this.scene.add(usernameSprite);
        }
    }
    
    createUserStatusCanvas(content) {
        const canvas = document.createElement('canvas');
        canvas.width = 200;
        canvas.height = 80;
        const ctx = canvas.getContext('2d');
        
        if (!content) {
            // Empty canvas for hidden state
            return canvas;
        }
        
        // Background with rounded corners
        ctx.fillStyle = 'rgba(0, 0, 0, 0.85)';
        ctx.strokeStyle = '#ffd700';
        ctx.lineWidth = 2;
        this.drawRoundedRect(ctx, 2, 2, 196, 76, 4);
        ctx.fill();
        ctx.stroke();
        
        // Text content
        ctx.fillStyle = '#fff';
        ctx.font = 'bold 12px Arial';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        
        const lines = content.split('\n');
        const lineHeight = 14;
        const startY = 40 - ((lines.length - 1) * lineHeight / 2);
        
        lines.forEach((line, index) => {
            ctx.fillText(line, 100, startY + index * lineHeight);
        });
        
        return canvas;
    }
    
    createUsernameCanvas(username) {
        const canvas = document.createElement('canvas');
        canvas.width = 120;
        canvas.height = 32;
        const ctx = canvas.getContext('2d');
        
        if (!username || username.trim() === '') {
            // Empty transparent canvas for hidden state
            return canvas;
        }
        
        // Background with rounded corners
        ctx.fillStyle = 'rgba(0, 0, 0, 0.8)';
        ctx.strokeStyle = '#ffd700';
        ctx.lineWidth = 1;
        this.drawRoundedRect(ctx, 1, 1, 118, 30, 6);
        ctx.fill();
        ctx.stroke();
        
        // Username text
        ctx.fillStyle = '#ffd700';
        ctx.font = 'bold 11px Arial';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        
        // Truncate long usernames
        let displayText = username;
        if (username.length > 12) {
            displayText = username.substring(0, 10) + '..';
        }
        
        ctx.fillText(displayText, 60, 16);
        
        return canvas;
    }
    
    createUserImageCanvas(imageData, callback) {
        const canvas = document.createElement('canvas');
        canvas.width = 128;
        canvas.height = 128;
        const ctx = canvas.getContext('2d');
        
        if (!imageData) {
            // Empty transparent canvas for hidden state
            if (callback) callback(canvas);
            return canvas;
        }
        
        // Create a promise-based image loading for base64 data
        const img = new Image();
        img.onload = () => {
            // Clear canvas for complete transparency
            ctx.clearRect(0, 0, canvas.width, canvas.height);
            
            // Draw the food image directly with no background or border
            // Use full canvas size for the image
            ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
            
            if (callback) callback(canvas);
        };
        
        img.onerror = () => {
            console.warn('Failed to load food image');
            // For transparent design, just leave canvas empty on error
            // The image sprite will remain hidden if no image loads
            ctx.clearRect(0, 0, canvas.width, canvas.height);
            
            if (callback) callback(canvas);
        };
        
        // Handle both MinIO URLs and base64 data
        if (imageData.startsWith('http://') || imageData.startsWith('https://')) {
            // MinIO URL
            img.src = imageData;
        } else if (imageData.startsWith('data:image/')) {
            // Complete data URL
            img.src = imageData;
        } else {
            // Legacy base64 encoded PNG
            img.src = 'data:image/png;base64,' + imageData;
        }
        
        return canvas;
    }
    
    /**
     * Calculate display size for consumable based on type for realistic proportions
     * @param {Object} consumable - The consumable item
     * @param {number} baseSize - Base size (128px)
     * @returns {number} - The scaled size
     */
    getConsumableDisplaySize(consumable, baseSize) {
        switch (consumable.itemType) {
            case 'DRINK':
                // Drinks scaled to 75% (increased from 50% - 150% of previous)
                return Math.round(baseSize * 0.75);
            case 'FOOD':
            case 'DESSERT':
                // Food scaled up to 180% (increased from 150% - 120% of previous)
                return Math.round(baseSize * 1.8);
            default:
                // Default size for unknown types
                return baseSize;
        }
    }
    
    createCombinedImageCanvas(consumablesWithImages, callback) {
        const baseImageSize = 128;
        const spacing = 8; // Small gap between images
        
        // Calculate individual sizes and total width based on item types
        const itemSizes = consumablesWithImages.map(consumable => this.getConsumableDisplaySize(consumable, baseImageSize));
        const totalWidth = itemSizes.reduce((sum, size) => sum + size, 0) + (spacing * (consumablesWithImages.length - 1));
        const maxHeight = Math.max(...itemSizes); // Use largest item height for canvas
        
        const canvas = document.createElement('canvas');
        canvas.width = totalWidth;
        canvas.height = maxHeight;
        const ctx = canvas.getContext('2d');
        
        // Clear canvas
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        
        let loadedImages = 0;
        const totalImages = consumablesWithImages.length;
        
        // Load all images and draw them side by side with type-based scaling
        let currentX = 0;
        consumablesWithImages.forEach((consumable, index) => {
            const img = new Image();
            const itemSize = itemSizes[index];
            
            img.onload = () => {
                // Center smaller items vertically
                const y = (maxHeight - itemSize) / 2;
                
                // Draw the image with type-based scaling
                ctx.drawImage(img, currentX, y, itemSize, itemSize);
                console.log(`Drawing ${consumable.itemType} "${consumable.itemName}" at size ${itemSize}x${itemSize} (${consumable.itemType === 'DRINK' ? '75% size' : 'food 180% size'})`);
                
                currentX += itemSize + spacing;
                
                loadedImages++;
                if (loadedImages === totalImages) {
                    // All images loaded, callback with completed canvas
                    if (callback) callback(canvas);
                }
            };
            
            img.onerror = () => {
                console.warn(`Failed to load food image for ${consumable.itemName}`);
                loadedImages++;
                if (loadedImages === totalImages) {
                    // All images processed (including errors), callback with canvas
                    if (callback) callback(canvas);
                }
            };
            
            // Handle both MinIO URLs and base64 data
            if (consumable.imageData && consumable.imageData.startsWith('http')) {
                // MinIO URL (use imageData field for backward compatibility)
                img.src = consumable.imageData;
            } else if (consumable.imageUrl) {
                // New imageUrl field
                img.src = consumable.imageUrl;
            } else if (consumable.imageData && consumable.imageData.startsWith('data:image/')) {
                // Complete data URL
                img.src = consumable.imageData;
            } else if (consumable.imageData) {
                // Legacy base64 encoded PNG
                img.src = 'data:image/png;base64,' + consumable.imageData;
            }
        });
    }
    
    hideUserImageSprite(seatId) {
        const seatIndex = seatId - 1;
        if (seatIndex >= 0 && seatIndex < this.sprites.userImageBoxes.length) {
            this.sprites.userImageBoxes[seatIndex].visible = false;
        }
    }
    
    updateMasterStatusSprite(status, displayName) {
        if (!this.sprites.masterStatusLabel) return;
        
        // Status color mapping
        const colorMap = {
            idle: '#90ee90',
            thinking: '#87ceeb',
            preparing_order: '#ffa500',
            busy: '#ffa500',
            serving: '#ff69b4',
            cleaning: '#dda0dd',
            conversing: '#20b2aa'
        };
        
        const color = colorMap[status.toLowerCase()] || '#ffd700';
        const text = displayName || status.replace('_', ' ');
        
        const canvas = this.createMasterStatusCanvas(text, color);
        this.sprites.masterStatusLabel.material.map = new THREE.CanvasTexture(canvas);
        this.sprites.masterStatusLabel.material.needsUpdate = true;
        this.sprites.masterStatusLabel.visible = true;
    }
    
    updateUserStatusSprite(seatId, consumables) {
        const seatIndex = seatId - 1;
        if (seatIndex < 0 || seatIndex >= this.sprites.userStatusBoxes.length) return;
        
        const statusSprite = this.sprites.userStatusBoxes[seatIndex];
        if (!statusSprite) return;
        
        if (!consumables || consumables.length === 0) {
            statusSprite.visible = false;
            return;
        }
        
        // Create content text with improved deduplication
        const uniqueConsumables = this.deduplicateConsumables(consumables);
        const content = uniqueConsumables.map(c => {
            const minutes = Math.floor((c.remainingSeconds || 0) / 60);
            const seconds = (c.remainingSeconds || 0) % 60;
            const timeStr = `${minutes}:${seconds.toString().padStart(2, '0')}`;
            return `${c.itemName} ${timeStr}`;
        }).join('\n');
        
        console.log(`Updating sprite for seat ${seatId} with ${uniqueConsumables.length} items:`, content);
        
        // Always update but cache the canvas to reduce recreation overhead
        if (!statusSprite.userData) {
            statusSprite.userData = {};
        }
        
        // Only recreate canvas if content structure changed (not just timers)
        const itemSignature = uniqueConsumables.map(c => `${c.itemName}_${c.orderId || c.itemId}`).join('|');
        const lastSignature = statusSprite.userData.itemSignature;
        
        if (lastSignature !== itemSignature) {
            console.log(`Content structure changed for seat ${seatId}, recreating canvas`);
            const canvas = this.createUserStatusCanvas(content);
            statusSprite.material.map = new THREE.CanvasTexture(canvas);
            statusSprite.material.needsUpdate = true;
            statusSprite.userData.itemSignature = itemSignature;
        } else {
            // Just update the existing canvas with new times
            const canvas = this.createUserStatusCanvas(content);
            statusSprite.material.map.image = canvas;
            statusSprite.material.map.needsUpdate = true;
        }
        
        statusSprite.visible = true;
        
        // Also try to update username box if we can find the customer sprite
        const customerSprite = this.sprites.customers[seatIndex];
        if (customerSprite && customerSprite.userData && customerSprite.userData.userName) {
            console.log(`👤 [USERNAME] Found userName in customer sprite: ${customerSprite.userData.userName}`);
            this.updateUsernameBox(seatId, customerSprite.userData.userName);
        } else {
            console.log(`⚠️ [USERNAME] No userName found in customer sprite for seat ${seatId}`);
        }
        
        // Update image display if any consumable has image data
        this.updateUserImageSprite(seatId, uniqueConsumables);
    }
    
    updateUserImageSprite(seatId, consumables) {
        const seatIndex = seatId - 1;
        if (seatIndex < 0 || seatIndex >= this.sprites.userImageBoxes.length) return;
        
        // Find all consumables with image data
        const consumablesWithImages = consumables.filter(c => 
            (c.imageUrl && c.imageUrl.trim()) || (c.imageData && c.imageData.trim())
        );
        
        if (consumablesWithImages.length === 0) {
            this.hideUserImageSprite(seatId);
            return;
        }
        
        console.log(`Updating image sprite for seat ${seatId} with ${consumablesWithImages.length} food images`);
        
        // Create a combined canvas with all images side by side
        this.createCombinedImageCanvas(consumablesWithImages, (canvas) => {
            const imageSprite = this.sprites.userImageBoxes[seatIndex];
            if (imageSprite) {
                const texture = new THREE.CanvasTexture(canvas);
                imageSprite.material.map = texture;
                imageSprite.material.needsUpdate = true;
                imageSprite.visible = true;
                
                // Adjust scale to keep images reasonably sized and centered
                const baseScale = 1.2;
                const maxWidth = 2.5; // Maximum width to prevent overlap with other seats
                
                // Calculate appropriate scale to fit within max width
                const naturalWidth = baseScale * consumablesWithImages.length;
                const finalScale = naturalWidth > maxWidth ? maxWidth / consumablesWithImages.length : baseScale;
                
                imageSprite.scale.set(finalScale * consumablesWithImages.length, finalScale, 1);
            }
        });
    }
    
    hideUserStatusSprite(seatId) {
        const seatIndex = seatId - 1;
        if (seatIndex >= 0 && seatIndex < this.sprites.userStatusBoxes.length) {
            this.sprites.userStatusBoxes[seatIndex].visible = false;
        }
        // Also hide image sprite
        if (seatIndex >= 0 && seatIndex < this.sprites.userImageBoxes.length) {
            this.sprites.userImageBoxes[seatIndex].visible = false;
        }
        // Also hide username sprite
        if (seatIndex >= 0 && seatIndex < this.sprites.usernameBoxes.length) {
            this.sprites.usernameBoxes[seatIndex].visible = false;
        }
    }
    
    hideMasterStatusSprite() {
        if (this.sprites.masterStatusLabel) {
            this.sprites.masterStatusLabel.visible = false;
        }
    }
    
    clearAllUserStatusSprites() {
        // Hide all user status sprites (used when user changes seats)
        for (let i = 0; i < this.sprites.userStatusBoxes.length; i++) {
            this.sprites.userStatusBoxes[i].visible = false;
        }
        // Also hide all image sprites
        for (let i = 0; i < this.sprites.userImageBoxes.length; i++) {
            this.sprites.userImageBoxes[i].visible = false;
        }
        // Also hide all username sprites
        for (let i = 0; i < this.sprites.usernameBoxes.length; i++) {
            this.sprites.usernameBoxes[i].visible = false;
        }
    }
    
    clearUserStatusSpritesForCurrentUser() {
        // More targeted clearing - only for current user when they change seats
        if (!window.wsClient?.userId) return;
        
        const currentUserId = window.wsClient.userId;
        
        // Only hide sprites for the current user by checking which seats they were in
        if (window.userStatusManager && window.userStatusManager.userStatuses) {
            window.userStatusManager.userStatuses.forEach((statusData, seatId) => {
                if (statusData.userId === currentUserId) {
                    // Only hide sprites for the current user's old seat
                    this.hideUserStatusSprite(seatId);
                    console.log(`Cleared status sprite for current user's old seat ${seatId}`);
                }
            });
        }
        
        console.log('Cleared user status sprites only for current user seat change');
    }
    
    // Helper method to draw rounded rectangles (browser compatibility)
    drawRoundedRect(ctx, x, y, width, height, radius) {
        ctx.beginPath();
        ctx.moveTo(x + radius, y);
        ctx.lineTo(x + width - radius, y);
        ctx.quadraticCurveTo(x + width, y, x + width, y + radius);
        ctx.lineTo(x + width, y + height - radius);
        ctx.quadraticCurveTo(x + width, y + height, x + width - radius, y + height);
        ctx.lineTo(x + radius, y + height);
        ctx.quadraticCurveTo(x, y + height, x, y + height - radius);
        ctx.lineTo(x, y + radius);
        ctx.quadraticCurveTo(x, y, x + radius, y);
        ctx.closePath();
    }

    // Helper method to remove duplicate consumables (frontend safety check)
    deduplicateConsumables(consumables) {
        // More lenient deduplication: only remove exact object duplicates
        const seen = new Map();
        const result = [];
        
        for (const consumable of consumables) {
            // Use orderId as primary key, fall back to name+startTime for legacy items
            const primaryKey = consumable.orderId || consumable.itemId;
            const fallbackKey = `${consumable.itemName}_${consumable.startTime || Date.now()}`;
            const key = primaryKey || fallbackKey;
            
            // Only filter if we've seen the exact same order/item
            if (!seen.has(key)) {
                seen.set(key, consumable);
                result.push(consumable);
            } else {
                // Only filter if times are very similar (within 5 seconds)
                const existing = seen.get(key);
                const timeDiff = Math.abs((existing.remainingSeconds || 0) - (consumable.remainingSeconds || 0));
                if (timeDiff > 5) {
                    // Different enough to be a separate item
                    result.push(consumable);
                    console.log(`Allowing similar consumable with time difference: ${consumable.itemName}`);
                } else {
                    console.log(`Filtered duplicate consumable: ${consumable.itemName}`);
                }
            }
        }
        
        // Sort by remaining time (longest first) for better display
        result.sort((a, b) => (b.remainingSeconds || 0) - (a.remainingSeconds || 0));
        
        // Limit total items to prevent UI overflow
        const maxItems = 6;
        if (result.length > maxItems) {
            console.log(`Limiting consumables from ${result.length} to ${maxItems} items`);
            return result.slice(0, maxItems);
        }
        
        return result;
    }

    createChefSpeechBubble() {
        // Create initial empty canvas for speech bubble
        const canvas = this.createSpeechBubbleCanvas('');
        const texture = new THREE.CanvasTexture(canvas);
        const material = new THREE.SpriteMaterial({ map: texture, transparent: true });
        this.sprites.chefSpeechBubble = new THREE.Sprite(material);
        
        // Position to the right side of the chef
        this.sprites.chefSpeechBubble.position.set(3, 1, -1.9);
        this.sprites.chefSpeechBubble.scale.set(4, 2, 1);
        this.sprites.chefSpeechBubble.visible = false; // Initially hidden
        this.scene.add(this.sprites.chefSpeechBubble);
    }

    createSpeechBubbleCanvas(text) {
        const canvas = document.createElement('canvas');
        canvas.width = 400;
        canvas.height = 180;
        const ctx = canvas.getContext('2d');
        
        if (!text) {
            // Empty canvas for hidden state
            return canvas;
        }
        
        // Clear canvas
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        
        // Speech bubble background
        const bubbleX = 10;
        const bubbleY = 10;
        const bubbleWidth = canvas.width - 20;
        const bubbleHeight = canvas.height - 30;
        
        // Main bubble
        ctx.fillStyle = 'rgba(0, 0, 0, 0.9)';
        ctx.strokeStyle = '#ffd700';
        ctx.lineWidth = 3;
        this.drawRoundedRect(ctx, bubbleX, bubbleY, bubbleWidth, bubbleHeight, 15);
        ctx.fill();
        ctx.stroke();
        
        // Speech pointer (triangle pointing left toward chef)
        const pointerX = bubbleX;
        const pointerY = bubbleY + bubbleHeight / 2;
        ctx.fillStyle = 'rgba(0, 0, 0, 0.9)';
        ctx.beginPath();
        ctx.moveTo(pointerX, pointerY - 15);
        ctx.lineTo(pointerX, pointerY + 15);
        ctx.lineTo(pointerX - 15, pointerY);
        ctx.closePath();
        ctx.fill();
        
        // Pointer border
        ctx.strokeStyle = '#ffd700';
        ctx.lineWidth = 3;
        ctx.stroke();
        
        // Text
        ctx.fillStyle = '#ffffff';
        ctx.font = 'bold 13px Trebuchet MS';
        ctx.textAlign = 'left';
        ctx.textBaseline = 'top';
        
        // Split text into sentences for line-by-line display
        const sentences = text.split(/([.!?])/);
        const completeSentences = [];
        
        // Rebuild sentences (combine text with punctuation)
        for (let i = 0; i < sentences.length; i += 2) {
            if (sentences[i] && sentences[i].trim()) {
                const sentence = sentences[i].trim() + (sentences[i + 1] || '');
                if (sentence.length > 0) {
                    completeSentences.push(sentence);
                }
            }
        }
        
        // Limit to 2 sentences max
        const displaySentences = completeSentences.slice(-2);
        
        // Word wrap each sentence and create final display lines
        const maxWidth = bubbleWidth - 40; // Leave padding
        const wrappedLines = [];
        
        displaySentences.forEach(sentence => {
            const words = sentence.split(' ');
            let currentLine = '';
            
            words.forEach(word => {
                const testLine = currentLine ? currentLine + ' ' + word : word;
                const metrics = ctx.measureText(testLine);
                
                if (metrics.width > maxWidth && currentLine) {
                    // Current line is full, start new line
                    wrappedLines.push(currentLine);
                    currentLine = word;
                } else {
                    currentLine = testLine;
                }
            });
            
            // Add the last line of this sentence
            if (currentLine) {
                wrappedLines.push(currentLine);
            }
        });
        
        // Draw text lines with left alignment
        const lineHeight = 24;
        const textStartX = bubbleX + 20; // Left padding
        const textStartY = bubbleY + 20; // Top padding
        
        wrappedLines.forEach((line, index) => {
            ctx.fillText(line, textStartX, textStartY + index * lineHeight);
        });
        
        return canvas;
    }

    showChefSpeechBubble(message) {
        // Clear any existing animation
        this.hideChefSpeechBubble();
        
        // Split message into sentences
        const sentenceParts = message.split(/([.!?])/);
        const sentences = [];
        
        for (let i = 0; i < sentenceParts.length; i += 2) {
            if (sentenceParts[i] && sentenceParts[i].trim()) {
                const sentence = sentenceParts[i].trim() + (sentenceParts[i + 1] || '');
                if (sentence.length > 0) {
                    sentences.push(sentence);
                }
            }
        }
        
        // Initialize animation state
        this.speechBubble.currentMessage = message;
        this.speechBubble.sentences = sentences;
        this.speechBubble.currentSentenceIndex = 0;
        this.speechBubble.currentWordInSentence = 0;
        this.speechBubble.displayedSentences = [];
        this.speechBubble.isAnimating = true;
        
        // Start sentence-by-sentence animation
        this.animateNextSentence();
    }

    animateNextSentence() {
        if (!this.speechBubble.isAnimating || 
            this.speechBubble.currentSentenceIndex >= this.speechBubble.sentences.length) {
            // Animation complete - hold for a shorter time then hide
            setTimeout(() => {
                this.hideChefSpeechBubble();
            }, 2000);
            return;
        }
        
        // Start animating current sentence word by word
        this.speechBubble.currentWordInSentence = 0;
        this.animateWordsInCurrentSentence();
    }

    animateWordsInCurrentSentence() {
        const currentSentence = this.speechBubble.sentences[this.speechBubble.currentSentenceIndex];
        if (!currentSentence) return;
        
        const words = currentSentence.split(' ');
        
        if (this.speechBubble.currentWordInSentence >= words.length) {
            // Current sentence complete - add to displayed sentences
            this.speechBubble.displayedSentences.push(currentSentence);
            
            // Keep only last 2 sentences
            if (this.speechBubble.displayedSentences.length > 2) {
                this.speechBubble.displayedSentences.shift();
            }
            
            // Move to next sentence after pause
            this.speechBubble.currentSentenceIndex++;
            this.speechBubble.animationTimer = setTimeout(() => {
                this.animateNextSentence();
            }, 900); // Pause between sentences
            return;
        }
        
        // Build current sentence progress
        const currentWords = words.slice(0, this.speechBubble.currentWordInSentence + 1);
        const currentSentenceProgress = currentWords.join(' ');
        
        // Combine with previous completed sentences
        const allSentences = [...this.speechBubble.displayedSentences, currentSentenceProgress];
        const displayText = allSentences.join(' ');
        
        // Update speech bubble
        const canvas = this.createSpeechBubbleCanvas(displayText);
        if (this.sprites.chefSpeechBubble) {
            this.sprites.chefSpeechBubble.material.map = new THREE.CanvasTexture(canvas);
            this.sprites.chefSpeechBubble.material.needsUpdate = true;
            this.sprites.chefSpeechBubble.visible = true;
        }
        
        // Move to next word
        this.speechBubble.currentWordInSentence++;
        
        // Calculate delay for next word
        const currentWord = words[this.speechBubble.currentWordInSentence - 1] || '';
        let delay = 140; // Fast word display
        
        if (currentWord.includes(',') || currentWord.includes(';')) {
            delay = 400; // Medium pause for commas
        }
        
        // Schedule next word
        this.speechBubble.animationTimer = setTimeout(() => {
            this.animateWordsInCurrentSentence();
        }, delay);
    }

    hideChefSpeechBubble() {
        // Clear any ongoing animation
        if (this.speechBubble.animationTimer) {
            clearTimeout(this.speechBubble.animationTimer);
            this.speechBubble.animationTimer = null;
        }
        
        // Reset animation state
        this.speechBubble.isAnimating = false;
        this.speechBubble.currentMessage = '';
        this.speechBubble.sentences = [];
        this.speechBubble.currentSentenceIndex = 0;
        this.speechBubble.currentWordInSentence = 0;
        this.speechBubble.displayedSentences = [];
        
        // Hide sprite
        if (this.sprites.chefSpeechBubble) {
            this.sprites.chefSpeechBubble.visible = false;
        }
    }

    getSeatStates() {
        return this.seatStates;
    }

    createTVDisplaySprite() {
        // Create initial empty canvas for TV display
        const canvas = this.createTVCanvas('');
        const texture = new THREE.CanvasTexture(canvas);
        const material = new THREE.SpriteMaterial({ map: texture, transparent: true });
        this.sprites.tvDisplay = new THREE.Sprite(material);
        
        // Position TV prominently above the scene (center-top)
        this.sprites.tvDisplay.position.set(0, 2, -1);
        this.sprites.tvDisplay.scale.set(6, 4, 1); // Large and prominent
        this.sprites.tvDisplay.visible = false; // Initially hidden
        this.scene.add(this.sprites.tvDisplay);
        
        console.log('📺 TV display sprite created and positioned');
    }

    createTVCanvas(videoSession, status = 'off') {
        const canvas = document.createElement('canvas');
        canvas.width = 480;
        canvas.height = 320;
        const ctx = canvas.getContext('2d');
        
        // Clear canvas
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        
        if (status === 'off' || !videoSession) {
            // TV is off - show black screen with subtle border
            this.drawTVFrame(ctx, canvas.width, canvas.height);
            this.drawTVScreen(ctx, canvas.width, canvas.height, '#000000', 'TV');
            return canvas;
        }
        
        // TV is on - show video information
        this.drawTVFrame(ctx, canvas.width, canvas.height);
        this.drawTVScreen(ctx, canvas.width, canvas.height, '#1a1a2e', videoSession.videoTitle || 'Playing Video');
        
        return canvas;
    }

    drawTVFrame(ctx, width, height) {
        // TV outer frame (wood/plastic)
        ctx.fillStyle = '#4a3c28';
        ctx.fillRect(0, 0, width, height);
        
        // TV frame highlight
        ctx.fillStyle = '#6b5b47';
        ctx.fillRect(5, 5, width - 10, height - 10);
        
        // Screen bezel
        ctx.fillStyle = '#2a2a2a';
        ctx.fillRect(15, 15, width - 30, height - 30);
    }

    drawTVScreen(ctx, width, height, backgroundColor, text) {
        const screenX = 25;
        const screenY = 25;
        const screenWidth = width - 50;
        const screenHeight = height - 50;
        
        // Screen background
        ctx.fillStyle = backgroundColor;
        ctx.fillRect(screenX, screenY, screenWidth, screenHeight);
        
        if (backgroundColor === '#000000') {
            // TV is off - minimal reflection
            const gradient = ctx.createLinearGradient(screenX, screenY, screenX + screenWidth, screenY + screenHeight);
            gradient.addColorStop(0, 'rgba(255, 255, 255, 0.1)');
            gradient.addColorStop(1, 'rgba(255, 255, 255, 0.02)');
            ctx.fillStyle = gradient;
            ctx.fillRect(screenX, screenY, screenWidth, screenHeight);
            
            // Small power indicator
            ctx.fillStyle = '#ff4444';
            ctx.beginPath();
            ctx.arc(width - 35, height - 35, 3, 0, Math.PI * 2);
            ctx.fill();
            
        } else {
            // TV is on - show content
            
            // Video playing indicator
            ctx.fillStyle = '#00ff00';
            ctx.beginPath();
            ctx.arc(screenX + 15, screenY + 15, 4, 0, Math.PI * 2);
            ctx.fill();
            
            // Title text
            if (text && text !== 'Playing Video') {
                ctx.fillStyle = '#ffffff';
                ctx.font = 'bold 16px Arial';
                ctx.textAlign = 'center';
                ctx.textBaseline = 'middle';
                
                // Word wrap for long titles
                const maxWidth = screenWidth - 40;
                const words = text.split(' ');
                const lines = [];
                let currentLine = '';
                
                for (const word of words) {
                    const testLine = currentLine ? currentLine + ' ' + word : word;
                    const metrics = ctx.measureText(testLine);
                    
                    if (metrics.width > maxWidth && currentLine) {
                        lines.push(currentLine);
                        currentLine = word;
                    } else {
                        currentLine = testLine;
                    }
                }
                if (currentLine) lines.push(currentLine);
                
                // Draw lines
                const lineHeight = 20;
                const startY = screenY + screenHeight/2 - (lines.length * lineHeight)/2;
                
                lines.forEach((line, index) => {
                    ctx.fillText(line, screenX + screenWidth/2, startY + index * lineHeight);
                });
            }
            
            // Video player simulation - simple waveform or bars
            this.drawVideoVisualization(ctx, screenX, screenY, screenWidth, screenHeight);
        }
    }

    drawVideoVisualization(ctx, x, y, width, height) {
        // Simple visualization - audio bars or similar
        const barCount = 20;
        const barWidth = (width - 60) / barCount;
        const baseY = y + height - 40;
        
        ctx.fillStyle = '#4CAF50';
        
        for (let i = 0; i < barCount; i++) {
            // Random height for animation effect (in real implementation, this would be based on audio data)
            const barHeight = Math.random() * 30 + 5;
            const barX = x + 30 + i * barWidth;
            
            ctx.fillRect(barX, baseY - barHeight, barWidth - 2, barHeight);
        }
        
        // YouTube-style play button in center
        ctx.fillStyle = 'rgba(255, 255, 255, 0.9)';
        ctx.beginPath();
        const centerX = x + width/2;
        const centerY = y + height/2 - 20;
        const size = 25;
        
        ctx.moveTo(centerX - size/2, centerY - size/2);
        ctx.lineTo(centerX + size/2, centerY);
        ctx.lineTo(centerX - size/2, centerY + size/2);
        ctx.closePath();
        ctx.fill();
    }

    updateTVSprite(videoSession) {
        if (!this.sprites.tvDisplay) {
            console.warn('📺 TV sprite not created yet');
            return;
        }
        
        console.log('📺 Updating TV sprite for video:', videoSession.videoTitle);
        
        // Create updated canvas
        const canvas = this.createTVCanvas(videoSession, 'on');
        const texture = new THREE.CanvasTexture(canvas);
        
        // Update sprite material
        this.sprites.tvDisplay.material.map = texture;
        this.sprites.tvDisplay.material.needsUpdate = true;
        this.sprites.tvDisplay.visible = true;
        
        // Add a subtle glow effect by adjusting emissive color
        this.sprites.tvDisplay.material.color = new THREE.Color(1.1, 1.1, 1.1);
    }

    hideTVSprite() {
        if (this.sprites.tvDisplay) {
            // Show TV as off before hiding
            const canvas = this.createTVCanvas('', 'off');
            const texture = new THREE.CanvasTexture(canvas);
            this.sprites.tvDisplay.material.map = texture;
            this.sprites.tvDisplay.material.needsUpdate = true;
            
            // Keep visible for a moment to show "TV off" state
            setTimeout(() => {
                if (this.sprites.tvDisplay) {
                    this.sprites.tvDisplay.visible = false;
                }
            }, 1000);
            
            // Reset color
            this.sprites.tvDisplay.material.color = new THREE.Color(1, 1, 1);
            
            console.log('📺 TV sprite hidden');
        }
    }

    // Test function for TV sprite
    testTVSprite() {
        console.log('🧪 Testing TV sprite');
        
        const testVideoSession = {
            videoTitle: 'Test Video - YouTube Video Sharing',
            videoId: 'test123',
            isPlaying: true
        };
        
        this.updateTVSprite(testVideoSession);
        
        // Hide after 5 seconds
        setTimeout(() => {
            this.hideTVSprite();
        }, 5000);
    }
}