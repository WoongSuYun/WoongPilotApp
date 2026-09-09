package kr.co.tesla.cameraalert.monitor

import kr.co.tesla.cameraalert.model.CameraMatch
import kr.co.tesla.cameraalert.model.SpeedCamera
import kr.co.tesla.cameraalert.model.VehiclePosition
import kotlin.math.*

object CameraDetector {
    fun nearestAhead(
        vehicle: VehiclePosition,
        cameras: List<SpeedCamera>,
        maxDistanceMeters: Double = 700.0,
        maxAngleDegrees: Double = 35.0
    ): CameraMatch? = cameras.asSequence().filter { camera ->
        // Cheap bounding box before trigonometry, important for nationwide datasets.
        val latitudeRadius = maxDistanceMeters / 111_000.0
        val longitudeRadius = latitudeRadius / cos(Math.toRadians(vehicle.latitude)).coerceAtLeast(0.01)
        abs(camera.latitude - vehicle.latitude) <= latitudeRadius &&
            abs((camera.longitude - vehicle.longitude + 540) % 360 - 180) <= longitudeRadius
    }.map { camera ->
        val distance = distanceMeters(vehicle.latitude, vehicle.longitude, camera.latitude, camera.longitude)
        val bearing = bearingDegrees(vehicle.latitude, vehicle.longitude, camera.latitude, camera.longitude)
        CameraMatch(camera, distance, angularDifference(vehicle.heading, bearing))
    }.filter { it.distanceMeters <= maxDistanceMeters && it.angleDegrees <= maxAngleDegrees }
        .minByOrNull { it.distanceMeters }

    internal fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earth = 6_371_000.0
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1); val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return earth * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    internal fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
        return (Math.toDegrees(atan2(sin(dl) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl))) + 360) % 360
    }

    private fun angularDifference(a: Double, b: Double): Double = abs((a - b + 540) % 360 - 180)
}
