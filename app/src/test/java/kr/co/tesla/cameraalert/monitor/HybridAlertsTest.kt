package kr.co.tesla.cameraalert.monitor

import kr.co.tesla.cameraalert.model.SafetyAlertType
import kr.co.tesla.cameraalert.model.SafetyMatch
import kr.co.tesla.cameraalert.model.CameraMatch
import kr.co.tesla.cameraalert.model.SpeedCamera
import org.junit.Assert.*
import org.junit.Test

class HybridAlertsTest {
    private fun event(source: CameraSource, id: String, latitude: Double = 37.5) = SourcedMatch(
        SafetyMatch(id, SafetyAlertType.SPEED_CAMERA, latitude, 127.0, 300.0, 60, "도로"), source
    )
    private fun publicMatch(id: String, latitude: Double = 37.5) =
        CameraMatch(SpeedCamera(id, latitude, 127.0, 60, "도로"), 300.0, 0.0)

    @Test fun `healthy Kakao feed takes precedence over public dataset`() {
        assertEquals(CameraSource.KAKAO, HybridAlerts.select(true,
            event(CameraSource.KAKAO, "kakao").match, publicMatch("public"))?.source)
    }

    @Test fun `stale Kakao feed falls back to public dataset`() {
        assertEquals(CameraSource.PUBLIC, HybridAlerts.select(false,
            event(CameraSource.KAKAO, "kakao").match, publicMatch("public"))?.source)
    }

    @Test fun `healthy feed without a camera does not substitute a public-road guess`() {
        assertNull(HybridAlerts.select(true, null, publicMatch("public")))
    }

    @Test fun `nearby duplicate from another provider only alerts once`() {
        val gate = CameraAlertGate()
        assertTrue(gate.shouldAlert(event(CameraSource.KAKAO, "kakao"), 10.0, 1_000))
        assertFalse(gate.shouldAlert(event(CameraSource.PUBLIC, "public", 37.5001), 15.0, 2_000))
        assertTrue(gate.shouldAlert(event(CameraSource.PUBLIC, "public", 37.5001), 100.0, 2_000))
    }

    @Test fun `camera first warning uses the selected fixed milestone`() {
        val gate = CameraAlertGate()
        assertEquals(500, gate.alertDistanceMeters(event(CameraSource.KAKAO, "camera"), 10.0, 1_000, 500))
    }

    @Test fun `camera outside first warning distance waits until it reaches the milestone`() {
        val gate = CameraAlertGate()
        val far = event(CameraSource.KAKAO, "camera").copy(
            match = event(CameraSource.KAKAO, "camera").match.copy(distanceMeters = 720.0))
        assertNull(gate.alertDistanceMeters(far, 10.0, 1_000, 700))
    }

    @Test fun `u turn warning is rounded to a hundred metre milestone`() {
        val gate = CameraAlertGate()
        assertEquals(700, gate.alertDistanceMeters(event(CameraSource.KAKAO, "camera"), 10.0, 1_000, 700))
        val returned = event(CameraSource.KAKAO, "camera").copy(
            match = event(CameraSource.KAKAO, "camera").match.copy(distanceMeters = 232.0))
        assertEquals(300, gate.alertDistanceMeters(returned, 190.0, 2_000, 700))
    }
}
