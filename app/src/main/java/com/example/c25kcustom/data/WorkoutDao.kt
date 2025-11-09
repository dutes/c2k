package com.example.c25kcustom.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(workout: WorkoutEntity)

    @Query("SELECT * FROM workouts ORDER BY timestamp DESC")
    fun observeWorkouts(): Flow<List<WorkoutEntity>>
}
