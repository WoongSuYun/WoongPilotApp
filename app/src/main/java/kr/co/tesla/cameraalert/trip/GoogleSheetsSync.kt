package kr.co.tesla.cameraalert.trip

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Queues completed trips locally, then appends them directly to the user's Google Sheet. */
object GoogleSheetsSync {
    const val SCOPE = "https://www.googleapis.com/auth/spreadsheets"
    private const val PREFS = "google_sheets_sync"
    private const val ENABLED = "enabled"
    private const val SHEET_ID = "sheet_id"
    private const val ACCOUNT = "account"
    private const val PENDING = "pending"

    data class Settings(val enabled: Boolean, val sheetId: String, val accountName: String)

    fun settings(context: Context) = prefs(context).let {
        Settings(it.getBoolean(ENABLED, false), it.getString(SHEET_ID, "").orEmpty(), it.getString(ACCOUNT, "").orEmpty())
    }

    fun save(context: Context, enabled: Boolean, sheetId: String, accountName: String) {
        prefs(context).edit().putBoolean(ENABLED, enabled).putString(SHEET_ID, sheetId)
            .putString(ACCOUNT, accountName).apply()
    }

    fun disable(context: Context) = prefs(context).edit().putBoolean(ENABLED, false).apply()

    fun enqueue(context: Context, record: TripRecord) {
        val values = JSONArray(prefs(context).getString(PENDING, "[]"))
        values.put(payload(record))
        prefs(context).edit().putString(PENDING, values.toString()).apply()
    }

    suspend fun test(context: Context): Boolean = withContext(Dispatchers.IO) {
        val config = settings(context)
        if (!config.enabled || config.sheetId.isBlank() || config.accountName.isBlank()) return@withContext false
        val token = accessToken(context, config.accountName) ?: return@withContext false
        request("https://sheets.googleapis.com/v4/spreadsheets/${config.sheetId}?fields=properties.title", token, null)
    }

    /** Adds the same shaped sample trip shown by the in-app card preview. */
    suspend fun appendTestRow(context: Context): Boolean = withContext(Dispatchers.IO) {
        val config = settings(context)
        if (!config.enabled || config.sheetId.isBlank() || config.accountName.isBlank()) return@withContext false
        val token = accessToken(context, config.accountName) ?: return@withContext false
        val endedAt = System.currentTimeMillis()
        val startedAt = endedAt - 42 * 60_000L
        append(config.sheetId, token, JSONObject().apply {
            put("vin", "TEST-WOONGPILOT")
            put("startedAt", startedAt); put("endedAt", endedAt)
            put("distanceKm", 18.4); put("batteryUsedPercent", 5)
            put("estimatedKwh", 3.8); put("kmPerKwh", 4.8)
            put("batteryStartPercent", 80); put("batteryEndPercent", 75)
        })
    }

    suspend fun syncPending(context: Context): Int = withContext(Dispatchers.IO) {
        val config = settings(context)
        if (!config.enabled || config.sheetId.isBlank() || config.accountName.isBlank()) return@withContext 0
        val token = accessToken(context, config.accountName) ?: return@withContext 0
        val values = JSONArray(prefs(context).getString(PENDING, "[]"))
        val remaining = JSONArray()
        var sent = 0
        for (index in 0 until values.length()) {
            val item = values.optJSONObject(index) ?: continue
            if (append(config.sheetId, token, item)) sent++ else remaining.put(item)
        }
        prefs(context).edit().putString(PENDING, remaining.toString()).apply()
        sent
    }

    private fun accessToken(context: Context, accountName: String): String? = runCatching {
        GoogleAuthUtil.getToken(
            context,
            Account(accountName, GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE),
            "oauth2:$SCOPE"
        )
    }.getOrNull()

    private fun append(sheetId: String, token: String, record: JSONObject): Boolean {
        val row = JSONArray().apply {
            put(record.optString("vin"))
            put(formatTime(record.optLong("startedAt")))
            put(formatTime(record.optLong("endedAt")))
            put(record.optDouble("distanceKm"))
            put(if (record.isNull("batteryUsedPercent")) "" else record.optDouble("batteryUsedPercent"))
            put(if (record.isNull("estimatedKwh")) "" else record.optDouble("estimatedKwh"))
            put(efficiencyKmPerKwh(record) ?: "")
            put(if (record.isNull("batteryStartPercent")) "" else record.optInt("batteryStartPercent"))
            put(if (record.isNull("batteryEndPercent")) "" else record.optInt("batteryEndPercent"))
            put(((record.optLong("endedAt") - record.optLong("startedAt")) / 60_000).coerceAtLeast(0))
        }
        val body = JSONObject().put("majorDimension", "ROWS").put("values", JSONArray().put(row))
        return request(
            "https://sheets.googleapis.com/v4/spreadsheets/$sheetId/values/A1:append?valueInputOption=USER_ENTERED&insertDataOption=INSERT_ROWS",
            token, body
        )
    }

    private fun request(endpoint: String, token: String, body: JSONObject?): Boolean = runCatching {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            connectTimeout = 15_000; readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (body != null) doOutput = true
        }
        try {
            if (body != null) connection.outputStream.bufferedWriter().use { it.write(body.toString()) }
            connection.responseCode in 200..299
        } finally { connection.disconnect() }
    }.getOrDefault(false)

    private fun payload(record: TripRecord) = JSONObject().apply {
        put("vin", record.vin); put("startedAt", record.startedAt); put("endedAt", record.endedAt)
        put("distanceKm", record.distanceKm); put("batteryUsedPercent", record.batteryUsedPercent)
        put("estimatedKwh", record.estimatedKwh); put("kmPerKwh", record.kmPerKwh)
        put("batteryStartPercent", record.batteryStartPercent); put("batteryEndPercent", record.batteryEndPercent)
    }
    /** Converts records queued by versions that stored the inverse Wh/km value. */
    private fun efficiencyKmPerKwh(record: JSONObject): Double? = when {
        !record.isNull("kmPerKwh") -> record.optDouble("kmPerKwh")
        !record.isNull("whPerKm") -> record.optDouble("whPerKm").takeIf { it > 0 }?.let { 1000.0 / it }
        else -> null
    }
    private fun formatTime(value: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(value))
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
