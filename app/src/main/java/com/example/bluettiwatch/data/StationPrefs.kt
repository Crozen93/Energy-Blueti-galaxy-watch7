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
        )
    }

    fun clearReading() {
        p.edit().remove("soc").remove("time").apply()
    }

    private fun Boolean?.toInt() = when (this) { true -> 1; false -> 0; null -> -1 }
    private fun Int.toBool(): Boolean? = when (this) { 1 -> true; 0 -> false; else -> null }
}
