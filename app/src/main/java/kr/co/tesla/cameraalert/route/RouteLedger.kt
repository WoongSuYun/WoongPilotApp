package kr.co.tesla.cameraalert.route

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.round

data class RoutePoint(val latitude: Double, val longitude: Double, val time: Long)
data class RouteRecord(val startedAt: Long, val endedAt: Long, val distanceKm: Double, val points: List<RoutePoint>)

/** Stores the phone-GPS route only while camera monitoring is running. */
object RouteLedger {
    private const val PREFS = "route_ledger"
    private const val ACTIVE = "active"
    private const val RECORDS = "records"
    private const val MAX_RECORDS = 100
    private const val MAX_POINTS = 1_500

    fun begin(context: Context, now: Long) {
        if (active(context) != null) return
        saveActive(context, JSONObject().apply {
            put("startedAt", now); put("distanceMeters", 0.0); put("points", JSONArray())
        })
    }

    fun observe(context: Context, fix: Location) {
        val value = active(context) ?: return
        val points = value.getJSONArray("points")
        val last = points.optJSONObject(points.length() - 1)
        val now = fix.time.takeIf { it > 0 } ?: System.currentTimeMillis()
        if (last == null) {
            points.put(point(fix.latitude, fix.longitude, now))
            saveActive(context, value)
            return
        }
        val distance = FloatArray(1)
        Location.distanceBetween(last.getDouble("lat"), last.getDouble("lng"), fix.latitude, fix.longitude, distance)
        val meters = distance[0].toDouble()
        // Ignore GPS jitter and physically implausible one-second jumps.
        if (meters !in 3.0..350.0) return
        value.put("distanceMeters", value.optDouble("distanceMeters") + meters)
        val lastTime = last.optLong("time")
        // Keep enough points to preserve turns, without growing preferences indefinitely.
        if (meters >= 12.0 || now - lastTime >= 10_000L) {
            points.put(point(fix.latitude, fix.longitude, now))
            while (points.length() > MAX_POINTS) points.remove(1)
        }
        saveActive(context, value)
    }

    fun finish(context: Context, now: Long): RouteRecord? {
        val active = active(context) ?: return null
        prefs(context).edit().remove(ACTIVE).apply()
        val points = points(active.optJSONArray("points") ?: JSONArray())
        val distanceKm = round(active.optDouble("distanceMeters") / 100.0) / 10.0
        if (points.size < 2 || distanceKm < 0.05) return null
        val record = RouteRecord(active.getLong("startedAt"), now, distanceKm, points)
        val records = JSONArray(prefs(context).getString(RECORDS, "[]"))
        records.put(toJson(record))
        while (records.length() > MAX_RECORDS) records.remove(0)
        prefs(context).edit().putString(RECORDS, records.toString()).apply()
        return record
    }

    fun records(context: Context): List<RouteRecord> = JSONArray(prefs(context).getString(RECORDS, "[]"))
        .let { array -> (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(::fromJson) } }
        .sortedByDescending { it.startedAt }

    /** Finds a previously saved monitor route for legacy trip cards that predate route linking. */
    fun matchingStart(context: Context, startedAt: Long, endedAt: Long): Long? {
        val leeway = 15 * 60_000L
        return records(context)
            .filter { it.endedAt >= startedAt - leeway && it.startedAt <= endedAt + leeway }
            .minByOrNull { kotlin.math.abs(it.startedAt - startedAt) + kotlin.math.abs(it.endedAt - endedAt) }
            ?.startedAt
    }

    /** A disposable-looking Seoul sample so the route screen can be checked before a drive. */
    fun addSample(context: Context): RouteRecord {
        val endedAt = System.currentTimeMillis()
        val startedAt = endedAt - 26 * 60_000L
        val points = listOf(
            RoutePoint(37.49795, 127.02762, startedAt),
            RoutePoint(37.50079, 127.03512, startedAt + 6 * 60_000L),
            RoutePoint(37.50438, 127.04234, startedAt + 13 * 60_000L),
            RoutePoint(37.50915, 127.04722, startedAt + 20 * 60_000L),
            RoutePoint(37.51302, 127.04996, endedAt)
        )
        val records = JSONArray(prefs(context).getString(RECORDS, "[]"))
        val record = RouteRecord(startedAt, endedAt, 3.1, points)
        records.put(toJson(record))
        while (records.length() > MAX_RECORDS) records.remove(0)
        prefs(context).edit().putString(RECORDS, records.toString()).apply()
        return record
    }

    fun delete(context: Context, startedAt: Long) {
        val kept = JSONArray()
        val records = JSONArray(prefs(context).getString(RECORDS, "[]"))
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: continue
            if (record.optLong("startedAt") != startedAt) kept.put(record)
        }
        prefs(context).edit().putString(RECORDS, kept.toString()).apply()
    }

    /** Complete GPS point data for the app backup format. */
    fun backupRecords(context: Context): JSONArray = JSONArray(prefs(context).getString(RECORDS, "[]"))

    /** Adds routes absent from the device and returns how many were restored. */
    fun restoreRecords(context: Context, backup: JSONArray): Int {
        val stored = JSONArray(prefs(context).getString(RECORDS, "[]"))
        val ids = (0 until stored.length()).mapNotNull { stored.optJSONObject(it)?.optLong("startedAt") }.toMutableSet()
        var added = 0
        for (index in 0 until backup.length()) {
            val item = backup.optJSONObject(index) ?: continue
            val valid = runCatching { fromJson(item) }.getOrNull() != null
            val id = item.optLong("startedAt", Long.MIN_VALUE)
            if (valid && id != Long.MIN_VALUE && ids.add(id)) { stored.put(item); added++ }
        }
        val sorted = (0 until stored.length()).mapNotNull { stored.optJSONObject(it) }.sortedByDescending { it.optLong("startedAt") }.take(MAX_RECORDS)
        prefs(context).edit().putString(RECORDS, JSONArray(sorted).toString()).apply()
        return added
    }

    private fun active(context: Context): JSONObject? = prefs(context).getString(ACTIVE, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    private fun saveActive(context: Context, value: JSONObject) = prefs(context).edit().putString(ACTIVE, value.toString()).apply()
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun point(lat: Double, lng: Double, time: Long) = JSONObject().apply { put("lat", lat); put("lng", lng); put("time", time) }
    private fun points(array: JSONArray) = (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let {
        RoutePoint(it.optDouble("lat"), it.optDouble("lng"), it.optLong("time"))
    } }
    private fun toJson(record: RouteRecord) = JSONObject().apply {
        put("startedAt", record.startedAt); put("endedAt", record.endedAt); put("distanceKm", record.distanceKm)
        put("points", JSONArray().apply { record.points.forEach { put(point(it.latitude, it.longitude, it.time)) } })
    }
    private fun fromJson(value: JSONObject): RouteRecord? = runCatching {
        RouteRecord(value.getLong("startedAt"), value.getLong("endedAt"), value.getDouble("distanceKm"),
            points(value.getJSONArray("points")))
    }.getOrNull()
}
