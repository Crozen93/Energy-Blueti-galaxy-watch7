package com.example.bluettiwatch.ui

/** Цвет кольца по уровню заряда (ARGB). */
fun socColor(soc: Int): Int = when {
    soc >= 50 -> 0xFF4CAF50.toInt()
    soc >= 20 -> 0xFFFFC107.toInt()
    else -> 0xFFF44336.toInt()
}
