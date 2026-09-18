package kr.co.tesla.cameraalert.monitor

import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.location.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kr.co.tesla.cameraalert.MainActivity
import kr.co.tesla.cameraalert.TeslaAuth
import kr.co.tesla.cameraalert.data.CameraRepository
import kr.co.tesla.cameraalert.kakao.KakaoSafetyMonitor
import kr.co.tesla.cameraalert.model.*
import kr.co.tesla.cameraalert.voice.AlertSpeaker
import kr.co.tesla.cameraalert.voice.AppSoundPlayer
import kr.co.tesla.cameraalert.voice.GeminiTts
import kr.co.tesla.cameraalert.trip.GoogleSheetsSync
import kr.co.tesla.cameraalert.trip.TripLedger
import kotlinx.coroutines.*

class CameraMonitorService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val location by lazy { getSystemService(LocationManager::class.java) }
    private var task: Job? = null
    private var gpsWatchdog: Job? = null
    private var cameras = emptyList<SpeedCamera>()
    private var gpsActive = false
    private var lastFix = 0L
    private var lastOverspeedToneAt = 0L
    private var overspeedCameraId: String? = null
    private lateinit var speaker: AlertSpeaker
    private lateinit var geminiSpeaker: GeminiTts
    private lateinit var sounds: AppSoundPlayer
    private val alerts = CameraAlertGate()
    private var activeCameraId: String? = null
    private var dismissedCameraId: String? = null
    private var dismissedSpeedCamera: DismissedSpeedCamera? = null
    private var activeSpeedCamera: ActiveSpeedCamera? = null
    private var departedSpeedCamera: DepartedSpeedCamera? = null
    private var kakao: KakaoSafetyMonitor? = null
    private var kakaoTask: Job? = null
    private var tripMovingSince: Long? = null
    private var tripStationarySince: Long? = null
    private var tripRequestActive = false
    private var tripLastStartCheckAt = 0L
    private var tripRequestToken = 0L
    private var tripAutoEnabledLast = false
    private var tripReadyLast = false
    private var tripObservedVin = ""

    private data class ActiveSpeedCamera(
        val id: String,
        val latitude: Double,
        val longitude: Double,
        val limitKph: Int?,
        var closestDistanceMeters: Double = Double.POSITIVE_INFINITY,
        var previousDistanceMeters: Double = Double.POSITIVE_INFINITY,
        var movingAwaySamples: Int = 0
    )

    /** A camera that briefly became farther away; a broad curve can legitimately do this. */
    private data class DepartedSpeedCamera(
        val id: String,
        val latitude: Double,
        val longitude: Double,
        var farthestDistanceMeters: Double
    )

    /** A passed/dismissed camera may be armed again only on a later, real approach. */
    private data class DismissedSpeedCamera(
        val id: String,
        val latitude: Double,
        val longitude: Double
    )

    override fun onCreate() {
        super.onCreate()
        running = true
        speaker = AlertSpeaker(this)
        geminiSpeaker = GeminiTts(this)
        sounds = AppSoundPlayer(this)
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(
            CHANNEL, "카메라 감시 상태", NotificationManager.IMPORTANCE_LOW))
        CameraAlertNotification.ensureChannel(this)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            prefs.edit().putString("status", "감시를 중지했습니다.").apply()
            stopSelf(); return START_NOT_STICKY
        }
        if (intent?.action == CameraAlertNotification.DISMISS_ACTION) {
            dismissedCameraId = activeCameraId
            activeSpeedCamera?.takeIf { it.id == activeCameraId }?.let {
                dismissedSpeedCamera = DismissedSpeedCamera(it.id, it.latitude, it.longitude)
            }
            CameraAlertOverlay.hide()
            CameraAlertNotification.cancel(this)
            if (task == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            status("카메라 감시 중 · 다음 카메라 안내 대기")
            return START_NOT_STICKY
        }
        startForeground(1001, notification("차량 연결 준비 중…"))
        val previous = task
        previous?.cancel()
        task = scope.launch {
            previous?.join()
            try {
                cameras = withContext(Dispatchers.IO) {
                    runCatching { CameraRepository(this@CameraMonitorService).load() }.getOrDefault(emptyList())
                }
                // Monitoring is deliberately caller-controlled. It only uses phone GPS and
                // never scans, connects to, or registers a Tesla vehicle.
                startGps()
                awaitCancellation()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status(e.message ?: "감시 시작 실패"); stopSelf() }
        }
        return START_NOT_STICKY
    }
    @SuppressLint("MissingPermission")
    private fun startGps() {
        check(location.isProviderEnabled(LocationManager.GPS_PROVIDER)) { "휴대폰 위치(GPS)를 켜 주세요" }
        if (!gpsActive) {
            location.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            gpsActive = true
            monitoringActive = true
            startKakao()
            gpsWatchdog = scope.launch {
                while (gpsActive && isActive) {
                    delay(5_000)
                    if (SystemClock.elapsedRealtime() - lastFix > 10_000) status("휴대폰 GPS 감시 중 · 정확한 위치 대기 중")
                }
            }
        }
        status("휴대폰 GPS 감시 중 · 위치 신호 대기 중")
    }
    private fun stopGps() {
        gpsActive = false
        monitoringActive = false
        gpsWatchdog?.cancel(); gpsWatchdog = null
        kakaoTask?.cancel(); kakaoTask = null
        kakao?.stop(); kakao = null
        runCatching { location.removeUpdates(this) }
    }
    private fun startKakao() {
        kakaoTask?.cancel()
        kakaoTask = scope.launch {
            while (isActive && gpsActive) {
                val monitor = KakaoSafetyMonitor()
                kakao = monitor
                try {
                    prefs.edit().putString("kakao_status", "카카오 안전 운행 연결 중…").apply()
                    monitor.start(application)
                    prefs.edit().putString("kakao_status", "카카오 인증 완료 · 주행 정보 대기").apply()
                    // A stalled feed falls back immediately, and its SDK session is retried after a minute.
                    var staleSince = SystemClock.elapsedRealtime()
                    while (isActive && gpsActive) {
                        delay(5_000)
                        if (monitor.healthy(isOnline())) staleSince = SystemClock.elapsedRealtime()
                        if (SystemClock.elapsedRealtime() - staleSince > 60_000) error("카카오 안내 응답 대기 시간 초과")
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    prefs.edit().putString("kakao_status", e.message ?: "카카오 연결 실패").apply()
                } catch (e: LinkageError) {
                    prefs.edit().putString("kakao_status", "이 기기에서 카카오 SDK를 불러올 수 없습니다").apply()
                } finally { monitor.stop(); if (kakao === monitor) kakao = null }
                delay(30_000)
            }
        }
    }
    private fun isOnline(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    override fun onLocationChanged(fix: Location) {
        if (!gpsActive) return
        val ageMs = (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000
        if (ageMs !in 0..5_000) {
            status("휴대폰 GPS 감시 중 · 위치 신호 갱신 대기"); return
        }
        if (!fix.hasAccuracy() || fix.accuracy > 40f) {
            status("휴대폰 GPS 감시 중 · 위치 정확도 확인 중"); return
        }
        observeTripGps(fix)
        if (!fix.hasSpeed() || fix.speed < 2f) {
            status("차량 정차 중 · 카메라 감시 준비됨"); return
        }
        if (!fix.hasBearing()) {
            status("주행 감지됨 · GPS 방향 확인 중"); return
        }
        lastFix = SystemClock.elapsedRealtime()
        val position = VehiclePosition(fix.latitude, fix.longitude, fix.bearing.toDouble(),
            fix.speed * 3.6, fix.time)
        val now = SystemClock.elapsedRealtime()
        alerts.observeHeading(position.heading, now)
        updateActiveSpeedCamera(position)
        val healthy = kakao?.healthy(isOnline()) == true
        val enabledTypes = SafetyAlertSettings.enabledTypes(this)
        val event = HybridAlerts.select(healthy,
            if (healthy) kakao?.nearest(position, enabledTypes) else null,
            if (!healthy && SafetyAlertType.SPEED_CAMERA in enabledTypes) CameraDetector.nearestAhead(position, cameras) else null)
        val source = if (healthy) "카카오" else if (cameras.isNotEmpty()) "공공데이터 보조" else "안내 불가 · 공공데이터 없음"
        prefs.edit().putString("camera_source", source).apply()
        if (event == null) {
            overspeedCameraId = null
            if (activeSpeedCamera == null) CameraAlertOverlay.hide()
            status("${position.speedKph.toInt()}km/h · $source"); return
        }
        val match = event.match
        val departed = departedSpeedCamera
        if (match.type == SafetyAlertType.SPEED_CAMERA && departed != null &&
            (departed.id == match.id || CameraDetector.distanceMeters(
                match.latitude, match.longitude, departed.latitude, departed.longitude) < 60)) {
            val distance = CameraDetector.distanceMeters(
                position.latitude, position.longitude, departed.latitude, departed.longitude)
            if (distance > departed.farthestDistanceMeters) {
                departed.farthestDistanceMeters = distance
                overspeedCameraId = null
                status("${position.speedKph.toInt()}km/h · $source · 경로 복귀 확인 중")
                return
            }
            if (distance > departed.farthestDistanceMeters - REAPPROACH_DISTANCE_METERS) {
                overspeedCameraId = null
                status("${position.speedKph.toInt()}km/h · $source · 경로 복귀 확인 중")
                return
            }
            departedSpeedCamera = null
            alerts.reapproachSpeedCamera(match.id, match.latitude, match.longitude, now)
        }
        // The event feed may continue returning the old camera after a turn.  A dismissed or
        // route-departed camera must not keep producing the repeated overspeed tone.
        if (match.type == SafetyAlertType.SPEED_CAMERA) {
            val dismissed = dismissedSpeedCamera
            val sameDismissedCamera = dismissed != null && (dismissed.id == match.id ||
                CameraDetector.distanceMeters(match.latitude, match.longitude, dismissed.latitude, dismissed.longitude) < 60)
            if (sameDismissedCamera) {
                val distance = CameraDetector.distanceMeters(position.latitude, position.longitude,
                    dismissed.latitude, dismissed.longitude)
                val returningFromFront = distance >= DISMISSED_CAMERA_REARM_DISTANCE_METERS &&
                    CameraDetector.isAhead(position, dismissed.latitude, dismissed.longitude)
                if (returningFromFront) {
                    dismissedCameraId = null
                    dismissedSpeedCamera = null
                    alerts.reapproachSpeedCamera(dismissed.id, dismissed.latitude, dismissed.longitude, now)
                } else {
                    overspeedCameraId = null
                    status("${position.speedKph.toInt()}km/h · $source · 다음 카메라 안내 대기")
                    return
                }
            } else if (dismissedCameraId == match.id) {
                // Keep the old behavior for a non-speed-camera manual dismissal, which has no
                // coordinate to safely distinguish an old feed from a future approach.
                overspeedCameraId = null
                status("${position.speedKph.toInt()}km/h · $source · 다음 카메라 안내 대기")
                return
            }
        }
        val detail = buildString {
            if (match.type != SafetyAlertType.BUS_LANE) append("${match.distanceMeters.toInt()}m 앞 · ")
            if (match.type == SafetyAlertType.SPEED_CAMERA) append("${match.limitKph ?: "--"}km/h · ")
            append("${match.type.label} · ${match.roadName} · ${event.source.label}")
        }
        val overspeed = match.type == SafetyAlertType.SPEED_CAMERA && match.limitKph != null &&
            position.speedKph > match.limitKph && SafetyAlertSettings.isOverspeedToneEnabled(this)
        if (overspeed) {
            if (overspeedCameraId != match.id || now - lastOverspeedToneAt >= 1_200) {
                overspeedCameraId = match.id
                lastOverspeedToneAt = now
                playOverspeedTone()
            }
        } else if (match.type == SafetyAlertType.SPEED_CAMERA) {
            overspeedCameraId = null
        }
        val announcedDistance = alerts.alertDistanceMeters(
            event, position.heading, now,
            SafetyAlertSettings.speedCameraFirstAlertDistance(this)
        )
        if (announcedDistance != null) {
            activeCameraId = match.id
            dismissedCameraId = null
            dismissedSpeedCamera = null
            val announcedDetail = detail.replaceFirst("${match.distanceMeters.toInt()}m", "${announcedDistance}m")
            status(announcedDetail)
            // Speed-camera/overspeed guidance is delivered by voice, the ongoing monitor
            // notification, and the optional floating badge. Do not create a separate
            // heads-up notification for it.
            if (match.type == SafetyAlertType.SPEED_CAMERA) {
                CameraAlertNotification.cancel(this)
            } else {
                CameraAlertNotification.show(this, announcedDetail)
            }
            // The floating card is intentionally reserved for enforcement cameras: it needs a
            // speed limit to be useful and should not cover navigation for other safety notices.
            if (match.type == SafetyAlertType.SPEED_CAMERA) {
                activeSpeedCamera = ActiveSpeedCamera(
                    match.id, match.latitude, match.longitude, match.limitKph,
                    closestDistanceMeters = match.distanceMeters,
                    previousDistanceMeters = match.distanceMeters
                )
                if (match.limitKph != null && prefs.getBoolean("floating_alert_enabled", false))
                    CameraAlertOverlay.show(this, match.distanceMeters.toInt(), match.limitKph, keepVisible = true)
            }
            scope.launch {
                val geminiSpoken = runCatching {
                    geminiSpeaker.speakSafetyWarning(match.type, announcedDistance, match.limitKph)
                }.getOrDefault(false)
                val systemSpoken = if (geminiSpoken) true else runCatching {
                    speaker.speakSafetyWarning(match.type, announcedDistance, match.limitKph)
                }.getOrDefault(false)
                if (!systemSpoken) playFallbackTone()
            }
        } else if (dismissedCameraId == match.id) {
            status("${position.speedKph.toInt()}km/h · $source · 다음 카메라 안내 대기")
        } else {
            status(detail)
        }
    }
    /**
     * Uses the already-running monitor GPS as a low-cost trip trigger. Tesla is contacted only
     * after sustained movement and after a sustained stop; it is never polled while moving.
     */
    private fun observeTripGps(fix: Location) {
        val automaticEnabled = prefs.getBoolean("trip_auto_enabled", false)
        if (!automaticEnabled) {
            if (tripAutoEnabledLast) invalidateTripRequest()
            tripAutoEnabledLast = false
            tripReadyLast = false
            tripMovingSince = null; tripStationarySince = null
            return
        }
        tripAutoEnabledLast = true
        val vin = prefs.getString("vin", "").orEmpty()
        if (vin.length != 17 || !TeslaAuth.isSignedIn(this)) {
            if (tripReadyLast) invalidateTripRequest()
            tripReadyLast = false
            tripStatus("자동 기록 대기 · Tesla 로그인과 차량 선택이 필요합니다")
            return
        }
        tripReadyLast = true
        if (tripObservedVin != vin) {
            if (tripObservedVin.isNotEmpty()) invalidateTripRequest()
            tripObservedVin = vin
            tripMovingSince = null
            tripStationarySince = null
        }
        val now = SystemClock.elapsedRealtime()
        val speedKph = if (fix.hasSpeed()) fix.speed * 3.6 else 0.0
        val activeTrip = TripLedger.active(this)
        if (activeTrip != null && activeTrip.optString("vin") != vin) {
            tripMovingSince = null
            tripStationarySince = null
            tripStatus("자동 기록 보류 · 진행 중 기록의 차량과 선택한 차량이 다릅니다")
            return
        }
        if (activeTrip == null) {
            tripStationarySince = null
            if (speedKph >= TRIP_START_SPEED_KPH) {
                val movingSince = tripMovingSince ?: now.also { tripMovingSince = it }
                if (now - movingSince >= TRIP_START_CONFIRM_MS &&
                    now - tripLastStartCheckAt >= TRIP_RECHECK_MS && !tripRequestActive) {
                    tripLastStartCheckAt = now
                    requestTripSnapshot(vin, checkingStop = false)
                }
            } else {
                if (tripMovingSince != null) invalidateTripRequest()
                tripMovingSince = null
            }
            return
        }
        tripMovingSince = null
        if (speedKph > TRIP_STOP_SPEED_KPH) {
            if (tripStationarySince != null) invalidateTripRequest()
            tripStationarySince = null
            return
        }
        val stationarySince = tripStationarySince ?: now.also { tripStationarySince = it }
        if (now - stationarySince >= TRIP_STOP_CONFIRM_MS && !tripRequestActive) {
            // Reset before the request so a D/R response (or a transient failure) waits another
            // five stationary minutes rather than requesting again on every GPS sample.
            tripStationarySince = now
            requestTripSnapshot(vin, checkingStop = true)
        }
    }

    private fun requestTripSnapshot(vin: String, checkingStop: Boolean) {
        val requestToken = ++tripRequestToken
        tripRequestActive = true
        tripStatus(if (checkingStop) "5분 정지 감지 · 차량 P 상태 확인 중" else "이동 감지 · 차량 주행 상태 확인 중")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { TeslaAuth.vehicleData(this@CameraMonitorService, vin, requireFresh = true) }
            }
            tripRequestActive = false
            if (requestToken != tripRequestToken || !prefs.getBoolean("trip_auto_enabled", false) ||
                prefs.getString("vin", "").orEmpty() != vin || !TeslaAuth.isSignedIn(this@CameraMonitorService)) return@launch
            val activeTrip = TripLedger.active(this@CameraMonitorService)
            if ((checkingStop && (activeTrip == null || activeTrip.optString("vin") != vin)) ||
                (!checkingStop && activeTrip != null)) return@launch
            val data = result.getOrElse {
                tripStatus("차량 상태 확인 실패 · 다음 GPS 조건에서 다시 확인합니다")
                return@launch
            }
            val odometer = data.odometerKm
            val battery = data.usableBatteryPercent ?: data.batteryPercent
            if (odometer == null || battery == null) {
                tripStatus("주행거리 또는 배터리 데이터 대기 중")
                return@launch
            }
            val driving = data.gear in setOf("D", "R")
            if (!checkingStop) {
                if (driving && TripLedger.active(this@CameraMonitorService) == null) {
                    TripLedger.begin(this@CameraMonitorService, vin, odometer, battery, data.model, data.trim,
                        System.currentTimeMillis())
                    tripStatus("운행 자동 기록 중 · Tesla 시작 상태 확인 완료")
                } else {
                    tripStatus("이동은 감지됐지만 차량이 ${data.gear ?: "P"} 상태입니다 · 다음 이동 때 다시 확인")
                }
                return@launch
            }
            if (data.gear == "P") {
                val record = TripLedger.finish(this@CameraMonitorService, odometer, battery, System.currentTimeMillis())
                if (record == null) {
                    if (TripLedger.active(this@CameraMonitorService) != null) {
                        tripStationarySince = SystemClock.elapsedRealtime()
                        tripStatus("주차 확인 · 주행거리 갱신 대기 중 · 5분 뒤 다시 확인")
                    } else {
                        tripStationarySince = null
                        tripStatus("주차 확인 · 기록할 이동 거리가 없습니다")
                    }
                } else {
                    tripStationarySince = null
                    tripStatus("운행 저장 · ${"%.1f".format(record.distanceKm)} km")
                    scope.launch(Dispatchers.IO) {
                        GoogleSheetsSync.enqueue(this@CameraMonitorService, record)
                        val uploaded = GoogleSheetsSync.syncPending(this@CameraMonitorService)
                        if (uploaded > 0) withContext(Dispatchers.Main) {
                            tripStatus("운행 저장 · Google Sheets ${uploaded}건 기록 완료")
                        }
                    }
                }
            } else {
                tripStatus("정차 중이나 차량이 ${data.gear ?: "알 수 없음"} 상태입니다 · 5분 뒤 다시 확인")
            }
        }
    }

    /** A response that was requested under an old GPS, VIN, or enabled-state is never applied. */
    private fun invalidateTripRequest() {
        tripRequestToken++
    }

    private fun tripStatus(text: String) {
        if (prefs.getString("trip_status", "") != text) prefs.edit().putString("trip_status", text).apply()
    }

    private fun playFallbackTone() = sounds.playFallbackWarning()
    private fun playOverspeedTone() = sounds.playOverspeed(SafetyAlertSettings.overspeedToneStyle(this))
    /**
     * Clears a camera after it has been passed, or when a turn takes the car away before it is
     * reached. The latter needs consecutive GPS samples to avoid dismissing the badge from normal
     * GPS drift while approaching the camera.
     */
    private fun updateActiveSpeedCamera(position: VehiclePosition) {
        val camera = activeSpeedCamera ?: return
        val distance = CameraDetector.distanceMeters(position.latitude, position.longitude, camera.latitude, camera.longitude)
        if (distance < camera.closestDistanceMeters) {
            camera.closestDistanceMeters = distance
        }
        // GPS arrives every second. At urban turning speeds the distance often grows only a
        // few metres per sample, so requiring a 12 m jump in *each* sample never fired.
        if (distance > camera.previousDistanceMeters + MOVING_AWAY_SAMPLE_DELTA_METERS) {
            camera.movingAwaySamples++
        } else if (distance < camera.previousDistanceMeters - MOVING_AWAY_SAMPLE_DELTA_METERS) {
            camera.movingAwaySamples = 0
        }
        camera.previousDistanceMeters = distance

        val passedCamera = camera.closestDistanceMeters <= PASS_CONFIRMATION_RADIUS_METERS &&
            distance >= camera.closestDistanceMeters + PASSING_AWAY_DELTA_METERS
        val turnedAwayBeforeCamera = camera.closestDistanceMeters > PASS_CONFIRMATION_RADIUS_METERS &&
            camera.movingAwaySamples >= TURN_AWAY_CONFIRMATION_SAMPLES &&
            distance >= camera.closestDistanceMeters + TURN_AWAY_DISTANCE_METERS
        if (passedCamera || turnedAwayBeforeCamera) {
            activeSpeedCamera = null
            if (activeCameraId == camera.id) activeCameraId = null
            CameraAlertOverlay.hide()
            CameraAlertNotification.cancel(this)
            if (passedCamera) {
                alerts.forgetSpeedCamera(camera.id, camera.latitude, camera.longitude)
                departedSpeedCamera = null
                dismissedCameraId = camera.id
                dismissedSpeedCamera = DismissedSpeedCamera(camera.id, camera.latitude, camera.longitude)
                playCameraPassedTone()
                status("과속카메라를 통과했습니다")
            } else {
                // A broad R-curve can increase the straight-line GPS distance before it
                // points back at the same camera.  Suppress it only while it keeps getting
                // farther away; re-arm when the distance has meaningfully decreased.
                departedSpeedCamera = DepartedSpeedCamera(
                    camera.id, camera.latitude, camera.longitude, distance)
                status("경로가 변경되어 과속카메라 안내를 종료했습니다")
            }
        }
        if (activeSpeedCamera != null && dismissedCameraId != camera.id && camera.limitKph != null &&
            prefs.getBoolean("floating_alert_enabled", false)) {
            CameraAlertOverlay.show(this, distance.toInt(), camera.limitKph, keepVisible = true)
        }
    }
    private fun playCameraPassedTone() = sounds.playCameraPassed()
    override fun onProviderDisabled(provider: String) { if (gpsActive) status("GPS가 꺼졌습니다 · 위치를 켜 주세요") }
    override fun onProviderEnabled(provider: String) {}
    @Deprecated("Legacy callback")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    private fun status(text: String) {
        prefs.edit().putString("status", text).apply()
        getSystemService(NotificationManager::class.java).notify(1001, notification(text))
    }
    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Tesla 카메라 알림")
        .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setOngoing(true).setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "감시 종료", PendingIntent.getService(this, 1, Intent(this, CameraMonitorService::class.java)
            .setAction(STOP), PendingIntent.FLAG_IMMUTABLE)).build()
    override fun onDestroy() {
        running = false
        monitoringActive = false
        task?.cancel(); scope.cancel(); stopGps(); sounds.release(); speaker.shutdown(); geminiSpeaker.shutdown()
        CameraAlertNotification.cancel(this)
        CameraAlertOverlay.hide()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val CHANNEL = "local_camera_monitor"
        const val STOP = "stop_monitor"
        private const val PASS_CONFIRMATION_RADIUS_METERS = 60.0
        private const val PASSING_AWAY_DELTA_METERS = 15.0
        // A small per-fix delta is intentional: at low-speed turns GPS positions are close
        // together, while the 20 m total-distance requirement filters normal GPS drift.
        private const val MOVING_AWAY_SAMPLE_DELTA_METERS = 2.0
        private const val TURN_AWAY_CONFIRMATION_SAMPLES = 2
        private const val TURN_AWAY_DISTANCE_METERS = 20.0
        private const val REAPPROACH_DISTANCE_METERS = 20.0
        private const val DISMISSED_CAMERA_REARM_DISTANCE_METERS = 150.0
        private const val TRIP_START_SPEED_KPH = 10.0
        private const val TRIP_STOP_SPEED_KPH = 2.0
        private const val TRIP_START_CONFIRM_MS = 30_000L
        private const val TRIP_STOP_CONFIRM_MS = 5 * 60_000L
        private const val TRIP_RECHECK_MS = 5 * 60_000L
        @Volatile private var running = false
        @Volatile private var monitoringActive = false
        fun isRunning(): Boolean = running
        fun isMonitoringActive(): Boolean = monitoringActive

        /** Call from the Android Bluetooth-connected routine. */
        fun start(context: android.content.Context) {
            if (!isRunning()) ContextCompat.startForegroundService(context, Intent(context, CameraMonitorService::class.java))
        }

        /** Call from the Android Bluetooth-disconnected routine. */
        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, CameraMonitorService::class.java))
        }
    }
}

