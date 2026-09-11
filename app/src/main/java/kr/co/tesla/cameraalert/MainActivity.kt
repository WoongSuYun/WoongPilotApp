package kr.co.tesla.cameraalert

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kr.co.tesla.cameraalert.ble.TeslaProtocol
import kr.co.tesla.cameraalert.ble.VehicleKey
import kr.co.tesla.cameraalert.data.CameraRepository
import kr.co.tesla.cameraalert.monitor.CameraMonitorService
import kr.co.tesla.cameraalert.monitor.CameraAlertNotification
import kr.co.tesla.cameraalert.kakao.KakaoSafetyMonitor
import kr.co.tesla.cameraalert.model.SafetyAlertSettings
import kr.co.tesla.cameraalert.model.SafetyAlertType
import kr.co.tesla.cameraalert.model.OverspeedToneStyle
import kr.co.tesla.cameraalert.trip.TripLedger
import kr.co.tesla.cameraalert.trip.GoogleSheetsSync
import kr.co.tesla.cameraalert.trip.SheetsWebhook
import kr.co.tesla.cameraalert.voice.AlertSpeaker
import kr.co.tesla.cameraalert.voice.AppSoundPlayer
import kr.co.tesla.cameraalert.voice.GeminiTts
import kotlinx.coroutines.*
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private companion object {
        const val CAMERA_DATA_UPDATED_AT = "camera_data_updated_at"
        const val CAMERA_DATA_COUNT = "camera_data_count"
        const val CAMERA_DATA_REFRESH_INTERVAL_MS = 30L * 24 * 60 * 60 * 1_000
    }
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var dashboard: DashboardView
    private lateinit var vin: EditText
    private lateinit var status: TextView
    private lateinit var alertSpeaker: AlertSpeaker
    private lateinit var geminiSpeaker: GeminiTts
    private lateinit var sounds: AppSoundPlayer
    private var pendingPair: Boolean? = null
    private var pendingGoogleSheetId: String? = null
    private val googleSheetsLogin = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val account = runCatching {
            GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)
        }.getOrNull()
        val sheetId = pendingGoogleSheetId
        pendingGoogleSheetId = null
        if (account?.email.isNullOrBlank() || sheetId.isNullOrBlank()) {
            Toast.makeText(this, "Google login failed. Check the Google Cloud OAuth setup.", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        GoogleSheetsSync.save(this, true, sheetId, account!!.email!!)
        lifecycleScope.launch {
            val connected = GoogleSheetsSync.test(this@MainActivity)
            Toast.makeText(this@MainActivity,
                if (connected) "Google Sheets connected. Completed trips will be recorded automatically."
                else "Signed in, but Sheets access failed. Check the API, OAuth setup, and spreadsheet permission.",
                Toast.LENGTH_LONG).show()
        }
    }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "status") runOnUiThread {
            val current = prefs.getString("status", "").orEmpty()
            status.text = current
            dashboard.updatePairingProgress(vin.text.toString() == prefs.getString("pairedVin", ""), current)
        }
        if (key == "pairedVin") runOnUiThread {
            val paired = vin.text.toString() == prefs.getString("pairedVin", "")
            dashboard.updatePairing(paired)
            dashboard.updatePairingProgress(paired, prefs.getString("status", "").orEmpty())
        }
        if (key == "kakao_status") runOnUiThread { dashboard.kakaoStatus.text = prefs.getString("kakao_status", "연결 확인 전") }
        if (key == "trip_status") runOnUiThread { dashboard.refreshTripLog() }
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val pair = pendingPair ?: return@registerForActivityResult
        pendingPair = null
        if (requiredPermissions(includeBluetooth = pair).all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED })
            launchMonitor(pair)
        else status.text = "키 등록에는 위치 및 근처 기기 권한이 필요합니다."
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val exportTrips = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(TripLedger.toCsv(this@MainActivity)) }
                    ?: error("백업 파일을 열 수 없습니다")
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, if (result.isSuccess) "차계부 CSV 백업을 완료했습니다." else "백업 실패: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    private val importTrips = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { TripLedger.importCsv(this@MainActivity, it) }
                    ?: error("복원 파일을 열 수 없습니다")
            }
            withContext(Dispatchers.Main) {
                val message = result.fold({ "차계부 ${it}건을 복원했습니다." }, { "복원 실패: ${it.message}" })
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alertSpeaker = AlertSpeaker(this)
        geminiSpeaker = GeminiTts(this)
        sounds = AppSoundPlayer(this)
        // Remove settings left by the older backend build.
        prefs.edit().remove("backend").remove("vehicleId").remove("oauth_state").apply()
        val initialVin = prefs.getString("vin", "").orEmpty()
        val initialTeslaName = if (prefs.getString("tesla_vehicle_name_vin", "") == initialVin)
            prefs.getString("tesla_vehicle_name", "").orEmpty() else ""
        dashboard = DashboardView(this, initialVin, initialTeslaName,
            prefs.getString("pairedVin", "").orEmpty().let { it.isNotEmpty() && it == prefs.getString("vin", "") },
            onPair = { begin(true) },
            onStart = { begin(false) },
            onStop = {
                CameraMonitorService.stop(this)
                prefs.edit().putString("status", "감시를 중지했습니다.").apply()
            }, onCheckKakao = { checkKakao() }, onTeslaLogin = { TeslaAuth.start(this) },
            onTeslaLogout = { confirmTeslaLogout() }, onWakeTesla = { wakeTeslaAndRefresh() },
            onRefreshTesla = { startTeslaRefresh() }, onVoiceSettings = { showVoiceSettings() },
            onGeminiSettings = { showGeminiVoiceSettings() },
            onGeminiAudioLibrary = { showGeminiAudioLibrary() },
            onSafetyAlertSettings = { showSafetyAlertSettings() },
            onSpeedCameraAlertSettings = { showSpeedCameraAlertSettings() },
            onPreviewCameraAlert = { previewCameraAlert() }, onMonitoringSettings = { showMonitoringSettings() },
            onCameraList = { startActivity(Intent(this, CameraListActivity::class.java)) },
            onClearPairing = { clearPairingForReregistration() },
            onExportTrips = { exportTrips.launch("tesla-trip-ledger.csv") },
            onImportTrips = { importTrips.launch(arrayOf("text/csv", "text/comma-separated-values", "application/csv")) },
            onConfigureSheets = { showGoogleSheetsSettings() }, onOpenSheets = { openGoogleSheet() },
            onAppendSampleTrip = { appendSampleTripRow() })
        vin = dashboard.vin
        status = dashboard.status
        TeslaVehicleCache.load(this, initialVin)?.let { snapshot ->
            dashboard.updateTeslaOverview(initialVin, snapshot.data, lastTeslaUpdateMessage(snapshot.updatedAt))
        }
        dashboard.kakaoStatus.text = "카카오 연결 확인 전"
        vin.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                dashboard.updatePairing(s.toString().isNotBlank() && s.toString() == prefs.getString("pairedVin", ""))
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        ViewCompat.setOnApplyWindowInsetsListener(dashboard) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(dashboard)
        if (TeslaAuth.acceptCallback(this, intent)) {
            status.text = "Tesla sign-in completed. Choose your VIN to continue."
            chooseTeslaVehicle()
        }
        androidx.core.view.WindowCompat.getInsetsController(window, dashboard).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        // The public camera database is a local fallback. Download it on a fresh install,
        // then refresh it at most once every 30 days.
        lifecycleScope.launch(Dispatchers.IO) {
            val repository = CameraRepository(this@MainActivity)
            val needsInitialDownload = repository.load().isEmpty()
            val lastRefresh = prefs.getLong(CAMERA_DATA_UPDATED_AT, 0L)
            val needsMonthlyRefresh = System.currentTimeMillis() - lastRefresh >= CAMERA_DATA_REFRESH_INTERVAL_MS
            if (needsInitialDownload || needsMonthlyRefresh) {
                runCatching { repository.refresh(BuildConfig.DATA_GO_KR_SERVICE_KEY) }.onSuccess { count ->
                    prefs.edit().putLong(CAMERA_DATA_UPDATED_AT, System.currentTimeMillis())
                        .putInt(CAMERA_DATA_COUNT, count).apply()
                }
            }
        }
        checkKakao()
    }
    private var kakaoCheck: Job? = null
    private fun showVoiceSettings() {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        val enabled = Switch(this).apply {
            text = "과속카메라 음성 안내 사용"
            isChecked = alertSpeaker.isEnabled()
            textSize = 16f
        }
        val voiceStatus = TextView(this).apply {
            text = "설치된 한국어 음성을 불러오는 중…"
            textSize = 13f
            setPadding(0, padding / 2, 0, 0)
        }
        val choices = mutableListOf(AlertSpeaker.VoiceOption(null, "기본 한국어 음성"))
        val voiceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, choices)
        val voicePicker = Spinner(this).apply { adapter = voiceAdapter }
        val speedLabel = TextView(this).apply {
            setPadding(0, padding, 0, 0)
            textSize = 15f
        }
        val speed = SeekBar(this).apply {
            max = 8
            progress = ((alertSpeaker.savedRate() - 0.6f) * 10).roundToInt().coerceIn(0, max)
        }
        fun selectedRate() = 0.6f + speed.progress / 10f
        fun updateRate() { speedLabel.text = "말하기 속도  ${"%.1f".format(selectedRate())}x" }
        updateRate()
        speed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(view: SeekBar?, progress: Int, fromUser: Boolean) = updateRate()
            override fun onStartTrackingTouch(view: SeekBar?) {}
            override fun onStopTrackingTouch(view: SeekBar?) {}
        })
        panel.addView(enabled)
        panel.addView(voiceStatus)
        panel.addView(voicePicker, LinearLayout.LayoutParams(-1, -2))
        panel.addView(speedLabel)
        panel.addView(speed, LinearLayout.LayoutParams(-1, -2))

        fun selectedVoice() = voicePicker.selectedItem as? AlertSpeaker.VoiceOption
            ?: AlertSpeaker.VoiceOption(null, "기본 한국어 음성")
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("음성 안내 설정")
            .setMessage("카메라 경고 시 한국어로 거리와 제한속도를 안내합니다.")
            .setView(panel)
            .setNegativeButton("취소", null)
            .setNeutralButton("미리 듣기", null)
            .setPositiveButton("저장") { _, _ ->
                alertSpeaker.save(enabled.isChecked, selectedVoice().name, selectedRate())
            }.show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            if (!alertSpeaker.preview(selectedVoice().name, selectedRate()))
                voiceStatus.text = "음성 엔진을 준비 중입니다. 잠시 후 다시 눌러 주세요."
        }
        alertSpeaker.whenReady {
            runOnUiThread {
                val saved = alertSpeaker.savedVoiceName()
                choices.clear(); choices.addAll(alertSpeaker.voiceOptions())
                voiceAdapter.notifyDataSetChanged()
                voicePicker.setSelection(choices.indexOfFirst { it.name == saved }.coerceAtLeast(0))
                voiceStatus.text = if (choices.size == 1) "한국어 음성 엔진을 찾지 못했습니다. 기기 TTS 설정을 확인하세요."
                else "목소리를 선택하고 ‘미리 듣기’로 확인하세요."
            }
        }
    }

    private fun showGeminiVoiceSettings() {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        val description = TextView(this).apply {
            text = "내 Gemini API 키로 AI 음성을 생성합니다. 인터넷이 필요하며, 호출이 실패하면 휴대폰 음성으로 자동 전환됩니다."
            textSize = 13f
        }
        val enabled = Switch(this).apply {
            text = "Gemini AI 음성 사용"
            textSize = 16f
            isChecked = geminiSpeaker.isEnabled()
            setPadding(0, padding, 0, 0)
        }
        val keyStatus = TextView(this).apply {
            textSize = 13f
            setPadding(0, padding, 0, 0)
        }
        val keyInput = EditText(this).apply {
            hint = "Gemini API 키 입력 또는 교체"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            contentDescription = "Gemini API 키"
        }
        val secondaryKeyInput = EditText(this).apply {
            hint = "Gemini API 키 2 입력 (키 1 한도 도달 시 사용)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            contentDescription = "Gemini API 키 2"
        }
        val voiceLabel = TextView(this).apply {
            text = "Gemini 목소리"
            textSize = 15f
            setPadding(0, padding, 0, 0)
        }
        val choices = GeminiTts.voiceOptions()
        val voicePicker = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, choices)
            setSelection(choices.indexOfFirst { it.name == geminiSpeaker.savedVoiceName() }.coerceAtLeast(0))
        }
        val geminiStatus = TextView(this).apply {
            textSize = 13f
            setPadding(0, padding, 0, 0)
        }
        val clearKey = Button(this).apply {
            text = "저장된 Gemini 키 삭제"
            isAllCaps = false
        }
        val clearCache = Button(this).apply {
            text = "Gemini 음성 캐시 비우기"
            isAllCaps = false
        }
        val revealKey = Button(this).apply {
            text = "저장된 Gemini 키 보기"
            isAllCaps = false
        }
        val revealSecondaryKey = Button(this).apply {
            text = "저장된 Gemini 키 2 보기"
            isAllCaps = false
        }
        var isKeyVisible = false
        var isSecondaryKeyVisible = false
        fun updateKeyStatus() {
            val saved = geminiSpeaker.hasAnyApiKey()
            val keyCount = listOf(geminiSpeaker.hasApiKey(), geminiSpeaker.hasSecondaryApiKey()).count { it }
            keyStatus.text = if (saved) "Gemini 키 ${keyCount}개가 이 기기에 암호화되어 저장되어 있습니다. 키 1이 429 한도에 도달하면 키 2로 자동 재시도합니다."
            else "저장된 Gemini 키가 없습니다."
            clearKey.isEnabled = saved
            revealKey.isEnabled = saved
            revealSecondaryKey.isEnabled = geminiSpeaker.hasSecondaryApiKey()
            if (!saved) {
                isKeyVisible = false
                revealKey.text = "저장된 Gemini 키 보기"
                keyInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            if (!geminiSpeaker.hasSecondaryApiKey()) {
                isSecondaryKeyVisible = false
                revealSecondaryKey.text = "저장된 Gemini 키 2 보기"
                secondaryKeyInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }
        panel.addView(description)
        panel.addView(enabled)
        panel.addView(keyStatus)
        panel.addView(keyInput, LinearLayout.LayoutParams(-1, -2))
        panel.addView(secondaryKeyInput, LinearLayout.LayoutParams(-1, -2))
        panel.addView(revealKey, LinearLayout.LayoutParams(-1, -2))
        panel.addView(revealSecondaryKey, LinearLayout.LayoutParams(-1, -2))
        panel.addView(clearKey, LinearLayout.LayoutParams(-1, -2))
        panel.addView(clearCache, LinearLayout.LayoutParams(-1, -2))
        panel.addView(voiceLabel)
        panel.addView(voicePicker, LinearLayout.LayoutParams(-1, -2))
        panel.addView(geminiStatus)
        updateKeyStatus()

        val scroll = ScrollView(this).apply { addView(panel) }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Gemini AI 음성 설정")
            .setView(scroll)
            .setNegativeButton("취소", null)
            .setNeutralButton("미리 듣기", null)
            .setPositiveButton("저장", null)
            .show()
        fun selectedVoice(): GeminiTts.VoiceOption =
            voicePicker.selectedItem as? GeminiTts.VoiceOption ?: choices.first()
        fun saveTypedKey(): Boolean {
            val typed = keyInput.text.toString().trim()
            val secondary = secondaryKeyInput.text.toString().trim()
            if (typed.isNotBlank() && !geminiSpeaker.saveApiKey(typed)) {
                keyInput.error = "키를 안전하게 저장하지 못했습니다. 다시 시도해 주세요."
                return false
            }
            if (secondary.isNotBlank() && !geminiSpeaker.saveSecondaryApiKey(secondary)) {
                secondaryKeyInput.error = "두 번째 키를 안전하게 저장하지 못했습니다. 다시 시도해 주세요."
                return false
            }
            if (typed.isNotBlank() || secondary.isNotBlank()) {
                keyInput.text?.clear()
                secondaryKeyInput.text?.clear()
                keyInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                isKeyVisible = false
                isSecondaryKeyVisible = false
                revealKey.text = "저장된 Gemini 키 보기"
                revealSecondaryKey.text = "저장된 Gemini 키 2 보기"
                updateKeyStatus()
                return true
            }
            return true
        }
        revealKey.setOnClickListener {
            if (isKeyVisible) {
                keyInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                isKeyVisible = false
                revealKey.text = "저장된 Gemini 키 보기"
            } else {
                val savedKey = geminiSpeaker.savedApiKey()
                if (savedKey == null) {
                    updateKeyStatus()
                    return@setOnClickListener
                }
                keyInput.setText(savedKey)
                keyInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                keyInput.setSelection(savedKey.length)
                isKeyVisible = true
                revealKey.text = "Gemini 키 숨기기"
            }
        }
        revealSecondaryKey.setOnClickListener {
            if (isSecondaryKeyVisible) {
                secondaryKeyInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                isSecondaryKeyVisible = false
                revealSecondaryKey.text = "저장된 Gemini 키 2 보기"
            } else {
                val savedKey = geminiSpeaker.savedSecondaryApiKey()
                if (savedKey == null) {
                    updateKeyStatus()
                    return@setOnClickListener
                }
                secondaryKeyInput.setText(savedKey)
                secondaryKeyInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                secondaryKeyInput.setSelection(savedKey.length)
                isSecondaryKeyVisible = true
                revealSecondaryKey.text = "Gemini 키 2 숨기기"
            }
        }
        clearKey.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Gemini 키 삭제")
                .setMessage("이 기기에 암호화되어 저장된 Gemini API 키를 삭제할까요?")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제") { _, _ ->
                    geminiSpeaker.clearApiKey()
                    geminiSpeaker.clearSecondaryApiKey()
                    keyInput.text?.clear()
                    enabled.isChecked = false
                    updateKeyStatus()
                    geminiStatus.text = "Gemini 키를 삭제했습니다."
                }.show()
        }
        clearCache.setOnClickListener {
            geminiSpeaker.clearCachedAudio()
            geminiStatus.text = "저장된 Gemini 음성 캐시를 비웠습니다."
        }
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!saveTypedKey()) return@setOnClickListener
            if (enabled.isChecked && !geminiSpeaker.hasAnyApiKey()) {
                keyInput.error = "Gemini AI 음성을 켜려면 API 키를 입력해 저장하세요."
                return@setOnClickListener
            }
            geminiSpeaker.save(enabled.isChecked, selectedVoice().name)
            dialog.dismiss()
        }
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            if (!saveTypedKey()) return@setOnClickListener
            if (!geminiSpeaker.hasAnyApiKey()) {
                keyInput.error = "먼저 Gemini API 키를 입력해 주세요."
                return@setOnClickListener
            }
            geminiStatus.text = "Gemini 음성을 생성하는 중…"
            lifecycleScope.launch {
                val played = geminiSpeaker.preview(selectedVoice().name)
                geminiStatus.text = if (played) {
                    if (geminiSpeaker.lastPlaybackUsedCache()) "저장된 Gemini 음성을 재생했습니다."
                    else "Gemini 음성을 생성해 저장하고 재생했습니다."
                }
                else "Gemini 음성을 재생하지 못했습니다. ${geminiSpeaker.lastFailureMessage().ifBlank { "API 키·인터넷·Gemini 사용 설정을 확인하세요." }}"
            }
        }
    }

    private fun showGeminiAudioLibrary() {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        val scroll = ScrollView(this).apply { addView(panel) }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Gemini 음성 보관함")
            .setView(scroll)
            .setNegativeButton("닫기", null)
            .setNeutralButton("캐시 비우기", null)
            .show()
        fun render() {
            panel.removeAllViews()
            val clips = geminiSpeaker.cachedAudioItems()
            if (clips.isEmpty()) {
                panel.addView(TextView(this).apply {
                    text = "저장된 Gemini 음성이 없습니다. 미리 듣기 또는 카메라 안내가 성공하면 여기에 보관됩니다."
                    textSize = 14f
                    setPadding(0, padding, 0, padding)
                })
                return
            }
            panel.addView(TextView(this).apply {
                text = "저장된 음성 ${clips.size}개 · 재생해도 Gemini API를 호출하지 않습니다."
                textSize = 13f
                setPadding(0, 0, 0, padding / 2)
            })
            clips.forEach { clip ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, padding / 2, 0, padding / 2)
                }
                row.addView(TextView(this).apply {
                    text = "${clip.voiceName} · Gemini 음성"
                    textSize = 14f
                })
                row.addView(TextView(this).apply {
                    text = clip.text
                    textSize = 13f
                    setPadding(0, 4, 0, 8)
                })
                val play = Button(this).apply { text = "▶ 재생"; isAllCaps = false }
                val result = TextView(this).apply { textSize = 12f }
                play.setOnClickListener {
                    play.isEnabled = false
                    result.text = "저장된 음성 재생 중…"
                    lifecycleScope.launch {
                        val played = geminiSpeaker.playCachedAudio(clip.key)
                        result.text = if (played) "저장된 음성을 재생했습니다."
                        else geminiSpeaker.lastFailureMessage().ifBlank { "저장된 음성을 재생하지 못했습니다." }
                        play.isEnabled = true
                    }
                }
                row.addView(play, LinearLayout.LayoutParams(-1, -2))
                row.addView(result)
                panel.addView(row)
            }
        }
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            geminiSpeaker.clearCachedAudio()
            render()
        }
        render()
    }

    /** Selects which Kakao safety-road notices are eligible for voice guidance. */
    private fun showSafetyAlertSettings() {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        panel.addView(TextView(this).apply {
            text = "카카오 안전운행 안내에서 받을 항목만 선택하세요. 과속카메라의 안내 거리와 경고음은 별도 설정에서 관리합니다."
            textSize = 13f
            setPadding(0, 0, 0, padding / 2)
        })
        val switches = SafetyAlertType.entries.associateWith { type ->
            Switch(this).apply {
                text = type.label
                textSize = 16f
                isChecked = SafetyAlertSettings.isEnabled(this@MainActivity, type)
                setPadding(0, padding / 4, 0, padding / 4)
            }.also(panel::addView)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("안전 안내 항목 설정")
            .setView(ScrollView(this).apply { addView(panel) })
            .setNegativeButton("취소", null)
            .setPositiveButton("저장") { _, _ ->
                SafetyAlertSettings.save(this, switches.mapValues { it.value.isChecked })
                status.text = "안전 안내 항목을 저장했습니다. 다음 안내부터 적용됩니다."
            }.show()
    }

    /** Settings that affect only speed-camera timing and the over-limit sound. */
    private fun showSpeedCameraAlertSettings() {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        panel.addView(TextView(this).apply {
            text = "과속카메라 첫 안내 시점과 제한속도 초과 경고음을 설정합니다. 첫 안내 뒤에는 카메라를 지날 때까지 화면 안내가 유지됩니다."
            textSize = 13f
            setPadding(0, 0, 0, padding / 2)
        })
        val firstAlertDistance = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                SafetyAlertSettings.speedCameraFirstAlertDistances.map { "첫 안내 거리: ${it}m 전" })
            setSelection(SafetyAlertSettings.speedCameraFirstAlertDistances.indexOf(
                SafetyAlertSettings.speedCameraFirstAlertDistance(this@MainActivity)))
        }
        val overspeedTone = Switch(this).apply {
            text = "제한속도 초과 경고음"
            textSize = 16f
            isChecked = SafetyAlertSettings.isOverspeedToneEnabled(this@MainActivity)
            setPadding(0, padding / 2, 0, padding / 4)
        }
        val tonePicker = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, OverspeedToneStyle.entries)
            setSelection(OverspeedToneStyle.entries.indexOf(SafetyAlertSettings.overspeedToneStyle(this@MainActivity)))
        }
        val previewTone = Button(this).apply {
            text = "초과 경고음 미리 듣기"
            isAllCaps = false
            setOnClickListener {
                sounds.playOverspeed(tonePicker.selectedItem as? OverspeedToneStyle ?: OverspeedToneStyle.SHORT_BEEP)
            }
        }
        panel.addView(firstAlertDistance)
        panel.addView(overspeedTone)
        panel.addView(tonePicker)
        panel.addView(previewTone)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("과속카메라 알림 설정")
            .setView(panel)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장") { _, _ ->
                SafetyAlertSettings.saveSpeedCameraFirstAlertDistance(this,
                    SafetyAlertSettings.speedCameraFirstAlertDistances[firstAlertDistance.selectedItemPosition])
                SafetyAlertSettings.saveOverspeedTone(this, overspeedTone.isChecked)
                SafetyAlertSettings.saveOverspeedToneStyle(this,
                    tonePicker.selectedItem as? OverspeedToneStyle ?: OverspeedToneStyle.SHORT_BEEP)
                status.text = "과속카메라 알림 설정을 저장했습니다. 다음 안내부터 적용됩니다."
            }.show()
    }

    private fun showLegacyCombinedSafetyAlertSettings() {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        panel.addView(TextView(this).apply {
            text = "차량 연결 중 카카오 안전 운행 데이터에서 받을 안내 항목을 선택하세요. 카카오 데이터가 없을 때는 과속카메라만 공공데이터로 보조합니다."
            textSize = 13f
            setPadding(0, 0, 0, padding / 2)
        })
        val switches = SafetyAlertType.entries.associateWith { type ->
            Switch(this).apply {
                text = type.label
                textSize = 16f
                isChecked = SafetyAlertSettings.isEnabled(this@MainActivity, type)
                setPadding(0, padding / 4, 0, padding / 4)
            }.also(panel::addView)
        }
        val firstAlertDistance = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                SafetyAlertSettings.speedCameraFirstAlertDistances.map { "과속카메라 첫 안내: ${it}m 전" })
            setSelection(SafetyAlertSettings.speedCameraFirstAlertDistances.indexOf(
                SafetyAlertSettings.speedCameraFirstAlertDistance(this@MainActivity)))
            setPadding(0, padding / 2, 0, padding / 4)
        }
        panel.addView(firstAlertDistance)
        val overspeedTone = Switch(this).apply {
            text = "과속카메라 제한속도 초과 경고음"
            textSize = 16f
            isChecked = SafetyAlertSettings.isOverspeedToneEnabled(this@MainActivity)
            setPadding(0, padding / 2, 0, padding / 4)
        }
        panel.addView(overspeedTone)
        val tonePicker = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, OverspeedToneStyle.entries)
            setSelection(OverspeedToneStyle.entries.indexOf(SafetyAlertSettings.overspeedToneStyle(this@MainActivity)))
        }
        val previewTone = Button(this).apply {
            text = "초과 경고음 미리 듣기"
            isAllCaps = false
            setOnClickListener {
                val style = tonePicker.selectedItem as? OverspeedToneStyle ?: OverspeedToneStyle.SHORT_BEEP
                sounds.playOverspeed(style)
            }
        }
        panel.addView(tonePicker)
        panel.addView(previewTone)
        val scroll = ScrollView(this).apply { addView(panel) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("안전 안내 항목 설정")
            .setView(scroll)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장") { _, _ ->
                SafetyAlertSettings.save(this, switches.mapValues { it.value.isChecked })
                SafetyAlertSettings.saveSpeedCameraFirstAlertDistance(this,
                    SafetyAlertSettings.speedCameraFirstAlertDistances[firstAlertDistance.selectedItemPosition])
                SafetyAlertSettings.saveOverspeedTone(this, overspeedTone.isChecked)
                SafetyAlertSettings.saveOverspeedToneStyle(this,
                    tonePicker.selectedItem as? OverspeedToneStyle ?: OverspeedToneStyle.SHORT_BEEP)
                status.text = "안전 안내 항목 설정을 저장했습니다. 다음 안내부터 적용됩니다."
            }.show()
    }
    private fun checkKakao() {
        if (kakaoCheck?.isActive == true) return
        if (CameraMonitorService.isRunning()) {
            dashboard.kakaoStatus.text = "감시 중에는 카카오 안전운행 피드가 이미 실행 중입니다. 감시를 중지한 뒤 단독 테스트할 수 있습니다."
            return
        }
        kakaoCheck = lifecycleScope.launch {
            dashboard.kakaoStatus.text = "카카오 인증 및 실시간 안전운행 응답 대기 중…"
            val message = try {
                val monitor = KakaoSafetyMonitor()
                try {
                    monitor.start(application)
                    val nearby = monitor.awaitLiveResponse()
                    if (nearby == null) "인증은 완료됐지만 15초 동안 안전운행 응답이 없습니다 · GPS·네트워크를 확인하세요"
                    else "실시간 응답 확인 완료 · 현재 주변 안전 안내 ${nearby}건 수신"
                } finally { monitor.stop() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { e.message ?: "카카오 연결 실패" }
            prefs.edit().putString("kakao_status", message).apply()
            dashboard.kakaoStatus.text = message
        }
    }
    private fun previewCameraAlert() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            status.text = "알림 미리보기를 위해 알림 권한을 허용해 주세요."
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        CameraAlertNotification.show(this, "500m 앞 · 60km/h · 미리보기 도로 · 테스트")
        if (prefs.getBoolean("floating_alert_enabled", false))
            kr.co.tesla.cameraalert.monitor.CameraAlertOverlay.show(this, 500, 60)
        lifecycleScope.launch {
            val geminiSpoken = geminiSpeaker.speakCameraWarning(500, 60)
            if (!geminiSpoken) alertSpeaker.speakCameraWarning(500, 60)
        }
        status.text = "카메라 알림 미리보기를 표시했습니다. 플로팅 권한이 있으면 제한속도 아이콘도 보입니다."
    }
    private fun showMonitoringSettings() {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        val floating = Switch(this).apply {
            text = "단속 시 플로팅 제한속도 아이콘"
            isChecked = prefs.getBoolean("floating_alert_enabled", false)
        }
        panel.addView(floating)
        panel.addView(TextView(this).apply {
            text = "다른 앱 위에 표시 권한이 있어야 하며, 단속 경고 중에만 제한속도와 거리를 띄웁니다."
            setPadding(0, 0, 0, padding / 2)
        })
        val cameraDataStatus = TextView(this).apply {
            text = publicCameraDataStatus()
            textSize = 13f
            setPadding(0, padding, 0, padding / 2)
        }
        panel.addView(cameraDataStatus)
        val updateCameraData = Button(this).apply { text = "카메라 데이터 지금 업데이트" }
        updateCameraData.setOnClickListener { refreshPublicCameraData(updateCameraData, cameraDataStatus) }
        panel.addView(updateCameraData)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("감시 모드 설정")
            .setView(panel)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장") { _, _ ->
                prefs.edit().putBoolean("floating_alert_enabled", floating.isChecked).apply()
                if (!floating.isChecked) kr.co.tesla.cameraalert.monitor.CameraAlertOverlay.hide()
                if (floating.isChecked && !Settings.canDrawOverlays(this)) {
                    status.text = "플로팅 아이콘을 쓰려면 ‘다른 앱 위에 표시’ 권한을 허용해 주세요."
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else status.text = "감시 모드 설정을 저장했습니다."
            }.show()
    }
    private fun publicCameraDataStatus(): String {
        val count = prefs.getInt(CAMERA_DATA_COUNT, 0)
        val updatedAt = prefs.getLong(CAMERA_DATA_UPDATED_AT, 0L)
        return if (updatedAt == 0L || count == 0) "공공데이터 카메라: 아직 저장된 목록 없음"
        else "공공데이터 카메라: ${count}건\n마지막 갱신: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(java.util.Date(updatedAt))}"
    }
    private fun refreshPublicCameraData(button: Button? = null, dataStatus: TextView? = null) {
        button?.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { CameraRepository(this@MainActivity).refresh(BuildConfig.DATA_GO_KR_SERVICE_KEY) }
            result.onSuccess { count ->
                prefs.edit().putLong(CAMERA_DATA_UPDATED_AT, System.currentTimeMillis())
                    .putInt(CAMERA_DATA_COUNT, count).apply()
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity,
                    result.fold({ "공공데이터 카메라 ${it}건을 업데이트했습니다." },
                        { "카메라 데이터 업데이트 실패: ${it.message}" }), Toast.LENGTH_LONG).show()
                if (result.isSuccess) dataStatus?.text = publicCameraDataStatus()
                button?.isEnabled = true
            }
        }
    }
    private fun openGoogleSheet() {
        val sheetId = GoogleSheetsSync.settings(this).sheetId
        if (sheetId.isBlank()) {
            Toast.makeText(this, "먼저 Google Sheets 로그인 · 자동 기록에서 스프레드시트를 연결해 주세요.", Toast.LENGTH_LONG).show()
            return
        }
        val url = "https://docs.google.com/spreadsheets/d/$sheetId/edit"
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, "스프레드시트를 열 수 있는 앱 또는 브라우저를 찾지 못했습니다.", Toast.LENGTH_LONG).show()
        }
    }

    private fun appendSampleTripRow() {
        lifecycleScope.launch {
            val written = GoogleSheetsSync.appendTestRow(this@MainActivity)
            Toast.makeText(this@MainActivity,
                if (written) "샘플 운행을 Google Sheets에 추가했습니다. TEST-WOONGPILOT 행을 확인해 주세요."
                else "샘플 운행 추가에 실패했습니다. Google Sheets 연결과 시트 편집 권한을 확인해 주세요.",
                Toast.LENGTH_LONG).show()
        }
    }
    private fun showGoogleSheetsSettings() {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val saved = GoogleSheetsSync.settings(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        val active = Switch(this).apply {
            text = "운행 종료 시 Google Sheets에 자동 기록"
            isChecked = saved.enabled
        }
        val sheet = EditText(this).apply {
            hint = "Google Sheets URL 또는 스프레드시트 ID"
            setText(saved.sheetId)
            minLines = 2
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        panel.addView(active)
        panel.addView(TextView(this).apply {
            text = "Apps Script URL은 필요하지 않습니다. Google 계정으로 로그인한 뒤, 기록할 스프레드시트 URL을 붙여 넣으세요. 연결 테스트는 해당 파일을 읽을 수 있는지 확인합니다."
            textSize = 13f
            setPadding(0, 0, 0, padding / 2)
        })
        panel.addView(sheet)
        if (saved.accountName.isNotBlank()) panel.addView(TextView(this).apply {
            text = "현재 연결 계정: ${saved.accountName}"
            textSize = 13f
            setPadding(0, padding / 2, 0, 0)
        })
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Google Sheets 자동 기록")
            .setView(panel)
            .setNeutralButton("샘플 운행 추가", null)
            .setNegativeButton("취소", null)
            .setPositiveButton("Google 로그인 · 연결") { _, _ ->
                if (!active.isChecked) {
                    GoogleSheetsSync.disable(this)
                    Toast.makeText(this, "Google Sheets 자동 기록을 껐습니다.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val sheetId = extractSpreadsheetId(sheet.text.toString())
                if (sheetId == null) {
                    Toast.makeText(this, "올바른 Google Sheets URL 또는 스프레드시트 ID를 입력해 주세요.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                pendingGoogleSheetId = sheetId
                val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestEmail().requestScopes(Scope(GoogleSheetsSync.SCOPE)).build()
                googleSheetsLogin.launch(GoogleSignIn.getClient(this, options).signInIntent)
            }.create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                lifecycleScope.launch {
                    val written = GoogleSheetsSync.appendTestRow(this@MainActivity)
                    Toast.makeText(this@MainActivity,
                        if (written) "샘플 운행을 Google Sheets에 추가했습니다. TEST-WOONGPILOT 행을 확인해 주세요."
                        else "샘플 운행 추가에 실패했습니다. Google 로그인과 시트 편집 권한을 확인해 주세요.",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun extractSpreadsheetId(value: String): String? {
        val trimmed = value.trim()
        val fromUrl = Regex("/spreadsheets/d/([A-Za-z0-9_-]+)").find(trimmed)?.groupValues?.getOrNull(1)
        return fromUrl ?: trimmed.takeIf { it.matches(Regex("[A-Za-z0-9_-]{20,}")) }
    }

    /** Retained only for source compatibility with previous local webhook settings. */
    private fun showLegacySheetsSettings() {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val (enabled, savedUrl) = SheetsWebhook.settings(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(padding, padding / 2, padding, 0)
        }
        val active = Switch(this).apply { text = "운행 종료 시 Google Sheets에 자동 기록"; isChecked = enabled }
        val url = EditText(this).apply {
            hint = "Apps Script 웹 앱 URL"; setText(savedUrl); minLines = 2
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        panel.addView(active)
        panel.addView(TextView(this).apply {
            text = "Apps Script를 웹 앱으로 배포한 뒤 URL을 붙여넣으세요. 네트워크가 없으면 기록은 기기에 보관되며 다음 운행 종료 때 재전송합니다."
            textSize = 13f; setPadding(0, 0, 0, padding / 2)
        })
        panel.addView(url)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Google Sheets 자동 기록")
            .setView(panel)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장 · 연결 테스트") { _, _ ->
                SheetsWebhook.save(this, active.isChecked, url.text.toString())
                lifecycleScope.launch {
                    val connected = SheetsWebhook.test(this@MainActivity)
                    Toast.makeText(this@MainActivity,
                        if (connected) "Google Sheets 웹훅 연결을 확인했습니다." else "연결 실패 · Apps Script URL과 배포 권한을 확인하세요.",
                        Toast.LENGTH_LONG).show()
                }
            }.show()
    }
    private fun chooseTeslaVehicle() {
        lifecycleScope.launch {
            dashboard.updateTeslaAccount(true)
            status.text = "Loading Tesla vehicles…"
            val vehicles = runCatching { TeslaAuth.vehicles(this@MainActivity) }.getOrElse {
                status.text = it.message ?: "Could not load Tesla vehicles"
                return@launch
            }
            if (vehicles.isEmpty()) {
                status.text = "No Tesla vehicles were returned for this account."
                return@launch
            }
            val names = vehicles.map { "${it.name} (${it.vin})" }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Select Tesla vehicle")
                .setItems(names) { _, index ->
                    vin.setText(vehicles[index].vin)
                    prefs.edit().putString("vin", vehicles[index].vin)
                        .putString("tesla_vehicle_name", vehicles[index].name)
                        .putString("tesla_vehicle_name_vin", vehicles[index].vin).apply()
                    dashboard.updateTeslaVehicleName(vehicles[index].name)
                    status.text = "Tesla vehicle selected. Register the card key next."
                    startTeslaRefresh()
                }.show()
        }
    }
    private var teslaRefresh: Job? = null
    private var teslaNameLookup: Job? = null
    private fun updateTeslaVehicleName(currentVin: String) {
        if (prefs.getString("tesla_vehicle_name_vin", "") == currentVin &&
            prefs.getString("tesla_vehicle_name", "").orEmpty().isNotBlank()) return
        if (teslaNameLookup?.isActive == true) return
        teslaNameLookup = lifecycleScope.launch {
            val vehicle = runCatching { TeslaAuth.vehicles(this@MainActivity) }
                .getOrNull()?.firstOrNull { it.vin == currentVin } ?: return@launch
            prefs.edit().putString("tesla_vehicle_name", vehicle.name)
                .putString("tesla_vehicle_name_vin", currentVin).apply()
            dashboard.updateTeslaVehicleName(vehicle.name)
        }
    }
    private fun confirmTeslaLogout() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Tesla 계정 연결 해제")
            .setMessage("이 휴대폰의 로그인 세션과 Cloudflare에 저장된 Tesla 토큰을 삭제합니다. 다시 사용하려면 로그인해야 합니다.")
            .setNegativeButton("취소", null)
            .setPositiveButton("연결 해제") { _, _ ->
                teslaRefresh?.cancel(); teslaRefresh = null
                lifecycleScope.launch {
                    dashboard.updateTeslaOverview("", null, "Tesla 계정 연결을 해제하는 중…")
                    runCatching { TeslaAuth.signOut(this@MainActivity) }
                    TeslaVehicleCache.clear(this@MainActivity)
                    dashboard.updateTeslaAccount(false)
                    dashboard.updateTeslaOverview("", null, "Tesla 계정 연결이 해제되었습니다.")
                }
            }.show()
    }
    private fun wakeTeslaAndRefresh() {
        val currentVin = prefs.getString("vin", "").orEmpty()
        if (!TeslaAuth.isSignedIn(this)) {
            dashboard.updateTeslaOverview(currentVin, null, "먼저 Tesla 계정에 로그인하세요.")
            return
        }
        if (currentVin.length != 17) {
            dashboard.updateTeslaOverview(currentVin, null, "먼저 Tesla 차량을 선택하세요.")
            return
        }
        teslaRefresh?.cancel(); teslaRefresh = null
        lifecycleScope.launch {
            dashboard.updateTeslaOverview(currentVin, null, "차량을 깨우는 중…")
            val result = runCatching { TeslaAuth.wakeVehicle(this@MainActivity, currentVin) }
            result.onSuccess {
                dashboard.updateTeslaOverview(currentVin, null, "차량 응답을 기다리는 중…")
                delay(12_000)
                waitForTeslaToWake(currentVin)
            }.onFailure {
                dashboard.updateTeslaOverview(currentVin, null, it.message ?: "차량 깨우기에 실패했습니다.")
            }
        }
    }
    private suspend fun waitForTeslaToWake(currentVin: String) {
        repeat(4) { attempt ->
            val data = runCatching { TeslaAuth.vehicleData(this@MainActivity, currentVin) }
            if (data.isSuccess) {
                renderTeslaData(currentVin, data.getOrThrow(), "차량 온라인 · 수동 새로고침으로 갱신")
                return
            }
            if (attempt < 3) {
                dashboard.updateTeslaOverview(currentVin, null, "차량을 깨우는 중… ${attempt + 1}/4 확인 완료")
                delay(15_000)
            } else {
                dashboard.updateTeslaOverview(currentVin, null, "차량이 아직 Tesla 서버에서 오프라인으로 표시됩니다.")
            }
        }
    }
    private fun renderTeslaData(vin: String, fresh: TeslaAuth.VehicleData, message: String) {
        val previous = TeslaVehicleCache.load(this, vin)?.data
        val merged = TeslaVehicleCache.merge(fresh, previous)
        val snapshot = TeslaVehicleCache.save(this, vin, merged)
        dashboard.updateTeslaOverview(vin, merged, "$message · ${lastTeslaUpdateMessage(snapshot.updatedAt)}")
    }
    private fun lastTeslaUpdateMessage(updatedAt: Long): String =
        "마지막 갱신: ${java.text.SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(java.util.Date(updatedAt))}"

    private fun startTeslaRefresh() {
        teslaRefresh?.cancel()
        val currentVin = prefs.getString("vin", "").orEmpty()
        val signedIn = TeslaAuth.isSignedIn(this)
        dashboard.updateTeslaAccount(signedIn)
        if (!signedIn) {
            dashboard.updateTeslaOverview(currentVin, null, "Tesla 계정 로그인 후 차량 정보를 표시합니다.")
            return
        }
        if (currentVin.length != 17) {
            dashboard.updateTeslaOverview(currentVin, null, "Tesla 차량을 선택하면 정보를 표시합니다.")
            return
        }
        updateTeslaVehicleName(currentVin)
        teslaRefresh = lifecycleScope.launch {
            val data = runCatching { TeslaAuth.vehicleData(this@MainActivity, currentVin) }
            data.onSuccess {
                renderTeslaData(currentVin, it, "새로고침 완료")
            }.onFailure {
                dashboard.updateTeslaOverview(currentVin, null,
                    "마지막 차량 정보를 표시 중 · ${it.message ?: "새로고침에 실패했습니다."}")
            }
        }
    }
    private fun requiredPermissions(includeBluetooth: Boolean): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (includeBluetooth && Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }.toTypedArray()
    private fun clearPairingForReregistration() {
        val value = vin.text.toString().trim().uppercase(Locale.ROOT)
        if (!TeslaProtocol.validVin(value)) { status.text = "삭제할 차량의 VIN을 먼저 입력하세요."; return }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("앱 키 등록 삭제")
            .setMessage("앱에 저장된 등록 상태와 이 휴대폰의 차량 키를 삭제합니다. 차량 화면의 키 목록은 바뀌지 않으므로, 기존 키가 남아 있다면 차량에서 별도로 삭제하세요. 이후 카드키로 새로 등록할 수 있습니다.")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                runCatching { VehicleKey.remove(value) }
                prefs.edit().remove("pairedVin").apply()
                dashboard.updatePairing(false)
                dashboard.updatePairingProgress(false, "")
                status.text = "앱 키 등록을 삭제했습니다. 카드키로 새로 등록하세요."
            }.show()
    }
    private fun begin(pair: Boolean) {
        val value = vin.text.toString().trim().uppercase(Locale.ROOT)
        if (!TeslaProtocol.validVin(value)) { status.text = "VIN 17자리를 확인하세요 (I, O, Q 제외)."; return }
        if (pair) dashboard.updatePairingProgress(false, "등록 시작")
        vin.setText(value)
        prefs.edit().putString("vin", value).apply()
        val permissions = requiredPermissions(includeBluetooth = pair)
        if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            pendingPair = pair; this.permissions.launch(permissions); return
        }
        launchMonitor(pair)
    }
    private fun launchMonitor(pair: Boolean) {
        // Pairing uses BLE once; ordinary monitoring is explicitly started by this caller.
        ContextCompat.startForegroundService(this, Intent(this, CameraMonitorService::class.java)
            .putExtra("pair", pair))
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    override fun onStart() {
        super.onStart(); prefs.registerOnSharedPreferenceChangeListener(listener)
        startTripRecorderIfEnabled()
        status.text = prefs.getString("status", "차량 키 등록부터 시작하세요.")
        dashboard.kakaoStatus.text = prefs.getString("kakao_status", "카카오 연결 확인 중…")
    }
    private fun startTripRecorderIfEnabled() {
        if (!prefs.getBoolean("trip_auto_enabled", false) ||
            !TeslaAuth.isSignedIn(this) ||
            prefs.getString("vin", "").orEmpty().length != 17) return
        ContextCompat.startForegroundService(this, Intent(this, kr.co.tesla.cameraalert.trip.TripMonitorService::class.java))
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (TeslaAuth.acceptCallback(this, intent)) {
            status.text = "Tesla sign-in completed. Choose your VIN to continue."
            chooseTeslaVehicle()
        }
    }
    override fun onStop() {
        teslaRefresh?.cancel(); teslaRefresh = null
        prefs.unregisterOnSharedPreferenceChangeListener(listener); super.onStop()
    }
    override fun onDestroy() {
        sounds.release()
        super.onDestroy()
    }
}

