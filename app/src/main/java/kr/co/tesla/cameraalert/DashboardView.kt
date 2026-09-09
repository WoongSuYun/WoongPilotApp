package kr.co.tesla.cameraalert

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import kr.co.tesla.cameraalert.trip.TripLedger
import kr.co.tesla.cameraalert.trip.TripMonitorService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Native dashboard: decorative driving graphic, real service status, and setup controls. */
class DashboardView(context: Context, savedVin: String, savedTeslaName: String, paired: Boolean,
    onPair: () -> Unit, onStart: () -> Unit, onStop: () -> Unit,
    onCheckKakao: () -> Unit, onTeslaLogin: () -> Unit, onTeslaLogout: () -> Unit,
    onWakeTesla: () -> Unit, onRefreshTesla: () -> Unit, onVoiceSettings: () -> Unit,
    onGeminiSettings: () -> Unit, onGeminiAudioLibrary: () -> Unit, onSafetyAlertSettings: () -> Unit,
    onPreviewCameraAlert: () -> Unit, onMonitoringSettings: () -> Unit, onCameraList: () -> Unit, onClearPairing: () -> Unit,
    private val onExportTrips: () -> Unit, private val onImportTrips: () -> Unit,
    private val onConfigureSheets: () -> Unit, private val onAppendSampleTrip: () -> Unit
) : ScrollView(context) {
    private val ink = Color.rgb(240, 244, 248)
    private val muted = Color.rgb(149, 164, 180)
    private val accent = Color.rgb(114, 235, 198)
    private val surface = Color.rgb(24, 33, 45)
    private val border = Color.rgb(43, 56, 70)
    val vin = EditText(context)
    val status = TextView(context)
    val kakaoStatus = TextView(context)
    private val teslaBattery = TextView(context)
    private val teslaTitle = TextView(context)
    private val teslaRange = TextView(context)
    private val teslaMeta = TextView(context)
    private val teslaDrive = TextView(context)
    private val teslaClimate = TextView(context)
    private val teslaGear = vehicleStat()
    private val teslaSpeed = vehicleStat()
    private val teslaVehicleState = vehicleStat()
    private val teslaLock = vehicleStat()
    private val teslaOutsideTemp = vehicleStat()
    private val teslaInsideTemp = vehicleStat()
    private val teslaDoors = vehicleStat()
    private val teslaSentry = vehicleStat()
    private val teslaStatus = TextView(context)
    private lateinit var teslaLoginAction: androidx.appcompat.widget.AppCompatButton
    private lateinit var teslaLogoutAction: androidx.appcompat.widget.AppCompatButton
    private val keyBadge = TextView(context)
    private lateinit var headerMessage: TextView
    private val pairingSteps = TextView(context)
    private val body = LinearLayout(context)
    private val monitorPage = column()
    private val vehiclePage = column()
    private val guidePage = column()
    private val tripPage = column()
    private val tabButtons = mutableMapOf<String, View>()
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Int = 22, outline: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (outline) setStroke(dp(1), border)
    }
    private fun text(value: String, size: Float = 14f, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun vehicleStat(): TextView = TextView(context).apply {
        textSize = 13f; setTextColor(ink); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL; minLines = 2; maxLines = 2
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = shape(Color.rgb(18, 48, 58), 12)
    }
    private fun vehicleStatRow(first: TextView, second: TextView): LinearLayout = row().apply {
        addView(first, LinearLayout.LayoutParams(0, -2, 1f))
        addView(second, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
    }
    private fun LinearLayout.space(height: Int) { addView(View(context), LinearLayout.LayoutParams(1, dp(height))) }
    private fun card(page: LinearLayout): LinearLayout = column().apply {
        background = shape(surface, outline = true); setPadding(dp(20), dp(20), dp(20), dp(20))
        page.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
    }
    private fun tripMetric(label: String, value: String) = column().apply {
        background = shape(Color.rgb(18, 48, 58), 14); setPadding(dp(12), dp(10), dp(12), dp(10))
        addView(text(label, 11f, muted, true))
        addView(text(value, 22f, ink, true))
    }
    private fun tripDuration(startedAt: Long, endedAt: Long): String {
        return durationText(((endedAt - startedAt) / 60_000).coerceAtLeast(0))
    }
    private fun durationText(minutes: Long): String {
        return if (minutes >= 60) "${minutes / 60}시간 ${minutes % 60}분" else "${minutes}분"
    }
    private fun tripBattery(trip: kr.co.tesla.cameraalert.trip.TripRecord): String = when {
        trip.batteryStartPercent != null && trip.batteryEndPercent != null ->
            "배터리 ${trip.batteryStartPercent}% → ${trip.batteryEndPercent}% · ${trip.batteryUsedPercent ?: 0}% 사용"
        trip.batteryUsedPercent != null -> "배터리 ${trip.batteryUsedPercent}% 사용"
        else -> "배터리 변화 없음"
    }
    private fun showTripCardPreview() {
        val preview = column().apply {
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(text("9. 12 오후 6:40", 13f, accent, true))
            addView(text("오후 6:40 → 오후 7:22", 12f, muted))
            space(12)
            val first = row()
            first.addView(tripMetric("거리", "18.4 km"), LinearLayout.LayoutParams(0, -2, 1f))
            first.addView(tripMetric("시간", "42분"), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            addView(first)
            space(8)
            val second = row()
            second.addView(tripMetric("에너지", "3.8 kWh"), LinearLayout.LayoutParams(0, -2, 1f))
            second.addView(tripMetric("전비", "4.8 km/kWh"), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            addView(second)
            space(10)
            addView(text("배터리 80% → 75% · 5% 사용", 14f, muted, true))
        }
        AlertDialog.Builder(context).setTitle("주행 기록 표시 예").setView(preview)
            .setPositiveButton("확인", null).show()
    }
    private fun showTab(name: String) {
        monitorPage.visibility = if (name == "monitor") View.VISIBLE else View.GONE
        vehiclePage.visibility = if (name == "vehicle") View.VISIBLE else View.GONE
        guidePage.visibility = if (name == "guide") View.VISIBLE else View.GONE
        tripPage.visibility = if (name == "trip") View.VISIBLE else View.GONE
        tabButtons.forEach { (key, view) -> view.alpha = if (key == name) 1f else .55f }
        if (name == "trip") renderTripLog()
    }
    private fun action(label: String, primary: Boolean = false, click: () -> Unit) = androidx.appcompat.widget.AppCompatButton(context).apply {
        text = label; textSize = 15f; isAllCaps = false; gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) Color.rgb(13, 33, 29) else ink)
        backgroundTintList = null
        background = RippleDrawable(ColorStateList.valueOf(0x226FFFFF),
            shape(if (primary) accent else Color.rgb(34, 46, 61), 15), null)
        minHeight = dp(54); minimumHeight = dp(54)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnClickListener { click() }
    }
    private fun heading(parent: LinearLayout, number: String, title: String, subtitle: String) {
        val line = row()
        line.addView(text(number, 13f, accent, true).apply {
            gravity = Gravity.CENTER; background = shape(Color.rgb(31, 58, 57), 12)
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        line.addView(column().apply {
            addView(text(title, 18f, ink, true)); addView(text(subtitle, 12f, muted))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        parent.addView(line)
    }
    private fun savedHeaderMessage(): String = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getString("header_message", "웅수♥하영♥크림").orEmpty().ifBlank { "웅수♥하영♥크림" }
    private fun showHeaderMessageSettings() {
        val input = EditText(context).apply {
            setText(savedHeaderMessage()); setSelection(text.length); hint = "예: 웅수♥하영♥크림"
            filters = arrayOf(InputFilter.LengthFilter(30)); isSingleLine = true
        }
        AlertDialog.Builder(context).setTitle("상단 문구 설정").setView(input)
            .setNeutralButton("기본값") { _, _ ->
                context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().remove("header_message").apply()
                headerMessage.text = savedHeaderMessage()
            }
            .setNegativeButton("취소", null)
            .setPositiveButton("저장") { _, _ ->
                val value = input.text.toString().trim().ifBlank { "웅수♥하영♥크림" }
                context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("header_message", value).apply()
                headerMessage.text = value
            }.show()
    }
    init {
        setBackgroundColor(Color.rgb(12, 18, 27)); isFillViewport = true
        isVerticalScrollBarEnabled = false
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(22), dp(24), dp(22), dp(28))
        addView(body, LayoutParams(-1, -2))
        val top = row()
        top.addView(column().apply {
            headerMessage = text(savedHeaderMessage(), 13f, accent, true).apply {
                letterSpacing = .04f; setOnClickListener { showHeaderMessageSettings() }
            }
            addView(headerMessage)
            space(5)
            addView(text("카메라 알림", 28f, ink, true))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(action("?", false) {
            AlertDialog.Builder(context).setTitle("처음 사용하는 방법")
                .setMessage("1. 차 안에서 VIN을 입력하고 키 등록을 요청하세요. 실물 카드키를 차량 콘솔에 대고 차량 화면에서 승인합니다.\n\n2. 감시 시작을 누르세요. 공공 카메라 데이터는 앱이 자동으로 갱신합니다.\n\n3. 위치·속도는 휴대폰 GPS를 사용하며 차량 연결이 끊기면 안내를 멈춥니다.\n\n앱 강제 종료나 재부팅 후에는 감시를 다시 시작하세요. 키 삭제는 차량의 잠금 설정에서 할 수 있습니다.")
                .setPositiveButton("확인", null).show()
        }.apply { contentDescription = "앱 사용 방법" }, LinearLayout.LayoutParams(dp(48), dp(48)))
        top.addView(action("✎", false) { showHeaderMessageSettings() }.apply {
            contentDescription = "상단 문구 설정"
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) })
        top.addView(action("⚙", false, onVoiceSettings).apply {
            contentDescription = "음성 안내 설정"
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) })
        body.addView(top); body.space(16)
        val tabs = row()
        fun tab(id: String, label: String) = action(label, false) { showTab(id) }.also {
            tabButtons[id] = it
            tabs.addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        tab("monitor", "감시")
        tab("vehicle", "차량")
        tab("guide", "안내·설정")
        tab("trip", "차계부")
        body.addView(tabs); body.space(16)
        body.addView(monitorPage, LinearLayout.LayoutParams(-1, -2))
        body.addView(vehiclePage, LinearLayout.LayoutParams(-1, -2))
        body.addView(guidePage, LinearLayout.LayoutParams(-1, -2))
        body.addView(tripPage, LinearLayout.LayoutParams(-1, -2))
        showTab("monitor")

        card(vehiclePage).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(25, 70, 72), Color.rgb(20, 31, 48))).apply {
                cornerRadius = dp(22).toFloat(); setStroke(dp(1), Color.rgb(65, 137, 128))
            }
            val header = row()
            teslaTitle.text = savedTeslaName.ifBlank { "내 테슬라" }
            teslaTitle.textSize = 13f; teslaTitle.setTextColor(accent)
            teslaTitle.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            teslaTitle.isSingleLine = true; teslaTitle.ellipsize = android.text.TextUtils.TruncateAt.END
            header.addView(teslaTitle, LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(text("↻ 새로고침", 12f, accent, true).apply {
                setPadding(dp(10), dp(8), dp(10), dp(8)); background = shape(Color.rgb(31, 86, 82), 10)
                setOnClickListener { onRefreshTesla() }
            })
            addView(header); space(12)
            val summary = row()
            teslaBattery.textSize = 42f; teslaBattery.setTextColor(ink); teslaBattery.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            teslaBattery.text = "--%"
            summary.addView(teslaBattery, LinearLayout.LayoutParams(0, -2, 1f))
            summary.addView(column().apply {
                teslaRange.textSize = 17f; teslaRange.setTextColor(ink); teslaRange.text = "차량 정보 대기"
                teslaMeta.textSize = 12f; teslaMeta.setTextColor(muted); teslaMeta.setPadding(0, dp(4), 0, 0)
                addView(teslaRange); addView(teslaMeta)
            }, LinearLayout.LayoutParams(0, -2, 1.35f))
            addView(summary); space(10)
            teslaDrive.textSize = 14f; teslaDrive.setTextColor(ink); teslaDrive.text = "기어 · 속도 · 보안 상태 대기"
            teslaClimate.textSize = 12f; teslaClimate.setTextColor(muted); teslaClimate.setPadding(0, dp(5), 0, 0)
            teslaClimate.text = "실내/외 온도 · 차량 소프트웨어 정보 대기"
            addView(teslaDrive); addView(teslaClimate)
            teslaDrive.visibility = View.GONE; teslaClimate.visibility = View.GONE
            listOf(teslaGear, teslaSpeed, teslaVehicleState, teslaLock, teslaOutsideTemp, teslaInsideTemp, teslaDoors, teslaSentry)
                .forEach { it.apply { text = "상태\n확인 중"; background = shape(Color.rgb(18, 48, 58), 12) } }
            addView(vehicleStatRow(teslaGear, teslaSpeed)); space(7)
            addView(vehicleStatRow(teslaVehicleState, teslaLock)); space(7)
            addView(vehicleStatRow(teslaOutsideTemp, teslaInsideTemp)); space(7)
            addView(vehicleStatRow(teslaDoors, teslaSentry)); space(10)
            teslaStatus.textSize = 12f; teslaStatus.setTextColor(muted); teslaStatus.text = "Tesla 로그인 후 자동 갱신됩니다."
            addView(teslaStatus); space(12)
            teslaLoginAction = action("Tesla 계정 로그인", true, onTeslaLogin)
            addView(teslaLoginAction, LinearLayout.LayoutParams(-1, -2))
            space(8)
            teslaLogoutAction = action("Tesla 계정 연결 해제", false, onTeslaLogout).apply { visibility = View.GONE }
            addView(teslaLogoutAction, LinearLayout.LayoutParams(-1, -2))
            space(8)
            addView(action("차량 깨우고 정보 가져오기", false, onWakeTesla), LinearLayout.LayoutParams(-1, -2))
        }

        card(monitorPage).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(27, 47, 58), Color.rgb(20, 29, 41))).apply {
                cornerRadius = dp(22).toFloat(); setStroke(dp(1), border)
            }
            addView(text("주행에 집중하세요", 23f, ink, true))
            addView(text("카메라는 가까워지기 전에 알려드릴게요.", 13f, muted))
            addView(RoadIllustration(context), LinearLayout.LayoutParams(-1, dp(158)))
            val chips = row()
            listOf("전방 700m", "접근 경고음", "휴대폰 GPS").forEach { label ->
                chips.addView(text(label, 11f, muted).apply {
                    gravity = Gravity.CENTER; setPadding(dp(4), dp(8), dp(4), dp(8))
                    background = shape(Color.rgb(22, 34, 46), 10)
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            }
            addView(chips); space(18)
            addView(text("감시 상태", 11f, accent, true).apply { letterSpacing = .08f })
            status.textSize = 15f; status.setTextColor(ink); status.setLineSpacing(dp(4).toFloat(), 1f)
            status.setPadding(0, dp(6), 0, dp(16)); status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(status, LinearLayout.LayoutParams(-1, -2))
            val controls = row()
            controls.addView(action("▶  감시 시작", true, onStart), LinearLayout.LayoutParams(0, -2, 1f))
            controls.addView(action("중지", false, onStop), LinearLayout.LayoutParams(dp(76), -2).apply { marginStart = dp(10) })
            addView(controls)
            space(8)
            addView(action("카메라 알림 미리보기", false, onPreviewCameraAlert), LinearLayout.LayoutParams(-1, -2))
            space(8)
            addView(action("감시 모드 설정", false, onMonitoringSettings), LinearLayout.LayoutParams(-1, -2))
            space(8)
            addView(action("공공데이터 카메라 목록", false, onCameraList), LinearLayout.LayoutParams(-1, -2))
        }
        vehiclePage.space(5)
        vehiclePage.addView(text("차량 키 준비", 19f, ink, true)); vehiclePage.space(12)
        card(vehiclePage).apply {
            heading(this, "01", "차량 키 연결", "처음 한 번, 카드키로 승인")
            space(16)
            keyBadge.textSize = 12f; keyBadge.setPadding(dp(10), dp(6), dp(10), dp(6))
            addView(keyBadge, LinearLayout.LayoutParams(-2, -2))
            updatePairing(paired)
            pairingSteps.textSize = 13f; pairingSteps.setTextColor(muted)
            pairingSteps.setLineSpacing(dp(4).toFloat(), 1f)
            pairingSteps.setPadding(0, dp(12), 0, 0)
            addView(pairingSteps, LinearLayout.LayoutParams(-1, -2))
            updatePairingProgress(paired, "")
            space(14)
            addView(text("차대번호 · VIN", 12f, muted)); space(6)
            vin.apply {
                id = View.generateViewId()
                hint = "17자리 VIN 입력"; setText(savedVin); setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(17))
                textSize = 15f; setTextColor(ink); setHintTextColor(muted)
                background = shape(Color.rgb(13, 22, 32), 12, true)
                setPadding(dp(14), dp(16), dp(14), dp(16))
                contentDescription = "차대번호 VIN 17자리"
            }
            addView(vin, LinearLayout.LayoutParams(-1, -2)); space(12)
            addView(action("카드키로 등록하기", false) {
                AlertDialog.Builder(context).setTitle("카드키 등록 준비")
                    .setMessage("1. 차량 가까이에서 Bluetooth와 위치를 켜세요.\n\n2. 계속을 누르면 차량을 찾습니다.\n\n3. 앱에 ‘카드키를 차량 콘솔에’라는 안내가 나오면 실물 카드키를 콘솔 리더기에 대고 차량 화면에서 승인하세요.\n\n아래 단계 표시에서 진행 상태를 확인할 수 있습니다.")
                    .setNegativeButton("취소", null).setPositiveButton("계속") { _, _ -> onPair() }.show()
            }, LinearLayout.LayoutParams(-1, -2))
            space(8)
            addView(action("앱 키 등록 삭제 · 다시 등록", false, onClearPairing), LinearLayout.LayoutParams(-1, -2))
        }
        card(guidePage).apply {
            heading(this, "02", "카카오 안전 안내", "목적지 없이 · 과속카메라 안내")
            space(16)
            kakaoStatus.textSize = 14f; kakaoStatus.setTextColor(ink)
            kakaoStatus.setLineSpacing(dp(3).toFloat(), 1f)
            addView(kakaoStatus); space(14)
            addView(action("카카오 실시간 응답 테스트", false, onCheckKakao), LinearLayout.LayoutParams(-1, -2))
        }
        card(guidePage).apply {
            heading(this, "02", "안내와 음성", "안전 안내 · Gemini 음성 · 차계부")
            space(16)
            addView(action("Gemini AI 음성 설정", false, onGeminiSettings), LinearLayout.LayoutParams(-1, -2)); space(8)
            addView(action("Gemini 음성 보관함", false, onGeminiAudioLibrary), LinearLayout.LayoutParams(-1, -2)); space(8)
            addView(action("안전 안내 항목 설정", false, onSafetyAlertSettings), LinearLayout.LayoutParams(-1, -2))
        }
        guidePage.addView(text("차량 연결 없이도 휴대폰 GPS·카카오 안전 안내를 사용할 수 있어요.\n화면을 꺼도 실행 중인 감시는 계속됩니다.", 12f, muted).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
        })
    }
    private fun renderTripLog() {
        tripPage.removeAllViews()
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val vinValue = prefs.getString("vin", "").orEmpty()
        val active = TripLedger.active(context)
        card(tripPage).apply {
            addView(text(if (active == null) "다음 운행을 기다리고 있어요" else "운행을 자동 기록하고 있어요", 20f, ink, true))
            addView(text(if (active == null) "D/R 진입과 P 주차를 자동 감지합니다." else "시작 ${tripTime(active.optLong("startedAt"))} · P 주차 후 자동 저장", 13f, muted))
            space(12)
            val controls = row()
            controls.addView(action("자동 기록 시작", true) {
                prefs.edit().putBoolean("trip_auto_enabled", true).apply()
                ContextCompat.startForegroundService(context, Intent(context, TripMonitorService::class.java))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            controls.addView(action("중지", false) {
                prefs.edit().putBoolean("trip_auto_enabled", false).apply()
                context.stopService(Intent(context, TripMonitorService::class.java))
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            addView(controls)
            space(8)
            val backup = row()
            backup.addView(action("CSV 백업", false, onExportTrips), LinearLayout.LayoutParams(0, -2, 1f))
            backup.addView(action("CSV 복원", false, onImportTrips), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            addView(backup)
            space(8)
            addView(action("Google Sheets 로그인 · 자동 기록", false, onConfigureSheets), LinearLayout.LayoutParams(-1, -2))
            space(8)
            addView(action("스프레드시트 샘플 운행 추가", false, onAppendSampleTrip), LinearLayout.LayoutParams(-1, -2))
            space(8)
            addView(action("표시 예", false) { showTripCardPreview() }, LinearLayout.LayoutParams(-1, -2))
        }
        val records = TripLedger.records(context, vinValue)
        val distance = records.sumOf { it.distanceKm }
        val energy = records.mapNotNull { it.estimatedKwh }.sum()
        val minutes = records.sumOf { ((it.endedAt - it.startedAt) / 60_000).coerceAtLeast(0) }
        val efficiency = if (energy > 0) distance / energy else null
        val batteryUsed = records.mapNotNull { it.batteryUsedPercent }.sum()
        card(tripPage).apply {
            addView(text("누적 운행", 13f, accent, true))
            space(10)
            val first = row()
            first.addView(tripMetric("누적 거리", "${"%.1f".format(distance)} km"), LinearLayout.LayoutParams(0, -2, 1f))
            first.addView(tripMetric("운행 시간", durationText(minutes)), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            addView(first)
            space(8)
            val second = row()
            second.addView(tripMetric("사용 에너지", "${"%.1f".format(energy)} kWh"), LinearLayout.LayoutParams(0, -2, 1f))
            second.addView(tripMetric("평균 전비", efficiency?.let { "${"%.1f".format(it)} km/kWh" } ?: "—"), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            addView(second)
            space(10)
            addView(text("${records.size}회 운행 · 배터리 총 ${batteryUsed}% 사용", 13f, muted, true))
        }
        tripPage.addView(text("운행 기록", 18f, ink, true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        if (records.isEmpty()) {
            tripPage.addView(text("저장된 운행이 없습니다. 자동 기록을 켠 뒤 주행해 보세요.", 14f, muted))
        }
        records.forEach { trip ->
            card(tripPage).apply {
                addView(text(tripTime(trip.startedAt), 13f, accent, true))
                addView(text("${tripTime(trip.startedAt)} → ${tripTime(trip.endedAt)}", 12f, muted))
                space(12)
                val first = row()
                first.addView(tripMetric("거리", "${"%.1f".format(trip.distanceKm)} km"), LinearLayout.LayoutParams(0, -2, 1f))
                first.addView(tripMetric("시간", tripDuration(trip.startedAt, trip.endedAt)), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
                addView(first)
                space(8)
                val second = row()
                second.addView(tripMetric("에너지", trip.estimatedKwh?.let { "${"%.1f".format(it)} kWh" } ?: "—"), LinearLayout.LayoutParams(0, -2, 1f))
                second.addView(tripMetric("전비", trip.kmPerKwh?.let { "${"%.1f".format(it)} km/kWh" } ?: "—"), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
                addView(second)
                space(10)
                addView(text(tripBattery(trip), 14f, muted, true))
            }
        }
    }
    private fun tripTime(time: Long) = SimpleDateFormat("M.d HH:mm", Locale.KOREA).format(Date(time))
    fun updatePairing(paired: Boolean) {
        keyBadge.text = if (paired) "✓  키 등록됨" else "○  키 등록 필요"
        keyBadge.setTextColor(if (paired) accent else muted)
        keyBadge.background = shape(if (paired) Color.rgb(31, 58, 57) else Color.rgb(34, 46, 61), 8)
    }
    fun updatePairingProgress(paired: Boolean, serviceStatus: String) {
        val stage = when {
            paired || serviceStatus.contains("키 등록 완료") || serviceStatus.contains("키 등록 확인 완료") -> 4
            serviceStatus.contains("카드키를 차량 콘솔") -> 3
            serviceStatus.contains("차량 연결") || serviceStatus.contains("서비스 검색") || serviceStatus.contains("알림 연결") -> 2
            serviceStatus.contains("차량 검색") || serviceStatus.contains("등록 시작") -> 1
            else -> 0
        }
        val failed = serviceStatus.contains("등록 실패") || serviceStatus.contains("키 등록을 확인하지 못했습니다")
        fun step(number: Int, value: String) = when {
            failed && number == stage -> "!  $number. $value"
            number < stage || (number == 4 && paired) -> "✓  $number. $value"
            number == stage && stage > 0 -> "▶  $number. $value"
            else -> "○  $number. $value"
        }
        pairingSteps.text = listOf(
            step(1, "VIN 확인 및 차량 찾기"),
            step(2, "차량 BLE 연결"),
            step(3, "카드키를 콘솔에 대고 차량 화면에서 승인"),
            step(4, "차량에 등록된 키 재확인")
        ).joinToString("\n") + if (failed) "\n\n등록에 실패했습니다. 차량 가까이에서 다시 시도하세요." else ""
        pairingSteps.setTextColor(if (failed) Color.rgb(255, 150, 130) else muted)
    }
    fun updateTeslaOverview(vin: String, data: TeslaAuth.VehicleData?, message: String) {
        teslaStatus.text = message
        if (data == null) return
        teslaBattery.text = data.batteryPercent?.let { "$it%" } ?: "--%"
        teslaRange.text = data.rangeKm?.let { "예상 $it km" } ?: "주행거리 정보 없음"
        val model = when (data.model?.lowercase()) {
            "model3" -> "Model 3"
            "modely" -> "Model Y"
            "models" -> "Model S"
            "modelx" -> "Model X"
            else -> data.model?.replaceFirstChar { it.uppercase() } ?: "Tesla"
        }
        val charge = when (data.chargeState?.lowercase()) {
            "disconnected" -> "충전기 미연결"
            "charging" -> "충전 중"
            "complete" -> "충전 완료"
            "stopped" -> "충전 중지"
            "starting" -> "충전 시작 중"
            else -> data.chargeState?.replaceFirstChar { it.uppercase() } ?: "상태 확인"
        }
        teslaMeta.text = "$model · $charge"
        val gear = data.gear ?: "P"
        val speed = data.speedKph ?: 0
        val lock = when (data.locked) { true -> "잠김"; false -> "잠금 해제"; null -> "잠금 상태 확인 중" }
        val connection = when (data.vehicleState?.lowercase()) {
            "online" -> "온라인"
            "asleep" -> "절전 중"
            "offline" -> "오프라인"
            else -> data.vehicleState?.replaceFirstChar { it.uppercase() } ?: "상태 확인"
        }
        teslaDrive.text = "기어 $gear · 현재 $speed km/h · $lock · $connection"
        fun temperature(value: Double) = if (value % 1.0 == 0.0) "${value.toInt()}°C" else "%.1f°C".format(value)
        val details = mutableListOf<String>()
        data.outsideTempC?.let { details += "외기 ${temperature(it)}" }
        data.insideTempC?.let { details += "실내 ${temperature(it)}" }
        details += when (data.doorsOpen) { true -> "도어 열림"; false -> "도어 닫힘"; null -> "도어 상태 확인 중" }
        details += when (data.sentryMode) { true -> "센트리 켜짐"; false -> "센트리 꺼짐"; null -> "센트리 상태 확인 중" }
        data.softwareVersion?.let { details += "v$it" }
        teslaClimate.text = details.joinToString(" · ")
        teslaGear.text = "기어\n$gear"
        teslaSpeed.text = "현재 속도\n$speed km/h"
        teslaVehicleState.text = "차량 상태\n$connection"
        teslaLock.text = "도어 잠금\n$lock"
        teslaOutsideTemp.text = "외기 온도\n${data.outsideTempC?.let(::temperature) ?: "--"}"
        teslaInsideTemp.text = "실내 온도\n${data.insideTempC?.let(::temperature) ?: "--"}"
        teslaDoors.text = "도어\n${when (data.doorsOpen) { true -> "열림"; false -> "닫힘"; null -> "확인 중" }}"
        teslaSentry.text = "센트리 모드\n${when (data.sentryMode) { true -> "켜짐"; false -> "꺼짐"; null -> "확인 중" }}"
    }
    fun updateTeslaVehicleName(name: String) {
        teslaTitle.text = name.ifBlank { "내 테슬라" }
    }
    fun updateTeslaAccount(signedIn: Boolean) {
        teslaLoginAction.text = if (signedIn) "Tesla 로그인 다시하기" else "Tesla 계정 로그인"
        teslaLogoutAction.visibility = if (signedIn) View.VISIBLE else View.GONE
        if (signedIn && teslaBattery.text == "--%") teslaStatus.text = "로그인 유지됨 · 차량 정보를 불러오는 중"
    }
}

/** Decorative top-down car and detection arcs, deliberately not a live map or speedometer. */
private class RoadIllustration(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width / 300f, height / 155f)
        canvas.save(); canvas.translate(width / 2f, height / 2f); canvas.scale(scale, scale)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f
        paint.shader = LinearGradient(0f, -78f, 0f, 78f, intArrayOf(0x002E5360, 0xFF37505E.toInt(), 0x002E5360), null, Shader.TileMode.CLAMP)
        canvas.drawLine(-68f, -78f, -98f, 78f, paint); canvas.drawLine(68f, -78f, 98f, 78f, paint)
        paint.shader = null; paint.color = 0x554B6775
        paint.pathEffect = DashPathEffect(floatArrayOf(8f, 12f), 0f)
        canvas.drawLine(-50f, -75f, -62f, 75f, paint); canvas.drawLine(50f, -75f, 62f, 75f, paint)
        paint.pathEffect = null
        for (i in 0..2) {
            paint.color = Color.argb(135 - i * 35, 114, 235, 198); paint.strokeWidth = 1.5f
            val r = 30f + i * 17
            canvas.drawArc(-r, -49f - r / 2, r, -12f + r / 2, 210f, 120f, false, paint)
        }
        paint.style = Paint.Style.FILL; paint.color = 0x44000000
        canvas.drawRoundRect(-28f, -16f, 28f, 70f, 18f, 18f, paint)
        paint.color = 0xFFB9CAD4.toInt(); canvas.drawRoundRect(-23f, -23f, 23f, 64f, 15f, 15f, paint)
        paint.color = 0xFFDFE9ED.toInt(); canvas.drawRoundRect(-19f, -20f, 19f, 61f, 13f, 13f, paint)
        paint.color = 0xFF243D4B.toInt(); canvas.drawRoundRect(-15f, -1f, 15f, 38f, 8f, 8f, paint)
        paint.color = 0xFF395968.toInt(); canvas.drawRoundRect(-12f, 2f, 12f, 14f, 4f, 4f, paint)
        paint.color = 0xFF74EBC6.toInt(); canvas.drawRoundRect(-17f, -15f, -9f, -12f, 2f, 2f, paint)
        canvas.drawRoundRect(9f, -15f, 17f, -12f, 2f, 2f, paint)
        paint.color = 0xFFE99A8C.toInt(); canvas.drawRect(-16f, 53f, -8f, 56f, paint); canvas.drawRect(8f, 53f, 16f, 56f, paint)
        paint.color = 0xFF1B2E3A.toInt(); canvas.drawRoundRect(94f, -43f, 128f, -13f, 9f, 9f, paint)
        paint.style = Paint.Style.STROKE; paint.color = 0xFF74EBC6.toInt(); paint.strokeWidth = 1.5f
        canvas.drawRoundRect(101f, -35f, 121f, -22f, 3f, 3f, paint); canvas.drawCircle(111f, -28.5f, 3.5f, paint)
        canvas.restore()
    }
}
