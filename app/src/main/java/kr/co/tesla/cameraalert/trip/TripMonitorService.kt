package kr.co.tesla.cameraalert.trip

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kr.co.tesla.cameraalert.MainActivity
import kr.co.tesla.cameraalert.TeslaAuth
import kr.co.tesla.cameraalert.monitor.CameraMonitorService

/** Foreground recorder: detects D/R -> P transitions without using the phone's GPS distance. */
class TripMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var parkedAt: Long? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "차계부 자동 기록", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putBoolean("trip_auto_enabled", false).putString("trip_status", "자동 기록 모드 꺼짐").apply()
            stopSelf(); return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_STICKY
        startForeground(ID, notification("차량 상태 확인 중…"))
        update("자동 기록 모드 켜짐 · 다음 운행 감지 대기")
        job?.cancel()
        job = scope.launch {
            val vin = getSharedPreferences("settings", MODE_PRIVATE).getString("vin", "").orEmpty()
            if (vin.length != 17 || !TeslaAuth.isSignedIn(this@TripMonitorService)) {
                update("Tesla 로그인과 차량 선택이 필요합니다"); stopSelf(); return@launch
            }
            while (isActive) {
                val monitoringEnabled = getSharedPreferences("settings", MODE_PRIVATE)
                    .getBoolean("auto_monitor_enabled", true)
                val tripLoggingEnabled = getSharedPreferences("settings", MODE_PRIVATE)
                    .getBoolean("trip_auto_enabled", false)
                if (!monitoringEnabled && !tripLoggingEnabled) {
                    stopSelf()
                    return@launch
                }
                runCatching { TeslaAuth.vehicleData(this@TripMonitorService, vin) }
                    .onSuccess { process(vin, it) }
                    .onFailure { update("차량 상태 대기 중 · 다음 확인 예정") }
                delay(if (monitoringEnabled) 15_000 else 60_000)
            }
        }
        return START_STICKY
    }

    private fun process(vin: String, data: TeslaAuth.VehicleData) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        // Camera monitoring uses this latest Fleet state to distinguish a brief BLE drop while
        // driving from a disconnect after the vehicle has been parked.
        prefs.edit().putString("vehicle_gear", data.gear.orEmpty()).apply()
        val driving = data.gear in setOf("D", "R")
        if (driving && prefs.getBoolean("auto_monitor_enabled", true)) {
            // Fleet D/R state can start monitoring even when BLE is delayed or unavailable.
            CameraMonitorService.startForDriving(this)
        }
        // Vehicle-state observation is also used for camera monitoring. Trip logging remains
        // opt-in, so do not create or update a trip while its switch is off.
        if (!prefs.getBoolean("trip_auto_enabled", false)) return
        val odo = data.odometerKm
        val battery = data.usableBatteryPercent ?: data.batteryPercent
        if (odo == null || battery == null) { update("주행거리 또는 배터리 데이터 대기 중"); return }
        val active = TripLedger.active(this)
        when {
            driving && active == null -> {
                TripLedger.begin(this, vin, odo, battery, data.model, data.trim, System.currentTimeMillis())
                parkedAt = null
                val capacity = TripLedger.batteryCapacity(vin, data.model, data.trim)
                update("운행 자동 기록 중 · ${"%.1f".format(odo)} km · ${capacity.source} ${capacity.kwh}kWh 기준")
            }
            driving -> {
                parkedAt = null
                update("운행 자동 기록 중 · ${"%.1f".format(odo)} km")
            }
            data.gear == "P" && active != null -> {
                val parked = parkedAt ?: System.currentTimeMillis().also { parkedAt = it }
                if (System.currentTimeMillis() - parked >= 90_000) {
                    val record = TripLedger.finish(this, odo, battery, System.currentTimeMillis())
                    parkedAt = null
                    record?.let {
                        GoogleSheetsSync.enqueue(this, it)
                        scope.launch {
                            val uploaded = GoogleSheetsSync.syncPending(this@TripMonitorService)
                            if (uploaded > 0) update("운행 저장 · Google Sheets ${uploaded}건 기록 완료")
                        }
                    }
                    update(record?.let { "운행 저장 · ${"%.1f".format(it.distanceKm)} km" } ?: "짧은 이동은 기록하지 않았습니다")
                } else update("주차 감지 · 운행 종료 확인 중")
            }
            else -> update(if (active == null) "다음 운행을 자동 감지합니다" else "운행 종료 상태 확인 중")
        }
    }

    private fun update(text: String) {
        getSharedPreferences("settings", MODE_PRIVATE).edit().putString("trip_status", text).apply()
        getSystemService(NotificationManager::class.java).notify(ID, notification(text))
    }
    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_menu_compass)
        .setContentTitle("Tesla 차계부 자동 기록")
        .setContentText(text).setOnlyAlertOnce(true).setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "기록 중지",
            PendingIntent.getService(this, 3, Intent(this, TripMonitorService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)).build()

    override fun onDestroy() { job?.cancel(); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object { const val STOP = "stop_trip_monitor"; private const val CHANNEL = "trip_monitor"; private const val ID = 1003 }
}
