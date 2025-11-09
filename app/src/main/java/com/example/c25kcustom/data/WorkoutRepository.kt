package com.example.c25kcustom.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class WorkoutRepository private constructor(private val workoutDao: WorkoutDao) {

    val workouts: Flow<List<WorkoutEntity>> = workoutDao.observeWorkouts()

    suspend fun recordWorkout(workout: WorkoutEntity) {
        withContext(Dispatchers.IO) {
            workoutDao.insert(workout)
        }
    }

    companion object {
        @Volatile
        private var instance: WorkoutRepository? = null

        fun getInstance(context: Context): WorkoutRepository {
            return instance ?: synchronized(this) {
                instance ?: WorkoutRepository(
                    WorkoutDatabase.getInstance(context).workoutDao()
                ).also { instance = it }
            }
        }
    }
}
