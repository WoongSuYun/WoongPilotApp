package kr.co.tesla.cameraalert.monitor

import kr.co.tesla.cameraalert.model.SpeedCamera
import kr.co.tesla.cameraalert.model.VehiclePosition
import org.junit.Assert.*
import org.junit.Test

class CameraDetectorTest {
    private val vehicle = VehiclePosition(37.5, 127.0, 0.0, 50.0, System.currentTimeMillis())

    @Test fun `북쪽 전방 카메라를 찾는다`() {
        val camera = SpeedCamera("n", 37.504, 127.0, 60, "test")
        val match = CameraDetector.nearestAhead(vehicle, listOf(camera))
        assertNotNull(match); assertTrue(match!!.distanceMeters in 430.0..460.0)
    }

    @Test fun `뒤쪽 카메라는 제외한다`() {
        val camera = SpeedCamera("s", 37.496, 127.0, 60, "test")
        assertNull(CameraDetector.nearestAhead(vehicle, listOf(camera)))
    }

    @Test fun `거리 밖 카메라는 제외한다`() {
        val camera = SpeedCamera("far", 37.51, 127.0, 60, "test")
        assertNull(CameraDetector.nearestAhead(vehicle, listOf(camera)))
    }
}
