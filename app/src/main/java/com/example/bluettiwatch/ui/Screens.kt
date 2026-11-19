package com.example.bluettiwatch.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import com.example.bluettiwatch.MainActivity
import com.example.bluettiwatch.data.ConnState
import com.example.bluettiwatch.data.Format
import com.example.bluettiwatch.data.Permissions
import com.example.bluettiwatch.data.StationPrefs
import com.example.bluettiwatch.data.StationRepository
import com.example.bluettiwatch.data.UiState
import com.example.bluettiwatch.work.RefreshScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Grey = Color(0xFFAAAAAA)
private val Track = Color(0xFF333333)

@Composable
fun AppRoot(
    activity: MainActivity,
    onPermissionsResult: () -> Unit,
    onDeviceSelected: (mac: String, name: String) -> Unit,
) {
    MaterialTheme {
        val granted by activity.permissionsGranted.collectAsState()
        val version by activity.deviceVersion.collectAsState()
        val context = LocalContext.current
        val hasDevice = remember(version) { StationPrefs(context).mac != null }
        var showScan by remember { mutableStateOf(false) }

        when {
            !granted -> PermissionScreen(onPermissionsResult)
            !hasDevice || showScan -> {
                DisposableEffect(Unit) {
                    activity.livePaused.value = true
                    onDispose { activity.livePaused.value = false }
                }
                ScanScreen(
                    canGoBack = hasDevice,
                    onBack = { showScan = false },
                    onSelected = { mac, name ->
                        onDeviceSelected(mac, name)
                        showScan = false
                    },
                )
            }
            else -> {
                val state by StationRepository.state.collectAsState()
                StatusScreen(
                    state = state,
                    onChangeDevice = { showScan = true },
                    onForget = {
                        StationRepository.forgetDevice(context)
                        RefreshScheduler.apply(context)
                        activity.deviceVersion.value += 1
                    },
                )
            }
        }
    }
}

// ---------------- Разрешения ----------------

@Composable
private fun PermissionScreen(onResult: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onResult() }
    val listState = rememberScalingLazyListState()
    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    "Нужен доступ к Bluetooth, чтобы подключиться к станции Bluetti.",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            item {
                Chip(
                    onClick = { launcher.launch(Permissions.REQUIRED) },
                    label = { Text("Разрешить") },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    "Если запрос не появляется — Настройки → Приложения → Bluetti → Разрешения.",
                    fontSize = 11.sp,
                    color = Grey,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ---------------- Поиск станции ----------------

private data class Found(val name: String, val address: String, val rssi: Int)

private val BLUETTI_NAME = Regex("^[A-Z]{2}[A-Z0-9]*\\d{8,}$")

@SuppressLint("MissingPermission")
@Composable
private fun ScanScreen(canGoBack: Boolean, onBack: () -> Unit, onSelected: (String, String) -> Unit) {
    BackHandler(enabled = canGoBack) { onBack() }
    val context = LocalContext.current
    val found = remember { mutableStateListOf<Found>() }
    var scanning by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    var showAll by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(attempt) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.bluetoothLeScanner
        val handler = Handler(Looper.getMainLooper())
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                val addr = result.device.address
                val idx = found.indexOfFirst { it.address == addr }
                val item = Found(name, addr, result.rssi)
                if (idx < 0) found.add(item) else found[idx] = item
            }

            override fun onScanFailed(errorCode: Int) {
                error = "Ошибка поиска ($errorCode)"
                scanning = false
            }
        }
        val stop = Runnable {
            try { scanner?.stopScan(callback) } catch (_: Exception) {}
            scanning = false
        }
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            error = "Включите Bluetooth на часах"
            scanning = false
        } else {
            error = null
            scanning = true
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(null, settings, callback)
            handler.postDelayed(stop, 15_000)
        }
        onDispose {
            handler.removeCallbacks(stop)
            try { scanner?.stopScan(callback) } catch (_: Exception) {}
        }
    }

    val visible = found
        .filter { showAll || BLUETTI_NAME.matches(it.name) || it.name.contains("BLUETTI", ignoreCase = true) }
        .sortedWith(compareByDescending<Found> { it.name.startsWith("AC180") }.thenByDescending { it.rssi })

    val listState = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    when {
                        error != null -> error!!
                        scanning -> "Ищу станции…"
                        visible.isEmpty() -> "Станция не найдена"
                        else -> "Выберите станцию"
                    },
                    textAlign = TextAlign.Center,
                )
            }
            items(visible.size) { i ->
                val d = visible[i]
                Chip(
                    onClick = { onSelected(d.address, d.name) },
                    label = { Text(d.name, maxLines = 1) },
                    secondaryLabel = { Text("${d.address}  ${d.rssi} dBm", maxLines = 1, fontSize = 10.sp) },
                    colors = if (d.name.startsWith("AC180")) ChipDefaults.primaryChipColors()
                    else ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!scanning) {
                item {
                    Chip(
                        onClick = { attempt++ },
                        label = { Text("Искать снова") },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                ToggleChip(
                    checked = showAll,
                    onCheckedChange = { showAll = it },
                    label = { Text("Все устройства", maxLines = 1) },
                    toggleControl = { Switch(checked = showAll) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    "Закройте приложение BLUETTI на телефоне — станция принимает только одно подключение.",
                    fontSize = 10.sp,
                    color = Grey,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ---------------- Главный экран ----------------

@Composable
private fun StatusScreen(state: UiState, onChangeDevice: () -> Unit, onForget: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { StationPrefs(context) }
    var bgRefresh by remember { mutableStateOf(prefs.backgroundRefresh) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var confirmOff by remember { mutableStateOf<Boolean?>(null) } // true = AC, false = DC
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(10_000)
            value = System.currentTimeMillis()
        }
    }
    LaunchedEffect(confirmOff) {
        if (confirmOff != null) {
            delay(4_000)
            confirmOff = null
        }
    }

    val r = state.reading
    val connected = state.conn == ConnState.CONNECTED
    val listState = rememberScalingLazyListState()

    fun onOutputTap(ac: Boolean, wantOn: Boolean) {
        if (!wantOn && confirmOff != ac) {
            confirmOff = ac
            return
        }
        confirmOff = null
        scope.launch { actionMessage = StationRepository.setOutput(ac, wantOn) }
    }

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            autoCentering = AutoCenteringParams(itemIndex = 0),
        ) {
            item { SocRing(state) }
            item { PowerRow(state) }
            item {
                Text(
                    statusLine(state, now),
                    fontSize = 11.sp,
                    color = if (state.conn == ConnState.ERROR) Color(0xFFFF8A80) else Grey,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            actionMessage?.let { msg ->
                item { Text(msg, fontSize = 11.sp, color = Color(0xFFFF8A80), textAlign = TextAlign.Center) }
            }
            item {
                OutputChip(
                    title = "Выход AC (220 В)",
                    on = r?.acOn,
                    confirming = confirmOff == true,
                    enabled = connected && !state.busySwitch,
                ) { onOutputTap(true, it) }
            }
            item {
                OutputChip(
                    title = "Выход DC (USB/12 В)",
                    on = r?.dcOn,
                    confirming = confirmOff == false,
                    enabled = connected && !state.busySwitch,
                ) { onOutputTap(false, it) }
            }
            item {
                ToggleChip(
                    checked = bgRefresh,
                    onCheckedChange = {
                        bgRefresh = it
                        prefs.backgroundRefresh = it
                        RefreshScheduler.apply(context)
                    },
                    label = { Text("Фоновое обновление", maxLines = 1) },
                    secondaryLabel = { Text("для плитки, раз в 15 мин", maxLines = 1) },
                    toggleControl = { Switch(checked = bgRefresh) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Chip(
                    onClick = onChangeDevice,
                    label = { Text("Сменить станцию") },
                    secondaryLabel = { Text(state.deviceName ?: "", maxLines = 1) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Chip(
                    onClick = onForget,
                    label = { Text("Забыть станцию") },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SocRing(state: UiState) {
    val r = state.reading
    val soc = r?.soc
    Box(modifier = Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = (soc ?: 0) / 100f,
            modifier = Modifier.fillMaxSize(),
            indicatorColor = Color(socColor(soc ?: 0)),
            trackColor = Track,
            strokeWidth = 8.dp,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (soc != null) "$soc%" else "--%",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
            )
            val est = state.estimate
            if (r != null) {
                Text(
                    when {
                        est == null -> "нет нагрузки"
                        else -> Format.duration(est.minutes)
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
                if (est != null) {
                    Text(
                        if (est.charging) "до полного заряда" else "до разряда",
                        fontSize = 11.sp,
                        color = Grey,
                    )
                }
            }
        }
    }
}

@Composable
private fun PowerRow(state: UiState) {
    val r = state.reading ?: return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            PowerValue("Вход", r.inputW, Color(0xFF81C784))
            PowerValue("Выход", r.outputW, Color(0xFFFFB74D))
        }
        Text(
            "AC ${r.acOutW} Вт · DC ${r.dcOutW} Вт",
            fontSize = 11.sp,
            color = Grey,
        )
        if (r.inputW > 0) {
            Text(
                "сеть ${r.acInW} Вт · солнце/авто ${r.dcInW} Вт",
                fontSize = 10.sp,
                color = Grey,
            )
        }
    }
}

@Composable
private fun PowerValue(label: String, watts: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = Grey)
        Text("$watts Вт", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun OutputChip(
    title: String,
    on: Boolean?,
    confirming: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ToggleChip(
        checked = on == true,
        onCheckedChange = onChange,
        label = { Text(title, maxLines = 1) },
        secondaryLabel = {
            Text(
                when {
                    confirming -> "Нажмите ещё раз — выключить"
                    on == null -> "состояние неизвестно"
                    on -> "включён"
                    else -> "выключен"
                },
                maxLines = 1,
                color = if (confirming) Color(0xFFFFB74D) else Color.Unspecified,
            )
        },
        toggleControl = { Switch(checked = on == true, enabled = enabled) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun statusLine(state: UiState, now: Long): String {
    val r = state.reading
    return when (state.conn) {
        ConnState.CONNECTED -> "Онлайн · " + (r?.let { Format.ago(it.timeMillis, now) } ?: "")
        ConnState.CONNECTING -> "Подключение…" + (r?.let { "\nданные от ${Format.time(it.timeMillis)}" } ?: "")
        ConnState.ERROR -> "Нет связи: ${state.message ?: ""}\nповтор через 5 с" +
            (r?.let { "\nданные от ${Format.time(it.timeMillis)}" } ?: "")
        ConnState.NO_DEVICE -> "Станция не выбрана"
    }
}
