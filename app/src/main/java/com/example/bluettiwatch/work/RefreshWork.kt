package com.example.bluettiwatch.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.bluettiwatch.data.StationPrefs
import com.example.bluettiwatch.data.StationRepository
import java.util.concurrent.TimeUnit

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        StationRepository.refreshOnce(applicationContext)
        return Result.success()
    }
}

object RefreshScheduler {
    private const val PERIODIC = "bluetti_periodic_refresh"
    private const val ONCE = "bluetti_refresh_now"
    private const val STALE_MS = 5 * 60_000L
    private const val MIN_ATTEMPT_GAP_MS = 2 * 60_000L

    /** Включает/выключает обновление раз в 15 минут согласно настройке. */
    fun apply(context: Context) {
        val wm = WorkManager.getInstance(context)
        val prefs = StationPrefs(context)
        if (prefs.backgroundRefresh && prefs.mac != null) {
            wm.enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).build(),
            )
        } else {
            wm.cancelUniqueWork(PERIODIC)
        }
    }

    /** Вызывается плиткой/усложнением: если данные устарели — обновить сейчас. */
    fun refreshIfStale(context: Context) {
        val prefs = StationPrefs(context)
        if (!prefs.backgroundRefresh || prefs.mac == null) return
        val now = System.currentTimeMillis()
        val last = prefs.lastReading()?.timeMillis ?: 0L
        if (now - last < STALE_MS || now - prefs.lastAttemptMillis < MIN_ATTEMPT_GAP_MS) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONCE,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RefreshWorker>().build(),
        )
    }
}
