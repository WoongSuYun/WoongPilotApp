package kr.co.tesla.cameraalert.trip

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** App Script web-hook queue. Failed uploads stay local and are retried later. */
object SheetsWebhook {
    private const val PREFS = "sheets_webhook"
    private const val URL_KEY = "url"
    private const val ENABLED = "enabled"
    private const val PENDING = "pending"

    fun settings(context: Context): Pair<Boolean, String> = prefs(context).let { it.getBoolean(ENABLED, false) to it.getString(URL_KEY, "").orEmpty() }
    fun save(context: Context, enabled: Boolean, url: String) = prefs(context).edit().putBoolean(ENABLED, enabled).putString(URL_KEY, url.trim()).apply()

    fun enqueue(context: Context, record: TripRecord) {
        val values = JSONArray(prefs(context).getString(PENDING, "[]"))
        values.put(payload(record))
        prefs(context).edit().putString(PENDING, values.toString()).apply()
    }

    suspend fun syncPending(context: Context): Int = withContext(Dispatchers.IO) {
        val (enabled, endpoint) = settings(context)
        if (!enabled || endpoint.isBlank()) return@withContext 0
        val values = JSONArray(prefs(context).getString(PENDING, "[]"))
        val remaining = JSONArray(); var sent = 0
        for (index in 0 until values.length()) {
            val value = values.optJSONObject(index) ?: continue
            if (post(endpoint, value)) sent++ else remaining.put(value)
        }
        prefs(context).edit().putString(PENDING, remaining.toString()).apply()
        sent
    }

    suspend fun test(context: Context): Boolean = withContext(Dispatchers.IO) {
        val (enabled, endpoint) = settings(context)
        enabled && endpoint.isNotBlank() && post(endpoint, JSONObject().put("type", "test").put("message", "WoongPilot webhook connection test"))
    }

    private fun post(endpoint: String, value: JSONObject): Boolean = runCatching {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 15_000; doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            connection.outputStream.bufferedWriter().use { it.write(value.toString()) }
            connection.responseCode in 200..299
        } finally { connection.disconnect() }
    }.getOrDefault(false)

    private fun payload(record: TripRecord) = JSONObject().apply {
        put("type", "trip")
        put("vin", record.vin); put("startedAt", record.startedAt); put("endedAt", record.endedAt)
        put("distanceKm", record.distanceKm); put("batteryUsedPercent", record.batteryUsedPercent)
        put("estimatedKwh", record.estimatedKwh); put("kmPerKwh", record.kmPerKwh)
    }
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
