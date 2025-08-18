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
            masterStatusLabel: null
        };
        this.raycaster = new THREE.Raycaster();
        this.mouse = new THREE.Vector2();
        
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
        // Try to load external chef image first
        const loader = new THREE.TextureLoader();
        loader.load('/assets/images/chef.png', (texture) => {
            const material = new THREE.SpriteMaterial({ map: texture, transparent: true });
            this.sprites.master = new THREE.Sprite(material);
            this.sprites.master.position.set(0, 1, 0);
            this.sprites.master.scale.set(4, 4, 1);
            this.scene.add(this.sprites.master);
        }, undefined, (error) => {
            console.warn('Could not load chef.png, falling back to canvas master');
            this.createFallbackMaster();
        });
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
        this.sprites.master.position.set(0, 1, 0);
        this.sprites.master.scale.set(4, 4, 1);
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
        });
    }
    
    createSeatSprite(seatNumber) {
        // Try to load external stool image first
        const loader = new THREE.TextureLoader();
        const sprite = new THREE.Sprite();
        
        loader.load('/assets/images/stool.png', (texture) => {
            const material = new THREE.SpriteMaterial({ map: texture, transparent: true });
            sprite.material = material;
        }, undefined, (error) => {
            console.warn('Could not load stool.png, falling back to canvas stool');
            sprite.material = this.createFallbackSeatMaterial(seatNumber);
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

    createCustomerSprite(seatNumber, isCurrentUser = false) {
        // Try to load external customer image first
        const loader = new THREE.TextureLoader();
        const sprite = new THREE.Sprite();
        
        const imageName = isCurrentUser ? 'customer-self.png' : 'customer.png';
        
        loader.load(`/assets/images/${imageName}`, (texture) => {
            const material = new THREE.SpriteMaterial({ map: texture, transparent: true });
            sprite.material = material;
        }, undefined, (error) => {
            console.warn(`Could not load ${imageName}, falling back to canvas customer`);
            sprite.material = this.createFallbackCustomerMaterial(isCurrentUser);
        });
        
        // Position customer slightly behind and above the seat
        const seat = this.seats[seatNumber - 1];
        if (seat) {
            sprite.position.set(seat.position.x, seat.position.y + 0.5, 2);
            sprite.scale.set(2, 2, 1);
        }
        
        return sprite;
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
    
    addCustomerToSeat(seatNumber, isCurrentUser = false) {
        // Remove any existing customer sprite for this seat
        this.removeCustomerFromSeat(seatNumber);
        
        // Create new customer sprite
        const customerSprite = this.createCustomerSprite(seatNumber, isCurrentUser);
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
            this.addCustomerToSeat(seatNumber, isCurrentUser);
            
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
                this.addCustomerToSeat(seatNumber, isCurrentUser);
            } else {
                this.updateSeatState(seatNumber, false, false);
                this.removeCustomerFromSeat(seatNumber);
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
            
            // Position above each seat - moved up higher
            const seat = this.seats[i];
            if (seat) {
                statusSprite.position.set(seat.position.x, seat.position.y + 2.5, 3);
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
            
            // Position image box centered where status box used to be
            if (seat) {
                imageSprite.position.set(seat.position.x, seat.position.y + 1.8, 3);
                imageSprite.scale.set(1.2, 1.2, 1);
            }
            imageSprite.visible = false; // Initially hidden
            
            this.sprites.userImageBoxes.push(imageSprite);
            this.scene.add(imageSprite);
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
        
        // Set image source (base64 data)
        if (imageData.startsWith('data:image/')) {
            img.src = imageData;
        } else {
            // Assume base64 encoded PNG
            img.src = 'data:image/png;base64,' + imageData;
        }
        
        return canvas;
    }
    
    createCombinedImageCanvas(consumablesWithImages, callback) {
        const imageSize = 128;
        const spacing = 8; // Small gap between images
        const totalWidth = (imageSize * consumablesWithImages.length) + (spacing * (consumablesWithImages.length - 1));
        
        const canvas = document.createElement('canvas');
        canvas.width = totalWidth;
        canvas.height = imageSize;
        const ctx = canvas.getContext('2d');
        
        // Clear canvas
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        
        let loadedImages = 0;
        const totalImages = consumablesWithImages.length;
        
        // Load all images and draw them side by side
        consumablesWithImages.forEach((consumable, index) => {
            const img = new Image();
            img.onload = () => {
                // Calculate position for this image
                const x = index * (imageSize + spacing);
                
                // Draw the image
                ctx.drawImage(img, x, 0, imageSize, imageSize);
                
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
            
            // Set image source
            if (consumable.imageData.startsWith('data:image/')) {
                img.src = consumable.imageData;
            } else {
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
        
        // Update image display if any consumable has image data
        this.updateUserImageSprite(seatId, uniqueConsumables);
    }
    
    updateUserImageSprite(seatId, consumables) {
        const seatIndex = seatId - 1;
        if (seatIndex < 0 || seatIndex >= this.sprites.userImageBoxes.length) return;
        
        // Find all consumables with image data
        const consumablesWithImages = consumables.filter(c => c.imageData && c.imageData.trim());
        
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

    getSeatStates() {
        return this.seatStates;
    }
}