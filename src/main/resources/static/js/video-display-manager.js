/**
 * VideoDisplayManager - Simple shared TV experience with personal volume control
 */
class VideoDisplayManager {
    constructor() {
        this.currentRoomId = null;
        this.currentVideoSession = null;
        this.youtubePlayer = null;
        this.isPlayerReady = false;
        this.progressInterval = null;
        this.isInitialized = false;
        this.currentVolume = 50; // Default 50% volume
    }

    /**
     * Initialize the video display manager
     */
    init(roomId) {
        if (this.isInitialized) {
            this.setRoomId(roomId);
            return;
        }
        
        console.log('🎬 Initializing Simple VideoDisplayManager for room:', roomId);
        this.currentRoomId = roomId;
        
        this.setupWebSocketListeners();
        this.loadYouTubeAPI();
        this.setupUIControls();
        
        this.isInitialized = true;
        console.log('📺 Simple VideoDisplayManager initialized');
    }

    /**
     * Set or change the current room ID
     */
    setRoomId(roomId) {
        if (this.currentRoomId !== roomId) {
            console.log('📺 Changing video room from', this.currentRoomId, 'to', roomId);
            
            // Clean up previous room state
            this.stopCurrentVideo();
            this.currentRoomId = roomId;
            
            // Update WebSocket subscriptions
            this.setupWebSocketListeners();
        }
    }

    /**
     * Setup WebSocket listeners for video messages
     */
    setupWebSocketListeners() {
        if (!window.wsClient || !window.wsClient.isConnected()) {
            console.log('⏳ WebSocket not ready, retrying video listener setup...');
            setTimeout(() => this.setupWebSocketListeners(), 1000);
            return;
        }

        const videoTopic = `/topic/room/${this.currentRoomId}/video`;
        
        console.log('📺 Setting up room video subscription for room:', this.currentRoomId);
        
        // Subscribe to room-specific video updates
        window.wsClient.stompClient.subscribe(videoTopic, (message) => {
            try {
                const videoMessage = JSON.parse(message.body);
                console.log('📺 [ROOM] Received room video message:', {
                    type: videoMessage.type,
                    title: videoMessage.videoTitle || 'N/A',
                    playbackTime: videoMessage.playbackTime || 0,
                    isPlaying: videoMessage.isPlaying
                });
                this.handleVideoMessage(videoMessage);
            } catch (e) {
                console.error('❌ Error parsing room video message:', e);
            }
        });
        
        console.log('✅ Video WebSocket listeners setup for room:', this.currentRoomId);
    }

    /**
     * Handle incoming video messages from WebSocket
     */
    handleVideoMessage(message) {
        switch (message.type) {
            case 'VIDEO_START':
                this.handleVideoStart(message);
                break;
            case 'VIDEO_SYNC':
                this.handleVideoSync(message);
                break;
            case 'VIDEO_COMPLETE':
                this.handleVideoComplete(message);
                break;
            default:
                console.log('🎬 Unhandled video message type:', message.type);
        }
    }

    /**
     * Handle video start message
     */
    handleVideoStart(message) {
        console.log('🎬 Starting video:', message.videoTitle, 'at', message.playbackTime + 's');
        
        // Calculate correct start time based on current playback position
        const currentPlaybackSeconds = message.playbackTime || 0;
        const adjustedStartTime = Date.now() - (currentPlaybackSeconds * 1000);
        
        this.currentVideoSession = {
            videoId: message.videoId,
            videoUrl: message.videoUrl,
            videoTitle: message.videoTitle,
            startTime: adjustedStartTime,
            isPlaying: message.isPlaying !== false,
            initiatorUserId: message.userId,
            initiatorUserName: message.userName
        };
        
        // Update TV display
        this.updateTVDisplay(this.currentVideoSession);
        
        // Start YouTube player at correct time
        this.startYouTubePlayer(message.videoId, currentPlaybackSeconds);
    }

    /**
     * Handle video sync message for time synchronization
     */
    handleVideoSync(message) {
        if (!this.currentVideoSession) {
            return; // No active session
        }
        
        const serverTime = message.playbackTime;
        const isServerPlaying = message.isPlaying;
        
        // Sync with server state - simple and reliable
        if (this.youtubePlayer && this.isPlayerReady) {
            const currentTime = this.youtubePlayer.getCurrentTime();
            const timeDifference = Math.abs(currentTime - serverTime);
            
            // Sync if difference is more than 2 seconds
            if (timeDifference > 2) {
                console.log('🎬 Syncing video time:', currentTime, '->', serverTime);
                this.youtubePlayer.seekTo(serverTime, true);
            }
            
            // Sync playback state
            const playerState = this.youtubePlayer.getPlayerState();
            const isPlayerPlaying = (playerState === YT.PlayerState.PLAYING);
            
            if (isServerPlaying && !isPlayerPlaying) {
                console.log('🎬 Syncing to server: playing video');
                this.youtubePlayer.playVideo();
            } else if (!isServerPlaying && isPlayerPlaying) {
                console.log('🎬 Syncing to server: pausing video');
                this.youtubePlayer.pauseVideo();
            }
        }
    }

    /**
     * Handle video completion
     */
    handleVideoComplete(message) {
        console.log('🎬 Video completed:', message.videoTitle);
        
        this.stopCurrentVideo();
        this.hideTVDisplay();
        
        // Show completion message briefly
        const videoTitle = document.getElementById('video-title');
        if (videoTitle) {
            videoTitle.textContent = 'Video completed: ' + (message.videoTitle || 'Video');
            setTimeout(() => {
                this.hideTVDisplay();
            }, 3000);
        }
    }

    /**
     * Load YouTube iframe API
     */
    loadYouTubeAPI() {
        if (window.YT && window.YT.Player) {
            console.log('🎬 YouTube API already loaded');
            return;
        }
        
        // Load YouTube iframe API
        const tag = document.createElement('script');
        tag.src = 'https://www.youtube.com/iframe_api';
        const firstScriptTag = document.getElementsByTagName('script')[0];
        firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);
        
        // Set global callback for when API is ready
        window.onYouTubeIframeAPIReady = () => {
            console.log('🎬 YouTube API loaded and ready');
        };
    }

    /**
     * Start YouTube player with video ID
     */
    startYouTubePlayer(videoId, startSeconds = 0) {
        if (!window.YT || !window.YT.Player) {
            console.log('🎬 YouTube API not ready, retrying...');
            setTimeout(() => this.startYouTubePlayer(videoId, startSeconds), 1000);
            return;
        }
        
        // Create hidden div for YouTube player if it doesn't exist
        let playerDiv = document.getElementById('youtube-player');
        if (!playerDiv) {
            playerDiv = document.createElement('div');
            playerDiv.id = 'youtube-player';
            playerDiv.style.cssText = `
                position: absolute;
                top: -9999px;
                left: -9999px;
                width: 640px;
                height: 360px;
                opacity: 0;
                pointer-events: none;
            `;
            document.body.appendChild(playerDiv);
        }
        
        // Destroy existing player
        if (this.youtubePlayer) {
            try {
                this.youtubePlayer.destroy();
                console.log('🎬 Previous YouTube player destroyed');
            } catch (e) {
                console.warn('🎬 Error destroying previous player:', e.message);
            }
        }
        
        // Create new YouTube player
        this.youtubePlayer = new YT.Player('youtube-player', {
            width: 640,
            height: 360,
            videoId: videoId,
            playerVars: {
                autoplay: 0,  // Don't autoplay
                controls: 0,
                disablekb: 1,
                fs: 0,
                modestbranding: 1,
                playsinline: 1
            },
            events: {
                onReady: (event) => {
                    console.log('🎬 YouTube player ready, seeking to', startSeconds + 's');
                    this.isPlayerReady = true;
                    
                    // Set volume to current volume setting
                    event.target.setVolume(this.currentVolume);
                    
                    if (startSeconds > 0) {
                        event.target.seekTo(startSeconds, true);
                    }
                    
                    // Try to play
                    event.target.playVideo();
                    console.log('🎬 Player started at volume', this.currentVolume + '%');
                },
                onStateChange: (event) => {
                    this.handlePlayerStateChange(event);
                }
            }
        });
    }

    /**
     * Handle YouTube player state changes
     */
    handlePlayerStateChange(event) {
        const state = event.data;
        
        switch (state) {
            case YT.PlayerState.PLAYING:
                console.log('🎬 YouTube player: Playing');
                break;
            case YT.PlayerState.PAUSED:
                console.log('🎬 YouTube player: Paused');
                break;
            case YT.PlayerState.ENDED:
                console.log('🎬 YouTube player: Ended');
                break;
        }
    }

    /**
     * Setup UI controls for volume and stop
     */
    setupUIControls() {
        const volumeSlider = document.getElementById('video-volume');
        const volumeDisplay = document.getElementById('volume-display');
        const stopBtn = document.getElementById('video-stop-btn');
        
        if (volumeSlider) {
            volumeSlider.addEventListener('input', (e) => {
                const volume = parseInt(e.target.value);
                this.setVolume(volume);
                if (volumeDisplay) {
                    volumeDisplay.textContent = volume + '%';
                }
            });
        }
        
        if (stopBtn) {
            stopBtn.addEventListener('click', () => {
                this.stopVideo();
            });
        }
    }

    /**
     * Set volume (0-100)
     */
    setVolume(volume) {
        this.currentVolume = Math.max(0, Math.min(100, volume));
        
        if (this.youtubePlayer && this.isPlayerReady) {
            this.youtubePlayer.setVolume(this.currentVolume);
            console.log('🎬 Volume set to', this.currentVolume + '%');
        }
    }

    /**
     * Update HTML TV display with video information
     */
    updateTVDisplay(videoSession) {
        const videoDisplay = document.getElementById('video-display');
        const videoTitle = document.getElementById('video-title');
        const videoInitiator = document.getElementById('video-initiator');
        const powerIndicator = document.querySelector('.tv-power-indicator');
        
        if (!videoDisplay) {
            console.warn('🎬 Video display element not found');
            return;
        }
        
        console.log('🎬 Updating TV display for video:', videoSession.videoTitle);
        
        // Show the TV display
        videoDisplay.classList.remove('hidden');
        
        // Update title
        if (videoTitle) {
            videoTitle.textContent = videoSession.videoTitle || 'YouTube Video';
        }
        
        // Update initiator info
        if (videoInitiator) {
            const initiatorName = videoSession.initiatorUserName || 'Unknown User';
            videoInitiator.textContent = `Played by: ${initiatorName}`;
        }
        
        // Update power indicator to show TV is on
        if (powerIndicator) {
            powerIndicator.classList.add('on');
        }
        
        // Start updating progress
        this.startProgressUpdates();
    }

    /**
     * Hide HTML TV display
     */
    hideTVDisplay() {
        const videoDisplay = document.getElementById('video-display');
        const videoTitle = document.getElementById('video-title');
        const videoInitiator = document.getElementById('video-initiator');
        const powerIndicator = document.querySelector('.tv-power-indicator');
        const progressFill = document.querySelector('.progress-fill');
        const videoTime = document.getElementById('video-time');
        
        if (videoDisplay) {
            videoDisplay.classList.add('hidden');
        }
        
        if (videoTitle) {
            videoTitle.textContent = 'No video playing';
        }
        
        if (videoInitiator) {
            videoInitiator.textContent = 'Played by: Nobody';
        }
        
        if (powerIndicator) {
            powerIndicator.classList.remove('on');
        }
        
        if (progressFill) {
            progressFill.style.width = '0%';
        }
        
        if (videoTime) {
            videoTime.textContent = '00:00 / 00:00';
        }
        
        this.stopProgressUpdates();
        console.log('🎬 TV display hidden');
    }

    /**
     * Start updating progress bar
     */
    startProgressUpdates() {
        this.stopProgressUpdates(); // Clear any existing interval
        
        this.progressInterval = setInterval(() => {
            this.updateProgress();
        }, 1000); // Update every second
    }

    /**
     * Stop updating progress bar
     */
    stopProgressUpdates() {
        if (this.progressInterval) {
            clearInterval(this.progressInterval);
            this.progressInterval = null;
        }
    }

    /**
     * Update progress bar and time display
     */
    updateProgress() {
        if (!this.currentVideoSession || !this.youtubePlayer || !this.isPlayerReady) {
            return;
        }
        
        const currentTime = this.youtubePlayer.getCurrentTime();
        const duration = this.youtubePlayer.getDuration();
        
        if (duration > 0) {
            const progressPercent = (currentTime / duration) * 100;
            const progressFill = document.querySelector('.progress-fill');
            const videoTime = document.getElementById('video-time');
            
            if (progressFill) {
                progressFill.style.width = progressPercent + '%';
            }
            
            if (videoTime) {
                const currentMin = Math.floor(currentTime / 60);
                const currentSec = Math.floor(currentTime % 60);
                const durationMin = Math.floor(duration / 60);
                const durationSec = Math.floor(duration % 60);
                
                videoTime.textContent = 
                    `${currentMin}:${currentSec.toString().padStart(2, '0')} / ` +
                    `${durationMin}:${durationSec.toString().padStart(2, '0')}`;
            }
        }
    }

    /**
     * Stop video for everyone (room-wide stop)
     */
    stopVideo() {
        console.log('🎬 User stopped video for room');
        
        if (!window.wsClient || !this.currentRoomId) return;
        
        const stopMessage = {
            type: 'VIDEO_STOP',
            userId: window.wsClient.userId,
            userName: window.wsClient.username,
            roomId: this.currentRoomId,
            timestamp: Date.now()
        };
        
        window.wsClient.stompClient.send('/app/room.sendMessage', {}, JSON.stringify({
            userId: window.wsClient.userId,
            userName: window.wsClient.username,
            content: '/tv stop',
            roomId: this.currentRoomId
        }));
    }

    /**
     * Stop current video completely
     */
    stopCurrentVideo() {
        if (this.youtubePlayer) {
            try {
                this.youtubePlayer.stopVideo();
            } catch (e) {
                console.warn('🎬 Error stopping YouTube player:', e.message);
            }
        }
        
        this.currentVideoSession = null;
        this.stopProgressUpdates();
    }

    /**
     * Cleanup method
     */
    cleanup() {
        this.stopCurrentVideo();
        this.hideTVDisplay();
        
        if (this.youtubePlayer) {
            try {
                this.youtubePlayer.destroy();
            } catch (e) {
                console.warn('🎬 Error destroying player:', e.message);
            }
            this.youtubePlayer = null;
        }
        
        this.isPlayerReady = false;
        
        const playerDiv = document.getElementById('youtube-player');
        if (playerDiv) {
            playerDiv.remove();
        }
        
        console.log('🧹 VideoDisplayManager cleaned up');
    }

    /**
     * Get current video session info for debugging
     */
    getDebugInfo() {
        return {
            currentRoomId: this.currentRoomId,
            currentVideoSession: this.currentVideoSession,
            currentVolume: this.currentVolume,
            isPlayerReady: this.isPlayerReady,
            youtubePlayerState: this.youtubePlayer ? this.youtubePlayer.getPlayerState() : null
        };
    }
}

// Create global instance
window.videoDisplayManager = new VideoDisplayManager();

console.log('Simple VideoDisplayManager loaded');