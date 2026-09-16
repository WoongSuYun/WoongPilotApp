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

    @Test fun `a camera is not announced again after a long signal wait`() {
        val gate = CameraAlertGate()
        assertEquals(500, gate.alertDistanceMeters(event(CameraSource.KAKAO, "camera"), 10.0, 1_000, 500))
        val resumed = event(CameraSource.KAKAO, "camera").copy(
            match = event(CameraSource.KAKAO, "camera").match.copy(distanceMeters = 120.0))

        assertNull(gate.alertDistanceMeters(resumed, 10.0, 181_001, 500))
    }

    @Test fun `a passed camera can be announced on a later approach`() {
        val gate = CameraAlertGate()
        val camera = event(CameraSource.KAKAO, "camera")
        assertEquals(500, gate.alertDistanceMeters(camera, 10.0, 1_000, 500))

        gate.forgetSpeedCamera(camera.match.id, camera.match.latitude, camera.match.longitude)
        assertEquals(500, gate.alertDistanceMeters(camera, 10.0, 181_001, 500))
    }

    @Test fun `a camera re-approached after a broad curve uses its current distance`() {
        val gate = CameraAlertGate()
        val camera = event(CameraSource.KAKAO, "camera")
        assertEquals(500, gate.alertDistanceMeters(camera, 10.0, 1_000, 500))

        gate.reapproachSpeedCamera(camera.match.id, camera.match.latitude, camera.match.longitude, 20_000)
        val returned = camera.copy(match = camera.match.copy(distanceMeters = 132.0))
        assertEquals(100, gate.alertDistanceMeters(returned, 10.0, 20_001, 500))
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
        assertEquals(200, gate.alertDistanceMeters(returned, 190.0, 2_000, 700))
    }

    @Test fun `new camera revealed immediately after a turn uses actual hundred metre distance`() {
        val gate = CameraAlertGate()
        gate.observeHeading(0.0, 1_000)
        gate.observeHeading(90.0, 2_000)
        val closeCamera = event(CameraSource.KAKAO, "new-camera").copy(
            match = event(CameraSource.KAKAO, "new-camera").match.copy(distanceMeters = 132.0))
        assertEquals(100, gate.alertDistanceMeters(closeCamera, 90.0, 3_000, 700))
    }

    @Test fun `turn warning rounds to the nearest hundred metres`() {
        val gate = CameraAlertGate()
        gate.observeHeading(0.0, 1_000)
        gate.observeHeading(90.0, 2_000)
        val camera230m = event(CameraSource.KAKAO, "camera-230m").copy(
            match = event(CameraSource.KAKAO, "camera-230m").match.copy(distanceMeters = 230.0))
        assertEquals(200, gate.alertDistanceMeters(camera230m, 90.0, 3_000, 500))

        val camera251m = event(CameraSource.KAKAO, "camera-251m", latitude = 37.503).copy(
            match = event(CameraSource.KAKAO, "camera-251m", latitude = 37.503).match.copy(distanceMeters = 251.0))
        assertEquals(300, gate.alertDistanceMeters(camera251m, 90.0, 4_000, 500))
    }

    @Test fun `gradual turn is detected across multiple GPS fixes`() {
        val gate = CameraAlertGate()
        gate.observeHeading(0.0, 1_000)
        gate.observeHeading(25.0, 2_000)
        gate.observeHeading(50.0, 3_000)
        gate.observeHeading(90.0, 4_000)
        val closeCamera = event(CameraSource.KAKAO, "gradual-turn-camera").copy(
            match = event(CameraSource.KAKAO, "gradual-turn-camera").match.copy(distanceMeters = 132.0))
        assertEquals(100, gate.alertDistanceMeters(closeCamera, 90.0, 5_000, 500))
    }

    @Test fun `children zone warning rounds its GPS distance to the nearest hundred metres`() {
        val gate = CameraAlertGate()
        val childrenZone = event(CameraSource.KAKAO, "children-zone").copy(
            match = event(CameraSource.KAKAO, "children-zone").match.copy(
                type = SafetyAlertType.CHILDREN_ZONE, distanceMeters = 99.0, limitKph = null))
        assertEquals(100, gate.alertDistanceMeters(childrenZone, 10.0, 1_000, 700))
    }

    @Test fun `children zone waits until it is within one hundred metres`() {
        val gate = CameraAlertGate()
        val childrenZone = event(CameraSource.KAKAO, "children-zone").copy(
            match = event(CameraSource.KAKAO, "children-zone").match.copy(
                type = SafetyAlertType.CHILDREN_ZONE, distanceMeters = 101.0, limitKph = null))
        assertNull(gate.alertDistanceMeters(childrenZone, 10.0, 1_000, 700))
        assertEquals(100, gate.alertDistanceMeters(childrenZone.copy(
            match = childrenZone.match.copy(distanceMeters = 99.0)), 10.0, 2_000, 700))
    }
}
