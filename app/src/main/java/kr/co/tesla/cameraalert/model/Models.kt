package kr.co.tesla.cameraalert.model

data class VehiclePosition(
    val latitude: Double,
    val longitude: Double,
    val heading: Double,
    val speedKph: Double,
    val timestampMs: Long
)

data class SpeedCamera(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val limitKph: Int,
    val roadName: String
)

data class CameraMatch(val camera: SpeedCamera, val distanceMeters: Double, val angleDegrees: Double)
