package kr.co.tesla.cameraalert

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kr.co.tesla.cameraalert.trip.TripLedger
import kr.co.tesla.cameraalert.trip.TripMonitorService
import java.text.SimpleDateFormat
import java.util.*

class TripLogActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val body by lazy { LinearLayout(this).apply { orientation = LinearLayout.VERTICAL } }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float = 14f, color: Int = Color.rgb(230, 235, 240)) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0, dp(6), 0, dp(6))
    }
    private fun surface(color: Int = Color.rgb(24, 33, 45)) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.rgb(48, 63, 80))
    }
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = surface(); setPadding(dp(16), dp(14), dp(16), dp(14))
        body.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }
    private fun action(value: String, primary: Boolean = false, click: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; setTextColor(if (primary) Color.rgb(13, 33, 29) else Color.WHITE)
        background = surface(if (primary) Color.rgb(114, 235, 198) else Color.rgb(34, 46, 61)); setOnClickListener { click() }
    }
    private fun tripMetric(labelText: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = surface(Color.rgb(18, 48, 58)); setPadding(dp(12), dp(10), dp(12), dp(10))
        addView(label(labelText, 11f, Color.LTGRAY).apply { typeface = Typeface.DEFAULT_BOLD })
        addView(label(value, 22f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD })
    }
    private fun duration(startedAt: Long, endedAt: Long): String {
        val minutes = ((endedAt - startedAt) / 60_000).coerceAtLeast(0)
        return if (minutes >= 60) "${minutes / 60}시간 ${minutes % 60}분" else "${minutes}분"
    }
    private fun battery(trip: kr.co.tesla.cameraalert.trip.TripRecord): String = when {
        trip.batteryStartPercent != null && trip.batteryEndPercent != null ->
            "배터리 ${trip.batteryStartPercent}% → ${trip.batteryEndPercent}% · ${trip.batteryUsedPercent ?: 0}% 사용"
        trip.batteryUsedPercent != null -> "배터리 ${trip.batteryUsedPercent}% 사용"
        else -> "배터리 변화 없음"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "차계부"
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(12, 18, 27)); addView(body, LinearLayout.LayoutParams(-1, -2))
        })
        render()
    }
    override fun onResume() { super.onResume(); render() }

    private fun render() {
        body.removeAllViews(); body.setPadding(dp(20), dp(20), dp(20), dp(28))
        body.addView(label("차계부", 28f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        body.addView(label("Tesla 차량 데이터 기반 자동 운행 기록", 13f, Color.rgb(114, 235, 198)))
        val active = TripLedger.active(this)
        card().apply {
            addView(label(if (active == null) "다음 운행을 기다리고 있어요" else "운행을 자동 기록하고 있어요", 18f, Color.WHITE).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(label(if (active == null) "자동 기록을 켜면 D/R 진입과 P 주차를 감지합니다." else "시작 ${format(active.optLong("startedAt"))} · P 주차 후 자동 저장", 13f, Color.LTGRAY))
            val controls = LinearLayout(this@TripLogActivity).apply { orientation = LinearLayout.HORIZONTAL }
            controls.addView(action("자동 기록 시작", true) {
            prefs.edit().putBoolean("trip_auto_enabled", true).apply()
            ContextCompat.startForegroundService(this@TripLogActivity, Intent(this@TripLogActivity, TripMonitorService::class.java))
            Toast.makeText(this@TripLogActivity, "차계부 자동 기록을 시작했습니다.", Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(0, -2, 1f))
            controls.addView(action("중지") {
            prefs.edit().putBoolean("trip_auto_enabled", false).apply()
            stopService(Intent(this@TripLogActivity, TripMonitorService::class.java))
            Toast.makeText(this@TripLogActivity, "자동 기록을 중지했습니다.", Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            addView(controls)
        }
        val vin = prefs.getString("vin", "").orEmpty()
        val records = TripLedger.records(this, vin)
        val totalDistance = records.sumOf { it.distanceKm }
        val totalKwh = records.mapNotNull { it.estimatedKwh }.sum()
        val totalMinutes = records.sumOf { ((it.endedAt - it.startedAt) / 60_000).coerceAtLeast(0) }
        val efficiency = if (totalKwh > 0) totalDistance / totalKwh else null
        val batteryUsed = records.mapNotNull { it.batteryUsedPercent }.sum()
        card().apply {
            addView(label("누적 운행", 13f, Color.rgb(114, 235, 198)))
            val first = LinearLayout(this@TripLogActivity).apply { orientation = LinearLayout.HORIZONTAL }
            first.addView(tripMetric("누적 거리", "${"%.1f".format(totalDistance)} km"), LinearLayout.LayoutParams(0, -2, 1f))
            first.addView(tripMetric("운행 시간", durationText(totalMinutes)), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            addView(first, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            val second = LinearLayout(this@TripLogActivity).apply { orientation = LinearLayout.HORIZONTAL }
            second.addView(tripMetric("사용 에너지", "${"%.1f".format(totalKwh)} kWh"), LinearLayout.LayoutParams(0, -2, 1f))
            second.addView(tripMetric("평균 전비", efficiency?.let { "${"%.1f".format(it)} km/kWh" } ?: "—"), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            addView(second, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(label("${records.size}회 운행 · 배터리 총 ${batteryUsed}% 사용", 13f, Color.LTGRAY))
        }
        body.addView(label("운행 기록", 18f).apply { typeface = Typeface.DEFAULT_BOLD })
        body.addView(label("전비는 배터리 % 변화와 차종별 추정 용량으로 계산합니다.", 12f, Color.LTGRAY))
        if (records.isEmpty()) body.addView(label("아직 저장된 운행이 없습니다. 자동 기록을 켠 뒤 주행해 보세요.", 15f, Color.LTGRAY))
        records.forEach { trip ->
            card().apply {
                addView(label(format(trip.startedAt), 13f, Color.rgb(114, 235, 198)).apply { typeface = Typeface.DEFAULT_BOLD })
                addView(label("${format(trip.startedAt)} → ${format(trip.endedAt)}", 12f, Color.LTGRAY))
                val first = LinearLayout(this@TripLogActivity).apply { orientation = LinearLayout.HORIZONTAL }
                first.addView(tripMetric("거리", "${"%.1f".format(trip.distanceKm)} km"), LinearLayout.LayoutParams(0, -2, 1f))
                first.addView(tripMetric("시간", duration(trip.startedAt, trip.endedAt)), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
                addView(first, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                val second = LinearLayout(this@TripLogActivity).apply { orientation = LinearLayout.HORIZONTAL }
                second.addView(tripMetric("에너지", trip.estimatedKwh?.let { "${"%.1f".format(it)} kWh" } ?: "—"), LinearLayout.LayoutParams(0, -2, 1f))
                second.addView(tripMetric("전비", trip.kmPerKwh?.let { "${"%.1f".format(it)} km/kWh" } ?: "—"), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
                addView(second, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                addView(label(battery(trip), 14f, Color.rgb(200, 215, 225)).apply { typeface = Typeface.DEFAULT_BOLD })
            }
        }
    }
    private fun format(time: Long) = SimpleDateFormat("M.d HH:mm", Locale.KOREA).format(Date(time))
    private fun durationText(minutes: Long): String =
        if (minutes >= 60) "${minutes / 60}시간 ${minutes % 60}분" else "${minutes}분"
}
