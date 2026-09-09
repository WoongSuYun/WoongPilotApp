package kr.co.tesla.cameraalert

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Read-only Fleet API screen. Live data is fetched only when the user taps refresh. */
class VehicleInfoActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var details: LinearLayout
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float = 15f, color: Int = Color.rgb(235, 241, 247)) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setLineSpacing(dp(3).toFloat(), 1f)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "차량 정보"
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(24), dp(22), dp(28)); setBackgroundColor(Color.rgb(12, 18, 27))
        }
        body.addView(label("TESLA VEHICLE", 11f, Color.rgb(114, 235, 198)))
        body.addView(label("차량 정보", 26f))
        body.addView(label("배터리와 차량 상태는 새로고침할 때만 조회합니다.", 13f, Color.rgb(149, 164, 180)).apply { setPadding(0, dp(6), 0, dp(18)) })
        status = label("준비됨", 14f, Color.rgb(149, 164, 180))
        body.addView(status)
        body.addView(Button(this).apply {
            text = "실시간 정보 새로고침"; isAllCaps = false; setOnClickListener { refresh() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(14) })
        details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(details)
        setContentView(ScrollView(this).apply { addView(body) })
        refresh()
    }

    private fun refresh() {
        val vin = prefs.getString("vin", "").orEmpty()
        if (!TeslaAuth.isSignedIn(this)) { status.text = "Tesla 계정 로그인이 필요합니다."; return }
        if (vin.length != 17) { status.text = "먼저 Tesla 차량을 선택해 VIN을 설정하세요."; return }
        lifecycleScope.launch {
            status.text = "차량 정보를 불러오는 중…"
            val data = runCatching { TeslaAuth.vehicleData(this@VehicleInfoActivity, vin) }.getOrElse {
                status.text = it.message ?: "차량 정보를 불러오지 못했습니다."; return@launch
            }
            status.text = "마지막 조회: 지금"
            details.removeAllViews()
            fun row(name: String, value: String?) {
                if (value == null) return
                details.addView(label(name, 12f, Color.rgb(114, 235, 198)).apply { setPadding(0, dp(10), 0, dp(2)) })
                details.addView(label(value, 18f))
            }
            row("배터리", data.batteryPercent?.let { "$it%" })
            row("사용 가능 배터리", data.usableBatteryPercent?.let { "$it%" })
            row("예상 주행 가능 거리", data.rangeKm?.let { "$it km" })
            row("충전 상태", data.chargeState)
            row("충전 전력", data.chargingPowerKw?.let { "$it kW" })
            row("주행 거리", data.odometerKm?.let { "$it km" })
            row("차종", listOfNotNull(data.model, data.trim).joinToString(" ").ifBlank { null })
            row("외장 색상", data.color)
            row("도어 잠금", data.locked?.let { if (it) "잠김" else "잠금 해제" })
            row("소프트웨어 업데이트", data.softwareStatus)
        }
    }
}
