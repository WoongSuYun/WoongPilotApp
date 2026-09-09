package kr.co.tesla.cameraalert.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.security.SecureRandom
import java.util.UUID

/** One GATT owner, serialized writes, bounded notifications and timeouts. Call on Main. */
@SuppressLint("MissingPermission")
class TeslaBleClient(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var gatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private val connected = CompletableDeferred<Unit>()
    private val discovered = CompletableDeferred<Unit>()
    private val subscribed = CompletableDeferred<Unit>()
    private val writes = Channel<Int>(8)
    private val incoming = Channel<ByteArray>(64)
    private var tx: BluetoothGattCharacteristic? = null
    private val random = SecureRandom()
    private fun randomBytes() = ByteArray(16).also(random::nextBytes)
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) connected.complete(Unit)
            else if (state == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val error = IllegalStateException("차량 BLE 연결이 끊겼습니다 ($status)")
                connected.completeExceptionally(error); discovered.completeExceptionally(error)
                subscribed.completeExceptionally(error); incoming.close(error); writes.close(error)
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) discovered.complete(Unit)
            else discovered.completeExceptionally(IllegalStateException("차량 서비스 검색 실패 ($status)"))
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) subscribed.complete(Unit)
            else subscribed.completeExceptionally(IllegalStateException("차량 알림 연결 실패 ($status)"))
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (writes.trySend(status).isFailure) writes.close(IllegalStateException("BLE 쓰기 응답 초과"))
        }
        @Deprecated("Legacy callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (android.os.Build.VERSION.SDK_INT < 33 && c.uuid == RX) receive(c.value ?: byteArrayOf())
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == RX) receive(value)
        }
        private fun receive(value: ByteArray) {
            if (incoming.trySend(value.copyOf()).isFailure) incoming.close(IllegalStateException("차량 응답이 너무 빠릅니다"))
        }
    }

    suspend fun run(vin: String, pair: Boolean, status: (String) -> Unit, verified: () -> Unit) {
        try {
            val key = VehicleKey.publicKey(vin)
            status("차량 검색 중…")
            val adapter = manager.adapter ?: error("Bluetooth를 지원하지 않습니다")
            check(adapter.isEnabled) { "Bluetooth를 켜 주세요" }
            scanner = adapter.bluetoothLeScanner ?: error("Bluetooth 검색을 시작할 수 없습니다")
            val found = CompletableDeferred<BluetoothDevice>()
            val name = TeslaProtocol.localName(vin)
            scanCallback = object : ScanCallback() {
                override fun onScanResult(type: Int, result: ScanResult) {
                    if (result.scanRecord?.deviceName == name) found.complete(result.device)
                }
                override fun onScanFailed(code: Int) {
                    found.completeExceptionally(IllegalStateException("차량 검색 실패 ($code)"))
                }
            }
            scanner!!.startScan(listOf(ScanFilter.Builder().setDeviceName(name).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
            val device = withTimeout(25_000) { found.await() }
            scanner!!.stopScan(scanCallback); scanCallback = null
            status("차량 연결 중…")
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            withTimeout(15_000) { connected.await() }
            check(gatt!!.discoverServices()) { "차량 서비스 검색 시작 실패" }
            withTimeout(12_000) { discovered.await() }
            val service = gatt!!.getService(SERVICE) ?: error("Tesla BLE 서비스를 찾지 못했습니다")
            tx = service.getCharacteristic(TX) ?: error("차량 쓰기 특성이 없습니다")
            val rx = service.getCharacteristic(RX) ?: error("차량 알림 특성이 없습니다")
            check(gatt!!.setCharacteristicNotification(rx, true))
            val descriptor = rx.getDescriptor(CCCD) ?: error("차량 알림 설정이 없습니다")
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            check(gatt!!.writeDescriptor(descriptor))
            withTimeout(10_000) { subscribed.await() }
            if (pair) {
                send(TeslaProtocol.addKey(key))
                status("카드키를 차량 콘솔에 대고 차량 화면에서 승인하세요")
            }
            val frames = TeslaProtocol.Frames()
            val route = randomBytes()
            var registered = false
            val deadline = android.os.SystemClock.elapsedRealtime() + if (pair) 90_000 else 15_000
            while (currentCoroutineContext().isActive) {
                val uuid = randomBytes()
                send(TeslaProtocol.keyQuery(key, route, uuid))
                val confirmed = withTimeoutOrNull(4_000) {
                    while (true) {
                        for (frame in frames.feed(incoming.receive())) {
                            val fields = TeslaProtocol.fields(frame)
                            // VCSEC BLE replies commonly omit request_uuid (field 50).
                            // This client sends one request at a time, so the unique route
                            // safely correlates an omitted UUID; reject it only if present
                            // and different.
                            val requestUuid = fields.firstOrNull { it.id == 50 }?.value
                            if (requestUuid != null && !requestUuid.contentEquals(uuid)) continue
                            val destination = fields.firstOrNull { it.id == 6 }?.value ?: continue
                            if (TeslaProtocol.fields(destination).firstOrNull { it.id == 2 }?.value?.contentEquals(route) != true) continue
                            val payload = fields.firstOrNull { it.id == 10 }?.value ?: continue
                            val entry = TeslaProtocol.fields(payload).firstOrNull { it.id == 17 }?.value ?: continue
                            val pub = TeslaProtocol.fields(entry).firstOrNull { it.id == 2 }?.value ?: continue
                            if (TeslaProtocol.fields(pub).firstOrNull { it.id == 1 }?.value?.contentEquals(key) == true)
                                return@withTimeoutOrNull true
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") false
                } == true
                if (confirmed) {
                    if (!registered) { registered = true; verified() }
                } else {
                    if (registered) error("차량 연결 확인 응답이 없습니다")
                    if (android.os.SystemClock.elapsedRealtime() >= deadline)
                        error(if (pair) "키 등록을 확인하지 못했습니다. 카드키 승인 후 다시 시도하세요" else "등록된 키를 확인할 수 없습니다. 키 등록을 먼저 진행하세요")
                }
                delay(if (registered) 5_000 else 1_000)
            }
        } finally { close() }
    }
    @Suppress("DEPRECATION")
    private suspend fun send(payload: ByteArray) {
        val data = byteArrayOf((payload.size shr 8).toByte(), payload.size.toByte()) + payload
        for (chunk in data.toList().chunked(20)) {
            val characteristic = tx ?: error("차량 연결이 없습니다")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = chunk.toByteArray()
            check(gatt?.writeCharacteristic(characteristic) == true) { "차량 전송 실패" }
            check(withTimeout(5_000) { writes.receive() } == BluetoothGatt.GATT_SUCCESS) { "차량 전송 거절" }
        }
    }
    fun close() {
        runCatching { scanCallback?.let { scanner?.stopScan(it) } }
        scanCallback = null
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }
    companion object {
        val SERVICE: UUID = UUID.fromString("00000211-b2d1-43f0-9b88-960cebf8b91e")
        val TX: UUID = UUID.fromString("00000212-b2d1-43f0-9b88-960cebf8b91e")
        val RX: UUID = UUID.fromString("00000213-b2d1-43f0-9b88-960cebf8b91e")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
