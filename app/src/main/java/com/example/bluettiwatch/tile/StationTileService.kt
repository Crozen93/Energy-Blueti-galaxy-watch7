package com.example.bluettiwatch.tile

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.CircularProgressIndicator
import androidx.wear.protolayout.material.ProgressIndicatorColors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.EdgeContentLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.example.bluettiwatch.MainActivity
import com.example.bluettiwatch.data.Format
import com.example.bluettiwatch.data.StationPrefs
import com.example.bluettiwatch.data.StationReading
import com.example.bluettiwatch.data.estimateFor
import com.example.bluettiwatch.ui.socColor
import com.example.bluettiwatch.work.RefreshScheduler
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

private const val RES_VERSION = "1"
private const val WHITE = 0xFFFFFFFF.toInt()
private const val GREY = 0xFFAAAAAA.toInt()
private const val TRACK = 0xFF333333.toInt()

/** Плитка: кольцо заряда, проценты, оставшееся время и мощность. Данные — последний снимок. */
class StationTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> {
        RefreshScheduler.refreshIfStale(this)
        val prefs = StationPrefs(this)
        val root = buildLayout(this, requestParams.deviceConfiguration, prefs.mac != null, prefs.lastReading())
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RES_VERSION)
            .setFreshnessIntervalMillis(15 * 60_000L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
            .build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RES_VERSION).build())
}

private fun buildLayout(
    context: Context,
    device: DeviceParameters,
    hasDevice: Boolean,
    r: StationReading?,
): LayoutElementBuilders.LayoutElement {
    val soc = r?.soc ?: 0
    val progress = CircularProgressIndicator.Builder()
        .setProgress(soc / 100f)
        .setStrokeWidth(dp(6f))
        .setCircularProgressIndicatorColors(ProgressIndicatorColors(argb(socColor(soc)), argb(TRACK)))
        .build()

    fun text(s: String, typography: Int, color: Int = WHITE) =
        Text.Builder(context, s).setTypography(typography).setColor(argb(color)).setMaxLines(2).build()

    val content = LayoutElementBuilders.Column.Builder()
        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)

    if (!hasDevice || r == null) {
        content.addContent(
            text(if (hasDevice) "Нет данных.\nОткройте приложение" else "Выберите станцию\nв приложении",
                Typography.TYPOGRAPHY_BODY2)
        )
    } else {
        val est = estimateFor(r.soc, r.batteryW)
        content
            .addContent(text("${r.soc}%", Typography.TYPOGRAPHY_DISPLAY2))
            .addContent(text(Format.estimateLine(est), Typography.TYPOGRAPHY_CAPTION1))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
            .addContent(text("Вх ${r.inputW} · Вых ${r.outputW} Вт", Typography.TYPOGRAPHY_CAPTION2, GREY))
    }

    val layoutBuilder = EdgeContentLayout.Builder(device)
        .setEdgeContent(progress)
        .setPrimaryLabelTextContent(text("Bluetti", Typography.TYPOGRAPHY_CAPTION1, GREY))
        .setContent(content.build())
    if (r != null) {
        layoutBuilder.setSecondaryLabelTextContent(
            text("обновлено ${Format.time(r.timeMillis)}", Typography.TYPOGRAPHY_CAPTION2, GREY)
        )
    }

    val openApp = ModifiersBuilders.Clickable.Builder()
        .setId("open_app")
        .setOnClick(
            ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(context.packageName)
                        .setClassName(MainActivity::class.java.name)
                        .build()
                )
                .build()
        )
        .build()

    return LayoutElementBuilders.Box.Builder()
        .setWidth(expand())
        .setHeight(expand())
        .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(openApp).build())
        .addContent(layoutBuilder.build())
        .build()
}
