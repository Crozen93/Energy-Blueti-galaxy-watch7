package com.example.bluettiwatch.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.bluettiwatch.MainActivity
import com.example.bluettiwatch.R
import com.example.bluettiwatch.data.Format
import com.example.bluettiwatch.data.StationPrefs
import com.example.bluettiwatch.data.StationReading
import com.example.bluettiwatch.data.estimateFor

/**
 * Следит за изменениями в показаниях и присылает уведомления с вибрацией:
 * пропал / появился свет, заряд ниже порога, батарея заряжена до 100%.
 */
object AlertMonitor {
    private const val TAG = "AlertMonitor"
    const val CHANNEL_ALERTS = "bluetti_alerts"

    private const val ID_GRID = 101
    private const val ID_LOW = 102
    private const val ID_FULL = 103

    /** Гистерезис, чтобы не получать повторные оповещения при колебаниях около порога. */
    private const val LOW_RESET_MARGIN = 5
    private const val FULL_RESET_SOC = 95

    private val PATTERN_GRID_OFF = longArrayOf(0, 500, 200, 500, 200, 500)
    private val PATTERN_GRID_ON = longArrayOf(0, 200, 150, 200)
    private val PATTERN_LOW = longArrayOf(0, 400, 200, 400)
    private val PATTERN_FULL = longArrayOf(0, 300)

    @Synchronized
    fun check(context: Context, r: StationReading) {
        val prefs = StationPrefs(context)

        // ---- свет в сети ----
        val grid = if (r.gridPresent) 1 else 0
        val prevGrid = prefs.lastGrid
        if (prevGrid != grid) {
            prefs.lastGrid = grid
            if (prevGrid != -1 && prefs.alertGrid) {
                if (grid == 0) {
                    val est = estimateFor(r.soc, r.batteryW)
                    val left = if (est != null && !est.charging) " Хватит на ≈ ${Format.duration(est.minutes)}." else ""
                    notify(context, ID_GRID, "⚡ Пропал свет", "Станция работает от батареи, заряд ${r.soc}%.$left",
                        PATTERN_GRID_OFF)
                } else {
                    val v = r.acInVoltage?.let { " (${it.toInt()} В)" } ?: ""
                    notify(context, ID_GRID, "💡 Свет появился", "Сеть снова есть$v, заряд ${r.soc}%.",
                        PATTERN_GRID_ON)
                }
            }
        }

        // ---- низкий заряд ----
        val threshold = prefs.lowThreshold
        if (r.soc <= threshold) {
            if (!prefs.lowAlerted) {
                prefs.lowAlerted = true
                if (prefs.alertLow) {
                    val est = estimateFor(r.soc, r.batteryW)
                    val left = if (est != null && !est.charging) " Осталось ≈ ${Format.duration(est.minutes)}." else ""
                    notify(context, ID_LOW, "🔋 Заряд ${r.soc}%", "Заряд станции опустился до $threshold%.$left",
                        PATTERN_LOW)
                }
            }
        } else if (r.soc >= threshold + LOW_RESET_MARGIN) {
            prefs.lowAlerted = false
        }

        // ---- полный заряд ----
        if (r.soc >= 100) {
            if (!prefs.fullAlerted) {
                prefs.fullAlerted = true
                if (prefs.alertFull) {
                    notify(context, ID_FULL, "✅ Станция заряжена", "Заряд 100%.", PATTERN_FULL)
                }
            }
        } else if (r.soc <= FULL_RESET_SOC) {
            prefs.fullAlerted = false
        }
    }

    /** Пробное уведомление из настроек. */
    fun test(context: Context) {
        notify(context, 199, "Проверка оповещений", "Так будут выглядеть уведомления Bluetti.", PATTERN_GRID_ON)
    }

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ALERTS) != null) return
        val ch = NotificationChannel(CHANNEL_ALERTS, "Оповещения станции", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Свет пропал / появился, низкий и полный заряд"
            enableVibration(true)
            vibrationPattern = PATTERN_GRID_OFF
        }
        nm.createNotificationChannel(ch)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun notify(context: Context, id: Int, title: String, text: String, pattern: LongArray) {
        Log.i(TAG, "$title — $text")
        ensureChannel(context)
        vibrate(context, pattern)
        if (!canNotify(context)) return
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "notify", e)
        }
    }

    /** Своя вибрация — заметна, даже если уведомления на часах приглушены. */
    private fun vibrate(context: Context, pattern: LongArray) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (e: Exception) {
            Log.w(TAG, "vibrate", e)
        }
    }
}
