package kr.co.tesla.cameraalert.monitor

import kr.co.tesla.cameraalert.model.*

enum class CameraSource(val label: String) { KAKAO("카카오"), PUBLIC("공공데이터") }
data class SourcedMatch(val match: SafetyMatch, val source: CameraSource)

/** A healthy empty Kakao result means no warning; it is not a provider failure. */
object HybridAlerts {
    fun kakaoFresh(now: Long, updatedAt: Long?, online: Boolean): Boolean =
        online && updatedAt != null && now - updatedAt in 0..10_000

    fun select(kakaoHealthy: Boolean, kakao: SafetyMatch?, public: CameraMatch?): SourcedMatch? =
        if (kakaoHealthy) kakao?.let { SourcedMatch(it, CameraSource.KAKAO) }
        else public?.let {
            SourcedMatch(SafetyMatch(it.camera.id, SafetyAlertType.SPEED_CAMERA,
                it.camera.latitude, it.camera.longitude, it.distanceMeters, it.camera.limitKph, it.camera.roadName), CameraSource.PUBLIC)
        }
}

/** Keep warning history across provider switches without retaining Kakao's data on disk. */
class CameraAlertGate {
    private data class Entry(val event: SourcedMatch, val heading: Double, val at: Long)
    private val recent = mutableListOf<Entry>()
    fun shouldAlert(event: SourcedMatch, heading: Double, now: Long): Boolean {
        recent.removeAll { now - it.at !in 0..120_000 }
        val safety = event.match
        val duplicate = recent.any {
            val other = it.event.match
            val sameDirection = kotlin.math.abs((heading - it.heading + 540) % 360 - 180) <= 45
            sameDirection && safety.type == other.type && ((event.source == it.event.source && safety.id == other.id) ||
                CameraDetector.distanceMeters(safety.latitude, safety.longitude, other.latitude, other.longitude) < 60)
        }
        if (!duplicate) recent.add(Entry(event, heading, now))
        return !duplicate
    }
}
