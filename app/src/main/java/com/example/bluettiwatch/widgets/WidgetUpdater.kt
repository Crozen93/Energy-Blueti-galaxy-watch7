package com.example.bluettiwatch.widgets

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.example.bluettiwatch.complication.BatteryComplicationService
import com.example.bluettiwatch.tile.StationTileService

/** Просит систему перерисовать плитку и усложнения после новых данных. */
object WidgetUpdater {
    fun requestUpdate(context: Context) {
        try {
            TileService.getUpdater(context).requestUpdate(StationTileService::class.java)
        } catch (e: Exception) {
            Log.w("WidgetUpdater", "tile update", e)
        }
        try {
            ComplicationDataSourceUpdateRequester
                .create(context, ComponentName(context, BatteryComplicationService::class.java))
                .requestUpdateAll()
        } catch (e: Exception) {
            Log.w("WidgetUpdater", "complication update", e)
        }
    }
}
