package com.example.bluettiwatch.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {
    fun duration(minutes: Long): String {
        if (minutes >= 99 * 60) return "> 99 ч"
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0L -> "$m мин"
            m == 0L -> "$h ч"
            else -> "$h ч $m мин"
        }
    }

    /** Короткий вариант для усложнения: "3ч20" / "45м". */
    fun durationShort(minutes: Long): String {
        if (minutes >= 99 * 60) return ">99ч"
        val h = minutes / 60
        val m = minutes % 60
        return if (h == 0L) "${m}м" else "${h}ч%02d".format(m)
    }

    fun estimateLine(e: Estimate?): String = when {
        e == null -> "Нагрузки нет"
        e.charging -> "≈ ${duration(e.minutes)} до 100%"
        else -> "≈ ${duration(e.minutes)} до разряда"
    }

    fun time(millis: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

    fun ago(millis: Long, now: Long = System.currentTimeMillis()): String {
        val sec = ((now - millis) / 1000).coerceAtLeast(0)
        return when {
            sec < 60 -> "только что"
            sec < 3600 -> "${sec / 60} мин назад"
            else -> "в ${time(millis)}"
        }
    }
}
