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
    private var lastHeading: Double? = null
    private var lastHeadingAt: Long = 0L
    private var turnReferenceHeading: Double? = null
    private var recentTurnAt: Long = Long.MIN_VALUE

    /** Records GPS heading even when no camera is currently selected. */
    fun observeHeading(heading: Double, now: Long) {
        val previous = lastHeading
        val reference = turnReferenceHeading
        if (previous == null || now - lastHeadingAt !in 0..HEADING_SAMPLE_MAX_AGE_MS) {
            turnReferenceHeading = heading
        } else if (reference != null && headingDifference(heading, reference) > SHARP_TURN_DEGREES) {
            // GPS bearing can change gradually over several fixes while turning. Compare with
            // the heading at the beginning of the sample window, not only the previous fix.
            recentTurnAt = now
            turnReferenceHeading = heading
        }
        lastHeading = heading
        lastHeadingAt = now
    }
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
        // A speed camera remains relevant while the car waits at a signal.  Expiring its
        // history by time alone made a stop longer than two minutes look like a newly found
        // camera and replay the configured first-distance announcement (for example 500 m).
        // It is removed explicitly after the vehicle passes it or leaves its route.
        recent.removeAll {
            it.event.match.type != SafetyAlertType.SPEED_CAMERA && now - it.at !in 0..120_000
        }
        val safety = event.match
        // These two everyday notices are intentionally close-range only.  Until the car is
        // within 100 m, leave no history entry so the first in-range fix can announce "100 m".
        if (safety.type in setOf(SafetyAlertType.CHILDREN_ZONE, SafetyAlertType.SPEED_BUMP) &&
            safety.distanceMeters > 100.0) return null
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
        val newlyRevealedAfterTurn = safety.type == SafetyAlertType.SPEED_CAMERA && related.isEmpty() &&
            now - recentTurnAt in 0..RECENT_TURN_WINDOW_MS
        recent.add(Entry(event, heading, now))
        return when {
            // Safety-zone guidance should sound like navigation guidance too.  Never speak
            // arbitrary GPS values such as "123 m ahead" for a children zone or sharp turn.
            safety.type != SafetyAlertType.SPEED_CAMERA -> roundToNearestHundred(safety.distanceMeters)
            reversedDirection || newlyRevealedAfterTurn -> roundToNearestHundred(safety.distanceMeters)
            else -> speedCameraFirstAlertDistance
        }
    }

    fun shouldAlert(event: SourcedMatch, heading: Double, now: Long): Boolean =
        alertDistanceMeters(event, heading, now) != null

    /** Releases the deduplication record once this camera is no longer ahead of the vehicle. */
    fun forgetSpeedCamera(id: String, latitude: Double, longitude: Double) {
        recent.removeAll {
            val other = it.event.match
            other.type == SafetyAlertType.SPEED_CAMERA &&
                (other.id == id || CameraDetector.distanceMeters(latitude, longitude, other.latitude, other.longitude) < 60)
        }
    }

    /** Re-enables a camera after a curved route temporarily moved away from it. */
    fun reapproachSpeedCamera(id: String, latitude: Double, longitude: Double, now: Long) {
        forgetSpeedCamera(id, latitude, longitude)
        // This is a continuation of an already announced approach, not a new 500/700 m
        // discovery.  Use the current rounded distance when it is announced again.
        recentTurnAt = now
    }

    private fun sameDirection(first: Double, second: Double): Boolean =
        headingDifference(first, second) <= 45

    private fun headingDifference(first: Double, second: Double): Double =
        kotlin.math.abs((first - second + 540) % 360 - 180)

    private fun roundToNearestHundred(distanceMeters: Double): Int =
        (kotlin.math.round(distanceMeters.coerceAtLeast(1.0) / 100.0) * 100).toInt().coerceAtLeast(100)

    private companion object {
        const val HEADING_SAMPLE_MAX_AGE_MS = 10_000L
        const val RECENT_TURN_WINDOW_MS = 15_000L
        const val SHARP_TURN_DEGREES = 60.0
    }
}
