package com.example.bluettiwatch.protocol;

/** Карта регистров AC180P (из bluetti-bt-lib, devices/ac180p.py) и расчёт времени работы. */
public final class Ac180p {
    private Ac180p() {}

    public static final int REG_BATTERY_SOC = 102;   // %
    public static final int REG_DC_OUTPUT_POWER = 140; // Вт
    public static final int REG_AC_OUTPUT_POWER = 142; // Вт
    public static final int REG_DC_INPUT_POWER = 144;  // Вт (солнце / авто)
    public static final int REG_AC_INPUT_POWER = 146;  // Вт (сеть)
    public static final int REG_CTRL_AC = 2011;        // 1 = вкл
    public static final int REG_CTRL_DC = 2012;        // 1 = вкл

    /** Ёмкость батареи AC180P, Вт·ч. */
    public static final double CAPACITY_WH = 1152.0;
    /** КПД разряда (инвертор/преобразователи) — приблизительно. */
    public static final double DISCHARGE_EFFICIENCY = 0.88;
    /** КПД заряда — приблизительно. */
    public static final double CHARGE_EFFICIENCY = 0.90;
    /** Ниже этого модуля мощности считаем, что батарея простаивает. */
    public static final double IDLE_THRESHOLD_W = 3.0;

    /**
     * Мощность на клеммах батареи: &gt;0 — заряжается, &lt;0 — разряжается.
     */
    public static double batteryPowerW(double inputW, double outputW) {
        return inputW * CHARGE_EFFICIENCY - outputW / DISCHARGE_EFFICIENCY;
    }

    /**
     * Минуты до разряда (если батарея разряжается) или до полного заряда (если заряжается).
     * Возвращает -1, если оценить нельзя (простой).
     */
    public static long estimateMinutes(int socPercent, double batteryPowerW) {
        if (Math.abs(batteryPowerW) < IDLE_THRESHOLD_W) return -1;
        double wh;
        if (batteryPowerW < 0) {
            wh = CAPACITY_WH * Math.max(0, socPercent) / 100.0;
        } else {
            wh = CAPACITY_WH * Math.max(0, 100 - socPercent) / 100.0;
        }
        return Math.round(wh / Math.abs(batteryPowerW) * 60.0);
    }
}
