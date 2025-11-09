package com.example.c25kcustom.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workouts")
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val totalSeconds: Int,
    val runIntervalSeconds: Int,
    val walkIntervalSeconds: Int,
    val cycles: Int,
    val includeWarmup: Boolean,
    val includeCooldown: Boolean,
    val warmupSeconds: Int,
    val cooldownSeconds: Int
)
