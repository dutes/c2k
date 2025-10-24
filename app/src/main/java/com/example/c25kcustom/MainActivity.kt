package com.example.c25kcustom

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.provider.CalendarContract
import android.util.TypedValue
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.AttrRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.c25kcustom.databinding.ActivityMainBinding
import com.google.android.material.color.DynamicColors
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private enum class UiState { SETUP, IN_PROGRESS }

    private lateinit var binding: ActivityMainBinding
    private var receiverRegistered = false
    private var currentState: UiState = UiState.SETUP
    private var isWorkoutPaused = false
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val prefs by lazy { getSharedPreferences("theme_prefs", MODE_PRIVATE) }
    private var intervalService: IntervalService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as IntervalService.LocalBinder
            intervalService = binder.service
            isBound = true
            if (intervalService?.isWorkoutFinished == true) {
                handleWorkoutFinished(Intent(IntervalService.ACTION_WORKOUT_FINISHED))
                intervalService?.stopServiceAndWorkout()
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
        }
    }

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
                IntervalService.ACTION_WORKOUT_FINISHED -> handleWorkoutFinished(intent)
                IntervalService.ACTION_WORKOUT_STOPPED -> handleWorkoutStopped()
                "android.media.VOLUME_CHANGED_ACTION", "android.media.STREAM_MUTE_CHANGED_ACTION" -> updateMusicPlayPauseButton()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val useDynamicColor = prefs.getBoolean("use_dynamic_color", true)
        if (useDynamicColor) {
            DynamicColors.applyToActivityIfAvailable(this)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        currentState = savedInstanceState?.getString(STATE_UI_STATE)?.let { stored ->
            runCatching { UiState.valueOf(stored) }.getOrDefault(UiState.SETUP)
        } ?: UiState.SETUP
        isWorkoutPaused = savedInstanceState?.getBoolean("state_workout_paused") ?: false

        setupListeners()
        updateCycleCountVisibility()
        showState(currentState)
        updatePauseButton()
        requestNotificationPermission()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_cycle_count -> {
                val show = !prefs.getBoolean("show_cycle_count", false)
                prefs.edit { putBoolean("show_cycle_count", show) }
                updateCycleCountVisibility()
                true
            }
            R.id.action_toggle_dynamic_color -> {
                val useDynamic = !prefs.getBoolean("use_dynamic_color", true)
                prefs.edit { putBoolean("use_dynamic_color", useDynamic) }
                recreate()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, IntervalService::class.java).also { intent ->
            bindService(intent, connection, BIND_AUTO_CREATE)
        }

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
        if (currentState == UiState.IN_PROGRESS) {
            sendBroadcast(Intent(this, IntervalService::class.java).setAction(IntervalService.ACTION_REQUEST_PROGRESS_UPDATE))
        }
        updateMusicPlayPauseButton()
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        if (receiverRegistered) {
            runCatching { unregisterReceiver(progressReceiver) }
            receiverRegistered = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_UI_STATE, currentState.name)
        outState.putBoolean("state_workout_paused", isWorkoutPaused)
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

        if (!validateInterval(binding.runMinutesInput)) valid = false
        if (!validateInterval(binding.walkMinutesInput)) valid = false

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

    private fun validateInterval(input: TextInputLayout): Boolean {
        val text = input.editText?.text?.toString()?.trim()
        val minutes = text?.toDoubleOrNull()
        if (minutes == null || minutes <= 0) {
            input.error = getString(R.string.error_positive_number)
            return false
        }

        if ((minutes * 2.0).rem(1.0) != 0.0) {
            input.error = getString(R.string.error_invalid_increment)
            return false
        }

        input.error = null
        return true
    }

    private fun startIntervalService() {
        val runSeconds = parseIntervalSeconds(binding.runMinutesInput) ?: 0
        val walkSeconds = parseIntervalSeconds(binding.walkMinutesInput) ?: 0
        val totalMinutes = binding.totalMinutesInput.editText?.text?.toString()?.toIntOrNull() ?: 0
        val cycles = binding.cycleCountInput.editText?.text?.toString()?.toIntOrNull() ?: 0
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
        binding.totalProgressIndicator.setIndicatorColor(getColorFromAttr(com.google.android.material.R.attr.colorPrimary))

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

    private fun handleWorkoutFinished(intent: Intent) {
        val totalWorkoutSeconds = intent.getIntExtra(IntervalService.EXTRA_TOTAL_WORKOUT_SECONDS, 0)
        val runSeconds = intent.getIntExtra(IntervalService.EXTRA_RUN_SECONDS, 0)
        val walkSeconds = intent.getIntExtra(IntervalService.EXTRA_WALK_SECONDS, 0)
        val cycles = intent.getIntExtra(IntervalService.EXTRA_CYCLE_COUNT, 0)

        isWorkoutPaused = false
        updatePauseButton()
        showState(UiState.SETUP)
        showCalendarDialog(totalWorkoutSeconds, runSeconds, walkSeconds, cycles)
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

    private fun parseIntervalSeconds(input: TextInputLayout): Int? {
        val text = input.editText?.text?.toString()?.trim()
        val minutes = text?.toDoubleOrNull()
        if (minutes == null || minutes <= 0) return null
        return (minutes * 60.0).roundToInt()
    }

    private fun showCalendarDialog(
        totalWorkoutSeconds: Int,
        runSeconds: Int,
        walkSeconds: Int,
        cycles: Int
    ) {
        AlertDialog.Builder(this)
            .setTitle("Workout Complete!")
            .setMessage("Would you like to add this workout to your calendar?")
            .setPositiveButton("Yes") { _, _ ->
                launchCalendarIntent(totalWorkoutSeconds, runSeconds, walkSeconds, cycles)
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun launchCalendarIntent(
        totalWorkoutSeconds: Int,
        runSeconds: Int,
        walkSeconds: Int,
        cycles: Int
    ) {
        val description = """
            Total Workout: ${formatSeconds(totalWorkoutSeconds)}
            Cycles: $cycles
            Run Interval: ${formatSeconds(runSeconds)}
            Walk Interval: ${formatSeconds(walkSeconds)}
        """.trimIndent()

        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, "C25K Workout")
            .putExtra(CalendarContract.Events.DESCRIPTION, description)
        startActivity(intent)
    }

    private fun updateCycleCountVisibility() {
        val show = prefs.getBoolean("show_cycle_count", false)
        binding.cycleCountInput.visibility = if (show) View.VISIBLE else View.GONE
    }

    companion object {
        private const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
        private const val STATE_UI_STATE = "state_ui"
    }
}
