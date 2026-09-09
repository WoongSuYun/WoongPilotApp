package kr.co.tesla.cameraalert.model

import android.content.Context

enum class OverspeedToneStyle(val label: String) {
    SHORT_BEEP("짧은 띵"),
    DOUBLE_BEEP("이중 알림"),
    ACK("확인음"),
    URGENT("강한 경고");

    override fun toString(): String = label
}

/** Alert types supplied by Kakao's on-route safety feed. */
enum class SafetyAlertType(
    val label: String,
    val defaultEnabled: Boolean,
    val maxDistanceMeters: Double
) {
    SPEED_CAMERA("과속·신호 단속 카메라", true, 700.0),
    SPEED_BUMP("과속 방지턱", true, 180.0),
    CHILDREN_ZONE("어린이 보호구역", true, 350.0),
    CHILDREN_ACCIDENT("어린이 사고 다발 구간", false, 350.0),
    ACCIDENT_PRONE("교통사고 다발 구간", false, 450.0),
    SHARP_TURN("급회전 구간", false, 300.0),
    RAILROAD_CROSSING("철도 건널목", false, 300.0),
    SLIPPERY_ROAD("미끄럼 주의 구간", false, 350.0),
    BUS_LANE("버스전용차로", false, 500.0);

    fun spokenText(distanceMeters: Int, limitKph: Int?): String {
        val distance = distanceMeters.coerceAtLeast(1)
        return when (this) {
            SPEED_CAMERA -> "전방 ${distance}미터, 제한속도 ${(limitKph ?: 0).coerceAtLeast(1)}킬로미터, 과속 단속 카메라입니다."
            SPEED_BUMP -> "전방 ${distance}미터, 과속 방지턱이 있습니다."
            CHILDREN_ZONE -> "전방 ${distance}미터, 어린이 보호구역입니다."
            CHILDREN_ACCIDENT -> "전방 ${distance}미터, 어린이 사고 다발 구간입니다."
            ACCIDENT_PRONE -> "전방 ${distance}미터, 교통사고 다발 구간입니다."
            SHARP_TURN -> "전방 ${distance}미터, 급회전 구간입니다."
            RAILROAD_CROSSING -> "전방 ${distance}미터, 철도 건널목이 있습니다."
            SLIPPERY_ROAD -> "전방 ${distance}미터, 미끄럼 주의 구간입니다."
            BUS_LANE -> "버스전용차로 구간입니다. 차로를 확인하세요."
        }
    }
}

data class SafetyMatch(
    val id: String,
    val type: SafetyAlertType,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Double,
    val limitKph: Int?,
    val roadName: String
)

/** Per-alert switches; defaults favor the three everyday driving notices. */
object SafetyAlertSettings {
    private const val PREFS = "safety_alert_settings"
    private const val OVERSPEED_TONE = "overspeed_tone"
    private const val OVERSPEED_TONE_STYLE = "overspeed_tone_style"
    private const val SPEED_CAMERA_FIRST_ALERT_DISTANCE = "speed_camera_first_alert_distance"

    /** The distances offered in the UI are deliberately fixed navigation-style milestones. */
    val speedCameraFirstAlertDistances = listOf(300, 500, 700)

    fun speedCameraFirstAlertDistance(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(SPEED_CAMERA_FIRST_ALERT_DISTANCE, 700)
            .takeIf { it in speedCameraFirstAlertDistances } ?: 700

    fun saveSpeedCameraFirstAlertDistance(context: Context, distanceMeters: Int) {
        require(distanceMeters in speedCameraFirstAlertDistances)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(SPEED_CAMERA_FIRST_ALERT_DISTANCE, distanceMeters).apply()
    }

    fun isEnabled(context: Context, type: SafetyAlertType): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(type.name, type.defaultEnabled)

    fun enabledTypes(context: Context): Set<SafetyAlertType> =
        SafetyAlertType.entries.filterTo(linkedSetOf()) { isEnabled(context, it) }

    fun save(context: Context, values: Map<SafetyAlertType, Boolean>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            values.forEach { (type, enabled) -> putBoolean(type.name, enabled) }
        }.apply()
    }
    fun isOverspeedToneEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(OVERSPEED_TONE, true)
    fun saveOverspeedTone(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(OVERSPEED_TONE, enabled).apply()
    }
    fun overspeedToneStyle(context: Context): OverspeedToneStyle = runCatching {
        OverspeedToneStyle.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(OVERSPEED_TONE_STYLE, OverspeedToneStyle.SHORT_BEEP.name).orEmpty())
    }.getOrDefault(OverspeedToneStyle.SHORT_BEEP)
    fun saveOverspeedToneStyle(context: Context, style: OverspeedToneStyle) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(OVERSPEED_TONE_STYLE, style.name).apply()
    }
}
