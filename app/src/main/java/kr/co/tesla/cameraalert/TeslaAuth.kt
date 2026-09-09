package kr.co.tesla.cameraalert

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** OAuth tokens remain in the Cloudflare Worker; the app only retains an opaque session id. */
object TeslaAuth {
    private const val ORIGIN = "https://tesla-auth.dndtnekd.workers.dev"
    private const val SESSION_KEY = "tesla_oauth_session"

    fun start(context: Context) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$ORIGIN/auth/tesla/login")))
    }

    fun acceptCallback(context: Context, intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (uri.scheme != "woongpilot" || uri.host != "oauth" || uri.path != "/callback") return false
        val sessionId = uri.getQueryParameter("session_id") ?: return false
        if (!sessionId.matches(Regex("[a-f0-9]{64}"))) return false
        context.getSharedPreferences("secure_session", Context.MODE_PRIVATE)
            .edit().putString(SESSION_KEY, sessionId).apply()
        return true
    }

    fun isSignedIn(context: Context): Boolean = context.getSharedPreferences("secure_session", Context.MODE_PRIVATE)
        .getString(SESSION_KEY, null) != null

    suspend fun signOut(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("secure_session", Context.MODE_PRIVATE)
        val sessionId = prefs.getString(SESSION_KEY, null)
        try {
            if (sessionId != null) {
                val connection = (URL("$ORIGIN/api/session/$sessionId/logout").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 10_000; readTimeout = 10_000
                }
                connection.inputStream.close()
                connection.disconnect()
            }
        } finally {
            prefs.edit().remove(SESSION_KEY).apply()
        }
    }

    data class Vehicle(val vin: String, val name: String)
    data class VehicleData(
        val batteryPercent: Int?, val usableBatteryPercent: Int?, val rangeKm: Int?,
        val chargeState: String?, val chargingPowerKw: Int?, val odometerKm: Double?,
        val model: String?, val trim: String?, val color: String?, val locked: Boolean?,
        val softwareStatus: String?, val softwareVersion: String?,
        val vehicleState: String?, val gear: String?, val speedKph: Int?,
        val sentryMode: Boolean?, val doorsOpen: Boolean?,
        val outsideTempC: Double?, val insideTempC: Double?
    )

    suspend fun vehicles(context: Context): List<Vehicle> = withContext(Dispatchers.IO) {
        val sessionId = context.getSharedPreferences("secure_session", Context.MODE_PRIVATE)
            .getString(SESSION_KEY, null) ?: error("Tesla sign-in is required")
        val connection = (URL("$ORIGIN/api/session/$sessionId/vehicles").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                .bufferedReader().use { it.readText() }
            if (connection.responseCode !in 200..299) error("Tesla vehicle request failed (${connection.responseCode})")
            val vehicles = JSONObject(body).optJSONArray("response") ?: return@withContext emptyList()
            buildList {
                for (index in 0 until vehicles.length()) {
                    val item = vehicles.getJSONObject(index)
                    val vin = item.optString("vin")
                    if (vin.length == 17) add(Vehicle(vin, item.optString("display_name", vin)))
                }
            }
        } finally { connection.disconnect() }
    }

    suspend fun vehicleData(context: Context, vin: String): VehicleData = withContext(Dispatchers.IO) {
        val sessionId = context.getSharedPreferences("secure_session", Context.MODE_PRIVATE)
            .getString(SESSION_KEY, null) ?: error("Tesla sign-in is required")
        val connection = (URL("$ORIGIN/api/session/$sessionId/vehicle/$vin/data").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
        }
        try {
            val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                .bufferedReader().use { it.readText() }
            if (connection.responseCode !in 200..299) {
                val detail = body.replace(Regex("\\s+"), " ").take(300)
                error("Tesla vehicle data request failed (${connection.responseCode}): $detail")
            }
            val response = JSONObject(body).optJSONObject("response") ?: error("Tesla did not return vehicle data")
            val charge = response.optJSONObject("charge_state")
            val vehicle = response.optJSONObject("vehicle_state")
            val config = response.optJSONObject("vehicle_config")
            val drive = response.optJSONObject("drive_state")
            val climate = response.optJSONObject("climate_state")
            val software = vehicle?.optJSONObject("software_update")
            fun JSONObject?.integer(name: String): Int? = this?.takeIf { it.has(name) && !it.isNull(name) }?.optDouble(name)?.toInt()
            fun JSONObject?.text(name: String): String? = this?.optString(name)?.takeIf { it.isNotBlank() && it != "null" }
            fun JSONObject?.bool(name: String): Boolean? = this?.takeIf { it.has(name) && !it.isNull(name) }?.optBoolean(name)
            fun JSONObject?.number(name: String): Double? {
                val value = this?.takeIf { it.has(name) && !it.isNull(name) }?.optDouble(name) ?: return null
                return value.takeIf { !it.isNaN() }
            }
            fun JSONObject?.milesToKm(name: String): Int? {
                val miles = this?.takeIf { it.has(name) && !it.isNull(name) }?.optDouble(name) ?: return null
                return miles.takeIf { !it.isNaN() }?.times(1.60934)?.toInt()
            }
            fun JSONObject?.milesToKmDecimal(name: String): Double? {
                val miles = this?.takeIf { it.has(name) && !it.isNull(name) }?.optDouble(name) ?: return null
                return miles.takeIf { !it.isNaN() }?.times(1.60934)
            }
            fun JSONObject?.anyPanelOpen(): Boolean? {
                val objectValue = this ?: return null
                val fields = listOf("df", "dr", "pf", "pr", "ft", "rt")
                    .filter { objectValue.has(it) && !objectValue.isNull(it) }
                return if (fields.isEmpty()) null else fields.any { objectValue.optInt(it) != 0 }
            }
            VehicleData(
                batteryPercent = charge.integer("battery_level"),
                usableBatteryPercent = charge.integer("usable_battery_level"),
                rangeKm = charge.milesToKm("battery_range"),
                chargeState = charge.text("charging_state"),
                chargingPowerKw = charge.integer("charger_power"),
                odometerKm = vehicle.milesToKmDecimal("odometer"),
                model = config.text("car_type"), trim = config.text("trim_badging"), color = config.text("exterior_color"),
                locked = vehicle.bool("locked"), softwareStatus = software.text("status"),
                softwareVersion = vehicle.text("car_version"), vehicleState = response.text("state"),
                gear = drive.text("shift_state")?.uppercase(), speedKph = drive.milesToKm("speed"),
                sentryMode = vehicle.bool("sentry_mode"), doorsOpen = vehicle.anyPanelOpen(),
                outsideTempC = climate.number("outside_temp"), insideTempC = climate.number("inside_temp")
            )
        } finally { connection.disconnect() }
    }

    suspend fun wakeVehicle(context: Context, vin: String) = withContext(Dispatchers.IO) {
        val sessionId = context.getSharedPreferences("secure_session", Context.MODE_PRIVATE)
            .getString(SESSION_KEY, null) ?: error("Tesla sign-in is required")
        val connection = (URL("$ORIGIN/api/session/$sessionId/vehicle/$vin/wake").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 20_000; doOutput = true
        }
        try {
            val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                .bufferedReader().use { it.readText() }
            if (connection.responseCode !in 200..299) {
                error("Tesla wake request failed (${connection.responseCode}): ${body.replace(Regex("\\s+"), " ").take(300)}")
            }
        } finally { connection.disconnect() }
    }
}
