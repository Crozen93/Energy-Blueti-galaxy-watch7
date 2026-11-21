package com.example.bluettiwatch.data

import android.content.Context

/** Выбранная станция, настройки и последний снимок (для плитки и усложнения). */
class StationPrefs(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("station", Context.MODE_PRIVATE)

    var mac: String?
        get() = p.getString("mac", null)
        set(v) = p.edit().putString("mac", v).apply()

    var name: String?
        get() = p.getString("name", null)
        set(v) = p.edit().putString("name", v).apply()

    var backgroundRefresh: Boolean
        get() = p.getBoolean("bg", true)
        set(v) = p.edit().putBoolean("bg", v).apply()

    var lastAttemptMillis: Long
        get() = p.getLong("last_attempt", 0)
        set(v) = p.edit().putLong("last_attempt", v).apply()

    /** Прежний тайм-аут экрана станции, который нужно вернуть (-1 — нечего возвращать). */
    var displayRestoreValue: Int
        get() = p.getInt("display_restore", -1)
        set(v) = p.edit().putInt("display_restore", v).apply()

    /** Когда вернуть прежний тайм-аут экрана. */
    var displayRestoreAt: Long
        get() = p.getLong("display_restore_at", 0)
        set(v) = p.edit().putLong("display_restore_at", v).apply()

    // ---------- оповещения ----------

    var alertGrid: Boolean
        get() = p.getBoolean("alert_grid", true)
        set(v) = p.edit().putBoolean("alert_grid", v).apply()

    var alertLow: Boolean
        get() = p.getBoolean("alert_low", true)
        set(v) = p.edit().putBoolean("alert_low", v).apply()

    var lowThreshold: Int
        get() = p.getInt("low_threshold", 20)
        set(v) = p.edit().putInt("low_threshold", v).apply()

    var alertFull: Boolean
        get() = p.getBoolean("alert_full", true)
        set(v) = p.edit().putBoolean("alert_full", v).apply()

    /** Постоянный мониторинг (служба держит соединение со станцией). */
    var monitor: Boolean
        get() = p.getBoolean("monitor", false)
        set(v) = p.edit().putBoolean("monitor", v).apply()

    /** Последнее известное состояние сети: 1 — есть, 0 — нет, -1 — неизвестно. */
    var lastGrid: Int
        get() = p.getInt("last_grid", -1)
        set(v) = p.edit().putInt("last_grid", v).apply()

    var lowAlerted: Boolean
        get() = p.getBoolean("low_alerted", false)
        set(v) = p.edit().putBoolean("low_alerted", v).apply()

    var fullAlerted: Boolean
        get() = p.getBoolean("full_alerted", false)
        set(v) = p.edit().putBoolean("full_alerted", v).apply()

    fun saveReading(r: StationReading) {
        p.edit()
            .putInt("soc", r.soc)
            .putInt("ac_out", r.acOutW)
            .putInt("dc_out", r.dcOutW)
            .putInt("ac_in", r.acInW)
            .putInt("dc_in", r.dcInW)
            .putInt("ac_on", r.acOn.toInt())
            .putInt("dc_on", r.dcOn.toInt())
            .putLong("time", r.timeMillis)
            .putFloat("ac_volt", r.acInVoltage?.toFloat() ?: -1f)
            .apply()
    }

    fun lastReading(): StationReading? {
        if (!p.contains("soc")) return null
        return StationReading(
            soc = p.getInt("soc", 0),
            acOutW = p.getInt("ac_out", 0),
            dcOutW = p.getInt("dc_out", 0),
            acInW = p.getInt("ac_in", 0),
            dcInW = p.getInt("dc_in", 0),
            acOn = p.getInt("ac_on", -1).toBool(),
            dcOn = p.getInt("dc_on", -1).toBool(),
            timeMillis = p.getLong("time", 0),
            acInVoltage = p.getFloat("ac_volt", -1f).takeIf { it >= 0f }?.toDouble(),
        )
    }

    fun clearReading() {
        p.edit().remove("soc").remove("time").remove("last_grid")
            .remove("low_alerted").remove("full_alerted").apply()
    }

    private fun Boolean?.toInt() = when (this) { true -> 1; false -> 0; null -> -1 }
    private fun Int.toBool(): Boolean? = when (this) { 1 -> true; 0 -> false; else -> null }
}
