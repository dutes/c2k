package com.example.c25kcustom

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.AttrRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.c25kcustom.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private enum class UiState { SETUP, IN_PROGRESS }

    private lateinit var binding: ActivityMainBinding
    private var receiverRegistered = false
    private var currentState: UiState = UiState.SETUP
    private var isWorkoutPaused = false
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (!isGranted) {
                Toast.makeText(
                    this,
                    "Notifications are disabled. You can enable them in the app settings.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            when (intent.action) {
                IntervalService.ACTION_PROGRESS_UPDATE -> handleProgressUpdate(intent)
                IntervalService.ACTION_WORKOUT_FINISHED -> handleWorkoutFinished()
                IntervalService.ACTION_WORKOUT_STOPPED -> handleWorkoutStopped()
                "android.media.VOLUME_CHANGED_ACTION", "android.media.STREAM_MUTE_CHANGED_ACTION" -> updateMusicPlayPauseButton()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentState = savedInstanceState?.getString(STATE_UI_STATE)?.let { stored ->
            runCatching { UiState.valueOf(stored) }.getOrDefault(UiState.SETUP)
        } ?: UiState.SETUP
        isWorkoutPaused = savedInstanceState?.getBoolean(STATE_WORKOUT_PAUSED) ?: false

        setupListeners()
        showState(currentState)
        updatePauseButton()
        requestNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(IntervalService.ACTION_PROGRESS_UPDATE)
                addAction(IntervalService.ACTION_WORKOUT_FINISHED)
                addAction(IntervalService.ACTION_WORKOUT_STOPPED)
                addAction("android.media.VOLUME_CHANGED_ACTION")
                addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
            }
            ContextCompat.registerReceiver(
                this,
                progressReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        updateMusicPlayPauseButton()
    }

    override fun onStop() {
        super.onStop()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(progressReceiver) }
            receiverRegistered = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_UI_STATE, currentState.name)
        outState.putBoolean(STATE_WORKOUT_PAUSED, isWorkoutPaused)
    }

    private fun setupListeners() {
        binding.selectPlaylistButton.setOnClickListener { handleSelectPlaylistClick() }
        binding.startButton.setOnClickListener {
            if (validateInputs()) {
                startIntervalService()
            }
        }
        binding.stopButton.setOnClickListener {
            sendIntervalAction(IntervalService.ACTION_STOP)
        }
        binding.pauseResumeButton.setOnClickListener { toggleWorkoutPause() }
        binding.musicPreviousButton.setOnClickListener { sendMusicControl(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        binding.musicPlayPauseButton.setOnClickListener {
            sendMusicControl(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            // It can take a moment for the system to update the isMusicActive state,
            // so we optimistically update the UI.
            binding.musicPlayPauseButton.postDelayed({ updateMusicPlayPauseButton() }, 200)
        }
        binding.musicNextButton.setOnClickListener { sendMusicControl(KeyEvent.KEYCODE_MEDIA_NEXT) }
    }

    private fun handleSelectPlaylistClick() {
        val launchIntent = packageManager.getLaunchIntentForPackage(YOUTUBE_MUSIC_PACKAGE)
        if (launchIntent != null) {
            try {
                startActivity(launchIntent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.toast_install_youtube_music, Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, R.string.toast_install_youtube_music, Toast.LENGTH_SHORT).show()
        }
    }

    private fun validateInputs(): Boolean {
        var valid = true

        val runMinutes = binding.runMinutesInput.editText?.text?.toString()?.toIntOrNull()
        if (runMinutes == null || runMinutes <= 0) {
            binding.runMinutesInput.error = getString(R.string.error_positive_number)
            valid = false
        } else {
            binding.runMinutesInput.error = null
        }

        val walkMinutes = binding.walkMinutesInput.editText?.text?.toString()?.toIntOrNull()
        if (walkMinutes == null || walkMinutes <= 0) {
            binding.walkMinutesInput.error = getString(R.string.error_positive_number)
            valid = false
        } else {
            binding.walkMinutesInput.error = null
        }

        val totalMinutesText = binding.totalMinutesInput.editText?.text?.toString()
        val cyclesText = binding.cycleCountInput.editText?.text?.toString()
        val totalMinutes = totalMinutesText?.toIntOrNull()
        val cycles = cyclesText?.toIntOrNull()

        if ((totalMinutes == null || totalMinutes <= 0) && (cycles == null || cycles <= 0)) {
            binding.totalMinutesInput.error = getString(R.string.error_minutes_or_cycles)
            binding.cycleCountInput.error = getString(R.string.error_minutes_or_cycles)
            valid = false
        } else {
            binding.totalMinutesInput.error = null
            binding.cycleCountInput.error = null
        }

        if (!valid) {
            Toast.makeText(this, R.string.error_fix_fields, Toast.LENGTH_SHORT).show()
        }

        return valid
    }

    private fun startIntervalService() {
        val runMinutes = binding.runMinutesInput.editText?.text?.toString()?.toIntOrNull() ?: 0
        val walkMinutes = binding.walkMinutesInput.editText?.text?.toString()?.toIntOrNull() ?: 0
        val runSeconds = runMinutes * 60
        val walkSeconds = walkMinutes * 60
        val totalMinutes = binding.totalMinutesInput.editText?.text?.toString()?.toIntOrNull() ?: 0
        val cycles = binding.cycleCountInput.editText?.toString()?.toIntOrNull() ?: 0
        val includeWarmup = binding.warmupSwitch.isChecked
        val includeCooldown = binding.cooldownSwitch.isChecked

        val intent = Intent(this, IntervalService::class.java).apply {
            action = IntervalService.ACTION_START
            putExtra(IntervalService.EXTRA_RUN_SECONDS, runSeconds)
            putExtra(IntervalService.EXTRA_WALK_SECONDS, walkSeconds)
            putExtra(IntervalService.EXTRA_TOTAL_MINUTES, totalMinutes)
            putExtra(IntervalService.EXTRA_CYCLE_COUNT, cycles)
            putExtra(IntervalService.EXTRA_INCLUDE_WARMUP, includeWarmup)
            putExtra(IntervalService.EXTRA_INCLUDE_COOLDOWN, includeCooldown)
        }

        ContextCompat.startForegroundService(this, intent)
        isWorkoutPaused = false
        updatePauseButton()
        showState(UiState.IN_PROGRESS)
    }

    private fun sendIntervalAction(action: String) {
        startService(Intent(this, IntervalService::class.java).setAction(action))
    }

    private fun toggleWorkoutPause() {
        val action = if (isWorkoutPaused) IntervalService.ACTION_RESUME else IntervalService.ACTION_PAUSE
        sendIntervalAction(action)
    }

    private fun handleProgressUpdate(intent: Intent) {
        val intervalTitle = intent.getStringExtra(IntervalService.EXTRA_INTERVAL_TITLE)
        val elapsed = intent.getIntExtra(IntervalService.EXTRA_INTERVAL_ELAPSED_SECONDS, 0)
        val remaining = intent.getIntExtra(IntervalService.EXTRA_INTERVAL_REMAINING_SECONDS, 0)
        val totalWorkout = intent.getIntExtra(IntervalService.EXTRA_TOTAL_WORKOUT_SECONDS, 0)
        val totalElapsed = intent.getIntExtra(IntervalService.EXTRA_TOTAL_ELAPSED_SECONDS, 0)
        val cycleNumber = intent.getIntExtra(IntervalService.EXTRA_CYCLE_NUMBER, 0)
        val totalCycles = intent.getIntExtra(IntervalService.EXTRA_TOTAL_CYCLES, 0)
        val paused = intent.getBooleanExtra(IntervalService.EXTRA_IS_PAUSED, false)

        isWorkoutPaused = paused

        val intervalDuration = elapsed + remaining
        val intervalProgress = if (intervalDuration > 0) {
            (elapsed * 100) / intervalDuration
        } else {
            0
        }
        binding.intervalProgressIndicator.progress = intervalProgress

        val totalProgress = if (totalWorkout > 0) {
            (totalElapsed * 100) / totalWorkout
        } else {
            0
        }
        binding.totalProgressIndicator.progress = totalProgress

        binding.intervalRemainingValue.text = formatSeconds(remaining)
        binding.intervalLabel.text = when {
            paused -> getString(R.string.label_interval_paused)
            !intervalTitle.isNullOrBlank() -> intervalTitle
            else -> getString(R.string.label_interval_paused)
        }
        val safeCycle = cycleNumber.coerceAtLeast(0)
        binding.cycleValue.text = when {
            safeCycle <= 0 -> ""
            totalCycles > 0 -> getString(R.string.format_cycle_with_total, safeCycle, totalCycles)
            else -> getString(R.string.format_cycle_open_ended, safeCycle)
        }

        val colorAttr = when {
            paused -> com.google.android.material.R.attr.colorSecondary
            intervalTitle == getString(R.string.segment_run_title) -> com.google.android.material.R.attr.colorPrimary
            else -> com.google.android.material.R.attr.colorTertiary
        }
        binding.intervalProgressIndicator.setIndicatorColor(getColorFromAttr(colorAttr))

        updatePauseButton()
        showState(UiState.IN_PROGRESS)
    }

    private fun handleWorkoutFinished() {
        Toast.makeText(this, R.string.event_workout_complete, Toast.LENGTH_LONG).show()
        isWorkoutPaused = false
        updatePauseButton()
        showState(UiState.SETUP)
    }

    private fun handleWorkoutStopped() {
        Toast.makeText(this, R.string.event_workout_stopped, Toast.LENGTH_SHORT).show()
        isWorkoutPaused = false
        updatePauseButton()
        showState(UiState.SETUP)
    }

    private fun formatSeconds(totalSeconds: Int): String {
        val safeSeconds = totalSeconds.coerceAtLeast(0)
        val minutes = safeSeconds / 60
        val seconds = safeSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    private fun showState(state: UiState) {
        currentState = state
        val target = if (state == UiState.SETUP) 0 else 1
        if (binding.stateSwitcher.displayedChild != target) {
            binding.stateSwitcher.displayedChild = target
        }
        if (state == UiState.IN_PROGRESS) {
            updateMusicPlayPauseButton()
        }
    }

    private fun updatePauseButton() {
        binding.pauseResumeButton.text = if (isWorkoutPaused) {
            getString(R.string.action_resume_workout)
        } else {
            getString(R.string.action_pause_workout)
        }
    }

    private fun updateMusicPlayPauseButton() {
        val isPlaying = audioManager.isMusicActive
        val icon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        binding.musicPlayPauseButton.setIconResource(icon)
    }

    private fun sendMusicControl(keyCode: Int) {
        val eventTime = SystemClock.uptimeMillis()
        val downEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        val upEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)
        audioManager.dispatchMediaKeyEvent(downEvent)
        audioManager.dispatchMediaKeyEvent(upEvent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun getColorFromAttr(@AttrRes attr: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    companion object {
        private const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
        private const val STATE_UI_STATE = "state_ui"
        private const val STATE_WORKOUT_PAUSED = "state_workout_paused"
    }
}
