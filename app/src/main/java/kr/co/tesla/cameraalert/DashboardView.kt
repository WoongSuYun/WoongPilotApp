package kr.co.tesla.cameraalert

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import kr.co.tesla.cameraalert.trip.MaintenanceLedger
import kr.co.tesla.cameraalert.trip.MaintenanceRecord
import kr.co.tesla.cameraalert.trip.TripLedger
import kr.co.tesla.cameraalert.route.RouteLedger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Native dashboard: decorative driving graphic, real service status, and setup controls. */
class DashboardView(context: Context, savedTeslaName: String,
    onStart: () -> Unit, onStop: () -> Unit,
    onCheckKakao: () -> Unit, onTeslaLogin: () -> Unit, onTeslaLogout: () -> Unit,
    onWakeTesla: () -> Unit, onRefreshTesla: () -> Unit, onVoiceSettings: () -> Unit,
    onGeminiSettings: () -> Unit, onGeminiAudioLibrary: () -> Unit, onSafetyAlertSettings: () -> Unit,
    onSpeedCameraAlertSettings: () -> Unit,
    onPreviewCameraAlert: () -> Unit, onMonitoringSettings: () -> Unit, onCameraList: () -> Unit,
    private val onExportTrips: () -> Unit, private val onImportTrips: () -> Unit,
    private val onConfigureSheets: () -> Unit, private val onOpenSheets: () -> Unit,
    private val onAppendSampleTrip: () -> Unit, private val onOpenRouteLog: () -> Unit
) : ScrollView(context) {
    private val ink = Color.rgb(240, 244, 248)
    private val muted = Color.rgb(149, 164, 180)
    private val accent = Color.rgb(114, 235, 198)
    private val surface = Color.rgb(24, 33, 45)
    private val border = Color.rgb(43, 56, 70)
    val status = TextView(context)
    val monitorState = TextView(context)
    val tripMonitorStatus = TextView(context)
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
    private lateinit var headerMessage: TextView
    private val body = LinearLayout(context)
    private val monitorPage = column()
    private val vehiclePage = column()
    private val guidePage = column()
    private val tripPage = column()
    private val tabButtons = mutableMapOf<String, View>()
    private val selectedTripKeys = mutableSetOf<String>()
    private var tripSelectionPopup: PopupWindow? = null
    private enum class LedgerPage { DRIVES, MAINTENANCE }
    private var ledgerPage = LedgerPage.DRIVES
    private fun tripKey(trip: kr.co.tesla.cameraalert.trip.TripRecord) = "${trip.vin}:${trip.startedAt}:${trip.endedAt}"
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
        if (name != "trip") tripSelectionPopup?.dismiss()
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
    private fun ledgerTab(label: String, page: LedgerPage) = TextView(context).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (ledgerPage == page) Color.rgb(13, 33, 29) else muted)
        setPadding(dp(10), dp(12), dp(10), dp(12))
        background = shape(if (ledgerPage == page) accent else Color.rgb(34, 46, 61), 14)
        setOnClickListener { ledgerPage = page; renderTripLog() }
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
    private fun buildInfoView() = text("앱 버전 ${BuildConfig.VERSION_NAME} · 빌드 ${BuildConfig.BUILD_DATE}", 11f, muted).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(2), 0, dp(10))
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
                .setMessage("1. 감시 시작을 누르거나 삼성 모드 및 루틴에서 ‘웅파일럿 감시 시작’을 실행하세요.\n\n2. 위치·속도는 휴대폰 GPS를 사용합니다. Bluetooth 연결 해제 조건에는 ‘웅파일럿 감시 종료’를 설정할 수 있습니다.\n\n3. 공공 카메라 데이터는 앱에서 업데이트할 수 있습니다.\n\n앱 강제 종료나 재부팅 후에는 감시를 다시 시작하세요.")
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
            teslaStatus.textSize = 12f; teslaStatus.setTextColor(muted); teslaStatus.text = "마지막 차량 정보를 표시합니다. 새로고침하면 갱신됩니다."
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
            monitorState.text = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("monitor_state", "감시 안 함")
            monitorState.textSize = 19f; monitorState.setTextColor(accent)
            monitorState.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            monitorState.setPadding(0, dp(6), 0, dp(4))
            addView(monitorState, LinearLayout.LayoutParams(-1, -2))
            status.textSize = 13f; status.setTextColor(muted); status.setLineSpacing(dp(3).toFloat(), 1f)
            status.setPadding(0, 0, 0, dp(14)); status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(status, LinearLayout.LayoutParams(-1, -2))
            addView(text("운행기록 상태", 11f, accent, true).apply { letterSpacing = .08f })
            tripMonitorStatus.text = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("trip_status", "운행기록 대기")
            tripMonitorStatus.textSize = 14f; tripMonitorStatus.setTextColor(ink)
            tripMonitorStatus.setLineSpacing(dp(3).toFloat(), 1f)
            tripMonitorStatus.setPadding(0, dp(6), 0, dp(16))
            addView(tripMonitorStatus, LinearLayout.LayoutParams(-1, -2))
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
            addView(action("안전 안내 항목 설정", false, onSafetyAlertSettings), LinearLayout.LayoutParams(-1, -2)); space(8)
            addView(action("과속카메라 알림 설정", false, onSpeedCameraAlertSettings), LinearLayout.LayoutParams(-1, -2))
        }
        guidePage.addView(text("차량 연결 없이도 휴대폰 GPS·카카오 안전 안내를 사용할 수 있어요.\n화면을 꺼도 실행 중인 감시는 계속됩니다.", 12f, muted).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
        })
        guidePage.addView(buildInfoView())
    }
    private fun renderTripLog() {
        tripPage.removeAllViews()
        tripPage.addView(row().apply {
            background = shape(Color.rgb(18, 48, 58), 16); setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(ledgerTab("운행 기록", LedgerPage.DRIVES), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ledgerTab("정비 기록", LedgerPage.MAINTENANCE), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        if (ledgerPage == LedgerPage.MAINTENANCE) {
            tripSelectionPopup?.dismiss()
            renderMaintenancePage()
            return
        }
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val vinValue = prefs.getString("vin", "").orEmpty()
        val active = TripLedger.active(context)
        val vehicle = TeslaVehicleCache.load(context, vinValue)?.data
        val modelName = when (vehicle?.model?.lowercase()) {
            "modely" -> "Model Y"
            "model3" -> "Model 3"
            "models" -> "Model S"
            "modelx" -> "Model X"
            else -> vehicle?.model.orEmpty()
        }
        val vehicleLabel = listOf(modelName, vehicle?.trim.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
        val capacity = vehicle?.let { TripLedger.batteryCapacity(vinValue, it.model, it.trim) }
        card(tripPage).apply {
            val vehicleHeader = row()
            vehicleHeader.addView(text("내 차량", 13f, accent, true), LinearLayout.LayoutParams(0, -2, 1f))
            vehicleHeader.addView(action("전비 다시 계산", false) {
                val selectedCapacity = capacity
                if (selectedCapacity == null || vinValue.isBlank()) {
                    Toast.makeText(context, "Tesla 차량 정보를 먼저 동기화해 주세요.", Toast.LENGTH_SHORT).show()
                    return@action
                }
                AlertDialog.Builder(context)
                    .setTitle("기존 전비 다시 계산")
                    .setMessage("${selectedCapacity.source} ${"%.1f".format(selectedCapacity.kwh)}kWh 기준으로 이 차량의 기존 운행 기록을 다시 계산합니다. 거리와 시간은 변경되지 않습니다.")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("다시 계산") { _, _ ->
                        val count = TripLedger.recalculateEfficiency(context, vinValue, selectedCapacity.kwh)
                        Toast.makeText(context, "${count}건의 운행 전비를 다시 계산했습니다.", Toast.LENGTH_LONG).show()
                        renderTripLog()
                    }.show()
            }, LinearLayout.LayoutParams(-2, -2))
            // Keep the dashboard focused on vehicle/status information; configuration is in one dialog.
            vehicleHeader.getChildAt(1).visibility = View.GONE
            vehicleHeader.addView(action("차계부 설정", false) { showTripSettingsDialog(vinValue, capacity, prefs) }, LinearLayout.LayoutParams(-2, -2))
            addView(vehicleHeader)
            addView(text(vehicleLabel.ifBlank { "차량 정보 동기화 대기" }, 18f, ink, true))
            addView(text(capacity?.let { "전비 계산 기준 · ${it.source} · ${"%.1f".format(it.kwh)} kWh" }
                ?: "Tesla 차량 정보를 받아오면 전비 기준 용량을 자동 설정합니다.", 12f, muted))
            space(14)
            addView(text(if (active == null) "다음 운행을 기다리고 있어요" else "● 운행 기록 중", 20f, ink, true))
            addView(text(if (active == null) "감시 중 GPS 경로를 기록하고, 감시 종료 시 Tesla 값 또는 GPS 경로로 저장합니다." else "시작 ${tripTime(active.optLong("startedAt"))} · 감시 종료 시 저장", 13f, muted))
            space(12)
            val automaticMode = Switch(context).apply {
                text = "자동 기록 모드 사용"
                textSize = 16f
                isChecked = prefs.getBoolean("trip_auto_enabled", false)
                setPadding(0, dp(4), 0, dp(4))
                setOnCheckedChangeListener { _, enabled ->
                    prefs.edit().putBoolean("trip_auto_enabled", enabled)
                        .putString("trip_status", if (enabled) "자동 기록 모드 켜짐 · 감시 모드 GPS 이동 감지 대기" else "자동 기록 모드 꺼짐").apply()
                    renderTripLog()
                }
            }
            addView(automaticMode)
            addView(text(if (active != null) "● 운행 기록 중 · 주차 후 자동 저장됩니다"
                else prefs.getString("trip_status", "자동 기록 모드가 꺼져 있습니다.").orEmpty(), 13f,
                if (active != null) accent else muted, true))
            val controls = row().apply { visibility = View.GONE }
            controls.addView(action("자동 기록 시작", true) {
                prefs.edit().putBoolean("trip_auto_enabled", true)
                    .putString("trip_status", "자동 기록 모드 켜짐 · 감시 모드 GPS 이동 감지 대기").apply()
            }, LinearLayout.LayoutParams(0, -2, 1f))
            controls.addView(action("중지", false) {
                prefs.edit().putBoolean("trip_auto_enabled", false)
                    .putString("trip_status", "자동 기록 모드 꺼짐").apply()
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            addView(controls)
            space(8)
            val backup = row()
            backup.addView(action("운행 데이터 백업", false, onExportTrips), LinearLayout.LayoutParams(0, -2, 1f))
            backup.addView(action("운행 데이터 복원", false, onImportTrips), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            addView(backup)
            space(8)
            addView(action("Google Sheets 로그인 · 자동 기록", false, onConfigureSheets), LinearLayout.LayoutParams(-1, -2))
            space(8)
            addView(action("연결된 Google Sheets 열기", false, onOpenSheets), LinearLayout.LayoutParams(-1, -2))
            space(8)
            addView(action("예시 경로 카드 추가", false) { addSampleRouteTrip() }, LinearLayout.LayoutParams(-1, -2))
            for (index in 7 until childCount) getChildAt(index).visibility = View.GONE
        }
        val records = TripLedger.records(context, vinValue)
        val lifetime = TripLedger.lifetime(context)
        selectedTripKeys.retainAll(records.map(::tripKey).toSet())
        val statisticsRecords = records.filter { it.countsTowardLifetime }
        val sampleCount = records.size - statisticsRecords.size
        val distance = statisticsRecords.sumOf { it.distanceKm }
        val efficiencyRecords = statisticsRecords.filter { (it.estimatedKwh ?: 0.0) > 0.0 && (it.efficiencyDistanceKm ?: 0.0) > 0.0 }
        val energy = efficiencyRecords.sumOf { it.estimatedKwh ?: 0.0 }
        val efficiencyDistance = efficiencyRecords.sumOf { it.efficiencyDistanceKm ?: 0.0 }
        val zeroEnergyCount = statisticsRecords.count { it.estimatedKwh == 0.0 }
        val minutes = statisticsRecords.sumOf { ((it.endedAt - it.startedAt) / 60_000).coerceAtLeast(0) }
        val efficiency = if (energy > 0) efficiencyDistance / energy else null
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
            addView(text("${statisticsRecords.size}회 운행", 13f, muted, true))
            if (zeroEnergyCount > 0) addView(text("0 kWh 단거리 ${zeroEnergyCount}건은 전비 계산에서 제외", 12f, muted))
            addView(text("누적 ${"%.1f".format(lifetime.distanceKm)} km · ${lifetime.tripCount}회 운행", 12f, muted))
            if (sampleCount > 0) addView(text("예시 경로 카드 ${sampleCount}건은 통계에서 제외", 12f, muted))
        }
        tripPage.addView(text("운행 기록", 18f, ink, true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        val selected = records.filter { tripKey(it) in selectedTripKeys }
        // Selection actions live in the floating bottom bar so they remain available while scrolling.
        if (false && selected.isNotEmpty()) {
            card(tripPage).apply {
                addView(text("${selected.size}개 운행 선택됨", 14f, accent, true))
                space(8)
                addView(action(if (selected.size >= 2) "선택한 ${selected.size}개 운행 합치기" else "합치려면 2개 이상 선택하세요", selected.size >= 2) {
                    if (selected.size < 2) {
                        Toast.makeText(context, "운행기록을 2개 이상 선택해 주세요.", Toast.LENGTH_SHORT).show()
                        return@action
                    }
                    AlertDialog.Builder(context)
                        .setTitle("운행기록 합치기")
                        .setMessage("선택한 카드를 하나로 합칩니다. GPS 경로는 휴식 구간을 잇지 않은 별도 구간으로 보관하며, 나중에 분리하여 원래 카드로 되돌릴 수 있습니다.")
                        .setNegativeButton("취소", null)
                        .setPositiveButton("합치기") { _, _ ->
                            if (TripLedger.merge(context, selected) == null) Toast.makeText(context, "일반 운행기록만 합칠 수 있습니다.", Toast.LENGTH_SHORT).show()
                            selectedTripKeys.clear(); renderTripLog()
                        }.show()
                }, LinearLayout.LayoutParams(-1, -2))
                space(8)
                addView(action("선택 해제", false) { selectedTripKeys.clear(); renderTripLog() }, LinearLayout.LayoutParams(-1, -2))
                space(8)
                addView(action("선택한 ${selected.size}개 운행 삭제", false) {
                    AlertDialog.Builder(context)
                        .setTitle("선택한 운행기록 삭제")
                        .setMessage("선택한 ${selected.size}개 운행기록과 연결된 GPS 경로를 삭제할까요? 이 작업은 되돌릴 수 없습니다.")
                        .setNegativeButton("취소", null)
                        .setPositiveButton("삭제") { _, _ ->
                            selected.forEach { trip ->
                                TripLedger.delete(context, trip.vin, trip.startedAt, trip.endedAt)
                                val routeIds = if (trip.routeStartedAts.isNotEmpty()) trip.routeStartedAts
                                    else (trip.routeStartedAt ?: RouteLedger.matchingStart(context, trip.startedAt, trip.endedAt))?.let(::listOf).orEmpty()
                                routeIds.forEach { RouteLedger.delete(context, it) }
                            }
                            selectedTripKeys.clear(); renderTripLog()
                        }.show()
                }, LinearLayout.LayoutParams(-1, -2))
            }
        }
        if (records.isEmpty()) {
            tripPage.addView(text("저장된 운행이 없습니다. 자동 기록을 켠 뒤 주행해 보세요.", 14f, muted))
        }
        records.forEach { trip ->
            val routeStartedAt = trip.routeStartedAt ?: RouteLedger.matchingStart(context, trip.startedAt, trip.endedAt)
            card(tripPage).apply {
                val header = row()
                header.addView(CheckBox(context).apply {
                    isChecked = tripKey(trip) in selectedTripKeys
                    contentDescription = "운행기록 선택"
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedTripKeys.add(tripKey(trip)) else selectedTripKeys.remove(tripKey(trip))
                        renderTripLog()
                    }
                }, LinearLayout.LayoutParams(dp(42), dp(42)))
                header.addView(text(tripTime(trip.startedAt), 13f, accent, true), LinearLayout.LayoutParams(0, -2, 1f))
                header.addView(TextView(context).apply {
                    text = "×"; textSize = 24f; gravity = Gravity.CENTER; setTextColor(muted)
                    background = shape(Color.rgb(34, 46, 61), 12)
                    setOnClickListener {
                        AlertDialog.Builder(context)
                            .setTitle("운행기록 삭제")
                            .setMessage("이 운행기록을 삭제할까요? 누적 운행에도 즉시 제외됩니다.")
                            .setNegativeButton("취소", null)
                            .setPositiveButton("삭제") { _, _ ->
                                TripLedger.delete(context, trip.vin, trip.startedAt, trip.endedAt)
                                routeStartedAt?.let { RouteLedger.delete(context, it) }
                                renderTripLog()
                            }.show()
                    }
                }, LinearLayout.LayoutParams(dp(36), dp(36)))
                addView(header)
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
                if (trip.mergedPartCount >= 2) {
                    space(8)
                    addView(text("${trip.mergedPartCount}개 구간을 합친 기록 · 분리하면 원래 카드와 경로로 복원됩니다.", 12f, muted))
                    space(8)
                    addView(action("합친 운행 분리", false) {
                        AlertDialog.Builder(context)
                            .setTitle("합친 운행 분리")
                            .setMessage("합치기 전의 카드와 GPS 경로 연결 상태로 되돌립니다.")
                            .setNegativeButton("취소", null)
                            .setPositiveButton("분리") { _, _ -> TripLedger.split(context, trip); renderTripLog() }.show()
                    }, LinearLayout.LayoutParams(-1, -2))
                }
                val routeStarts = TripLedger.routeIdsForMap(context, trip)
                if (routeStarts.isNotEmpty()) {
                    space(10)
                    addView(action("경로 보기", false) {
                        context.startActivity(Intent(context, RouteMapActivity::class.java)
                            .putExtra(RouteMapActivity.EXTRA_STARTED_AT, routeStarts.first())
                            .putExtra(RouteMapActivity.EXTRA_STARTED_ATS, routeStarts.toLongArray()))
                    }, LinearLayout.LayoutParams(-1, -2))
                }
            }
        }
        updateTripSelectionPopup(records)
    }
    private fun showTripSettingsDialog(
        vin: String,
        capacity: TripLedger.BatteryCapacity?,
        prefs: android.content.SharedPreferences
    ) {
        val settings = column().apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
        settings.addView(text("운행 기록 설정", 18f, ink, true))
        settings.addView(text("자동 기록, 전비 계산, 백업과 연동 기능을 관리합니다.", 13f, muted))
        settings.space(12)
        settings.addView(action("전비 다시 계산", false) {
            if (capacity == null || vin.isBlank()) {
                Toast.makeText(context, "Tesla 차량 정보를 먼저 동기화해 주세요.", Toast.LENGTH_SHORT).show()
                return@action
            }
            AlertDialog.Builder(context)
                .setTitle("기존 전비 다시 계산")
                .setMessage("${capacity.source} ${"%.1f".format(capacity.kwh)} kWh 기준으로 기존 운행기록의 전비를 다시 계산합니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("다시 계산") { _, _ ->
                    val count = TripLedger.recalculateEfficiency(context, vin, capacity.kwh)
                    Toast.makeText(context, "${count}건의 운행 전비를 다시 계산했습니다.", Toast.LENGTH_LONG).show()
                    renderTripLog()
                }.show()
        }, LinearLayout.LayoutParams(-1, -2))
        settings.space(8)
        settings.addView(Switch(context).apply {
            text = "자동 기록 모드 사용"; textSize = 16f
            isChecked = prefs.getBoolean("trip_auto_enabled", false)
            setOnCheckedChangeListener { _, enabled ->
                prefs.edit().putBoolean("trip_auto_enabled", enabled)
                    .putString("trip_status", if (enabled) "자동 기록 모드 켜짐" else "자동 기록 모드 꺼짐").apply()
            }
        })
        settings.space(8)
        val backup = row()
        backup.addView(action("운행 데이터 백업", false, onExportTrips), LinearLayout.LayoutParams(0, -2, 1f))
        backup.addView(action("운행 데이터 복원", false, onImportTrips), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        settings.addView(backup)
        settings.space(8)
        settings.addView(action("Google Sheets 로그인 · 자동 기록", false, onConfigureSheets), LinearLayout.LayoutParams(-1, -2))
        settings.space(8)
        settings.addView(action("연결된 Google Sheets 열기", false, onOpenSheets), LinearLayout.LayoutParams(-1, -2))
        settings.space(8)
        settings.addView(action("예시 경로 카드 추가", false) { addSampleRouteTrip() }, LinearLayout.LayoutParams(-1, -2))
        AlertDialog.Builder(context).setTitle("차계부 설정").setView(ScrollView(context).apply { addView(settings) })
            .setPositiveButton("닫기", null).show()
    }

    private fun updateTripSelectionPopup(records: List<kr.co.tesla.cameraalert.trip.TripRecord>) {
        tripSelectionPopup?.dismiss()
        val selected = records.filter { tripKey(it) in selectedTripKeys }
        if (selected.isEmpty()) return
        val panel = row().apply {
            background = shape(Color.rgb(18, 48, 58), 18, outline = true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            elevation = dp(8).toFloat()
            addView(text("${selected.size}개 선택", 14f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(action("선택 해제", false) { selectedTripKeys.clear(); renderTripLog() },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            addView(action("삭제", false) { confirmTripDelete(selected) },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            addView(action(if (selected.size >= 2) "합치기" else "1개 더 선택", true) {
                if (selected.size >= 2) confirmTripMerge(selected)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        }
        tripSelectionPopup = PopupWindow(panel, -1, -2, false).apply {
            isOutsideTouchable = false; elevation = dp(8).toFloat()
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            showAtLocation(rootView, Gravity.BOTTOM, dp(12), dp(16))
        }
    }

    private fun confirmTripMerge(selected: List<kr.co.tesla.cameraalert.trip.TripRecord>) {
        AlertDialog.Builder(context)
            .setTitle("운행기록 합치기")
            .setMessage("선택한 카드를 하나로 합칩니다. GPS 경로는 휴식 구간을 잇지 않은 별도 구간으로 보관하며, 나중에 분리하여 원래 카드로 되돌릴 수 있습니다.")
            .setNegativeButton("취소", null)
            .setPositiveButton("합치기") { _, _ ->
                if (TripLedger.merge(context, selected) == null) Toast.makeText(context, "선택한 운행기록을 합칠 수 없습니다.", Toast.LENGTH_SHORT).show()
                selectedTripKeys.clear()
                tripSelectionPopup?.dismiss()
                renderTripLog()
            }.show()
    }

    private fun confirmTripDelete(selected: List<kr.co.tesla.cameraalert.trip.TripRecord>) {
        AlertDialog.Builder(context)
            .setTitle("선택한 운행 삭제")
            .setMessage("선택한 ${selected.size}개 운행기록과 연결된 GPS 경로를 삭제할까요? 이 작업은 되돌릴 수 없습니다.")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                selected.forEach { trip ->
                    TripLedger.delete(context, trip.vin, trip.startedAt, trip.endedAt)
                    TripLedger.routeIdsForMap(context, trip).forEach { RouteLedger.delete(context, it) }
                }
                selectedTripKeys.clear()
                tripSelectionPopup?.dismiss()
                renderTripLog()
            }.show()
    }

    private fun renderMaintenancePage() {
        val records = MaintenanceLedger.records(context)
        val totalCost = records.sumOf { it.costWon }
        card(tripPage).apply {
            addView(text("차계부", 13f, accent, true))
            addView(text("정비 기록", 24f, ink, true))
            addView(text("수리와 소모품 교체 내역을 한곳에서 관리하세요.", 13f, muted))
            space(14)
            val metrics = row()
            metrics.addView(tripMetric("누적 정비비", "${String.format(Locale.KOREA, "%,d", totalCost)}원"), LinearLayout.LayoutParams(0, -2, 1f))
            metrics.addView(tripMetric("기록 건수", "${records.size}건"), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            addView(metrics)
            space(12)
            addView(action("+ 정비 기록 추가", true) { showMaintenanceEntryDialog() }, LinearLayout.LayoutParams(-1, -2))
            if (records.isEmpty()) {
                space(14)
                addView(text("아직 정비 기록이 없습니다. 엔진오일·타이어·소모품 교체처럼 필요한 내역을 추가해 보세요.", 13f, muted))
            }
        }
        if (records.isNotEmpty()) tripPage.addView(text("최근 정비 내역", 18f, ink, true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        records.forEach { record ->
            card(tripPage).apply {
                val header = row()
                header.addView(text(SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(record.occurredAt)), 13f, accent, true),
                    LinearLayout.LayoutParams(0, -2, 1f))
                header.addView(action("수정", false) { showMaintenanceEntryDialog(record) }, LinearLayout.LayoutParams(-2, -2))
                header.addView(action("삭제", false) {
                    AlertDialog.Builder(context)
                        .setTitle("정비 기록 삭제")
                        .setMessage("이 정비 기록을 삭제할까요?")
                        .setNegativeButton("취소", null)
                        .setPositiveButton("삭제") { _, _ -> MaintenanceLedger.delete(context, record.id); renderTripLog() }
                        .show()
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
                addView(header)
                addView(text(record.description, 17f, ink, true))
                addView(text("${String.format(Locale.KOREA, "%,d", record.costWon)}원", 20f, accent, true))
                val details = listOfNotNull(
                    record.odometerKm?.let { "주행거리 ${"%.1f".format(it)} km" },
                    record.shop.takeIf { it.isNotBlank() }
                ).joinToString(" · ")
                if (details.isNotBlank()) addView(text(details, 12f, muted))
                if (record.note.isNotBlank()) {
                    space(6)
                    addView(text(record.note, 13f, muted))
                }
            }
        }
    }

    private fun showMaintenanceEntryDialogLegacy(record: MaintenanceRecord? = null) {
        val form = column().apply { setPadding(dp(20), dp(4), dp(20), dp(4)) }
        fun field(label: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT): EditText {
            form.addView(text(label, 12f, muted, true))
            return EditText(context).apply {
                setText(value); inputType = type; setTextColor(ink); setHintTextColor(muted)
                backgroundTintList = ColorStateList.valueOf(accent)
                form.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).apply { isLenient = false }
        val occurredCalendar = java.util.Calendar.getInstance().apply { record?.let { timeInMillis = it.occurredAt } }
        form.addView(text("날짜·시간 *", 12f, muted, true))
        val date = TextView(context).apply {
            textSize = 16f; setTextColor(ink); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14)); background = shape(Color.rgb(34, 46, 61), 12)
            fun updateLabel() { text = dateFormat.format(Date(occurredCalendar.timeInMillis)) }
            updateLabel()
            setOnClickListener {
                android.app.DatePickerDialog(context, { _, year, month, day ->
                    occurredCalendar.set(java.util.Calendar.YEAR, year)
                    occurredCalendar.set(java.util.Calendar.MONTH, month)
                    occurredCalendar.set(java.util.Calendar.DAY_OF_MONTH, day)
                    android.app.TimePickerDialog(context, { _, hour, minute ->
                        occurredCalendar.set(java.util.Calendar.HOUR_OF_DAY, hour)
                        occurredCalendar.set(java.util.Calendar.MINUTE, minute)
                        occurredCalendar.set(java.util.Calendar.SECOND, 0)
                        occurredCalendar.set(java.util.Calendar.MILLISECOND, 0)
                        updateLabel()
                    }, occurredCalendar.get(java.util.Calendar.HOUR_OF_DAY), occurredCalendar.get(java.util.Calendar.MINUTE), true).show()
                }, occurredCalendar.get(java.util.Calendar.YEAR), occurredCalendar.get(java.util.Calendar.MONTH), occurredCalendar.get(java.util.Calendar.DAY_OF_MONTH)).show()
            }
            form.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        val maintenanceTypes = listOf(
            "타이어 교체", "타이어 위치 교환", "타이어 수리", "와이퍼 블레이드 교체",
            "에어컨 필터 교체", "워셔액 보충", "브레이크액 점검·교체", "브레이크 패드·디스크 점검",
            "12V 배터리 교체", "냉각수 점검·교체", "정기 점검", "기타"
        )
        form.addView(text("정비 항목 *", 12f, muted, true))
        val maintenanceType = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, maintenanceTypes)
            backgroundTintList = ColorStateList.valueOf(accent)
            form.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        val description = field("기타 정비 내용 *", type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply {
            visibility = View.GONE
        }
        maintenanceType.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                description.visibility = if (maintenanceTypes[position] == "기타") View.VISIBLE else View.GONE
            }
        }
        val cost = field("금액(원) *", type = InputType.TYPE_CLASS_NUMBER)
        val odometer = field("주행거리(km, 선택)", type = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val shop = field("정비소·구매처(선택)")
        val note = field("메모(선택)", type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply { minLines = 2 }
        AlertDialog.Builder(context)
            .setTitle("정비 기록 추가")
            .setView(form)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장") { _, _ ->
                val costWon = cost.text.toString().trim().toLongOrNull()
                val odometerKm = odometer.text.toString().trim().takeIf { it.isNotBlank() }?.toDoubleOrNull()
                val selectedType = maintenanceType.selectedItem.toString()
                val maintenanceDescription = if (selectedType == "기타") description.text.toString().trim() else selectedType
                if (maintenanceDescription.isBlank() || costWon == null || odometerKm == null && odometer.text.isNotBlank()) {
                    Toast.makeText(context, "날짜·정비 항목·금액을 확인해 주세요.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                MaintenanceLedger.add(context, occurredCalendar.timeInMillis, maintenanceDescription, costWon, odometerKm,
                    shop.text.toString(), note.text.toString())
                renderTripLog()
            }
            .show()
    }

    private fun showMaintenanceEntryDialog(record: MaintenanceRecord? = null) {
        val form = column().apply { setPadding(dp(20), dp(4), dp(20), dp(4)) }
        fun field(label: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT): EditText {
            form.addView(text(label, 12f, muted, true))
            return EditText(context).apply {
                setText(value); inputType = type; setTextColor(ink); setHintTextColor(muted)
                backgroundTintList = ColorStateList.valueOf(accent)
                form.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)
        val occurred = java.util.Calendar.getInstance().apply { record?.let { timeInMillis = it.occurredAt } }
        form.addView(text("날짜·시간 *", 12f, muted, true))
        val dateButton = TextView(context).apply {
            textSize = 16f; setTextColor(ink); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14)); background = shape(Color.rgb(34, 46, 61), 12)
            fun refresh() { text = dateFormat.format(Date(occurred.timeInMillis)) }
            refresh()
            setOnClickListener {
                android.app.DatePickerDialog(context, { _, year, month, day ->
                    occurred.set(year, month, day)
                    android.app.TimePickerDialog(context, { _, hour, minute ->
                        occurred.set(java.util.Calendar.HOUR_OF_DAY, hour)
                        occurred.set(java.util.Calendar.MINUTE, minute)
                        occurred.set(java.util.Calendar.SECOND, 0)
                        occurred.set(java.util.Calendar.MILLISECOND, 0)
                        refresh()
                    }, occurred.get(java.util.Calendar.HOUR_OF_DAY), occurred.get(java.util.Calendar.MINUTE), true).show()
                }, occurred.get(java.util.Calendar.YEAR), occurred.get(java.util.Calendar.MONTH), occurred.get(java.util.Calendar.DAY_OF_MONTH)).show()
            }
            form.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        val types = listOf("타이어 교체", "타이어 위치 교환", "타이어 수리", "와이퍼 블레이드 교체", "에어컨 필터 교체",
            "워셔액 보충", "브레이크액 점검·교체", "브레이크 패드·디스크 점검", "12V 배터리 교체", "냉각수 점검·교체", "정기 점검", "기타")
        form.addView(text("정비 항목 *", 12f, muted, true))
        val type = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, types)
            backgroundTintList = ColorStateList.valueOf(accent)
            form.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        val detail = field("기타 정비 내용 *", type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        type.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                detail.visibility = if (types[position] == "기타") View.VISIBLE else View.GONE
            }
        }
        val knownType = record?.let { types.indexOf(it.description) } ?: 0
        if (knownType >= 0) type.setSelection(knownType) else {
            type.setSelection(types.lastIndex)
            detail.setText(record?.description.orEmpty())
            detail.visibility = View.VISIBLE
        }
        if (types[type.selectedItemPosition] != "기타") detail.visibility = View.GONE
        val cost = field("금액(원) *", record?.costWon?.toString().orEmpty(), InputType.TYPE_CLASS_NUMBER)
        formatWonInput(cost)
        val odometer = field("주행거리(km, 선택)", record?.odometerKm?.toString().orEmpty(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val shop = field("정비소·구매처(선택)", record?.shop.orEmpty())
        val note = field("메모(선택)", record?.note.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply { minLines = 2 }
        AlertDialog.Builder(context)
            .setTitle(if (record == null) "정비 기록 추가" else "정비 기록 수정")
            .setView(form)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장") { _, _ ->
                val costWon = cost.text.toString().replace(",", "").toLongOrNull()
                val odometerKm = odometer.text.toString().trim().takeIf { it.isNotBlank() }?.toDoubleOrNull()
                val selected = type.selectedItem.toString()
                val description = if (selected == "기타") detail.text.toString().trim() else selected
                if (description.isBlank() || costWon == null || (odometer.text.isNotBlank() && odometerKm == null)) {
                    Toast.makeText(context, "정비 항목과 금액을 확인해 주세요.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (record == null) MaintenanceLedger.add(context, occurred.timeInMillis, description, costWon, odometerKm, shop.text.toString(), note.text.toString())
                else MaintenanceLedger.update(context, record.copy(occurredAt = occurred.timeInMillis, description = description, costWon = costWon,
                    odometerKm = odometerKm, shop = shop.text.toString().trim(), note = note.text.toString().trim()))
                renderTripLog()
            }.show()
    }

    private fun formatWonInput(input: EditText) {
        var formatting = false
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(value: Editable?) {
                if (formatting) return
                val digits = value?.toString()?.replace(",", "").orEmpty()
                val formatted = digits.toLongOrNull()?.let { String.format(Locale.KOREA, "%,d", it) } ?: digits
                if (formatted == value?.toString()) return
                formatting = true
                input.setText(formatted)
                input.setSelection(formatted.length)
                formatting = false
            }
        })
        input.text?.let { input.setText(it) }
    }

    private fun tripTime(time: Long) = SimpleDateFormat("M.d HH:mm", Locale.KOREA).format(Date(time))
    private fun addSampleRouteTrip() {
        val route = RouteLedger.addSample(context)
        val vin = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("vin", "").orEmpty()
        TripLedger.recordGpsTrip(context, vin, route, countsTowardLifetime = false)
        onAppendSampleTrip()
        Toast.makeText(context, "예시 GPS 운행기록을 추가했습니다.", Toast.LENGTH_SHORT).show()
        renderTripLog()
    }
    fun refreshTripLog() = renderTripLog()
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
