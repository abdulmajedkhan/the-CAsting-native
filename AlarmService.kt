package com.gdelataillade.alarm.alarm
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.gdelataillade.alarm.casting.CastingHelper
import com.gdelataillade.alarm.casting.CastingState
import com.gdelataillade.alarm.casting.CastingStateListener
import com.gdelataillade.alarm.models.AlarmSettings
import com.gdelataillade.alarm.services.AlarmRingingLiveData
import com.gdelataillade.alarm.services.AlarmStorage
import com.gdelataillade.alarm.services.AudioService
import com.gdelataillade.alarm.services.NotificationHandler
import com.gdelataillade.alarm.services.NotificationOnKillService
import com.gdelataillade.alarm.services.VibrationService
import com.gdelataillade.alarm.services.VolumeService
import io.flutter.Log

class AlarmService : Service() {
    companion object {
        private const val TAG = "AlarmService"
        var instance: AlarmService? = null
        @JvmStatic var ringingAlarmIds: List<Int> = listOf()

        // Safety timeout in case completion detection fails (2 hours max)
        private const val MAX_SAFETY_TIMEOUT_MS = 7200000L // 2 hours
        private const val PLAYBACK_VERIFICATION_DELAY_MS = 10000L // 10 seconds
    }

    private var alarmId: Int = 0
    private var audioService: AudioService? = null
    private var vibrationService: VibrationService? = null
    private var volumeService: VolumeService? = null
    private var alarmStorage: AlarmStorage? = null

    private var castingHelper: CastingHelper? = null
    private var isAlarmCastingActive: Boolean = false
    private var alarmSafetyTimeoutRunnable: Runnable? = null
    private var playbackVerificationRunnable: Runnable? = null

    private var isPlayingSequence: Boolean = false
    private var currentSequenceStep: Int = 0
    private var primaryAudioCompleted: Boolean = false
    private var secondaryAudioScheduled: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private var currentAlarmSettings: AlarmSettings? = null

    // Button press and screen receivers
    private var volumeReceiver: android.content.BroadcastReceiver? = null
    private var screenReceiver: android.content.BroadcastReceiver? = null
    private var lastProgrammaticVolumeTime: Long = 0

    // Casting state listener
    private var castingStateListener: CastingStateListener? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🔊 AlarmService CREATED")
        instance = this
        audioService = AudioService(this)
        vibrationService = VibrationService(this)
        volumeService = VolumeService(this)
        alarmStorage = AlarmStorage(this)

        castingHelper = CastingHelper.getInstance(this)
        Log.d(TAG, "🎯 AlarmService initialized with CastingHelper")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "🔊 AlarmService onStartCommand called")

        if (intent == null) {
            Log.w(TAG, "🔊 Null intent received - stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        val id = intent.getIntExtra("id", 0)
        alarmId = id
        val action = intent.getStringExtra(AlarmReceiver.EXTRA_ALARM_ACTION)

        Log.d(TAG, "🔊 Processing alarm ID: $id, Action: $action")

        if (action == "STOP_ALARM" && id != 0) {
            Log.d(TAG, "🔊 STOP ALARM command received for ID: $id")
            unsaveAlarm(id)
            return START_NOT_STICKY
        }

        isAlarmCastingActive = false
        cancelSafetyTimeout()
        cancelPlaybackVerification()

        val notificationHandler = NotificationHandler(this)
        val appIntent =
                applicationContext.packageManager.getLaunchIntentForPackage(
                        applicationContext.packageName
                )
        val pendingIntent =
                PendingIntent.getActivity(
                        this,
                        id,
                        appIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

        val alarmSettingsJson = intent.getStringExtra("alarmSettings")
        if (alarmSettingsJson == null) {
            Log.e(TAG, "❌ Intent is missing AlarmSettings")
            stopSelf()
            return START_NOT_STICKY
        }

        val alarmSettings =
                try {
                    AlarmSettings.fromJson(alarmSettingsJson).let { settings ->
                        // CRITICAL FIX: Google Cast SDK requires a visible UI (Foreground) to maintain session reliability.
                        // If casting is enabled, we FORCE androidFullScreenIntent to true.
                        // This ensures the screen wakes up and the app enters a valid state for the MediaRouter.
                        if (settings.castEnabled) {
                            Log.d(TAG, " Casting enabled: FORCING androidFullScreenIntent = true for reliability")
                            settings.copy(androidFullScreenIntent = true)
                        } else {
                            settings
                        }
                    }.also {
                        currentAlarmSettings = it
                        Log.d(TAG, "🔊 Alarm settings parsed (and potentially modified) for ID: $id")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Cannot parse AlarmSettings from Intent: ${e.message}")
                    stopSelf()
                    return START_NOT_STICKY
                }

        val notification =
                notificationHandler.buildNotification(
                        alarmSettings.notificationSettings,
                        alarmSettings.androidFullScreenIntent,
                        pendingIntent,
                        id,
                )

        try {
            startAlarmService(id, notification)
            Log.d(TAG, "🔊 Foreground service started for alarm: $id")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception while starting foreground service: ${e.message}", e)
            return START_NOT_STICKY
        }

        if (!alarmSettings.allowAlarmOverlap &&
                        ringingAlarmIds.isNotEmpty() &&
                        action != "STOP_ALARM"
        ) {
            Log.d(TAG, "🔊 Alarm overlap detected - ignoring new alarm with id: $id")
            unsaveAlarm(id)
            return START_NOT_STICKY
        }

        if (alarmSettings.androidFullScreenIntent) {
            AlarmRingingLiveData.instance.update(true)
            Log.d(TAG, "🔊 Full screen intent enabled")
        }

        AlarmPlugin.alarmTriggerApi?.alarmRang(id.toLong()) {
            if (it.isSuccess) {
                Log.d(TAG, "🔊 Alarm rang notification for $id processed successfully")
            } else {
                Log.d(TAG, "🔊 Alarm rang notification for $id encountered error")
            }
        }

        Log.d(TAG, "🔊 Starting alarm audio handling for ID: $id")
        lastProgrammaticVolumeTime = System.currentTimeMillis() // Set initial time to ignore first volume setup
        registerButtonReceivers(id)
        handleAlarmAudio(alarmSettings, id)

        val wakeLock =
                (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "app:AlarmWakelockTag"
                )
        wakeLock.acquire(5 * 60 * 1000L)
        Log.d(TAG, "🔊 Wake lock acquired")

        val storage = alarmStorage
        if (storage != null) {
            val storedAlarms = storage.getSavedAlarms()
            if (storedAlarms.isEmpty() || storedAlarms.all { storedAlarm -> storedAlarm.id == id }
            ) {
                stopService(Intent(this, NotificationOnKillService::class.java))
                Log.d(TAG, "🔊 Turning off the warning notification.")
            } else {
                Log.d(TAG, "🔊 Keeping warning notification for other pending alarms.")
            }
        }

        return START_STICKY
    }

    private fun handleAlarmAudio(alarmSettings: AlarmSettings, alarmId: Int) {
        val shouldCast = alarmSettings.castEnabled
        val hasSavedDevice = castingHelper?.hasSavedDeviceForAlarm() == true
        val castUrl = alarmSettings.resolvePrimaryCastUrl()

        Log.d(TAG, "🎯 CASTING CHECK:")
        Log.d(TAG, "🎯   Should cast: $shouldCast")
        Log.d(TAG, "🎯   Has saved device: $hasSavedDevice")
        Log.d(TAG, "🎯   Cast URL available: ${!castUrl.isNullOrEmpty()}")
        Log.d(TAG, "🎯   Saved device name: ${castingHelper?.getSavedDeviceName()}")
        Log.d(TAG, "🎯   Saved device ID: ${castingHelper?.getSavedDeviceId()}")

        if (shouldCast && hasSavedDevice && !castUrl.isNullOrEmpty()) {
            Log.d(TAG, "🎯 ATTEMPTING ALARM CASTING for alarm $alarmId")
            // Wait 2.5s for service to stabilize, notification to post, and Cast context to be ready
            handler.postDelayed({
                handleAlarmCasting(alarmSettings, alarmId)
            }, 2500)
        } else {
            Log.d(TAG, "🔊 Starting LOCAL AUDIO for alarm $alarmId")
            if (!shouldCast) Log.d(TAG, "🔊   Reason: Casting disabled")
            if (!hasSavedDevice) Log.d(TAG, "🔊   Reason: No saved device")
            if (castUrl.isNullOrEmpty()) Log.d(TAG, "🔊   Reason: No cast URL")
            startLocalAudio(alarmSettings, alarmId)
        }
    }

    private fun handleAlarmCasting(alarmSettings: AlarmSettings, alarmId: Int) {
        val title = "Alarm $alarmId"
        val safeCastVolume = 1.0 // Hardcoded for testing
        val primaryCastUrl = alarmSettings.resolvePrimaryCastUrl()
        val secondaryCastUrl = alarmSettings.resolveSecondaryCastUrl()

        if (primaryCastUrl.isNullOrEmpty()) {
            Log.w(TAG, "🎯 No primary cast URL available, using local audio")
            startLocalAudio(alarmSettings, alarmId)
            return
        }

        val deviceId = castingHelper?.getSavedDeviceId()
        val deviceName = castingHelper?.getSavedDeviceName() ?: "Unknown Device"

        if (deviceId == null) {
            Log.w(TAG, "🎯 No saved device ID, using local audio")
            startLocalAudio(alarmSettings, alarmId)
            return
        }

        Log.d(TAG, "🎯 THREE-TIER RECONNECTION STRATEGY for alarm $alarmId")
        
        // Use three-tier strategy: Tier 1 (resume) → Tier 2 (reconnect) → Tier 3 (local)
        castingHelper?.reconnectForAlarm(deviceId, deviceName) { success ->
            if (success) {
                Log.d(TAG, "✅ Reconnection successful - casting audio")
                castAudioToDevice(alarmSettings, alarmId, title, safeCastVolume, primaryCastUrl, secondaryCastUrl)
            } else {
                Log.w(TAG, "⚠️ TIER 3: All reconnection attempts failed - using local audio")
                startLocalAudio(alarmSettings, alarmId)
            }
        }
    }

    /**
     * Cast audio to the connected device
     */
    private fun castAudioToDevice(
            alarmSettings: AlarmSettings,
            alarmId: Int,
            title: String,
            volume: Double,
            primaryUrl: String,
            secondaryUrl: String?
    ) {
        try {
            val shouldPlaySequence = alarmSettings.shouldPlaySequence()

            val success =
                    if (shouldPlaySequence && !secondaryUrl.isNullOrEmpty()) {
                        castingHelper?.castAudioSequenceForAlarm(
                                primaryUrl = primaryUrl,
                                secondaryUrl = secondaryUrl,
                                title = title,
                                volume = volume,
                                sequenceGapMs = alarmSettings.sequenceGapMs,
                                stopAfterSecondary = alarmSettings.stopAfterSecondary,
                                onStarted = {
                                    Log.d(TAG, "✅ Alarm sequence casting started")
                                    isAlarmCastingActive = true
                                    setupCastingCompletionDetection(alarmSettings, alarmId)
                                    setupSafetyTimeout(alarmSettings, alarmId)
                                },
                                onFailed = { error ->
                                    Log.w(TAG, "⚠️ Alarm sequence casting failed: $error")
                                    startLocalAudio(alarmSettings, alarmId)
                                },
                                onCompletion = {
                                    Log.d(TAG, "✅ Alarm sequence completed")
                                    if (alarmSettings.stopAfterSecondary) {
                                        unsaveAlarm(alarmId)
                                    }
                                }
                        )
                                ?: false
                    } else {
                        castingHelper?.castAudioForAlarm(
                                url = primaryUrl,
                                title = title,
                                volume = volume,
                                onStarted = {
                                    Log.d(TAG, "✅ Alarm casting started")
                                    isAlarmCastingActive = true
                                    setupCastingCompletionDetection(alarmSettings, alarmId)
                                    setupSafetyTimeout(alarmSettings, alarmId)
                                },
                                onFailed = { error ->
                                    Log.w(TAG, "⚠️ Alarm casting failed: $error")
                                    startLocalAudio(alarmSettings, alarmId)
                                },
                                onCompletion = {
                                    Log.d(TAG, "✅ Alarm audio completed")
                                    if (!alarmSettings.loopAudio) {
                                        unsaveAlarm(alarmId)
                                    }
                                }
                        )
                                ?: false
                    }

            if (!success) {
                Log.w(TAG, "⚠️ Casting failed, falling back to local audio.")
                startLocalAudio(alarmSettings, alarmId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during casting: ${e.message}")
            startLocalAudio(alarmSettings, alarmId)
        }
    }

    private fun castWithExistingSession(
            alarmSettings: AlarmSettings,
            alarmId: Int,
            title: String,
            volume: Double,
            primaryCastUrl: String,
            secondaryCastUrl: String?
    ) {
        try {
            val shouldPlaySequence = alarmSettings.shouldPlaySequence()

            val success =
                    if (shouldPlaySequence && !secondaryCastUrl.isNullOrEmpty()) {
                        castingHelper?.castAudioSequenceForAlarm(
                                primaryUrl = primaryCastUrl,
                                secondaryUrl = secondaryCastUrl,
                                title = title,
                                volume = volume,
                                sequenceGapMs = alarmSettings.sequenceGapMs,
                                stopAfterSecondary = alarmSettings.stopAfterSecondary,
                                onStarted = {
                                    Log.d(TAG, "✅ Alarm sequence casting started")
                                    isAlarmCastingActive = true
                                    setupCastingCompletionDetection(alarmSettings, alarmId)
                                    setupSafetyTimeout(alarmSettings, alarmId)
                                },
                                onFailed = { error ->
                                    Log.w(TAG, "⚠️ Alarm sequence casting failed: $error")
                                    startLocalAudio(alarmSettings, alarmId)
                                },
                                onCompletion = {
                                    Log.d(TAG, "✅ Alarm sequence completed")
                                    if (alarmSettings.stopAfterSecondary) {
                                        unsaveAlarm(alarmId)
                                    }
                                }
                        )
                                ?: false
                    } else {
                        castingHelper?.castAudioForAlarm(
                                url = primaryCastUrl,
                                title = title,
                                volume = volume,
                                onStarted = {
                                    Log.d(TAG, "✅ Alarm casting started")
                                    isAlarmCastingActive = true
                                    setupCastingCompletionDetection(alarmSettings, alarmId)
                                    setupSafetyTimeout(alarmSettings, alarmId)
                                },
                                onFailed = { error ->
                                    Log.w(TAG, "⚠️ Alarm casting failed: $error")
                                    startLocalAudio(alarmSettings, alarmId)
                                },
                                onCompletion = {
                                    Log.d(TAG, "✅ Alarm audio completed")
                                    if (!alarmSettings.loopAudio) {
                                        unsaveAlarm(alarmId)
                                    }
                                }
                        )
                                ?: false
                    }

            if (!success) {
                Log.w(TAG, "⚠️ Existing session casting failed, trying new session...")
                createNewCastSession(
                        alarmSettings,
                        alarmId,
                        title,
                        volume,
                        primaryCastUrl,
                        secondaryCastUrl
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error with existing session: ${e.message}")
            startLocalAudio(alarmSettings, alarmId)
        }
    }

    private fun createNewCastSession(
            alarmSettings: AlarmSettings,
            alarmId: Int,
            title: String,
            volume: Double,
            primaryCastUrl: String,
            secondaryCastUrl: String?
    ) {
        Log.d(TAG, "🎯 createNewCastSession with volume: $volume")

        val savedDeviceId = castingHelper?.getSavedDeviceId()
        if (savedDeviceId == null) {
            Log.w(TAG, "❌ No saved device ID")
            startLocalAudio(alarmSettings, alarmId)
            return
        }

        // Initialize Cast if not already
        castingHelper?.initializeForAlarm()

        // Improved discovery wait: Try multiple times if device not found immediately
        var discoveryAttempts = 0
        val maxDiscoveryAttempts = 10
        val discoveryInterval = 1000L

        val discoveryCheckRunnable = object : Runnable {
            override fun run() {
                discoveryAttempts++
                Log.d(TAG, "🎯 Discovery attempt $discoveryAttempts/$maxDiscoveryAttempts for device $savedDeviceId")

                val devices = castingHelper?.getAvailableDevices() ?: emptyList()
                val deviceFound = devices.any { it["id"] == savedDeviceId }

                if (deviceFound) {
                    Log.d(TAG, "✅ Device found! Attempting to connect...")
                    attemptConnectAndCast()
                } else if (discoveryAttempts < maxDiscoveryAttempts) {
                    handler.postDelayed(this, discoveryInterval)
                } else {
                    Log.w(TAG, "❌ Device discovery timed out after ${maxDiscoveryAttempts}s")
                    startLocalAudio(alarmSettings, alarmId)
                }
            }

            private fun attemptConnectAndCast() {
                val connected = castingHelper?.connectToDevice(savedDeviceId) ?: false

                if (connected) {
                    Log.d(TAG, "✅ Device connection initiated")

                    // Wait for connection to establish with retries
                    var connectionAttempts = 0
                    val maxConnectionAttempts = 6
                    val connectionInterval = 1000L

                    val connectionCheckRunnable = object : Runnable {
                        override fun run() {
                            connectionAttempts++
                            if (castingHelper?.isSessionActive() == true) {
                                Log.d(TAG, "✅ Session active, now casting...")
                                castWithExistingSession(
                                        alarmSettings,
                                        alarmId,
                                        title,
                                        volume,
                                        primaryCastUrl,
                                        secondaryCastUrl
                                )
                            } else if (connectionAttempts < maxConnectionAttempts) {
                                Log.d(TAG, "🔄 Waiting for connection... ($connectionAttempts/$maxConnectionAttempts)")
                                handler.postDelayed(this, connectionInterval)
                            } else {
                                Log.w(TAG, "❌ Session not active after ${maxConnectionAttempts}s")
                                startLocalAudio(alarmSettings, alarmId)
                            }
                        }
                    }
                    handler.postDelayed(connectionCheckRunnable, 500)
                } else {
                    Log.w(TAG, "❌ Connection failed immediately")
                    startLocalAudio(alarmSettings, alarmId)
                }
            }
        }

        handler.post(discoveryCheckRunnable)
    }

    private fun setupCastingCompletionDetection(alarmSettings: AlarmSettings, alarmId: Int) {
        Log.d(TAG, "🎯 Setting up casting completion detection")

        // Remove previous listener if any
        castingStateListener?.let { listener: CastingStateListener ->
            castingHelper?.removeStateListener(listener)
        }

        // Create new listener
        castingStateListener =
                object : CastingStateListener {
                    override fun onCastingStateChanged(state: CastingState) {
                        when (state) {
                            is CastingState.Ended -> {
                                Log.d(TAG, "🎯 Casting completed: ${state.mediaUrl}")
                                handler.post {
                                    if (isAlarmCastingActive) {
                                        handleAudioCompletion(alarmSettings, alarmId)
                                    }
                                }
                            }
                            is CastingState.Error -> {
                                Log.w(TAG, "⚠️ Casting error: ${state.message}")
                                handler.post {
                                    if (isAlarmCastingActive) {
                                        Log.d(
                                                TAG,
                                                "🎯 Falling back to local audio due to casting error"
                                        )
                                        startLocalAudio(alarmSettings, alarmId)
                                    }
                                }
                            }
                            else -> {
                                // Other states can be ignored
                            }
                        }
                    }

                    override fun onCastStarted() {
                        // Not used here
                    }

                    override fun onCastFailed(error: String) {
                        // Not used here
                    }

                    override fun onCastStopped() {
                        // Not used here
                    }

                    override fun onPlaybackStateUpdated(
                            state: com.gdelataillade.alarm.casting.PlaybackState
                    ) {
                        // Check if playback has ended based on state
                        if (state.hasEnded) {
                            Log.d(TAG, "🎯 Playback state indicates completion")
                            handler.post {
                                if (isAlarmCastingActive) {
                                    handleAudioCompletion(alarmSettings, alarmId)
                                }
                            }
                        }
                    }
                }
    }

    private fun handleAudioCompletion(alarmSettings: AlarmSettings, alarmId: Int) {
        Log.d(TAG, "🎯 Handling audio completion for alarm $alarmId")

        if (alarmSettings.shouldPlaySequence() && alarmSettings.stopAfterSecondary) {
            Log.d(TAG, "🎯 AUTO-STOPPING alarm $alarmId after sequence completion")
            unsaveAlarm(alarmId)
        } else if (!alarmSettings.loopAudio) {
            Log.d(TAG, "🎯 AUTO-STOPPING alarm $alarmId after single audio completion")
            unsaveAlarm(alarmId)
        }
    }

    private fun setupSafetyTimeout(alarmSettings: AlarmSettings, alarmId: Int) {
        Log.d(TAG, "🎯 Setting up safety timeout")

        cancelSafetyTimeout()

        // Safety timeout as absolute last resort (2 hours max)
        alarmSafetyTimeoutRunnable = Runnable {
            if (isAlarmCastingActive) {
                Log.w(TAG, "⚠️ Safety timeout reached for alarm $alarmId")

                // Check if audio is actually still playing
                val isPlaying = castingHelper?.isPlaying() ?: false
                val currentPosition = castingHelper?.getCurrentPosition() ?: 0L
                val duration = castingHelper?.getDuration() ?: 0L

                if (!isPlaying || (duration > 0 && currentPosition >= duration - 5000)) {
                    Log.d(TAG, "✅ Audio seems to have finished - stopping alarm")
                    unsaveAlarm(alarmId)
                } else {
                    Log.d(TAG, "⏸️ Audio still playing at $currentPosition/$duration")
                    // Schedule another check
                    setupSafetyTimeout(alarmSettings, alarmId)
                }
            }
        }

        handler.postDelayed(alarmSafetyTimeoutRunnable!!, MAX_SAFETY_TIMEOUT_MS)
    }

    private fun cancelSafetyTimeout() {
        alarmSafetyTimeoutRunnable?.let {
            handler.removeCallbacks(it)
            alarmSafetyTimeoutRunnable = null
            Log.d(TAG, "🛑 Safety timeout cancelled")
        }
    }

    private fun cancelPlaybackVerification() {
        playbackVerificationRunnable?.let {
            handler.removeCallbacks(it)
            playbackVerificationRunnable = null
        }
    }

    private fun startLocalAudio(alarmSettings: AlarmSettings, id: Int) {
        Log.d(TAG, "🔊 STARTING LOCAL AUDIO PLAYBACK for alarm $id")

        setupAudioForAlarm(alarmSettings)

        if (alarmSettings.shouldPlaySequence()) {
            Log.d(TAG, "🔊 Playing AUDIO SEQUENCE for alarm $id")
            playAudioSequence(alarmSettings, id)
        } else {
            Log.d(TAG, "🔊 Playing SINGLE AUDIO for alarm $id")
            playSinglePrimaryAudio(alarmSettings, id)
        }

        if (alarmSettings.vibrate) {
            vibrationService?.startVibrating(longArrayOf(0, 500, 500), 1)
            Log.d(TAG, "🔊 Vibration started")
        }

        ringingAlarmIds = audioService?.getPlayingMediaPlayersIds() ?: listOf()
        Log.d(TAG, "🔊 Currently ringing alarms: $ringingAlarmIds")
    }

    private fun setupAudioForAlarm(alarmSettings: AlarmSettings) {
        if (alarmSettings.volumeSettings.volume != null) {
            // Convert to Double safely
            val volumeValue =1.0

            volumeService?.setVolume(volumeValue, alarmSettings.volumeSettings.volumeEnforced, true)
            Log.d(TAG, "🔊 Volume set to: $volumeValue")
        }
        volumeService?.requestAudioFocus()
        Log.d(TAG, "🔊 Audio focus requested")
    }
    private fun playAudioSequence(alarmSettings: AlarmSettings, alarmId: Int) {
        val primaryPath = alarmSettings.assetAudioPath
        val secondaryPath = alarmSettings.secondaryAudioPath

        if (primaryPath == null) {
            Log.e(TAG, "❌ Primary audio path is null - cannot play sequence")
            return
        }

        Log.d(TAG, "🔊 Playing audio sequence:")
        Log.d(TAG, "🔊   Primary: $primaryPath")
        Log.d(TAG, "🔊   Secondary: $secondaryPath")
        Log.d(TAG, "🔊   Play secondary: ${alarmSettings.playSecondaryAudio}")
        Log.d(TAG, "🔊   Stop after secondary: ${alarmSettings.stopAfterSecondary}")

        isPlayingSequence = true
        currentSequenceStep = 1
        primaryAudioCompleted = false
        secondaryAudioScheduled = false

        audioService?.setOnAudioCompleteListener {
            Log.d(TAG, "🔊 Audio completion listener triggered")
            handleSequenceCompletion(alarmSettings, alarmId, primaryPath, secondaryPath)
        }

        audioService?.playAudio(
                alarmId,
                primaryPath,
                false,
                alarmSettings.volumeSettings.fadeDuration,
                alarmSettings.volumeSettings.fadeSteps
        )
        Log.d(TAG, "🔊 Primary audio started playing")
    }

    private fun handleSequenceCompletion(
            alarmSettings: AlarmSettings,
            alarmId: Int,
            primaryPath: String,
            secondaryPath: String?
    ) {
        Log.d(TAG, "🔊 Handling sequence completion - Step: $currentSequenceStep")

        when {
            !primaryAudioCompleted && !secondaryAudioScheduled -> {
                Log.d(TAG, "🔊 Primary audio completed for alarm $alarmId")
                primaryAudioCompleted = true

                if (alarmSettings.playSecondaryAudio && !secondaryPath.isNullOrEmpty()) {
                    Log.d(TAG, "🔊 Scheduling secondary audio for alarm $alarmId")
                    secondaryAudioScheduled = true
                    currentSequenceStep = 2

                    handler.postDelayed(
                            {
                                Log.d(TAG, "🔊 Starting secondary audio playback")
                                audioService?.playAudio(
                                        alarmId,
                                        secondaryPath,
                                        false,
                                        alarmSettings.volumeSettings.fadeDuration,
                                        alarmSettings.volumeSettings.fadeSteps
                                )
                            },
                            alarmSettings.sequenceGapMs
                    )
                } else {
                    Log.d(TAG, "🔊 Secondary audio skipped for alarm $alarmId")
                    completeSequenceIfNeeded(alarmSettings, alarmId)
                }
            }
            primaryAudioCompleted && secondaryAudioScheduled -> {
                Log.d(TAG, "🔊 Secondary audio completed for alarm $alarmId")
                completeSequenceIfNeeded(alarmSettings, alarmId)
            }
        }
    }

    private fun completeSequenceIfNeeded(alarmSettings: AlarmSettings, alarmId: Int) {
        if (alarmSettings.stopAfterSecondary) {
            Log.d(TAG, "🔊 Audio sequence completed - stopping alarm $alarmId")
            unsaveAlarm(alarmId)
        }
        resetSequenceState()
    }

    private fun resetSequenceState() {
        Log.d(TAG, "🔊 Resetting sequence state")
        isPlayingSequence = false
        currentSequenceStep = 0
        primaryAudioCompleted = false
        secondaryAudioScheduled = false
    }

    private fun playSinglePrimaryAudio(alarmSettings: AlarmSettings, alarmId: Int) {
        val primaryPath = alarmSettings.assetAudioPath

        if (primaryPath == null) {
            Log.e(TAG, "❌ Primary audio path is null - cannot play alarm")
            return
        }

        Log.d(TAG, "🔊 Playing single primary audio: $primaryPath")
        Log.d(TAG, "🔊 Loop audio: ${alarmSettings.loopAudio}")

        if (!alarmSettings.loopAudio) {
            audioService?.setOnAudioCompleteListener {
                Log.d(TAG, "🔊 Single audio completed")
                handleSingleAudioCompletion(alarmId)
            }
        }

        audioService?.playAudio(
                alarmId,
                primaryPath,
                alarmSettings.loopAudio,
                alarmSettings.volumeSettings.fadeDuration,
                alarmSettings.volumeSettings.fadeSteps
        )
    }

    private fun handleSingleAudioCompletion(alarmId: Int) {
        Log.d(TAG, "🔊 Handling single audio completion for alarm $alarmId")
        if (!isAlarmCastingActive) {
            // FIX: If loop is false and primary audio finishes, kill the alarm
            unsaveAlarm(alarmId)
        }
    }

    private fun startAlarmService(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(id, notification)
        }
    }

    fun handleStopAlarmCommand(alarmId: Int) {
        Log.d(TAG, "🛑 STOP ALARM COMMAND received for: $alarmId")
        if (alarmId == 0) return
        unsaveAlarm(alarmId)
    }

    private fun unsaveAlarm(id: Int) {
        Log.d(TAG, "🛑 UNSAVING alarm: $id")
        alarmStorage?.unsaveAlarm(id)
        AlarmPlugin.alarmTriggerApi?.alarmStopped(id.toLong()) { result ->
            if (result.isSuccess) {
                Log.d(TAG, "🛑 Alarm stopped notification for $id processed successfully")
            } else {
                Log.d(TAG, "🛑 Alarm stopped notification for $id encountered error")
            }
        }
        stopAlarm(id)
    }

    private fun stopAlarm(id: Int) {
        Log.d(TAG, "🛑 STOPPING ALARM: $id")
        AlarmRingingLiveData.instance.update(false)

        try {
            cancelSafetyTimeout()
            cancelPlaybackVerification()
            castingStateListener?.let { listener: CastingStateListener ->
                castingHelper?.removeStateListener(listener)
                castingStateListener = null
            }

            if (isAlarmCastingActive) {
                Log.d(TAG, "🛑 Stopping alarm casting for alarm $id")
                castingHelper?.stopCastingAlarm()
                isAlarmCastingActive = false
            }

            unregisterButtonReceivers()

            resetSequenceState()

            audioService?.stopAudio(id)
            ringingAlarmIds = audioService?.getPlayingMediaPlayersIds() ?: listOf()
            Log.d(TAG, "🛑 Remaining ringing alarms: $ringingAlarmIds")

            vibrationService?.stopVibrating()
            volumeService?.restorePreviousVolume(true)
            volumeService?.abandonAudioFocus()
            Log.d(TAG, "🛑 Audio resources cleaned up")

            if (audioService?.isMediaPlayerEmpty() == true) {
                Log.d(TAG, "🛑 No more media players - stopping service")
                stopSelf()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION") stopForeground(true)
            }
            Log.d(TAG, "🛑 Foreground service stopped")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error stopping alarm: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "🔊 AlarmService onDestroy for alarm $alarmId")

        cancelSafetyTimeout()
        cancelPlaybackVerification()

        // Remove casting state listener
        castingStateListener?.let { listener: CastingStateListener ->
            castingHelper?.removeStateListener(listener)
            castingStateListener = null
        }

        if (isAlarmCastingActive) {
            castingHelper?.stopCastingAlarm()
            isAlarmCastingActive = false
            Log.d(TAG, "🔊 Casting stopped during service destruction")
        }

        resetSequenceState()
        ringingAlarmIds = listOf()
        currentAlarmSettings = null

        audioService?.cleanUp()
        vibrationService?.stopVibrating()
        volumeService?.restorePreviousVolume(true)
        volumeService?.abandonAudioFocus()

        AlarmRingingLiveData.instance.update(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }

        unregisterButtonReceivers()

        instance = null
        Log.d(TAG, "🔊 AlarmService DESTROYED")
        super.onDestroy()
    }

    fun updateLastProgrammaticVolumeTime() {
        lastProgrammaticVolumeTime = System.currentTimeMillis()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerButtonReceivers(alarmId: Int) {
        Log.d(TAG, "Registering button receivers for alarm $alarmId")

        // 1. Volume button receiver
        if (volumeReceiver == null) {
            volumeReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                        val now = System.currentTimeMillis()
                        // Ignore volume changes within 2 seconds of a programmatic change
                        if (now - lastProgrammaticVolumeTime < 2000) {
                            Log.d(TAG, "Ignoring volume change (likely programmatic or too soon)")
                            return
                        }

                        Log.d(TAG, "⏹ Volume button pressed while alarm ringing - STOPPING")
                        unsaveAlarm(alarmId)
                    }
                }
            }
            registerReceiver(volumeReceiver, android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
        }

        // 2. Screen/Power button receiver
        if (screenReceiver == null) {
            screenReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                        Log.d(TAG, "⏹ Power button pressed (Screen Off) while alarm ringing - STOPPING")
                        unsaveAlarm(alarmId)
                    }
                }
            }
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            registerReceiver(screenReceiver, filter)
        }
    }

    private fun unregisterButtonReceivers() {
        volumeReceiver?.let {
            try { unregisterReceiver(it) } catch (e: Exception) {}
            volumeReceiver = null
        }
        screenReceiver?.let {
            try { unregisterReceiver(it) } catch (e: Exception) {}
            screenReceiver = null
        }
        Log.d(TAG, "Button receivers unregistered")
    }
}
