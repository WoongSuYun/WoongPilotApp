package kr.co.tesla.cameraalert.route

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.round

/** [breakBefore] prevents the map from joining two positions across a GPS outage. */
data class RoutePoint(val latitude: Double, val longitude: Double, val time: Long, val breakBefore: Boolean = false)
data class RouteRecord(val startedAt: Long, val endedAt: Long, val distanceKm: Double, val points: List<RoutePoint>)

/**
 * Raw GPS points live in an app-private file per drive. Preferences contain only compact
 * metadata, so long drives no longer lose their middle or rewrite one giant preference value.
 */
object RouteLedger {
    private const val PREFS = "route_ledger"
    private const val ACTIVE = "active"
    private const val RECORDS = "records"
    private const val MAX_RECORDS = 100
    private const val GPS_GAP_MS = 10_000L

    fun begin(context: Context, now: Long) {
        if (active(context) != null) return
        activeFile(context).delete()
        saveActive(context, JSONObject().apply {
            put("startedAt", now); put("distanceMeters", 0.0); put("lastObservedAt", now)
        })
    }

    fun observe(context: Context, fix: Location) {
        val value = active(context) ?: return
        val now = fix.time.takeIf { it > 0 } ?: System.currentTimeMillis()
        val last = value.optJSONObject("lastPoint")
        if (last == null) {
            val first = point(fix.latitude, fix.longitude, now)
            appendPoint(context, first)
            value.put("lastPoint", first).put("lastObservedAt", now)
            saveActive(context, value)
            return
        }
        val distance = FloatArray(1)
        Location.distanceBetween(last.getDouble("lat"), last.getDouble("lng"), fix.latitude, fix.longitude, distance)
        val meters = distance[0].toDouble()
        val gapMs = now - value.optLong("lastObservedAt", last.optLong("time", now))
        value.put("lastObservedAt", now)
        if (gapMs >= GPS_GAP_MS) {
            val resumed = point(fix.latitude, fix.longitude, now, breakBefore = true)
            appendPoint(context, resumed)
            value.put("lastPoint", resumed)
            saveActive(context, value)
            return
        }
        if (meters !in 3.0..350.0) {
            saveActive(context, value)
            return
        }
        value.put("distanceMeters", value.optDouble("distanceMeters") + meters)
        if (meters >= 12.0 || now - last.optLong("time") >= 10_000L) {
            val next = point(fix.latitude, fix.longitude, now)
            appendPoint(context, next)
            value.put("lastPoint", next)
        }
        saveActive(context, value)
    }

    fun finish(context: Context, now: Long): RouteRecord? {
        val active = active(context) ?: return null
        prefs(context).edit().remove(ACTIVE).apply()
        val startedAt = active.getLong("startedAt")
        val points = readPoints(activeFile(context))
        val distanceKm = round(active.optDouble("distanceMeters") / 100.0) / 10.0
        if (points.size < 2 || distanceKm < 0.05) {
            activeFile(context).delete()
            return null
        }
        val file = routeFile(context, startedAt)
        file.delete()
        if (!activeFile(context).renameTo(file)) {
            activeFile(context).copyTo(file, overwrite = true)
            activeFile(context).delete()
        }
        val record = RouteRecord(startedAt, now, distanceKm, points)
        appendRecord(context, record, file.name)
        return record
    }

    fun records(context: Context): List<RouteRecord> = storedRecords(context).mapNotNull { fromJson(context, it) }
        .sortedByDescending { it.startedAt }

    fun matchingStart(context: Context, startedAt: Long, endedAt: Long): Long? {
        val leeway = 15 * 60_000L
        return records(context).filter { it.endedAt >= startedAt - leeway && it.startedAt <= endedAt + leeway }
            .minByOrNull { kotlin.math.abs(it.startedAt - startedAt) + kotlin.math.abs(it.endedAt - endedAt) }?.startedAt
    }

    fun addSample(context: Context): RouteRecord {
        val endedAt = System.currentTimeMillis()
        val startedAt = endedAt - 26 * 60_000L
        val points = listOf(
            RoutePoint(37.49795, 127.02762, startedAt), RoutePoint(37.50079, 127.03512, startedAt + 6 * 60_000L),
            RoutePoint(37.50438, 127.04234, startedAt + 13 * 60_000L), RoutePoint(37.50915, 127.04722, startedAt + 20 * 60_000L),
            RoutePoint(37.51302, 127.04996, endedAt)
        )
        val record = RouteRecord(startedAt, endedAt, 3.1, points)
        val file = routeFile(context, startedAt)
        writePoints(file, points)
        appendRecord(context, record, file.name)
        return record
    }

    fun delete(context: Context, startedAt: Long) {
        val kept = JSONArray()
        storedRecords(context).forEach { stored ->
            if (stored.optLong("startedAt") == startedAt) stored.optString("routeFile").takeIf { it.isNotBlank() }
                ?.let { File(routesDirectory(context), it).delete() }
            else kept.put(stored)
        }
        prefs(context).edit().putString(RECORDS, kept.toString()).apply()
    }

    /** Complete point data is materialized only when creating a portable backup. */
    fun backupRecords(context: Context): JSONArray = JSONArray().apply {
        storedRecords(context).forEach { stored ->
            val portable = JSONObject(stored.toString())
            val fileName = portable.optString("routeFile")
            if (fileName.isNotBlank()) {
                portable.remove("routeFile")
                portable.put("points", JSONArray().apply {
                    readPoints(File(routesDirectory(context), fileName)).forEach { put(point(it.latitude, it.longitude, it.time, it.breakBefore)) }
                })
            }
            put(portable)
        }
    }

    fun restoreRecords(context: Context, backup: JSONArray): Int {
        val stored = storedRecords(context).toMutableList()
        val ids = stored.map { it.optLong("startedAt") }.toMutableSet()
        var added = 0
        for (index in 0 until backup.length()) {
            val item = backup.optJSONObject(index) ?: continue
            val record = fromJson(context, item) ?: continue
            if (!ids.add(record.startedAt)) continue
            val file = routeFile(context, record.startedAt)
            writePoints(file, record.points)
            stored += metadata(record, file.name)
            added++
        }
        saveStoredRecords(context, stored)
        return added
    }

    private fun appendRecord(context: Context, record: RouteRecord, fileName: String) {
        val records = storedRecords(context).toMutableList()
        records += metadata(record, fileName)
        saveStoredRecords(context, records)
    }

    private fun saveStoredRecords(context: Context, values: List<JSONObject>) {
        val sorted = values.sortedByDescending { it.optLong("startedAt") }.take(MAX_RECORDS)
        prefs(context).edit().putString(RECORDS, JSONArray(sorted).toString()).apply()
    }

    private fun storedRecords(context: Context): List<JSONObject> = JSONArray(prefs(context).getString(RECORDS, "[]"))
        .let { values -> (0 until values.length()).mapNotNull(values::optJSONObject) }
    private fun metadata(record: RouteRecord, fileName: String) = JSONObject().apply {
        put("startedAt", record.startedAt); put("endedAt", record.endedAt); put("distanceKm", record.distanceKm); put("routeFile", fileName)
    }
    /** Migrates an in-progress route written by older versions before continuing it in a file. */
    private fun active(context: Context): JSONObject? {
        val value = prefs(context).getString(ACTIVE, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val legacyPoints = value.optJSONArray("points") ?: return value
        val points = (0 until legacyPoints.length()).mapNotNull { legacyPoints.optJSONObject(it)?.let(::pointFromJson) }
        writePoints(activeFile(context), points)
        value.remove("points")
        points.lastOrNull()?.let { last ->
            value.put("lastPoint", point(last.latitude, last.longitude, last.time, last.breakBefore))
            value.put("lastObservedAt", last.time)
        }
        saveActive(context, value)
        return value
    }
    private fun saveActive(context: Context, value: JSONObject) = prefs(context).edit().putString(ACTIVE, value.toString()).apply()
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun routesDirectory(context: Context) = File(context.filesDir, "routes").apply { mkdirs() }
    private fun activeFile(context: Context) = File(routesDirectory(context), "active-route.jsonl")
    private fun routeFile(context: Context, startedAt: Long) = File(routesDirectory(context), "route-$startedAt.jsonl")
    private fun appendPoint(context: Context, value: JSONObject) = activeFile(context).appendText(value.toString() + "\n")
    private fun writePoints(file: File, points: List<RoutePoint>) = file.writeText(buildString {
        points.forEach { append(point(it.latitude, it.longitude, it.time, it.breakBefore)).append('\n') }
    })
    private fun readPoints(file: File): List<RoutePoint> = runCatching {
        if (!file.exists()) emptyList() else file.useLines { lines -> lines.mapNotNull { line ->
            runCatching { pointFromJson(JSONObject(line)) }.getOrNull()
        }.toList() }
    }.getOrDefault(emptyList())
    private fun point(lat: Double, lng: Double, time: Long, breakBefore: Boolean = false) = JSONObject().apply {
        put("lat", lat); put("lng", lng); put("time", time); if (breakBefore) put("breakBefore", true)
    }
    private fun pointFromJson(value: JSONObject) = RoutePoint(value.optDouble("lat"), value.optDouble("lng"), value.optLong("time"), value.optBoolean("breakBefore"))
    private fun fromJson(context: Context, value: JSONObject): RouteRecord? = runCatching {
        val fileName = value.optString("routeFile")
        val points = if (fileName.isNotBlank()) readPoints(File(routesDirectory(context), fileName)) else
            value.optJSONArray("points")?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::pointFromJson) } }.orEmpty()
        RouteRecord(value.getLong("startedAt"), value.getLong("endedAt"), value.getDouble("distanceKm"), points)
    }.getOrNull()
}
