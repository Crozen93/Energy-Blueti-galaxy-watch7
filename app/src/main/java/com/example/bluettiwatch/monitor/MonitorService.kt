package com.example.bluettiwatch.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.bluettiwatch.MainActivity
import com.example.bluettiwatch.R
import com.example.bluettiwatch.data.Permissions
import com.example.bluettiwatch.data.StationPrefs
import com.example.bluettiwatch.data.StationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Постоянный мониторинг: держит BLE-соединение со станцией и опрашивает её
 * каждые 30 с (каждые 4 с, когда открыт экран приложения), чтобы оповещения
 * о пропаже света приходили сразу. Расходует батарею часов.
 */
class MonitorService : Service() {

    companion object {
        private const val TAG = "MonitorService"
        private const val CHANNEL = "bluetti_monitor"
        private const val NOTIFICATION_ID = 1

        /** Запустить или остановить службу согласно настройке. */
        fun sync(context: Context, restart: Boolean = false) {
            val app = context.applicationContext
            val prefs = StationPrefs(app)
            val intent = Intent(app, MonitorService::class.java)
            val shouldRun = prefs.monitor && prefs.mac != null && Permissions.canConnect(app)
            if (!shouldRun || restart) app.stopService(intent)
            if (shouldRun) {
                try {
                    ContextCompat.startForegroundService(app, intent)
                } catch (e: Exception) {
                    Log.w(TAG, "cannot start monitor", e)
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = StationPrefs(this)
        if (!prefs.monitor || prefs.mac == null || !Permissions.canConnect(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive != true) {
            // Без wake lock часы засыпают и опрос останавливается
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BluettiWatch:monitor")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            job = scope.launch {
                StationRepository.runLive(applicationContext, monitor = true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun buildNotification() = run {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm?.getNotificationChannel(CHANNEL) == null) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Мониторинг станции", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle("Bluetti: мониторинг")
            .setContentText("Слежу за светом и зарядом станции")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }
}
