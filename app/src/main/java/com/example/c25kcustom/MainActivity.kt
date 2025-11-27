package com.example.c25kcustom

import com.example.c25kcustom.R
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
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.AttrRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.c25kcustom.data.WorkoutEntity
import com.example.c25kcustom.data.WorkoutRepository
import com.example.c25kcustom.databinding.ActivityMainBinding
import com.example.c25kcustom.databinding.ItemCalendarDayBinding
import com.example.c25kcustom.databinding.PageCalendarBinding
import com.example.c25kcustom.databinding.PageTotalsBinding
import com.example.c25kcustom.databinding.PageWorkoutBinding
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.textfield.TextInputLayout
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private enum class UiState { SETUP, IN_PROGRESS }

    private lateinit var binding: ActivityMainBinding
    private var workoutBinding: PageWorkoutBinding? = null
    private var totalsBinding: PageTotalsBinding? = null
    private var calendarBinding: PageCalendarBinding? = null
    private lateinit var pagerAdapter: MainPagerAdapter

    private var receiverRegistered = false
    private var currentState: UiState = UiState.SETUP
    private var isWorkoutPaused = false
    private var currentMonth: YearMonth = YearMonth.now()
    private var latestWorkouts: List<WorkoutEntity> = emptyList()

    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val prefs by lazy { getSharedPreferences("theme_prefs", MODE_PRIVATE) }
    private val workoutHistoryPrefs by lazy { getSharedPreferences("workout_history", MODE_PRIVATE) }
    private val repository by lazy { WorkoutRepository.getInstance(applicationContext) }

    private var intervalService: IntervalService? = null
    private var isBound = false

    private val calendarAdapter = CalendarDayAdapter { cell ->
        if (cell.dayNumber != null && cell.workouts.isNotEmpty()) {
            showCalendarDetail(cell)
        } else if (cell.dayNumber != null) {
            Toast.makeText(this, R.string.calendar_no_workouts, Toast.LENGTH_SHORT).show()
        }
    }

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
                "android.media.VOLUME_CHANGED_ACTION", "android.media.STREAM_MUTE_CHANGED_ACTION" ->
                    updateMusicPlayPauseButton()
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
        isWorkoutPaused = savedInstanceState?.getBoolean(STATE_WORKOUT_PAUSED) ?: false
        currentMonth = savedInstanceState?.getString(STATE_CURRENT_MONTH)?.let {
            runCatching { YearMonth.parse(it) }.getOrElse { YearMonth.now() }
        } ?: YearMonth.now()

        setupPager()
        requestNotificationPermission()
        observeWorkoutHistory()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val hasWorkout = getLastWorkoutSummary() != null
        menu.findItem(R.id.action_add_last_workout_to_calendar)?.isEnabled = hasWorkout
        return super.onPrepareOptionsMenu(menu)
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
            R.id.action_add_last_workout_to_calendar -> {
                val lastWorkout = getLastWorkoutSummary()
                if (lastWorkout == null) {
                    Toast.makeText(this, R.string.toast_no_recorded_workout, Toast.LENGTH_SHORT).show()
                } else {
                    launchCalendarIntent(
                        lastWorkout.totalWorkoutSeconds,
                        lastWorkout.runSeconds,
                        lastWorkout.walkSeconds,
                        lastWorkout.cycles
                    )
                }
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
        invalidateOptionsMenu()
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
        outState.putBoolean(STATE_WORKOUT_PAUSED, isWorkoutPaused)
        outState.putString(STATE_CURRENT_MONTH, currentMonth.toString())
    }

    private fun setupPager() {
        pagerAdapter = MainPagerAdapter()
        binding.viewPager.adapter = pagerAdapter
        binding.viewPager.offscreenPageLimit = 2
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                PAGE_CALENDAR -> getString(R.string.tab_title_calendar)
                PAGE_WORKOUT -> getString(R.string.tab_title_workout)
                PAGE_TOTALS -> getString(R.string.tab_title_totals)
                else -> null
            }
        }.attach()
        binding.viewPager.setCurrentItem(PAGE_WORKOUT, false)
    }

    private fun observeWorkoutHistory() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.workouts.collect { workouts ->
                    latestWorkouts = workouts
                    renderTotals()
                    renderCalendar()
                    invalidateOptionsMenu()
                }
            }
        }
    }

    private fun setupWorkoutPage(binding: PageWorkoutBinding) {
        workoutBinding = binding
        binding.selectPlaylistButton.setOnClickListener { handleSelectPlaylistClick() }
        binding.startButton.setOnClickListener {
            if (validateInputs()) {
                checkForPartialCycles()
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

        updateCycleCountVisibility()
        showState(currentState)
        updatePauseButton()
        updateMusicPlayPauseButton()
    }

    private fun setupTotalsPage(binding: PageTotalsBinding) {
        totalsBinding = binding
        binding.exportButton.setOnClickListener { exportWorkouts() }
        renderTotals()
    }

    private fun setupCalendarPage(binding: PageCalendarBinding) {
        calendarBinding = binding
        binding.calendarRecycler.layoutManager = GridLayoutManager(this, 7)
        binding.calendarRecycler.adapter = calendarAdapter
        binding.calendarRecycler.setHasFixedSize(true)
        binding.previousMonthButton.setOnClickListener {
            currentMonth = currentMonth.minusMonths(1)
            renderCalendar()
        }
        binding.nextMonthButton.setOnClickListener {
            currentMonth = currentMonth.plusMonths(1)
            renderCalendar()
        }
        binding.uploadMonthButton.setOnClickListener { uploadCurrentMonth() }
        renderCalendar()
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
        val binding = workoutBinding ?: return false
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

    private fun checkForPartialCycles() {
        val binding = workoutBinding ?: return
        val runSeconds = parseIntervalSeconds(binding.runMinutesInput) ?: 0
        val walkSeconds = parseIntervalSeconds(binding.walkMinutesInput) ?: 0
        val totalMinutes = binding.totalMinutesInput.editText?.text?.toString()?.toIntOrNull() ?: 0
        val cycles = binding.cycleCountInput.editText?.text?.toString()?.toIntOrNull() ?: 0
        val includeWarmup = binding.warmupSwitch.isChecked
        val includeCooldown = binding.cooldownSwitch.isChecked

        // If user specified cycles explicitly, or if inputs are invalid/zero, just start (service handles it)
        if (cycles > 0 || totalMinutes <= 0 || runSeconds <= 0 || walkSeconds <= 0) {
            startIntervalService()
            return
        }

        val warmupSeconds = if (includeWarmup) IntervalService.WARMUP_SECONDS else 0
        val cooldownSeconds = if (includeCooldown) IntervalService.COOLDOWN_SECONDS else 0
        val totalSeconds = totalMinutes * 60
        val availableForIntervals = totalSeconds
        val cycleDuration = runSeconds + walkSeconds

        if (availableForIntervals % cycleDuration != 0 || availableForIntervals < cycleDuration) {
            val roundedCycles = if (availableForIntervals < 0) 0 else availableForIntervals / cycleDuration
            
            if (roundedCycles < 1) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dialog_partial_cycles_title)
                    .setMessage(R.string.dialog_error_insufficient_time)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return
            }

            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_partial_cycles_title)
                .setMessage(getString(R.string.dialog_partial_cycles_message, roundedCycles))
                .setPositiveButton(R.string.action_continue) { _, _ ->
                    startIntervalService(forceCycles = roundedCycles)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            startIntervalService()
        }
    }

    private fun startIntervalService(forceCycles: Int? = null) {
        val pageBinding = workoutBinding ?: return
        val runSeconds = parseIntervalSeconds(pageBinding.runMinutesInput) ?: 0
        val walkSeconds = parseIntervalSeconds(pageBinding.walkMinutesInput) ?: 0
        var totalMinutes = pageBinding.totalMinutesInput.editText?.text?.toString()?.toIntOrNull() ?: 0
        var cycles = pageBinding.cycleCountInput.editText?.text?.toString()?.toIntOrNull() ?: -1
        val includeWarmup = pageBinding.warmupSwitch.isChecked
        val includeCooldown = pageBinding.cooldownSwitch.isChecked

        if (forceCycles != null) {
            cycles = forceCycles
            totalMinutes = 0 // Disable total time mode to enforce strict cycle count
        }

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
        binding.viewPager.setCurrentItem(PAGE_WORKOUT, true)
    }

    private fun sendIntervalAction(action: String) {
        startService(Intent(this, IntervalService::class.java).setAction(action))
    }

    private fun toggleWorkoutPause() {
        val action = if (isWorkoutPaused) IntervalService.ACTION_RESUME else IntervalService.ACTION_PAUSE
        sendIntervalAction(action)
    }

    private fun handleProgressUpdate(intent: Intent) {
        val binding = workoutBinding ?: return
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
        this.binding.viewPager.setCurrentItem(PAGE_WORKOUT, true)
    }

    private fun handleWorkoutFinished(intent: Intent) {
        val totalWorkoutSeconds = intent.getIntExtra(IntervalService.EXTRA_TOTAL_WORKOUT_SECONDS, 0)
        val runSeconds = intent.getIntExtra(IntervalService.EXTRA_RUN_SECONDS, 0)
        val walkSeconds = intent.getIntExtra(IntervalService.EXTRA_WALK_SECONDS, 0)
        val cycles = intent.getIntExtra(IntervalService.EXTRA_CYCLE_COUNT, 0)
        val includeWarmup = intent.getBooleanExtra(IntervalService.EXTRA_INCLUDE_WARMUP, false)
        val includeCooldown = intent.getBooleanExtra(IntervalService.EXTRA_INCLUDE_COOLDOWN, false)
        val warmupSeconds = intent.getIntExtra(IntervalService.EXTRA_WARMUP_SECONDS, 0)
        val cooldownSeconds = intent.getIntExtra(IntervalService.EXTRA_COOLDOWN_SECONDS, 0)

        saveLastWorkoutSummary(totalWorkoutSeconds, runSeconds, walkSeconds, cycles)
        invalidateOptionsMenu()

        lifecycleScope.launch {
            if (totalWorkoutSeconds > 0 && runSeconds > 0 && walkSeconds > 0) {
                val entity = WorkoutEntity(
                    timestamp = System.currentTimeMillis(),
                    totalSeconds = totalWorkoutSeconds,
                    runIntervalSeconds = runSeconds,
                    walkIntervalSeconds = walkSeconds,
                    cycles = cycles,
                    includeWarmup = includeWarmup,
                    includeCooldown = includeCooldown,
                    warmupSeconds = warmupSeconds,
                    cooldownSeconds = cooldownSeconds
                )
                repository.recordWorkout(entity)
            }
        }

        isWorkoutPaused = false
        updatePauseButton()
        showState(UiState.SETUP)
        binding.viewPager.setCurrentItem(PAGE_WORKOUT, true)
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

    private fun formatDurationLong(seconds: Int): String {
        val safeSeconds = seconds.coerceAtLeast(0)
        val hours = safeSeconds / 3600
        val minutes = (safeSeconds % 3600) / 60
        val secs = safeSeconds % 60
        val builder = StringBuilder()
        if (hours > 0) {
            builder.append(hours).append("h ")
        }
        builder.append(minutes).append("m")
        if (secs > 0) {
            builder.append(" ").append(secs).append("s")
        }
        return builder.toString().trim()
    }

    private fun showState(state: UiState) {
        currentState = state
        val binding = workoutBinding ?: return
        val target = if (state == UiState.SETUP) 0 else 1
        if (binding.stateSwitcher.displayedChild != target) {
            binding.stateSwitcher.displayedChild = target
        }
        if (state == UiState.IN_PROGRESS) {
            updateMusicPlayPauseButton()
        }
    }

    private fun updatePauseButton() {
        val binding = workoutBinding ?: return
        binding.pauseResumeButton.text = if (isWorkoutPaused) {
            getString(R.string.action_resume_workout)
        } else {
            getString(R.string.action_pause_workout)
        }
    }

    private fun updateMusicPlayPauseButton() {
        val binding = workoutBinding ?: return
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
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_calendar_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveLastWorkoutSummary(
        totalWorkoutSeconds: Int,
        runSeconds: Int,
        walkSeconds: Int,
        cycles: Int
    ) {
        if (totalWorkoutSeconds <= 0) return
        workoutHistoryPrefs.edit {
            putInt(KEY_LAST_TOTAL_SECONDS, totalWorkoutSeconds)
            putInt(KEY_LAST_RUN_SECONDS, runSeconds)
            putInt(KEY_LAST_WALK_SECONDS, walkSeconds)
            putInt(KEY_LAST_CYCLES, cycles)
        }
    }

    private fun getLastWorkoutSummary(): WorkoutSummary? {
        val totalSeconds = workoutHistoryPrefs.getInt(KEY_LAST_TOTAL_SECONDS, -1)
        if (totalSeconds <= 0) return null
        val runSeconds = workoutHistoryPrefs.getInt(KEY_LAST_RUN_SECONDS, -1)
        val walkSeconds = workoutHistoryPrefs.getInt(KEY_LAST_WALK_SECONDS, -1)
        val cycles = workoutHistoryPrefs.getInt(KEY_LAST_CYCLES, -1)
        if (runSeconds < 0 || walkSeconds < 0 || cycles < 0) return null
        return WorkoutSummary(totalSeconds, runSeconds, walkSeconds, cycles)
    }

    private fun updateCycleCountVisibility() {
        val binding = workoutBinding ?: return
        val show = prefs.getBoolean("show_cycle_count", false)
        binding.cycleCountInput.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun exportWorkouts() {
        if (latestWorkouts.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_workouts_to_export, Toast.LENGTH_SHORT).show()
            return
        }
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())
        val zone = ZoneId.systemDefault()
        val csv = buildString {
            appendLine("Date,Active Seconds,Run Seconds,Walk Seconds,Cycles,Warmup Seconds,Cooldown Seconds")
            latestWorkouts.forEach { workout ->
                val date = Instant.ofEpochMilli(workout.timestamp).atZone(zone).toLocalDate()
                append(date.format(formatter)).append(',')
                append(workout.totalSeconds).append(',')
                append(workout.runIntervalSeconds * workout.cycles).append(',')
                append(workout.walkIntervalSeconds * workout.cycles).append(',')
                append(workout.cycles).append(',')
                append(workout.warmupSeconds).append(',')
                append(workout.cooldownSeconds).append('\n')
            }
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_share_title))
            putExtra(Intent.EXTRA_TEXT, csv)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.export_share_title)))
    }

    private fun renderTotals() {
        val binding = totalsBinding ?: return
        val workouts = latestWorkouts
        val totalWorkouts = workouts.size
        val totalRunSeconds = workouts.sumOf { it.runIntervalSeconds * it.cycles }
        val totalWalkSeconds = workouts.sumOf { it.walkIntervalSeconds * it.cycles }
        val totalActiveSeconds = workouts.sumOf { it.totalSeconds }
        val averageCycles = if (totalWorkouts > 0) {
            workouts.sumOf { it.cycles }.toDouble() / totalWorkouts
        } else {
            0.0
        }
        binding.totalWorkoutsValue.text = totalWorkouts.toString()
        binding.totalRunTimeValue.text = formatDurationLong(totalRunSeconds)
        binding.totalWalkTimeValue.text = formatDurationLong(totalWalkSeconds)
        binding.totalWorkoutTimeValue.text = formatDurationLong(totalActiveSeconds)
        binding.averageCyclesValue.text = String.format(Locale.getDefault(), "%.1f", averageCycles)
    }

    private fun renderCalendar() {
        val binding = calendarBinding ?: return
        binding.monthLabel.text = formatMonthLabel(currentMonth)
        val cells = buildCalendarCells(currentMonth, latestWorkouts)
        calendarAdapter.submitList(cells)
    }

    private fun formatMonthLabel(month: YearMonth): String {
        val monthName = month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        return "$monthName ${month.year}"
    }

    private fun buildCalendarCells(month: YearMonth, workouts: List<WorkoutEntity>): List<CalendarCell> {
        val firstDay = month.atDay(1)
        val daysInMonth = month.lengthOfMonth()
        val zone = ZoneId.systemDefault()
        val grouped = workouts.groupBy { workout ->
            Instant.ofEpochMilli(workout.timestamp).atZone(zone).toLocalDate()
        }
        val leadingEmpty = (firstDay.dayOfWeek.value % 7)
        val cells = mutableListOf<CalendarCell>()
        repeat(leadingEmpty) {
            cells.add(CalendarCell(null, emptyList()))
        }
        for (day in 1..daysInMonth) {
            val date = month.atDay(day)
            val dayWorkouts = grouped[date].orEmpty()
            cells.add(CalendarCell(day, dayWorkouts))
        }
        while (cells.size % 7 != 0) {
            cells.add(CalendarCell(null, emptyList()))
        }
        return cells
    }

    private fun showCalendarDetail(cell: CalendarCell) {
        val day = cell.dayNumber ?: return
        val date = currentMonth.atDay(day)
        if (cell.workouts.isEmpty()) {
            Toast.makeText(this, R.string.calendar_no_workouts, Toast.LENGTH_SHORT).show()
            return
        }

        val items = cell.workouts.map { workout ->
            getString(
                R.string.calendar_detail_item_format,
                formatDurationLong(workout.totalSeconds),
                workout.cycles
            )
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(date.format(DateTimeFormatter.ofPattern("MMM d, yyyy")))
            .setItems(items.toTypedArray()) { _, index ->
                val workout = cell.workouts[index]
                launchCalendarIntent(
                    workout.totalSeconds,
                    workout.runIntervalSeconds,
                    workout.walkIntervalSeconds,
                    workout.cycles
                )
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun uploadCurrentMonth() {
        val zone = ZoneId.systemDefault()
        val workoutsThisMonth = latestWorkouts.filter {
            val workoutDate = Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate()
            workoutDate.year == currentMonth.year && workoutDate.month == currentMonth.month
        }

        if (workoutsThisMonth.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_workouts_to_export, Toast.LENGTH_SHORT).show()
            return
        }

        workoutsThisMonth.forEach { workout ->
            launchCalendarIntent(
                workout.totalSeconds,
                workout.runIntervalSeconds,
                workout.walkIntervalSeconds,
                workout.cycles
            )
        }
    }

    private inner class MainPagerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = 3

        override fun getItemViewType(position: Int): Int = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                PAGE_CALENDAR -> {
                    val binding = PageCalendarBinding.inflate(inflater, parent, false)
                    setupCalendarPage(binding)
                    CalendarViewHolder(binding)
                }
                PAGE_TOTALS -> {
                    val binding = PageTotalsBinding.inflate(inflater, parent, false)
                    setupTotalsPage(binding)
                    TotalsViewHolder(binding)
                }
                else -> {
                    val binding = PageWorkoutBinding.inflate(inflater, parent, false)
                    setupWorkoutPage(binding)
                    WorkoutViewHolder(binding)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            // No-op
        }
    }

    private inner class WorkoutViewHolder(binding: PageWorkoutBinding) : RecyclerView.ViewHolder(binding.root)

    private inner class TotalsViewHolder(binding: PageTotalsBinding) : RecyclerView.ViewHolder(binding.root)

    private inner class CalendarViewHolder(binding: PageCalendarBinding) : RecyclerView.ViewHolder(binding.root)

    private inner class CalendarDayAdapter(
        private val onDaySelected: (CalendarCell) -> Unit
    ) : RecyclerView.Adapter<CalendarDayAdapter.DayViewHolder>() {

        private var cells: List<CalendarCell> = emptyList()

        inner class DayViewHolder(private val binding: ItemCalendarDayBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(cell: CalendarCell) {
                if (cell.dayNumber != null) {
                    binding.dayNumber.visibility = View.VISIBLE
                    binding.dayNumber.text = cell.dayNumber.toString()
                    val hasWorkout = cell.workouts.isNotEmpty()
                    binding.workoutIndicator.visibility = if (hasWorkout) View.VISIBLE else View.GONE
                    binding.root.isEnabled = true
                    binding.root.alpha = if (hasWorkout) 1f else 0.6f
                    binding.root.setOnClickListener {
                        onDaySelected(cell)
                    }
                } else {
                    binding.dayNumber.visibility = View.INVISIBLE
                    binding.dayNumber.text = ""
                    binding.workoutIndicator.visibility = View.GONE
                    binding.root.isEnabled = false
                    binding.root.alpha = 0.3f
                    binding.root.setOnClickListener(null)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayViewHolder {
            val binding = ItemCalendarDayBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return DayViewHolder(binding)
        }

        override fun onBindViewHolder(holder: DayViewHolder, position: Int) {
            holder.bind(cells[position])
        }

        override fun getItemCount(): Int = cells.size

        fun submitList(newCells: List<CalendarCell>) {
            cells = newCells
            notifyDataSetChanged()
        }
    }

    private data class CalendarCell(val dayNumber: Int?, val workouts: List<WorkoutEntity>)

    companion object {
        private const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
        private const val STATE_UI_STATE = "state_ui"
        private const val STATE_WORKOUT_PAUSED = "state_workout_paused"
        private const val STATE_CURRENT_MONTH = "state_current_month"
        private const val KEY_LAST_TOTAL_SECONDS = "last_total_seconds"
        private const val KEY_LAST_RUN_SECONDS = "last_run_seconds"
        private const val KEY_LAST_WALK_SECONDS = "last_walk_seconds"
        private const val KEY_LAST_CYCLES = "last_cycles"
        private const val PAGE_CALENDAR = 0
        private const val PAGE_WORKOUT = 1
        private const val PAGE_TOTALS = 2
    }

    private data class WorkoutSummary(
        val totalWorkoutSeconds: Int,
        val runSeconds: Int,
        val walkSeconds: Int,
        val cycles: Int
    )
}
