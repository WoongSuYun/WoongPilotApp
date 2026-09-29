package kr.co.tesla.cameraalert

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kr.co.tesla.cameraalert.route.RouteLedger
import kr.co.tesla.cameraalert.route.RouteRecord
import java.text.SimpleDateFormat
import java.util.*

class RouteLogActivity : AppCompatActivity() {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "감시 경로 기록"
        render()
    }
    override fun onResume() { super.onResume(); render() }

    private fun render() {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(28)) }
        list.addView(TextView(this).apply { text = "감시 경로 기록"; textSize = 26f; setTextColor(Color.rgb(20, 30, 42)) })
        list.addView(TextView(this).apply {
            text = "감시 시작부터 종료까지 휴대폰 GPS로 기록한 이동거리와 경로입니다. 지도는 설치된 지도 앱 또는 브라우저에서 열립니다."
            textSize = 14f; setTextColor(Color.DKGRAY); setPadding(0, dp(8), 0, dp(16))
        })
        list.addView(Button(this).apply {
            text = "예시 경로 추가"; isAllCaps = false
            setOnClickListener { RouteLedger.addSample(this@RouteLogActivity); render() }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        val records = RouteLedger.records(this)
        if (records.isEmpty()) list.addView(TextView(this).apply { text = "아직 저장된 경로가 없습니다. 감시를 시작하고 이동한 뒤 종료해 보세요."; textSize = 16f })
        records.forEach { record -> list.addView(routeCard(record), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.rgb(245, 247, 250)); addView(list) })
    }

    private fun routeCard(record: RouteRecord) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); setPadding(dp(16), dp(14), dp(16), dp(14))
        addView(TextView(this@RouteLogActivity).apply { text = format(record.startedAt); textSize = 17f; setTextColor(Color.rgb(15, 85, 70)) })
        addView(TextView(this@RouteLogActivity).apply { text = "${format(record.startedAt)} ~ ${format(record.endedAt)}  ·  ${"%.1f".format(record.distanceKm)} km"; textSize = 14f; setPadding(0, dp(6), 0, dp(10)) })
        addView(Button(this@RouteLogActivity).apply { text = "지도에서 경로 보기"; isAllCaps = false; setOnClickListener { openMap(record) } })
        addView(Button(this@RouteLogActivity).apply {
            text = "기록 삭제"; isAllCaps = false
            setOnClickListener {
                AlertDialog.Builder(this@RouteLogActivity)
                    .setTitle("경로 기록 삭제")
                    .setMessage("이 경로 기록을 삭제할까요? 삭제 후에는 되돌릴 수 없습니다.")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("삭제") { _, _ ->
                        RouteLedger.delete(this@RouteLogActivity, record.startedAt)
                        render()
                    }.show()
            }
        })
    }

    private fun openMap(record: RouteRecord) {
        startActivity(Intent(this, RouteMapActivity::class.java)
            .putExtra(RouteMapActivity.EXTRA_STARTED_AT, record.startedAt))
    }
    private fun format(time: Long) = SimpleDateFormat("M.d HH:mm", Locale.KOREA).format(Date(time))
}
