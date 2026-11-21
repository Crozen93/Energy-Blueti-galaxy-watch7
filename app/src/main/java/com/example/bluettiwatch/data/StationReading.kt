package com.example.bluettiwatch.data

import com.example.bluettiwatch.ble.BluettiBleClient
import com.example.bluettiwatch.ble.ModbusException
import com.example.bluettiwatch.protocol.Ac180p

/** Один снимок состояния станции. */
data class StationReading(
    val soc: Int,
    val acOutW: Int,
    val dcOutW: Int,
    val acInW: Int,
    val dcInW: Int,
    val acOn: Boolean?,
    val dcOn: Boolean?,
    val timeMillis: Long,
    /** Напряжение сети на входе, В (null — не удалось прочитать). */
    val acInVoltage: Double? = null,
) {
    /** Есть ли свет в сети: по напряжению, а если его нет — по мощности с сети. */
    val gridPresent: Boolean get() = acInVoltage?.let { it > 80.0 } ?: (acInW > 0)

    val inputW: Int get() = acInW + dcInW
    val outputW: Int get() = acOutW + dcOutW
    val batteryW: Double get() = Ac180p.batteryPowerW(inputW.toDouble(), outputW.toDouble())
}

/** Оценка: минуты до разряда / до полного заряда. */
data class Estimate(val minutes: Long, val charging: Boolean)

fun estimateFor(soc: Int, batteryW: Double): Estimate? {
    val min = Ac180p.estimateMinutes(soc, batteryW)
    if (min < 0) return null
    return Estimate(min, batteryW > 0)
}

/** Читает все нужные регистры AC180P по одному (как в проверенной bluetti-bt-lib). */
suspend fun BluettiBleClient.readStation(): StationReading {
    val soc = readUInt16(Ac180p.REG_BATTERY_SOC)
    val dcOut = readUInt16(Ac180p.REG_DC_OUTPUT_POWER)
    val acOut = readUInt16(Ac180p.REG_AC_OUTPUT_POWER)
    val dcIn = readUInt16(Ac180p.REG_DC_INPUT_POWER)
    val acIn = readUInt16(Ac180p.REG_AC_INPUT_POWER)
    val acVoltage = try {
        readUInt16(Ac180p.REG_AC_INPUT_VOLTAGE) / 10.0
    } catch (e: ModbusException) {
        null
    }
    val acOn = readSwitch(Ac180p.REG_CTRL_AC)
    val dcOn = readSwitch(Ac180p.REG_CTRL_DC)
    return StationReading(
        soc = soc.coerceIn(0, 100),
        acOutW = acOut,
        dcOutW = dcOut,
        acInW = acIn,
        dcInW = dcIn,
        acOn = acOn,
        dcOn = dcOn,
        timeMillis = System.currentTimeMillis(),
        acInVoltage = acVoltage,
    )
}

private suspend fun BluettiBleClient.readSwitch(register: Int): Boolean? =
    try {
        readUInt16(register) == 1
    } catch (e: ModbusException) {
        null
    }
