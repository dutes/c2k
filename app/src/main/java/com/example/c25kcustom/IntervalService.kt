package com.example.c25kcustom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.UUID
import kotlin.collections.ArrayDeque
import kotlin.math.min

class IntervalService : Service(), TextToSpeech.OnInitListener {

    inner class LocalBinder : Binder() {
        val service: IntervalService = this@IntervalService
    }

    private val binder = LocalBinder()

    var isWorkoutFinished = false
        private set

    private data class IntervalSegment(
        val type: SegmentType,
        val durationSeconds: Int,
        val title: String,
        val announcement: String
    )

    private enum class SegmentType {
        WARMUP, RUN, WALK, COOLDOWN
    }

    private var segments: List<IntervalSegment> = emptyList()
    private var currentIndex = -1
    private var countdown: CountDownTimer? = null
    private var remainingMillis: Long = 0L
    private var isPaused = false
    private var totalCycles: Int = 0
    private var totalWorkoutSeconds: Int = 0
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingUtterances = ArrayDeque<Pair<String, Boolean>>()
    private var halfwayAnnouncementDelivered = true
    private var lastThirtySecondsAnnouncementDelivered = true
    private var previousRemainingSeconds: Int? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        tts = TextToSpeech(this, this)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                abandonAudioFocus()
            }
            override fun onError(utteranceId: String?) {
                abandonAudioFocus()
            }
        })

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "C25KCustomCoach::IntervalWakelock"
        ).apply { setReferenceCounted(false) }
    }

    override fun onDestroy() {
        countdown?.cancel()
        tts?.stop()
        tts?.shutdown()
        abandonAudioFocus()
        synchronized(pendingUtterances) { pendingUtterances.clear() }
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> pauseIntervals()
            ACTION_RESUME -> resumeIntervals()
            ACTION_STOP -> stopIntervals()
            ACTION_START -> startIntervals(intent)
            ACTION_REQUEST_PROGRESS_UPDATE -> broadcastProgress()
            else -> {
                // Ignore
            }
        }
        return START_NOT_STICKY
    }

    fun stopServiceAndWorkout() {
        stopIntervals()
    }

    private fun startIntervals(intent: Intent) {
        isWorkoutFinished = false
        countdown?.cancel()
        val runSeconds = intent.getIntExtra(EXTRA_RUN_SECONDS, 0)
        val walkSeconds = intent.getIntExtra(EXTRA_WALK_SECONDS, 0)
        val totalMinutes = intent.getIntExtra(EXTRA_TOTAL_MINUTES, 0)
        val cycleCount = intent.getIntExtra(EXTRA_CYCLE_COUNT, 0)
        val includeWarmup = intent.getBooleanExtra(EXTRA_INCLUDE_WARMUP, false)
        val includeCooldown = intent.getBooleanExtra(EXTRA_INCLUDE_COOLDOWN, false)

        if (runSeconds <= 0 || walkSeconds <= 0) {
            stopSelf()
            return
        }

        segments = buildSegments(
            runSeconds = runSeconds,
            walkSeconds = walkSeconds,
            totalMinutes = totalMinutes,
            cycleCount = cycleCount,
            includeWarmup = includeWarmup,
            includeCooldown = includeCooldown
        )
        totalWorkoutSeconds = segments.sumOf { it.durationSeconds }
        totalCycles = segments.count { it.type == SegmentType.RUN }
        currentIndex = -1
        isPaused = false
        remainingMillis = 0L

        val timeout = (totalWorkoutSeconds * 1000L) + 60000L // Workout duration + 1 minute buffer
        wakeLock?.let { if (!it.isHeld) it.acquire(timeout) }

        val notification = buildNotification(
            title = getString(R.string.notification_title),
            content = getString(R.string.notification_preparing),
            isPaused = false
        )
        startForeground(NOTIFICATION_ID, notification)
        startNextSegment()
    }

    private fun stopIntervals() {
        isPaused = false
        tts?.stop()
        sendWorkoutStoppedBroadcast()
        finalizeService(removeNotification = true)
    }

    private fun pauseIntervals() {
        if (isPaused || segments.isEmpty()) return
        countdown?.cancel()
        isPaused = true
        updateNotification()
        broadcastProgress()
    }

    private fun resumeIntervals() {
        if (!isPaused || segments.isEmpty()) return
        isPaused = false
        if (remainingMillis <= 0L) {
            startNextSegment()
            return
        }
        startSegmentTimer(remainingMillis)
        updateNotification()
        broadcastProgress()
    }

    private fun startNextSegment() {
        if (segments.isEmpty()) {
            finishWorkout()
            return
        }
        currentIndex += 1
        if (currentIndex >= segments.size) {
            finishWorkout()
            return
        }
        val segment = segments[currentIndex]
        remainingMillis = segment.durationSeconds * 1000L
        if (segment.type == SegmentType.RUN) {
            halfwayAnnouncementDelivered = false
            lastThirtySecondsAnnouncementDelivered = false
        } else {
            halfwayAnnouncementDelivered = true
            lastThirtySecondsAnnouncementDelivered = true
        }
        previousRemainingSeconds = segment.durationSeconds
        announceSegment(segment)
        startSegmentTimer(remainingMillis)
        updateNotification()
        broadcastProgress()
    }

    private fun startSegmentTimer(durationMillis: Long) {
        countdown?.cancel()
        if (segments.getOrNull(currentIndex) == null) return
        if (durationMillis <= 0L) {
            remainingMillis = 0L
            updateNotification()
            broadcastProgress()
            startNextSegment()
            return
        }

        countdown = object : CountDownTimer(durationMillis, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                remainingMillis = millisUntilFinished
                maybeAnnounceRunMilestones(millisUntilFinished)
                updateNotification()
                broadcastProgress()
            }

            override fun onFinish() {
                remainingMillis = 0
                previousRemainingSeconds = 0
                updateNotification()
                broadcastProgress()
                startNextSegment()
            }
        }.start()
    }

    private fun finishWorkout() {
        isWorkoutFinished = true
        countdown?.cancel()
        isPaused = false
        pauseMusic()
        speak(getString(R.string.notification_complete))
        val completedNotification = buildNotification(
            title = getString(R.string.notification_title),
            content = getString(R.string.notification_complete),
            isPaused = false,
            showActions = false
        )
        notificationManager.notify(NOTIFICATION_ID, completedNotification)
        sendWorkoutFinishedBroadcast()
        // Do not stop the service here. Wait for the activity to connect.
    }

    private fun broadcastProgress() {
        val segment = segments.getOrNull(currentIndex)
        val remainingSeconds = if (segment != null) {
            val millis = remainingMillis.coerceAtLeast(0L)
            val seconds = if (isPaused) {
                (millis / 1000L).toInt()
            } else {
                ((millis + 999) / 1000L).toInt()
            }
            seconds.coerceIn(0, segment.durationSeconds)
        } else {
            0
        }
        val elapsedSeconds = if (segment != null) {
            (segment.durationSeconds - remainingSeconds).coerceIn(0, segment.durationSeconds)
        } else {
            0
        }

        val completedSegmentsDuration = if (currentIndex > 0) {
            segments.subList(0, currentIndex).sumOf { it.durationSeconds }
        } else {
            0
        }
        val totalElapsed = completedSegmentsDuration + elapsedSeconds

        val intent = Intent(ACTION_PROGRESS_UPDATE)
            .setPackage(packageName)
            .putExtra(EXTRA_INTERVAL_TITLE, segment?.title)
            .putExtra(EXTRA_INTERVAL_ELAPSED_SECONDS, elapsedSeconds)
            .putExtra(EXTRA_INTERVAL_REMAINING_SECONDS, remainingSeconds)
            .putExtra(EXTRA_TOTAL_WORKOUT_SECONDS, totalWorkoutSeconds)
            .putExtra(EXTRA_TOTAL_ELAPSED_SECONDS, totalElapsed)
            .putExtra(EXTRA_CYCLE_NUMBER, currentCycleNumber())
            .putExtra(EXTRA_TOTAL_CYCLES, totalCycles)
            .putExtra(EXTRA_IS_PAUSED, isPaused)
        sendBroadcast(intent)
    }

    private fun currentCycleNumber(): Int {
        if (segments.isEmpty()) return 0

        val safeIndex = currentIndex.coerceIn(0, segments.lastIndex)
        val completedRuns = if (currentIndex < 0) {
            0
        } else {
            var count = 0
            for (i in 0..safeIndex) {
                if (segments[i].type == SegmentType.RUN) {
                    count++
                }
            }
            count
        }

        if (completedRuns > 0) {
            return completedRuns
        }

        val hasRunSegment = segments.any { it.type == SegmentType.RUN }
        return if (hasRunSegment) 1 else 0
    }

    private fun sendWorkoutFinishedBroadcast() {
        val runSegments = segments.filter { it.type == SegmentType.RUN }
        val walkSegments = segments.filter { it.type == SegmentType.WALK }
        val runSeconds = runSegments.firstOrNull()?.durationSeconds ?: 0
        val walkSeconds = walkSegments.firstOrNull()?.durationSeconds ?: 0
        val cycles = runSegments.size

        val intent = Intent(ACTION_WORKOUT_FINISHED)
            .setPackage(packageName)
            .putExtra(EXTRA_TOTAL_WORKOUT_SECONDS, totalWorkoutSeconds)
            .putExtra(EXTRA_RUN_SECONDS, runSeconds)
            .putExtra(EXTRA_WALK_SECONDS, walkSeconds)
            .putExtra(EXTRA_CYCLE_COUNT, cycles)
        sendBroadcast(intent)
    }

    private fun sendWorkoutStoppedBroadcast() {
        val intent = Intent(ACTION_WORKOUT_STOPPED).setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun buildSegments(
        runSeconds: Int,
        walkSeconds: Int,
        totalMinutes: Int,
        cycleCount: Int,
        includeWarmup: Boolean,
        includeCooldown: Boolean
    ): List<IntervalSegment> {
        val result = mutableListOf<IntervalSegment>()
        val warmupSeconds = if (includeWarmup) 5 * 60 else 0
        val cooldownSeconds = if (includeCooldown) 5 * 60 else 0

        if (includeWarmup) {
            result.add(
                IntervalSegment(
                    SegmentType.WARMUP,
                    warmupSeconds,
                    getString(R.string.segment_warmup_title),
                    getString(R.string.segment_warmup_announcement)
                )
            )
        }

        val workoutSegments = mutableListOf<IntervalSegment>()
        val baseCycleDuration = runSeconds + walkSeconds

        when {
            cycleCount > 0 -> {
                repeat(cycleCount) {
                    workoutSegments.add(
                        IntervalSegment(
                            SegmentType.RUN,
                            runSeconds,
                            getString(R.string.segment_run_title),
                            getString(
                                R.string.segment_run_announcement,
                                formatAnnouncementDuration(runSeconds)
                            )
                        )
                    )
                    workoutSegments.add(
                        IntervalSegment(
                            SegmentType.WALK,
                            walkSeconds,
                            getString(R.string.segment_walk_title),
                            getString(
                                R.string.segment_walk_announcement,
                                formatAnnouncementDuration(walkSeconds)
                            )
                        )
                    )
                }
            }
            totalMinutes > 0 && baseCycleDuration > 0 -> {
                var remaining = totalMinutes * 60 - warmupSeconds - cooldownSeconds
                if (remaining < 0) remaining = 0
                while (remaining > 0) {
                    val runDuration = min(runSeconds, remaining)
                    if (runDuration > 0) {
                        workoutSegments.add(
                            IntervalSegment(
                                SegmentType.RUN,
                                runDuration,
                                getString(R.string.segment_run_title),
                                getString(
                                    R.string.segment_run_announcement,
                                    formatAnnouncementDuration(runDuration)
                                )
                            )
                        )
                        remaining -= runDuration
                    }

                    val walkDuration = min(walkSeconds, remaining)
                    if (walkDuration > 0) {
                        workoutSegments.add(
                            IntervalSegment(
                                SegmentType.WALK,
                                walkDuration,
                                getString(R.string.segment_walk_title),
                                getString(
                                    R.string.segment_walk_announcement,
                                    formatAnnouncementDuration(walkDuration)
                                )
                            )
                        )
                        remaining -= walkDuration
                    }

                    if (runDuration == 0 && walkDuration == 0) {
                        break
                    }
                }

                if (workoutSegments.none { it.type == SegmentType.RUN }) {
                    workoutSegments.add(
                        IntervalSegment(
                            SegmentType.RUN,
                            runSeconds,
                            getString(R.string.segment_run_title),
                            getString(
                                R.string.segment_run_announcement,
                                formatAnnouncementDuration(runSeconds)
                            )
                        )
                    )
                    workoutSegments.add(
                        IntervalSegment(
                            SegmentType.WALK,
                            walkSeconds,
                            getString(R.string.segment_walk_title),
                            getString(
                                R.string.segment_walk_announcement,
                                formatAnnouncementDuration(walkSeconds)
                            )
                        )
                    )
                }
            }
            else -> {
                workoutSegments.add(
                    IntervalSegment(
                        SegmentType.RUN,
                        runSeconds,
                        getString(R.string.segment_run_title),
                        getString(
                            R.string.segment_run_announcement,
                            formatAnnouncementDuration(runSeconds)
                        )
                    )
                )
                workoutSegments.add(
                    IntervalSegment(
                        SegmentType.WALK,
                        walkSeconds,
                        getString(R.string.segment_walk_title),
                        getString(
                            R.string.segment_walk_announcement,
                            formatAnnouncementDuration(walkSeconds)
                        )
                    )
                )
            }
        }

        result.addAll(workoutSegments)

        return appendCooldown(result, includeCooldown, cooldownSeconds)
    }

    private fun appendCooldown(
        segments: MutableList<IntervalSegment>,
        includeCooldown: Boolean,
        cooldownSeconds: Int
    ): List<IntervalSegment> {
        if (includeCooldown) {
            segments.add(
                IntervalSegment(
                    SegmentType.COOLDOWN,
                    cooldownSeconds,
                    getString(R.string.segment_cooldown_title),
                    getString(R.string.segment_cooldown_announcement)
                )
            )
        }
        return segments
    }

    private fun formatAnnouncementDuration(totalSeconds: Int): String {
        val safeSeconds = totalSeconds.coerceAtLeast(0)
        val minutes = safeSeconds / 60
        val seconds = safeSeconds % 60
        val parts = mutableListOf<String>()
        if (minutes > 0) {
            parts += resources.getQuantityString(R.plurals.duration_minutes, minutes, minutes)
        }
        if (seconds > 0) {
            parts += resources.getQuantityString(R.plurals.duration_seconds, seconds, seconds)
        } else if (parts.isEmpty()) {
            parts += resources.getQuantityString(R.plurals.duration_seconds, 0, 0)
        }
        return parts.joinToString(separator = " ")
    }

    private fun announceSegment(segment: IntervalSegment) {
        if (segment.type == SegmentType.RUN) {
            val cycleNumber = currentCycleNumber()
            val totalRunSegments = totalCycles
            if (cycleNumber > 0 && totalRunSegments > 0) {
                val cycleAnnouncement = getString(
                    R.string.segment_cycle_intro,
                    cycleNumber,
                    totalRunSegments
                )
                speak(cycleAnnouncement, flush = true)
                speak(segment.announcement, flush = false)
                return
            }
        }
        speak(segment.announcement)
    }

    private fun updateNotification() {
        val segment = segments.getOrNull(currentIndex)
        val segmentTitle = segment?.title ?: getString(R.string.notification_preparing)
        val content = if (segment != null) {
            val secondsRemaining = if (isPaused) {
                (remainingMillis / 1000L).toInt()
            } else {
                ((remainingMillis + 999) / 1000L).toInt()
            }
            val formatRes = if (isPaused) {
                R.string.notification_content_paused_format
            } else {
                R.string.notification_content_format
            }
            getString(formatRes, segmentTitle, secondsRemaining)
        } else {
            getString(R.string.notification_preparing)
        }
        val notification = buildNotification(
            getString(R.string.notification_title),
            content,
            isPaused
        )
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        title: String,
        content: String,
        isPaused: Boolean,
        showActions: Boolean = true
    ): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_MAIN,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setOnlyAlertOnce(true)
            .setColor(ContextCompat.getColor(this, R.color.md_theme_primary))
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (showActions) {
            if (isPaused) {
                builder.addAction(
                    0,
                    getString(R.string.action_resume),
                    actionPendingIntent(ACTION_RESUME, REQUEST_RESUME)
                )
            } else {
                builder.addAction(
                    0,
                    getString(R.string.action_pause),
                    actionPendingIntent(ACTION_PAUSE, REQUEST_PAUSE)
                )
            }
            builder.addAction(
                0,
                getString(R.string.action_stop),
                actionPendingIntent(ACTION_STOP, REQUEST_STOP)
            )
        }

        return builder.build()
    }

    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent? {
        val intent = Intent(this, IntervalService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun maybeAnnounceRunMilestones(millisUntilFinished: Long) {
        val segment = segments.getOrNull(currentIndex) ?: return
        if (segment.type != SegmentType.RUN) {
            previousRemainingSeconds = segment.durationSeconds
            return
        }

        val remainingSeconds = ((millisUntilFinished + 999) / 1000L).toInt().coerceAtLeast(0)
        val previousSeconds = previousRemainingSeconds ?: segment.durationSeconds

        if (!halfwayAnnouncementDelivered) {
            val halfwayThreshold = segment.durationSeconds / 2
            if (previousSeconds > halfwayThreshold && remainingSeconds <= halfwayThreshold) {
                announceEncouragement(R.string.segment_run_halfway_encouragement)
                halfwayAnnouncementDelivered = true
            }
        }

        if (!lastThirtySecondsAnnouncementDelivered) {
            val lastThirtyThreshold = 30
            if (segment.durationSeconds >= lastThirtyThreshold &&
                previousSeconds > lastThirtyThreshold &&
                remainingSeconds <= lastThirtyThreshold
            ) {
                announceEncouragement(R.string.segment_run_last_thirty_encouragement)
                lastThirtySecondsAnnouncementDelivered = true
            }
        }

        previousRemainingSeconds = remainingSeconds
    }

    private fun announceEncouragement(@StringRes messageResId: Int) {
        val encouragement = getString(messageResId)
        speak(encouragement, flush = true)
    }

    private fun speak(text: String, flush: Boolean = true): Boolean {
        if (text.isBlank()) return false
        if (!ttsReady) {
            synchronized(pendingUtterances) {
                if (flush) {
                    pendingUtterances.clear()
                }
                pendingUtterances.addLast(text to flush)
            }
            return false
        }
        if (audioFocusRequest == null) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { _ -> }
                .build()
        }
        val focusGranted = audioManager?.requestAudioFocus(audioFocusRequest!!)
        if (focusGranted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            synchronized(pendingUtterances) {
                if (flush) {
                    pendingUtterances.clear()
                }
                pendingUtterances.addLast(text to flush)
            }
            return false
        }
        if (flush) {
            synchronized(pendingUtterances) { pendingUtterances.clear() }
        }
        val utteranceId = UUID.randomUUID().toString()
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val result = tts?.speak(text, queueMode, null, utteranceId)
        return result == TextToSpeech.SUCCESS
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
    }

    private fun finalizeService(removeNotification: Boolean) {
        countdown?.cancel()
        isPaused = false
        tts?.stop()
        abandonAudioFocus()
        wakeLock?.let { if (it.isHeld) it.release() }
        val flag = if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH
        stopForeground(flag)
        stopSelf()
    }

    private fun pauseMusic() {
        val eventTime = SystemClock.uptimeMillis()
        val downEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0)
        val upEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0)
        audioManager?.dispatchMediaKeyEvent(downEvent)
        audioManager?.dispatchMediaKeyEvent(upEvent)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.getDefault())
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            if (ttsReady) {
                playPendingUtterances()
            } else {
                synchronized(pendingUtterances) { pendingUtterances.clear() }
            }
        } else {
            ttsReady = false
            synchronized(pendingUtterances) { pendingUtterances.clear() }
        }
    }

    private fun playPendingUtterances() {
        val queued: List<Pair<String, Boolean>>
        synchronized(pendingUtterances) {
            if (pendingUtterances.isEmpty()) return
            queued = pendingUtterances.toList()
            pendingUtterances.clear()
        }
        var first = true
        for ((text, flush) in queued) {
            val shouldFlush = if (first) flush else false
            speak(text, flush = shouldFlush)
            first = false
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        )
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.example.c25kcustom.action.START"
        const val ACTION_PAUSE = "com.example.c25kcustom.action.PAUSE"
        const val ACTION_RESUME = "com.example.c25kcustom.action.RESUME"
        const val ACTION_STOP = "com.example.c25kcustom.action.STOP"
        const val ACTION_PROGRESS_UPDATE = "com.example.c25kcustom.action.PROGRESS_UPDATE"
        const val ACTION_WORKOUT_FINISHED = "com.example.c25kcustom.action.WORKOUT_FINISHED"
        const val ACTION_WORKOUT_STOPPED = "com.example.c25kcustom.action.WORKOUT_STOPPED"
        const val ACTION_REQUEST_PROGRESS_UPDATE = "com.example.c25kcustom.action.REQUEST_PROGRESS_UPDATE"

        const val EXTRA_RUN_SECONDS = "extra_run_seconds"
        const val EXTRA_WALK_SECONDS = "extra_walk_seconds"
        const val EXTRA_TOTAL_MINUTES = "extra_total_minutes"
        const val EXTRA_CYCLE_COUNT = "extra_cycle_count"
        const val EXTRA_INCLUDE_WARMUP = "extra_include_warmup"
        const val EXTRA_INCLUDE_COOLDOWN = "extra_include_cooldown"
        const val EXTRA_INTERVAL_TITLE = "extra_interval_title"
        const val EXTRA_INTERVAL_ELAPSED_SECONDS = "extra_interval_elapsed_seconds"
        const val EXTRA_INTERVAL_REMAINING_SECONDS = "extra_interval_remaining_seconds"
        const val EXTRA_TOTAL_WORKOUT_SECONDS = "extra_total_workout_seconds"
        const val EXTRA_TOTAL_ELAPSED_SECONDS = "extra_total_elapsed_seconds"
        const val EXTRA_CYCLE_NUMBER = "extra_cycle_number"
        const val EXTRA_TOTAL_CYCLES = "extra_total_cycles"
        const val EXTRA_IS_PAUSED = "extra_is_paused"

        private const val CHANNEL_ID = "c25k_intervals"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_MAIN = 2001
        private const val REQUEST_PAUSE = 2002
        private const val REQUEST_RESUME = 2003
        private const val REQUEST_STOP = 2004
    }
}
