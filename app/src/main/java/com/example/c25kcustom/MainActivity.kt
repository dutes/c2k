package com.example.c25kcustom

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.c25kcustom.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
    }

    private fun setupListeners() {
        binding.startButton.setOnClickListener {
            if (validateInputs()) {
                startIntervalService()
            }
        }

        binding.stopButton.setOnClickListener {
            stopService(Intent(this, IntervalService::class.java))
            binding.statusText.text = getString(R.string.status_stopped)
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
        binding.statusText.text = getString(R.string.status_running)
    }
}
