package com.example.bluettiwatch.complication

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.example.bluettiwatch.MainActivity
import com.example.bluettiwatch.data.Format
import com.example.bluettiwatch.data.StationPrefs
import com.example.bluettiwatch.data.estimateFor
import com.example.bluettiwatch.work.RefreshScheduler

/** Усложнение для циферблата: заряд станции (кольцо или короткий текст). */
class BatteryComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, 85, "3ч20")

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        RefreshScheduler.refreshIfStale(this)
        val r = StationPrefs(this).lastReading()
        val soc = r?.soc
        val est = r?.let { estimateFor(it.soc, it.batteryW) }
        val title = when {
            r == null -> null
            est == null -> "—"
            est.charging -> "↑" + Format.durationShort(est.minutes)
            else -> Format.durationShort(est.minutes)
        }
        return build(request.complicationType, soc, title)
    }

    private fun build(type: ComplicationType, soc: Int?, title: String?): ComplicationData? {
        val text = PlainComplicationText.Builder(if (soc != null) "$soc%" else "--").build()
        val description = PlainComplicationText.Builder(
            if (soc != null) "Заряд Bluetti $soc%" else "Заряд Bluetti неизвестен"
        ).build()
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return when (type) {
            ComplicationType.RANGED_VALUE ->
                RangedValueComplicationData.Builder(
                    (soc ?: 0).toFloat(), 0f, 100f, description
                )
                    .setText(text)
                    .apply { title?.let { setTitle(PlainComplicationText.Builder(it).build()) } }
                    .setTapAction(tap)
                    .build()

            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(text, description)
                    .apply { title?.let { setTitle(PlainComplicationText.Builder(it).build()) } }
                    .setTapAction(tap)
                    .build()

            else -> null
        }
    }
}
