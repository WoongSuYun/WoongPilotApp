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
    /**
     * Returns the distance that should be announced, or null for an already-announced camera.
     *
     * A normal approach is announced at the user's selected milestone rather than at the
     * arbitrary GPS sample that happened to discover it.  When the driver reverses direction
     * (for example after a U-turn), use a 100 m milestone so the new approach is never spoken
     * as values such as 132 m or 232 m.
     */
    fun alertDistanceMeters(
        event: SourcedMatch,
        heading: Double,
        now: Long,
        speedCameraFirstAlertDistance: Int = 700
    ): Int? {
        recent.removeAll { now - it.at !in 0..120_000 }
        val safety = event.match
        val related = recent.filter {
            val other = it.event.match
            safety.type == other.type && ((event.source == it.event.source && safety.id == other.id) ||
                CameraDetector.distanceMeters(safety.latitude, safety.longitude, other.latitude, other.longitude) < 60)
        }
        val duplicate = related.any { sameDirection(heading, it.heading) }
        if (duplicate) return null

        // Do not announce a normal camera approach before its selected milestone is crossed.
        if (safety.type == SafetyAlertType.SPEED_CAMERA && related.isEmpty() &&
            safety.distanceMeters > speedCameraFirstAlertDistance) return null

        val reversedDirection = related.isNotEmpty()
        recent.add(Entry(event, heading, now))
        return when {
            safety.type != SafetyAlertType.SPEED_CAMERA -> safety.distanceMeters.toInt().coerceAtLeast(1)
            reversedDirection -> roundUpToHundred(safety.distanceMeters)
            else -> speedCameraFirstAlertDistance
        }
    }

    fun shouldAlert(event: SourcedMatch, heading: Double, now: Long): Boolean =
        alertDistanceMeters(event, heading, now) != null

    private fun sameDirection(first: Double, second: Double): Boolean =
        kotlin.math.abs((first - second + 540) % 360 - 180) <= 45

    private fun roundUpToHundred(distanceMeters: Double): Int =
        (kotlin.math.ceil(distanceMeters.coerceAtLeast(1.0) / 100.0) * 100).toInt()
}
