package com.example.c25kcustom

import android.Manifest
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.example.c25kcustom.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private enum class UiState { SETUP, IN_PROGRESS }

    private data class PlaylistSelection(val id: Long, val name: String)

    private lateinit var binding: ActivityMainBinding
    private var selectedPlaylist: PlaylistSelection? = null
    private var pendingPlaylistLaunch = false
    private var receiverRegistered = false
    private var currentState: UiState = UiState.SETUP
    private var isWorkoutPaused = false
    private val preferences by lazy {
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    private val playlistPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { uri ->
                    handlePlaylistSelection(uri)
                }
            }
        }

    private val playlistPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                if (pendingPlaylistLaunch) {
                    launchPlaylistPicker()
                }
            } else {
                Toast.makeText(this, R.string.toast_playlist_permission_denied, Toast.LENGTH_SHORT)
                    .show()
            }
            pendingPlaylistLaunch = false
        }

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            when (intent.action) {
                IntervalService.ACTION_PROGRESS_UPDATE -> handleProgressUpdate(intent)
                IntervalService.ACTION_WORKOUT_FINISHED -> handleWorkoutFinished()
                IntervalService.ACTION_WORKOUT_STOPPED -> handleWorkoutStopped()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        selectedPlaylist = savedInstanceState?.let {
            val id = it.getLong(STATE_PLAYLIST_ID, INVALID_PLAYLIST_ID)
            val name = it.getString(STATE_PLAYLIST_NAME)
            if (id != INVALID_PLAYLIST_ID && !name.isNullOrBlank()) {
                PlaylistSelection(id, name)
            } else {
                null
            }
        }
        currentState = savedInstanceState?.getString(STATE_UI_STATE)?.let { stored ->
            runCatching { UiState.valueOf(stored) }.getOrDefault(UiState.SETUP)
        } ?: UiState.SETUP
        isWorkoutPaused = savedInstanceState?.getBoolean(STATE_WORKOUT_PAUSED) ?: false

        setupListeners()
        updatePlaylistDisplay()
        showState(currentState)
        updatePauseButton()
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(IntervalService.ACTION_PROGRESS_UPDATE)
                addAction(IntervalService.ACTION_WORKOUT_FINISHED)
                addAction(IntervalService.ACTION_WORKOUT_STOPPED)
            }
            ContextCompat.registerReceiver(
                this,
                progressReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
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
        val playlist = selectedPlaylist
        if (playlist != null) {
            outState.putLong(STATE_PLAYLIST_ID, playlist.id)
            outState.putString(STATE_PLAYLIST_NAME, playlist.name)
        }
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
        binding.musicPlayPauseButton.setOnClickListener { sendMusicControl(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        binding.musicNextButton.setOnClickListener { sendMusicControl(KeyEvent.KEYCODE_MEDIA_NEXT) }
    }

    private fun handleSelectPlaylistClick() {
        if (!isYouTubeMusicInstalled()) {
            Toast.makeText(this, R.string.toast_install_youtube_music, Toast.LENGTH_SHORT).show()
            return
        }
        if (hasPlaylistPermission()) {
            launchPlaylistPicker()
        } else {
            pendingPlaylistLaunch = true
            playlistPermissionLauncher.launch(requiredPlaylistPermission())
        }
    }

    private fun hasPlaylistPermission(): Boolean {
        val permission = requiredPlaylistPermission()
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requiredPlaylistPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun launchPlaylistPicker() {
        pendingPlaylistLaunch = false
        val uri = MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI
        val availableTargets = findPlaylistPickerTargets(uri)

        if (availableTargets.isEmpty()) {
            Toast.makeText(
                this,
                R.string.toast_playlist_picker_unavailable,
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val preferredComponent = loadPreferredPlaylistPicker()
        if (preferredComponent != null) {
            val preferredTarget = availableTargets.firstOrNull { it.component == preferredComponent }
            if (preferredTarget != null && launchPlaylistPicker(preferredTarget, remember = true)) {
                return
            }
            clearPreferredPlaylistPicker()
        }

        if (availableTargets.size == 1) {
            if (launchPlaylistPicker(availableTargets.first(), remember = true)) {
                return
            }
            Toast.makeText(
                this,
                R.string.toast_playlist_picker_launch_failed,
                Toast.LENGTH_LONG
            ).show()
            return
        }

        showPlaylistPickerChooser(availableTargets)
    }

    private fun showPlaylistPickerChooser(targets: List<PlaylistPickerTarget>) {
        val labels = targets.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_playlist_picker_title)
            .setItems(labels) { _, which ->
                val target = targets[which]
                if (launchPlaylistPicker(target, remember = true)) {
                    return@setItems
                }
                Toast.makeText(
                    this,
                    R.string.toast_playlist_picker_launch_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchPlaylistPicker(target: PlaylistPickerTarget, remember: Boolean): Boolean {
        val uri = MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI
        val intent = Intent(Intent.ACTION_PICK, uri).apply {
            type = MediaStore.Audio.Playlists.CONTENT_TYPE
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            component = target.component
        }
        return try {
            playlistPickerLauncher.launch(intent)
            if (remember) {
                savePreferredPlaylistPicker(target.component)
            }
            true
        } catch (error: RuntimeException) {
            false
        }
    }

    private fun findPlaylistPickerTargets(uri: Uri): List<PlaylistPickerTarget> {
        val baseIntent = Intent(Intent.ACTION_PICK, uri).apply {
            type = MediaStore.Audio.Playlists.CONTENT_TYPE
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        }

        val targets = LinkedHashMap<ComponentName, PlaylistPickerTarget>()

        val youtubeIntent = Intent(baseIntent).apply { setPackage(YOUTUBE_MUSIC_PACKAGE) }
        val youtubeTargets = packageManager.queryIntentActivities(youtubeIntent, 0)
        for (info in youtubeTargets) {
            val component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
            val label = info.loadLabel(packageManager)?.toString() ?: info.activityInfo.packageName
            targets[component] = PlaylistPickerTarget(component, label)
        }

        val fallbackTargets = packageManager.queryIntentActivities(baseIntent, 0)
        for (info in fallbackTargets) {
            val component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
            if (targets.containsKey(component)) {
                continue
            }
            val label = info.loadLabel(packageManager)?.toString() ?: info.activityInfo.packageName
            targets[component] = PlaylistPickerTarget(component, label)
        }

        return targets.values.sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun savePreferredPlaylistPicker(component: ComponentName) {
        preferences.edit().putString(KEY_PLAYLIST_PICKER_COMPONENT, component.flattenToString()).apply()
    }

    private fun loadPreferredPlaylistPicker(): ComponentName? {
        val flattened = preferences.getString(KEY_PLAYLIST_PICKER_COMPONENT, null) ?: return null
        return ComponentName.unflattenFromString(flattened)
    }

    private fun clearPreferredPlaylistPicker() {
        preferences.edit().remove(KEY_PLAYLIST_PICKER_COMPONENT).apply()
    }

    private data class PlaylistPickerTarget(val component: ComponentName, val label: String)

    private fun handlePlaylistSelection(uri: Uri) {
        val playlist = readPlaylistSelection(uri)
        if (playlist != null) {
            selectedPlaylist = playlist
            updatePlaylistDisplay()
            if (currentState == UiState.IN_PROGRESS) {
                binding.inProgressPlaylistValue.text = playlistNameOrPlaceholder()
            }
        }
    }

    private fun readPlaylistSelection(uri: Uri): PlaylistSelection? {
        val projection = arrayOf(
            MediaStore.Audio.Playlists._ID,
            MediaStore.Audio.Playlists.NAME
        )
        return try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists._ID)
                    val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists.NAME)
                    val id = cursor.getLong(idIndex)
                    val name = cursor.getString(nameIndex)
                    PlaylistSelection(id, name ?: "")
                } else {
                    val id = runCatching { ContentUris.parseId(uri) }.getOrNull()
                    val name = uri.lastPathSegment
                    if (id != null && !name.isNullOrBlank()) {
                        PlaylistSelection(id, name)
                    } else {
                        null
                    }
                }
            }
        } catch (security: SecurityException) {
            Toast.makeText(this, R.string.toast_playlist_permission_denied, Toast.LENGTH_SHORT)
                .show()
            null
        }
    }

    private fun validateInputs(): Boolean {
        var valid = true

        val runSeconds = binding.runSecondsInput.editText?.text?.toString()?.toIntOrNull()
        if (runSeconds == null || runSeconds <= 0) {
            binding.runSecondsInput.error = getString(R.string.error_positive_number)
            valid = false
        } else {
            binding.runSecondsInput.error = null
        }

        val walkSeconds = binding.walkSecondsInput.editText?.text?.toString()?.toIntOrNull()
        if (walkSeconds == null || walkSeconds <= 0) {
            binding.walkSecondsInput.error = getString(R.string.error_positive_number)
            valid = false
        } else {
            binding.walkSecondsInput.error = null
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

        if (selectedPlaylist == null) {
            Toast.makeText(this, R.string.error_playlist_required, Toast.LENGTH_SHORT).show()
            valid = false
        }

        if (!valid) {
            Toast.makeText(this, R.string.error_fix_fields, Toast.LENGTH_SHORT).show()
        }

        return valid
    }

    private fun startIntervalService() {
        val runSeconds = binding.runSecondsInput.editText?.text?.toString()?.toInt() ?: 0
        val walkSeconds = binding.walkSecondsInput.editText?.text?.toString()?.toInt() ?: 0
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
        binding.inProgressPlaylistValue.text = playlistNameOrPlaceholder()
        showState(UiState.IN_PROGRESS)
        playSelectedPlaylist()
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
        val cycleNumber = intent.getIntExtra(IntervalService.EXTRA_CYCLE_NUMBER, 0)
        val totalCycles = intent.getIntExtra(IntervalService.EXTRA_TOTAL_CYCLES, 0)
        val paused = intent.getBooleanExtra(IntervalService.EXTRA_IS_PAUSED, false)

        isWorkoutPaused = paused
        binding.intervalLabel.text = when {
            paused -> getString(R.string.label_interval_paused)
            !intervalTitle.isNullOrBlank() -> intervalTitle
            else -> getString(R.string.label_interval_paused)
        }
        binding.intervalElapsedValue.text = formatSeconds(elapsed)
        binding.intervalRemainingValue.text = formatSeconds(remaining)
        val safeCycle = cycleNumber.coerceAtLeast(0)
        binding.cycleValue.text = when {
            safeCycle <= 0 -> getString(R.string.format_cycle_open_ended, 0)
            totalCycles > 0 -> getString(R.string.format_cycle_with_total, safeCycle, totalCycles)
            else -> getString(R.string.format_cycle_open_ended, safeCycle)
        }
        updatePauseButton()
        showState(UiState.IN_PROGRESS)
    }

    private fun handleWorkoutFinished() {
        Toast.makeText(this, R.string.event_workout_complete, Toast.LENGTH_LONG).show()
        isWorkoutPaused = false
        updatePauseButton()
        updatePlaylistDisplay()
        showState(UiState.SETUP)
    }

    private fun handleWorkoutStopped() {
        Toast.makeText(this, R.string.event_workout_stopped, Toast.LENGTH_SHORT).show()
        isWorkoutPaused = false
        updatePauseButton()
        updatePlaylistDisplay()
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
            binding.inProgressPlaylistValue.text = playlistNameOrPlaceholder()
        }
    }

    private fun updatePlaylistDisplay() {
        binding.selectedPlaylistText.text = playlistNameOrPlaceholder()
    }

    private fun updatePauseButton() {
        binding.pauseResumeButton.text = if (isWorkoutPaused) {
            getString(R.string.action_resume_workout)
        } else {
            getString(R.string.action_pause_workout)
        }
    }

    private fun playlistNameOrPlaceholder(): String {
        val name = selectedPlaylist?.name?.takeIf { it.isNotBlank() }
        return name ?: getString(R.string.label_no_playlist)
    }

    private fun playSelectedPlaylist() {
        val playlist = selectedPlaylist ?: return
        val playIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(YOUTUBE_MUSIC_PACKAGE)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE)
            putExtra(MediaStore.EXTRA_MEDIA_PLAYLIST, playlist.name)
            putExtra(SearchManager.QUERY, playlist.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            if (playIntent.resolveActivity(packageManager) != null) {
                startActivity(playIntent)
                return
            }
        } catch (notFound: ActivityNotFoundException) {
            // ignored, fallback below
        }

        val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
            data = ContentUris.withAppendedId(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, playlist.id)
            setPackage(YOUTUBE_MUSIC_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            if (fallbackIntent.resolveActivity(packageManager) != null) {
                startActivity(fallbackIntent)
                return
            }
        } catch (notFound: ActivityNotFoundException) {
            // ignored, fallback below
        }

        if (!openYouTubeMusic()) {
            Toast.makeText(this, R.string.toast_playlist_playback_failed, Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun openYouTubeMusic(): Boolean {
        val launchIntent = packageManager.getLaunchIntentForPackage(YOUTUBE_MUSIC_PACKAGE)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return false

        return try {
            startActivity(launchIntent)
            true
        } catch (error: RuntimeException) {
            false
        }
    }

    private fun sendMusicControl(keyCode: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
        val eventTime = SystemClock.uptimeMillis()
        val downEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        val upEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)
        audioManager.dispatchMediaKeyEvent(downEvent)
        audioManager.dispatchMediaKeyEvent(upEvent)
    }

    private fun isYouTubeMusicInstalled(): Boolean {
        return runCatching { packageManager.getPackageInfo(YOUTUBE_MUSIC_PACKAGE, 0) }.isSuccess
    }

    companion object {
        private const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
        private const val PREFERENCES_NAME = "playlist_preferences"
        private const val KEY_PLAYLIST_PICKER_COMPONENT = "playlist_picker_component"
        private const val INVALID_PLAYLIST_ID = -1L
        private const val STATE_PLAYLIST_ID = "state_playlist_id"
        private const val STATE_PLAYLIST_NAME = "state_playlist_name"
        private const val STATE_UI_STATE = "state_ui"
        private const val STATE_WORKOUT_PAUSED = "state_workout_paused"
    }
}
