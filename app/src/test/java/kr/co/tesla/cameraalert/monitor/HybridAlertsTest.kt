package kr.co.tesla.cameraalert.monitor

import kr.co.tesla.cameraalert.model.CameraMatch
import kr.co.tesla.cameraalert.model.SpeedCamera
import org.junit.Assert.*
import org.junit.Test

class HybridAlertsTest {
    private fun event(source: CameraSource, id: String, latitude: Double = 37.5) = SourcedMatch(
        CameraMatch(SpeedCamera(id, latitude, 127.0, 60, "도로"), 300.0, 0.0), source
    )

    @Test fun `healthy Kakao feed takes precedence over public dataset`() {
        assertEquals(CameraSource.KAKAO, HybridAlerts.select(true,
            event(CameraSource.KAKAO, "kakao").match, event(CameraSource.PUBLIC, "public").match)?.source)
    }

    @Test fun `stale Kakao feed falls back to public dataset`() {
        assertEquals(CameraSource.PUBLIC, HybridAlerts.select(false,
            event(CameraSource.KAKAO, "kakao").match, event(CameraSource.PUBLIC, "public").match)?.source)
    }

    @Test fun `healthy feed without a camera does not substitute a public-road guess`() {
        assertNull(HybridAlerts.select(true, null, event(CameraSource.PUBLIC, "public").match))
    }

    @Test fun `nearby duplicate from another provider only alerts once`() {
        val gate = CameraAlertGate()
        assertTrue(gate.shouldAlert(event(CameraSource.KAKAO, "kakao"), 10.0, 1_000))
        assertFalse(gate.shouldAlert(event(CameraSource.PUBLIC, "public", 37.5001), 15.0, 2_000))
        assertTrue(gate.shouldAlert(event(CameraSource.PUBLIC, "public", 37.5001), 100.0, 2_000))
    }
}
