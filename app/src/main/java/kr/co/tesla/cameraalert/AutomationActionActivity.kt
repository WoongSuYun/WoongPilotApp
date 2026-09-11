package kr.co.tesla.cameraalert

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import kr.co.tesla.cameraalert.monitor.CameraMonitorService

/**
 * Entry point for launcher shortcuts and compatible automation apps.
 * It intentionally has no UI: it performs the requested monitoring action and closes.
 */
class AutomationActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.action) {
            ACTION_START_MONITORING -> {
                CameraMonitorService.start(this)
                getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putString("status", "자동화 요청으로 감시를 시작했습니다.").apply()
            }
            ACTION_STOP_MONITORING -> {
                CameraMonitorService.stop(this)
                getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putString("status", "자동화 요청으로 감시를 종료했습니다.").apply()
            }
        }
        finish()
    }

    companion object {
        const val ACTION_START_MONITORING = "kr.co.tesla.cameraalert.action.START_MONITORING"
        const val ACTION_STOP_MONITORING = "kr.co.tesla.cameraalert.action.STOP_MONITORING"
    }
}
