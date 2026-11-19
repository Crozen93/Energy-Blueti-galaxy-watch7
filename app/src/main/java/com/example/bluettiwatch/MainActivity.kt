package com.example.bluettiwatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.bluettiwatch.data.Permissions
import com.example.bluettiwatch.data.StationPrefs
import com.example.bluettiwatch.data.StationRepository
import com.example.bluettiwatch.ui.AppRoot
import com.example.bluettiwatch.work.RefreshScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Разрешения Bluetooth выданы. */
    val permissionsGranted = MutableStateFlow(false)

    /** Номер выбранной станции (меняется при выборе — перезапускает опрос). */
    val deviceVersion = MutableStateFlow(0)

    /** Опрос на паузе (например, во время поиска станций). */
    val livePaused = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionsGranted.value = Permissions.allGranted(this)
        StationRepository.loadCached(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(permissionsGranted, deviceVersion, livePaused) { granted, _, paused ->
                    granted && !paused
                }.collectLatest { canRun ->
                    if (canRun && StationPrefs(this@MainActivity).mac != null) {
                        StationRepository.runLive(applicationContext)
                    }
                }
            }
        }

        setContent {
            AppRoot(
                activity = this,
                onPermissionsResult = { permissionsGranted.value = Permissions.allGranted(this) },
                onDeviceSelected = { mac, name ->
                    val prefs = StationPrefs(this)
                    if (prefs.mac != mac) prefs.clearReading()
                    prefs.mac = mac
                    prefs.name = name
                    RefreshScheduler.apply(this)
                    StationRepository.loadCached(this)
                    deviceVersion.value += 1
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        permissionsGranted.value = Permissions.allGranted(this)
    }
}
