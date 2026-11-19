package com.example.bluettiwatch.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.bluettiwatch.protocol.BluettiCrypto
import com.example.bluettiwatch.protocol.BluettiSession
import com.example.bluettiwatch.protocol.Modbus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class BleException(message: String) : Exception(message)
class ModbusException(val code: Int) : Exception("Станция отклонила запрос (код $code)")

/**
 * Одноразовое BLE-подключение к станции Bluetti: connect() -> запросы -> close().
 * Для нового подключения создаётся новый экземпляр.
 */
@SuppressLint("MissingPermission")
class BluettiBleClient(private val context: Context, private val address: String) {

    companion object {
        private const val TAG = "BluettiBle"
        val NOTIFY_UUID: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        val WRITE_UUID: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val REQUEST_TIMEOUT_MS = 5_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gattOpMutex = Mutex()   // одна GATT-операция записи за раз
    private val commandMutex = Mutex()  // один Modbus-запрос за раз

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var mtu = 23
    @Volatile private var servicesRequested = false
    @Volatile private var closed = false

    private val setupDone = CompletableDeferred<Unit>()
    private val handshakeDone = CompletableDeferred<Unit>()
    @Volatile private var writeAck: CompletableDeferred<Int>? = null
    @Volatile private var pendingFrame: CompletableDeferred<ByteArray>? = null

    private val session = BluettiSession(BluettiCrypto(), true, object : BluettiSession.Callbacks {
        override fun send(data: ByteArray) {
            scope.launch {
                try {
                    writeRaw(data)
                } catch (e: Exception) {
                    Log.w(TAG, "Handshake write failed", e)
                    handshakeDone.completeExceptionally(BleException("Ошибка рукопожатия: ${e.message}"))
                }
            }
        }

        override fun onReady() {
            handshakeDone.complete(Unit)
        }

        override fun onFrame(frame: ByteArray) {
            pendingFrame?.complete(frame)
        }

        override fun onProtocolError(message: String) {
            Log.w(TAG, "Protocol: $message")
            if (message.contains("Подпись") || message.contains("отклонила")) {
                handshakeDone.completeExceptionally(BleException(message))
            }
        }
    })

    val isEncrypted: Boolean get() = session.isEncrypted

    /** Подключение + рукопожатие. Бросает исключение при ошибке (и сам закрывает соединение). */
    suspend fun connect() {
        try {
            val manager = context.getSystemService(BluetoothManager::class.java)
                ?: throw BleException("Bluetooth недоступен")
            val adapter = manager.adapter ?: throw BleException("Bluetooth недоступен")
            if (!adapter.isEnabled) throw BleException("Bluetooth выключен")
            val device = adapter.getRemoteDevice(address)

            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                ?: throw BleException("Не удалось начать подключение")

            withTimeoutOrNull(20_000) { setupDone.await() }
                ?: throw BleException("Станция не отвечает")

            // Станция сама присылает challenge. Если его нет — старая прошивка без шифрования.
            val ok = withTimeoutOrNull(6_000) { handshakeDone.await() }
            if (ok == null) {
                if (!session.sawChallenge()) {
                    Log.i(TAG, "No challenge received, using unencrypted mode")
                    session.useUnencrypted()
                } else {
                    withTimeoutOrNull(15_000) { handshakeDone.await() }
                        ?: throw BleException("Не удалось установить шифрование")
                }
            }
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    fun close() {
        if (closed) return
        closed = true
        val g = gatt
        gatt = null
        try {
            g?.disconnect()
            g?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close", e)
        }
        failPending(BleException("Соединение закрыто"))
        scope.cancel()
    }

    // ---------- Modbus ----------

    suspend fun readRegisters(address: Int, quantity: Int): ByteArray {
        val frame = transact(Modbus.readCommand(address, quantity), Modbus.readResponseLength(quantity))
        if (Modbus.isException(frame)) throw ModbusException(Modbus.exceptionCode(frame))
        if (!Modbus.crcOk(frame)) throw BleException("Ошибка CRC в ответе")
        val payload = Modbus.readPayload(frame)
        if (payload.size < quantity * 2) throw BleException("Короткий ответ станции")
        return payload
    }

    suspend fun readUInt16(address: Int): Int = Modbus.uint16(readRegisters(address, 1), 0)

    suspend fun writeRegister(address: Int, value: Int) {
        val frame = transact(Modbus.writeCommand(address, value), Modbus.WRITE_RESPONSE_LENGTH)
        if (Modbus.isException(frame)) throw ModbusException(Modbus.exceptionCode(frame))
    }

    private suspend fun transact(command: ByteArray, expectedLength: Int): ByteArray =
        commandMutex.withLock {
            if (closed) throw BleException("Соединение закрыто")
            val deferred = CompletableDeferred<ByteArray>()
            pendingFrame = deferred
            try {
                writeRaw(session.wrapCommand(command, expectedLength))
                withTimeoutOrNull(REQUEST_TIMEOUT_MS) { deferred.await() }
                    ?: throw BleException("Нет ответа от станции")
            } finally {
                pendingFrame = null
            }
        }

    // ---------- GATT ----------

    private suspend fun writeRaw(data: ByteArray) = gattOpMutex.withLock {
        val g = gatt ?: throw BleException("Нет соединения")
        val c = writeChar ?: throw BleException("Нет характеристики записи")
        val props = c.properties
        val noResponse = (props and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0 &&
            (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        val writeType = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        val chunkSize = (mtu - 3).coerceAtLeast(20)
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunkSize, data.size)
            val chunk = data.copyOfRange(offset, end)
            val ack = CompletableDeferred<Int>()
            writeAck = ack
            if (!writeCharacteristicCompat(g, c, chunk, writeType)) {
                throw BleException("Не удалось отправить данные")
            }
            // withTimeoutOrNull, а не withTimeout: таймаут не должен выглядеть как отмена корутины
            val status = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { ack.await() }
                ?: throw BleException("Станция не подтвердила запись")
            if (status != BluetoothGatt.GATT_SUCCESS) throw BleException("Ошибка записи ($status)")
            offset = end
        }
    }

    private fun writeCharacteristicCompat(
        g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, type: Int
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
    } else {
        @Suppress("DEPRECATION")
        run {
            c.writeType = type
            c.value = value
            g.writeCharacteristic(c)
        }
    }

    private fun writeDescriptorCompat(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                d.value = value
                g.writeDescriptor(d)
            }
        }

    private fun findCharacteristic(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? {
        for (service in g.services) {
            service.getCharacteristic(uuid)?.let { return it }
        }
        return null
    }

    private fun failPending(e: Exception) {
        setupDone.completeExceptionally(e)
        handshakeDone.completeExceptionally(e)
        writeAck?.completeExceptionally(e)
        pendingFrame?.completeExceptionally(e)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (!g.requestMtu(517)) startDiscovery(g)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                failPending(BleException("Соединение разорвано (код $status)"))
                try { g.close() } catch (_: Exception) {}
                if (gatt === g) gatt = null
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            Log.d(TAG, "mtu=$newMtu status=$status")
            startDiscovery(g)
        }

        private fun startDiscovery(g: BluetoothGatt) {
            if (servicesRequested) return
            servicesRequested = true
            if (!g.discoverServices()) setupDone.completeExceptionally(BleException("Ошибка поиска сервисов"))
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setupDone.completeExceptionally(BleException("Ошибка поиска сервисов ($status)"))
                return
            }
            val notify = findCharacteristic(g, NOTIFY_UUID)
            val write = findCharacteristic(g, WRITE_UUID)
            if (notify == null || write == null) {
                setupDone.completeExceptionally(BleException("Это не станция Bluetti (нет FF01/FF02)"))
                return
            }
            writeChar = write
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                setupDone.complete(Unit)
                return
            }
            val value = if ((notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            if (!writeDescriptorCompat(g, cccd, value)) {
                setupDone.completeExceptionally(BleException("Не удалось включить уведомления"))
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid == CCCD_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) setupDone.complete(Unit)
                else setupDone.completeExceptionally(BleException("Не удалось включить уведомления ($status)"))
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeAck?.complete(status)
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == NOTIFY_UUID) session.onNotification(value)
        }

        // Android 12
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = c.value ?: return
            if (c.uuid == NOTIFY_UUID) session.onNotification(value.copyOf())
        }
    }
}
