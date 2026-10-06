package kr.co.tesla.cameraalert.model

data class VehiclePosition(
    val latitude: Double,
    val longitude: Double,
    val heading: Double,
    val speedKph: Double,
    val timestampMs: Long
)
