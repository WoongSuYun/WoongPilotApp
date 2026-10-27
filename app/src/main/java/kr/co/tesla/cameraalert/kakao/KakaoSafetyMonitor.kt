package kr.co.tesla.cameraalert.kakao

import android.app.Application
import android.os.SystemClock
import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.KNRoutePriority
import com.kakaomobility.knsdk.guidance.knguidance.*
import com.kakaomobility.knsdk.guidance.knguidance.locationguide.KNGuide_Location
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.KNGuide_Safety
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.*
import com.kakaomobility.knsdk.guidance.knguidance.voiceguide.KNGuide_Voice
import com.kakaomobility.knsdk.guidance.knguidance.voiceguide.KNVoiceCode
import kr.co.tesla.cameraalert.model.SafetyAlertType
import kr.co.tesla.cameraalert.model.SafetyMatch
import kr.co.tesla.cameraalert.model.VehiclePosition
import kr.co.tesla.cameraalert.monitor.HybridAlerts
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Emits only safety events for which KNSDK itself requested a voice guidance. */
class KakaoSafetyMonitor : KNGuidance_SafetyGuideDelegate, KNGuidance_LocationGuideDelegate,
    KNGuidance_VoiceGuideDelegate {
    private var guidance: KNGuidance? = null
    private var running = false
    private var safetyReceived = false
    private var latest = emptyList<KNSafety>()
    private var updatedAt: Long? = null
    private var busLaneGuideAt: Long? = null

    var onSafetyVoiceRequested: ((SafetyMatch) -> Unit)? = null
    /** True means the app deliberately delegates this guidance voice to KNSDK. */
    var useKakaoVoice: (() -> Boolean)? = null

    suspend fun start(application: Application) {
        KakaoRuntime.prepare(application)
        currentCoroutineContext().ensureActive()
        val guide = KNSDK.sharedGuidance() ?: error("Kakao safety guidance is unavailable")
        guidance = guide
        running = true
        guide.safetyGuideDelegate = this
        guide.locationGuideDelegate = this
        guide.voiceGuideDelegate = this
        guide.useDirSound = false
        guide.useBackgroundUpdate = true
        guide.judgeOverSpeedAlert = { _, _, _, _, _ -> false }
        guide.startWithTrip(null, KNRoutePriority.KNRoutePriority_Recommand, 0)
    }

    fun healthy(online: Boolean): Boolean = running && safetyReceived &&
        HybridAlerts.kakaoFresh(SystemClock.elapsedRealtime(), updatedAt, online)

    suspend fun awaitLiveResponse(timeoutMs: Long = 15_000): Int? = withTimeoutOrNull(timeoutMs) {
        while (!safetyReceived && updatedAt == null) delay(250)
        latest.size
    }

    override fun guidanceDidUpdateSafetyGuide(guidance: KNGuidance, safetyGuide: KNGuide_Safety?) {
        if (!running) return
        safetyReceived = true
        latest = safetyGuide?.safetiesOnGuide.orEmpty().toList()
    }

    override fun guidanceDidUpdateAroundSafeties(guidance: KNGuidance, safeties: List<KNSafety>?) = Unit

    override fun guidanceDidUpdateLocation(guidance: KNGuidance, locationGuide: KNGuide_Location) {
        if (!running) return
        latest = guidance.safetyGuide?.safetiesOnGuide.orEmpty().toList()
        updatedAt = SystemClock.elapsedRealtime()
    }

    private fun currentSafetyForVoice(): SafetyMatch? = latest.asSequence()
        .filter { !it.passed }
        .mapNotNull { safety -> runCatching {
            val type = alertType(safety.code) ?: return@runCatching null
            val point = safety.location.pos
            val wgs = KNSDK.convertKATECToWGS84(point.x.toInt(), point.y.toInt())
            if (wgs.y !in 33.0..39.5 || wgs.x !in 124.0..132.0) return@runCatching null
            val meters = guidance?.locationGuide?.location?.distToLocation(safety.location)
                ?.takeIf { it >= 0 }?.toDouble() ?: return@runCatching null
            val limit = (safety as? KNSafety_Camera)?.speedLimit?.takeIf { it in 10..130 }
            if (type == SafetyAlertType.SPEED_CAMERA && limit == null) return@runCatching null
            SafetyMatch("kakao_${safety.safetyId}", type, wgs.y, wgs.x, meters, limit,
                safety.location.roadName ?: type.label)
        }.getOrNull() }.minByOrNull { it.distanceMeters }

    /** Compatibility accessor for the retired GPS-derived path; it is not an alert trigger. */
    fun nearest(position: VehiclePosition, enabledTypes: Set<SafetyAlertType>): SafetyMatch? =
        currentSafetyForVoice()?.takeIf { it.type in enabledTypes }

    override fun shouldPlayVoiceGuide(
        guidance: KNGuidance,
        voiceGuide: KNGuide_Voice,
        strings: MutableList<ByteArray>
    ): Boolean {
        if (useKakaoVoice?.invoke() == true) return true
        val busLane = voiceGuide.voiceCode == KNVoiceCode.KNVoiceCode_BusLaneGuide
        if (busLane) busLaneGuideAt = SystemClock.elapsedRealtime()
        // This is KNSDK's final map-matched decision. Suppress its audio and substitute ours.
        val event = if (busLane) busLaneGuideAt?.let {
            SafetyMatch("kakao_bus_lane_$it", SafetyAlertType.BUS_LANE, 0.0, 0.0, 0.0, null, "Kakao guidance")
        } else currentSafetyForVoice()
        event?.let { onSafetyVoiceRequested?.invoke(it) }
        return false
    }

    override fun willPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice) = Unit
    override fun didFinishPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice) = Unit

    fun stop() {
        running = false
        updatedAt = null
        busLaneGuideAt = null
        safetyReceived = false
        latest = emptyList()
        onSafetyVoiceRequested = null
        useKakaoVoice = null
        guidance?.let { guide ->
            runCatching { guide.stop() }
            guide.safetyGuideDelegate = null
            guide.locationGuideDelegate = null
            guide.voiceGuideDelegate = null
        }
        guidance = null
    }

    companion object {
        private val speedCodes = setOf(
            KNSafetyCode.KNSafetyCode_SpeedViolationCamera,
            KNSafetyCode.KNSafetyCode_MovableSpeedViolationCamera,
            KNSafetyCode.KNSafetyCode_SignalAndSpeedViolationCamera,
            KNSafetyCode.KNSafetyCode_BuslaneAndSpeedViolationCamera,
            KNSafetyCode.KNSafetyCode_LaneAndSpeedViolationCamera,
            KNSafetyCode.KNSafetyCode_BoxedSpeedViolationCamera,
            KNSafetyCode.KNSafetyCode_SpeedViolationBackwardCamera,
            KNSafetyCode.KNSafetyCode_SignalAndSpeedViolationBackwardCamera,
            KNSafetyCode.KNSafetyCode_SpeedViolationSectionInCamera,
            KNSafetyCode.KNSafetyCode_SpeedViolationSectionOutCamera
        )

        private fun alertType(code: KNSafetyCode): SafetyAlertType? = when (code) {
            in speedCodes -> SafetyAlertType.SPEED_CAMERA
            KNSafetyCode.KNSafetyCode_Hump -> SafetyAlertType.SPEED_BUMP
            KNSafetyCode.KNSafetyCode_ChildrenProtectionZone -> SafetyAlertType.CHILDREN_ZONE
            KNSafetyCode.KNSafetyCode_ChildrenAccidentPos -> SafetyAlertType.CHILDREN_ACCIDENT
            KNSafetyCode.KNSafetyCode_TrafficAccidentPos,
            KNSafetyCode.KNSafetyCode_CarAccidentPos,
            KNSafetyCode.KNSafetyCode_PedestrianAccidentPos -> SafetyAlertType.ACCIDENT_PRONE
            KNSafetyCode.KNSafetyCode_SharpTurnSection -> SafetyAlertType.SHARP_TURN
            KNSafetyCode.KNSafetyCode_RailroadCrossing -> SafetyAlertType.RAILROAD_CROSSING
            KNSafetyCode.KNSafetyCode_SlippingRoad,
            KNSafetyCode.KNSafetyCode_FrozenRoad,
            KNSafetyCode.KNSafetyCode_FrozenRoadLive -> SafetyAlertType.SLIPPERY_ROAD
            else -> null
        }
    }
}
