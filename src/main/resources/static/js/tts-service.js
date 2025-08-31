class TTSService {
    constructor() {
        // Use Spring Boot proxy endpoint instead of external TTS API
        this.apiUrl = '/api/tts';
        this.isEnabled = true;
        this.currentAudio = null;
        this.isPlaying = false;
        this.defaultVoice = 'am_michael';
        this.timeout = 10000;
        this.configLoaded = true; // No need to load config from server anymore
        
        // Queue system for managing TTS - separate generation and playback
        this.speechQueue = []; // Queue of requests to generate
        this.audioQueue = [];  // Queue of generated audio blobs ready to play
        this.isProcessingQueue = false;
        this.isProcessingPlayback = false;
        
        // Simple timestamp cutoff to prevent TTS spam on reload
        // Add 2 second buffer to allow page loading to complete
        this.startTime = new Date(Date.now() + 2000);
        
        // Initialize audio context for better browser compatibility
        this.audioContext = null;
        this.initializeAudioContext();
        
        // Load status from server for UI display
        this.loadStatus();
    }

    /**
     * Initialize Web Audio API context
     */
    initializeAudioContext() {
        try {
            this.audioContext = new (window.AudioContext || window.webkitAudioContext)();
        } catch (error) {
            console.warn('Web Audio API not available:', error);
        }
    }

    /**
     * Load TTS status from server for display purposes
     */
    async loadStatus() {
        try {
            console.log('🔊 TTS: Loading status from server...');
            
            const response = await fetch('/api/tts/status');
            if (response.ok) {
                const status = await response.json();
                
                this.defaultVoice = status.defaultVoice;
                this.isEnabled = status.enabled;
                this.timeout = status.timeout;
                
                console.log('🔊 TTS: Status loaded:', status);
            } else {
                console.warn('🔊 TTS: Failed to load status from server');
            }
        } catch (error) {
            console.warn('🔊 TTS: Error loading status:', error);
        }
    }

    /**
     * Add speech request to queue
     * @param {string} text - The text to convert to speech
     * @param {string} voice - Optional voice parameter (defaults to Master voice)
     * @returns {Promise<void>}
     */
    async speak(text, voice = null) {
        if (!this.isEnabled || !text || text.trim().length === 0) {
            return;
        }

        // Simple cutoff: skip TTS for first few seconds after page load
        const requestTime = new Date();
        if (requestTime < this.startTime) {
            console.log('🔊 TTS: Skipping message during initial load period');
            return;
        }

        // Clean the text first
        const cleanText = this.cleanTextForTTS(text);
        
        // Create speech request
        const speechRequest = {
            text: cleanText,
            voice: voice || this.defaultVoice,
            timestamp: Date.now(),
            id: Math.random().toString(36).substr(2, 9)
        };

        console.log('🔊 TTS: Adding to queue:', speechRequest.id, '-', cleanText.substring(0, 50) + '...');

        // Add to speech queue for generation
        this.speechQueue.push(speechRequest);
        
        // Update queue display
        this.notifyQueueUpdate();

        // Start processing speech generation immediately (parallel generation)
        this.generateSpeechAsync(speechRequest);

        // Note: Don't start playback queue here - it will be started when audio is ready
    }

    /**
     * Generate speech asynchronously and add to audio queue
     * @param {Object} speechRequest - The speech request object
     */
    async generateSpeechAsync(speechRequest) {
        try {
            console.log('🔊 TTS: Generating speech for:', speechRequest.id);
            
            const audioBlob = await this.generateSpeechBlob(speechRequest.text, speechRequest.voice);
            
            // Create audio item with the original request info
            const audioItem = {
                id: speechRequest.id,
                text: speechRequest.text,
                voice: speechRequest.voice,
                audioBlob: audioBlob,
                audioUrl: URL.createObjectURL(audioBlob),
                timestamp: speechRequest.timestamp
            };
            
            // Add to audio queue in correct order
            this.audioQueue.push(audioItem);
            console.log('🔊 TTS: Audio ready for:', speechRequest.id, '- Queue length:', this.audioQueue.length);
            
            // Remove from speech generation queue
            const index = this.speechQueue.findIndex(req => req.id === speechRequest.id);
            if (index !== -1) {
                this.speechQueue.splice(index, 1);
            }
            
            this.notifyQueueUpdate();
            
            // Restart playback queue if it's not running and we have audio ready
            if (!this.isProcessingPlayback && this.audioQueue.length > 0) {
                console.log('🔊 TTS: Restarting playback queue - audio is ready');
                this.processPlaybackQueue();
            }
            
        } catch (error) {
            console.error('🔊 TTS: Error generating speech for:', speechRequest.id, error);
            
            // Remove failed request from speech queue
            const index = this.speechQueue.findIndex(req => req.id === speechRequest.id);
            if (index !== -1) {
                this.speechQueue.splice(index, 1);
                this.notifyQueueUpdate();
            }
            
            // Show user-friendly error message
            if (window.meshiya && window.meshiya.uiManager) {
                window.meshiya.uiManager.addSystemMessage('TTS service unavailable');
            }
        }
    }

    /**
     * Process the audio playback queue sequentially
     */
    async processPlaybackQueue() {
        if (this.isProcessingPlayback) {
            console.log('🔊 TTS: Playback queue already processing, skipping');
            return;
        }

        this.isProcessingPlayback = true;
        console.log('🔊 TTS: Starting playback queue processing - Audio queue length:', this.audioQueue.length);

        while (this.audioQueue.length > 0) {
            const audioItem = this.audioQueue.shift();
            console.log('🔊 TTS: Playing audio:', audioItem.id, '-', audioItem.text.substring(0, 50) + '...');

            try {
                await this.playAudio(audioItem.audioUrl);
                console.log('🔊 TTS: Completed playback:', audioItem.id);
                
                // Clean up the URL after playback
                URL.revokeObjectURL(audioItem.audioUrl);
                
            } catch (error) {
                console.error('🔊 TTS: Error playing audio:', audioItem.id, error);
                URL.revokeObjectURL(audioItem.audioUrl);
            }

            // Update queue display
            this.notifyQueueUpdate();
        }

        this.isProcessingPlayback = false;
        console.log('🔊 TTS: Playback queue processing completed');
    }

    /**
     * Generate speech blob (without playing)
     * @param {string} text - The text to convert to speech
     * @param {string} voice - Voice to use
     * @returns {Promise<Blob>} - Audio blob
     */
    async generateSpeechBlob(text, voice) {
        console.log('🔊 TTS: Converting text to speech:', text.substring(0, 50) + '...');

        // Prepare request data
        const requestData = {
            text: text,
            voice: voice
        };

        // Make API call to Spring Boot TTS proxy
        const response = await fetch(this.apiUrl, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(requestData)
        });

        console.log('🔊 TTS: API Response status:', response.status, response.statusText);

        if (!response.ok) {
            const errorText = await response.text();
            console.error('🔊 TTS: API Error response:', errorText);
            throw new Error(`TTS API request failed: ${response.status} ${response.statusText}`);
        }

        // Get audio data as blob
        const audioBlob = await response.blob();
        console.log('🔊 TTS: Generated audio blob size:', audioBlob.size, 'bytes');
        
        return audioBlob;
    }

    /**
     * Clean text for better TTS pronunciation
     * @param {string} text - Raw text from Master response
     * @returns {string} - Cleaned text suitable for TTS
     */
    cleanTextForTTS(text) {
        return text
            // Remove markdown formatting
            .replace(/\*\*/g, '') // Remove bold
            .replace(/\*/g, '')   // Remove italic
            .replace(/`/g, '')    // Remove code formatting
            .replace(/_/g, ' ')   // Replace underscores with spaces
            
            // Clean up action descriptions in asterisks
            .replace(/\*([^*]+)\*/g, '$1') // Keep content, remove asterisks
            
            // Normalize whitespace
            .replace(/\s+/g, ' ')
            .trim()
            
            // Ensure proper sentence endings for natural speech
            .replace(/([.!?])\s*$/, '$1');
    }

    /**
     * Play audio from URL
     * @param {string} audioUrl - The audio URL to play
     * @returns {Promise<void>}
     */
    async playAudio(audioUrl) {
        return new Promise((resolve, reject) => {
            try {
                this.currentAudio = new Audio(audioUrl);
                this.currentAudio.volume = 0.7; // Set comfortable volume
                
                this.currentAudio.onloadstart = () => {
                    console.log('🔊 TTS: Audio loading started');
                };

                this.currentAudio.oncanplaythrough = () => {
                    console.log('🔊 TTS: Audio ready to play');
                };

                this.currentAudio.onplay = () => {
                    this.isPlaying = true;
                    console.log('🔊 TTS: Audio playback started');
                    this.notifyPlaybackStart();
                };

                this.currentAudio.onended = () => {
                    this.isPlaying = false;
                    console.log('🔊 TTS: Audio playback ended');
                    this.cleanup(audioUrl);
                    this.notifyPlaybackEnd();
                    resolve();
                };

                this.currentAudio.onerror = (error) => {
                    this.isPlaying = false;
                    console.error('🔊 TTS: Audio playback error:', error);
                    this.cleanup(audioUrl);
                    reject(error);
                };

                this.currentAudio.onabort = () => {
                    this.isPlaying = false;
                    console.log('🔊 TTS: Audio playback aborted');
                    this.cleanup(audioUrl);
                    resolve();
                };

                // Start playing
                this.currentAudio.play().catch(error => {
                    console.error('🔊 TTS: Failed to start audio playback:', error);
                    this.cleanup(audioUrl);
                    reject(error);
                });

            } catch (error) {
                console.error('🔊 TTS: Error creating audio element:', error);
                reject(error);
            }
        });
    }

    /**
     * Stop current audio playback and clear all queues
     */
    stop() {
        console.log('🔊 TTS: Stopping TTS and clearing all queues');
        
        // Stop current audio
        if (this.currentAudio && this.isPlaying) {
            this.currentAudio.pause();
            this.currentAudio.currentTime = 0;
            this.isPlaying = false;
        }
        
        // Clear all queues
        this.clearQueue();
    }
    
    /**
     * Stop only the current audio (but keep queue)
     */
    stopCurrent() {
        if (this.currentAudio && this.isPlaying) {
            console.log('🔊 TTS: Stopping current audio playback');
            this.currentAudio.pause();
            this.currentAudio.currentTime = 0;
            this.isPlaying = false;
        }
    }
    
    /**
     * Clear all queues
     */
    clearQueue() {
        const speechQueueLength = this.speechQueue.length;
        const audioQueueLength = this.audioQueue.length;
        
        // Clean up audio URLs before clearing
        this.audioQueue.forEach(audioItem => {
            if (audioItem.audioUrl) {
                URL.revokeObjectURL(audioItem.audioUrl);
            }
        });
        
        // Clear both queues
        this.speechQueue = [];
        this.audioQueue = [];
        this.isProcessingQueue = false;
        this.isProcessingPlayback = false;
        
        const totalCleared = speechQueueLength + audioQueueLength;
        if (totalCleared > 0) {
            console.log('🔊 TTS: Cleared queues - Speech:', speechQueueLength, 'Audio:', audioQueueLength);
            this.notifyQueueUpdate();
        }
    }
    
    /**
     * Skip the current audio and move to next in queue
     */
    skipCurrent() {
        console.log('🔊 TTS: Skipping current audio');
        this.stopCurrent();
        
        // Continue processing playback if there are more audio items ready
        if (this.audioQueue.length > 0 && !this.isProcessingPlayback) {
            this.processPlaybackQueue();
        }
    }

    /**
     * Clean up audio resources
     * @param {string} audioUrl - The audio URL to revoke
     */
    cleanup(audioUrl) {
        if (audioUrl) {
            URL.revokeObjectURL(audioUrl);
        }
        this.currentAudio = null;
    }

    /**
     * Enable or disable TTS
     * @param {boolean} enabled - Whether TTS should be enabled
     */
    setEnabled(enabled) {
        this.isEnabled = enabled;
        console.log(`🔊 TTS: ${enabled ? 'Enabled' : 'Disabled'}`);
        
        if (!enabled) {
            this.stop();
        }
    }

    /**
     * Check if TTS is currently enabled
     * @returns {boolean}
     */
    isAvailable() {
        return this.isEnabled;
    }

    /**
     * Check if TTS is currently playing
     * @returns {boolean}
     */
    isCurrentlyPlaying() {
        return this.isPlaying;
    }

    /**
     * Set the default voice for Master responses
     * @param {string} voice - The voice identifier
     */
    setDefaultVoice(voice) {
        this.defaultVoice = voice;
        console.log('🔊 TTS: Default voice set to:', voice);
    }

    /**
     * Get current TTS settings
     * @returns {Object}
     */
    getSettings() {
        return {
            enabled: this.isEnabled,
            defaultVoice: this.defaultVoice,
            isPlaying: this.isPlaying,
            apiUrl: this.apiUrl,
            startTime: this.startTime
        };
    }

    /**
     * Notify UI about playback start (can be extended for visual indicators)
     */
    notifyPlaybackStart() {
        // Dispatch custom event for UI components to listen
        document.dispatchEvent(new CustomEvent('tts-playback-start'));
    }

    /**
     * Notify UI about playback end (can be extended for visual indicators)
     */
    notifyPlaybackEnd() {
        // Dispatch custom event for UI components to listen
        document.dispatchEvent(new CustomEvent('tts-playback-end'));
    }

    /**
     * Test TTS functionality with sample text
     */
    async test() {
        const testText = "Hello! The TTS service is working correctly.";
        console.log('🔊 TTS: Testing with sample text');
        await this.speak(testText);
    }
    
    /**
     * Get current queue status
     */
    getQueueStatus() {
        const totalQueue = this.speechQueue.length + this.audioQueue.length;
        const currentPlayingItem = this.isPlaying && this.audioQueue.length > 0 ? 
                                  this.audioQueue[0].text.substring(0, 50) + '...' : null;
        
        return {
            queueLength: totalQueue,
            speechQueueLength: this.speechQueue.length,
            audioQueueLength: this.audioQueue.length,
            isProcessing: this.isProcessingQueue || this.isProcessingPlayback,
            isGenerating: this.speechQueue.length > 0,
            isPlaying: this.isPlaying,
            currentItem: currentPlayingItem
        };
    }
    
    /**
     * Get queue contents for display
     */
    getQueue() {
        const speechItems = this.speechQueue.map(item => ({
            id: item.id,
            text: item.text.length > 100 ? item.text.substring(0, 100) + '...' : item.text,
            voice: item.voice,
            timestamp: item.timestamp,
            status: 'generating'
        }));
        
        const audioItems = this.audioQueue.map(item => ({
            id: item.id,
            text: item.text.length > 100 ? item.text.substring(0, 100) + '...' : item.text,
            voice: item.voice,
            timestamp: item.timestamp,
            status: 'ready'
        }));
        
        return [...speechItems, ...audioItems];
    }
    
    /**
     * Notify UI about queue updates
     */
    notifyQueueUpdate() {
        const status = this.getQueueStatus();
        document.dispatchEvent(new CustomEvent('tts-queue-update', { detail: status }));
    }
}