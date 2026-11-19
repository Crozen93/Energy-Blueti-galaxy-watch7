package com.example.bluettiwatch.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.bluettiwatch.ble.BluettiBleClient
import com.example.bluettiwatch.protocol.Ac180p
import com.example.bluettiwatch.widgets.WidgetUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class ConnState { NO_DEVICE, CONNECTING, CONNECTED, ERROR }

data class UiState(
    val conn: ConnState = ConnState.NO_DEVICE,
    val message: String? = null,
    val reading: StationReading? = null,
    val estimate: Estimate? = null,
    val deviceName: String? = null,
    val busySwitch: Boolean = false,
)

object Permissions {
    val REQUIRED = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    fun allGranted(context: Context) = REQUIRED.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun canConnect(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
}

object StationRepository {
    private const val TAG = "StationRepo"
    private const val POLL_MS = 4_000L
    private const val RETRY_MS = 5_000L
    private const val WIDGET_PUSH_MS = 60_000L

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Только одно BLE-подключение к станции одновременно (станция держит одно соединение). */
    private val connectionMutex = Mutex()

    @Volatile private var liveClient: BluettiBleClient? = null
    private var emaBatteryW: Double? = null
    private var lastWidgetPush = 0L

    /** Показать последние сохранённые данные сразу при открытии. */
    fun loadCached(context: Context) {
        val prefs = StationPrefs(context)
        val cached = prefs.lastReading()
        _state.update {
            it.copy(
                reading = it.reading ?: cached,
                estimate = it.estimate ?: cached?.let { r -> estimateFor(r.soc, r.batteryW) },
                deviceName = prefs.name,
            )
        }
    }

    fun forgetDevice(context: Context) {
        val prefs = StationPrefs(context)
        prefs.mac = null
        prefs.name = null
        prefs.clearReading()
        _state.value = UiState()
        WidgetUpdater.requestUpdate(context.applicationContext)
    }

    /** Живой опрос, пока экран приложения открыт. Отменяется вызывающей стороной. */
    suspend fun runLive(context: Context) {
        val app = context.applicationContext
        val prefs = StationPrefs(app)
        loadCached(app)
        while (true) {
            val mac = prefs.mac
            if (mac == null) {
                _state.update { it.copy(conn = ConnState.NO_DEVICE) }
                return
            }
            _state.update { it.copy(conn = ConnState.CONNECTING, message = null, deviceName = prefs.name) }
            connectionMutex.withLock {
                val client = BluettiBleClient(app, mac)
                try {
                    client.connect()
                    liveClient = client
                    emaBatteryW = null
                    _state.update { it.copy(conn = ConnState.CONNECTED, message = null) }
                    while (true) {
                        publish(app, client.readStation(), live = true)
                        delay(POLL_MS)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "live session failed", e)
                    _state.update { it.copy(conn = ConnState.ERROR, message = e.message ?: e.javaClass.simpleName) }
                } finally {
                    liveClient = null
                    client.close()
                }
            }
            delay(RETRY_MS)
        }
    }

    /** Разовое обновление в фоне (WorkManager). true — данные получены. */
    suspend fun refreshOnce(context: Context): Boolean {
        val app = context.applicationContext
        val prefs = StationPrefs(app)
        val mac = prefs.mac ?: return false
        if (!Permissions.canConnect(app)) return false
        if (!connectionMutex.tryLock()) return true // приложение открыто и уже опрашивает станцию
        prefs.lastAttemptMillis = System.currentTimeMillis()
        val client = BluettiBleClient(app, mac)
        try {
            val reading = withTimeoutOrNull(45_000) {
                client.connect()
                client.readStation()
            } ?: return false
            publish(app, reading, live = false)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "background refresh failed", e)
            return false
        } finally {
            client.close()
            connectionMutex.unlock()
        }
    }

    /** Включить/выключить выход AC или DC. Возвращает текст ошибки или null. */
    suspend fun setOutput(ac: Boolean, on: Boolean): String? {
        val client = liveClient ?: return "Нет соединения со станцией"
        _state.update { it.copy(busySwitch = true) }
        return try {
            client.writeRegister(if (ac) Ac180p.REG_CTRL_AC else Ac180p.REG_CTRL_DC, if (on) 1 else 0)
            _state.update { s ->
                s.copy(reading = s.reading?.let { if (ac) it.copy(acOn = on) else it.copy(dcOn = on) })
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "write failed", e)
            "Не удалось: ${e.message}"
        } finally {
            _state.update { it.copy(busySwitch = false) }
        }
    }

    private fun publish(app: Context, r: StationReading, live: Boolean) {
        StationPrefs(app).saveReading(r)

        val power = if (live) {
            val prev = emaBatteryW
            val smoothed = if (prev == null || (prev > 0) != (r.batteryW > 0)) r.batteryW
            else prev * 0.7 + r.batteryW * 0.3
            emaBatteryW = smoothed
            smoothed
        } else {
            r.batteryW
        }
        _state.update { it.copy(reading = r, estimate = estimateFor(r.soc, power)) }

        val now = System.currentTimeMillis()
        if (!live || now - lastWidgetPush > WIDGET_PUSH_MS) {
            lastWidgetPush = now
            WidgetUpdater.requestUpdate(app)
        }
    }
}
