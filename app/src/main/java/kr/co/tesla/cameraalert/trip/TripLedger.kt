package kr.co.tesla.cameraalert.trip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kr.co.tesla.cameraalert.route.RouteLedger
import kr.co.tesla.cameraalert.route.RouteRecord
import java.io.BufferedReader
import kotlin.math.round

data class TripRecord(
    val vin: String, val startedAt: Long, val endedAt: Long,
    val distanceKm: Double, val batteryUsedPercent: Int?, val estimatedKwh: Double?, val kmPerKwh: Double?,
    val batteryStartPercent: Int?, val batteryEndPercent: Int?, val routeStartedAt: Long? = null,
    val routeStartedAts: List<Long> = routeStartedAt?.let(::listOf).orEmpty(), val mergedPartCount: Int = 0,
    /** Distance whose battery use was measurable; this may exclude 0 kWh short trips. */
    val efficiencyDistanceKm: Double? = null,
    val countsTowardLifetime: Boolean = true
)

/** Small app-private trip ledger. Odometer is the source of truth for trip distance. */
object TripLedger {
    private const val PREFS = "trip_ledger"
    private const val RECORDS = "records"
    private const val ACTIVE = "active"
    private const val LIFETIME_PREFS = "trip_lifetime"
    private const val LIFETIME_INITIALIZED = "initialized"
    private const val LIFETIME_DISTANCE_KM = "distance_km"
    private const val LIFETIME_TRIP_COUNT = "trip_count"

    data class BatteryCapacity(val kwh: Double, val source: String)

    fun begin(
        context: Context,
        vin: String,
        odometerKm: Double,
        batteryPercent: Int,
        model: String?,
        trim: String?,
        now: Long
    ) {
        if (active(context) != null) return
        val capacity = batteryCapacity(vin, model, trim)
        val value = JSONObject().apply {
            put("vin", vin); put("startedAt", now); put("odometerKm", odometerKm)
            put("batteryPercent", batteryPercent); put("model", model ?: ""); put("trim", trim ?: "")
            put("batteryCapacityKwh", capacity.kwh); put("batteryCapacitySource", capacity.source)
        }
        prefs(context).edit().putString(ACTIVE, value.toString()).apply()
    }

    fun finish(context: Context, odometerKm: Double, batteryPercent: Int, now: Long, routeStartedAt: Long? = null): TripRecord? {
        val start = active(context) ?: return null
        val distance = round((odometerKm - start.getDouble("odometerKm")) * 10) / 10
        // A delayed server response can briefly report an older odometer. Retain the active
        // trip in that case so the next stationary check can finish it safely.
        if (distance < 0) return null
        prefs(context).edit().remove(ACTIVE).apply()
        if (distance < 0.1) return null
        val batteryUsed = (start.getInt("batteryPercent") - batteryPercent).takeIf { it >= 0 }
        val capacity = start.optDouble("batteryCapacityKwh", Double.NaN).takeIf { it.isFinite() && it > 0 }
            ?: batteryCapacity(start.getString("vin"), start.optString("model"), start.optString("trim")).kwh
        val kwh = batteryUsed?.let { percent -> round(capacity * percent) / 100.0 }
        val kmPerKwh = kwh?.takeIf { it > 0 }?.let { round(distance / it * 10) / 10.0 }
        val record = TripRecord(start.getString("vin"), start.getLong("startedAt"), now, distance, batteryUsed, kwh, kmPerKwh,
            start.getInt("batteryPercent"), batteryPercent, routeStartedAt)
        append(context, record)
        return record
    }

    /** Saves a phone-GPS trip when Tesla telemetry is unavailable. Battery values intentionally stay empty. */
    fun recordGpsTrip(context: Context, vin: String, route: RouteRecord?, countsTowardLifetime: Boolean = true): TripRecord? {
        route ?: return null
        prefs(context).edit().remove(ACTIVE).apply()
        val record = TripRecord(vin, route.startedAt, route.endedAt, route.distanceKm,
            null, null, null, null, null, route.startedAt, countsTowardLifetime = countsTowardLifetime)
        append(context, record)
        return record
    }

    data class LifetimeStats(val distanceKm: Double, val tripCount: Long)

    /** Lifetime distance/count survives pruning of the recent-card list. */
    fun lifetime(context: Context): LifetimeStats {
        val store = lifetimePrefs(context)
        if (!store.getBoolean(LIFETIME_INITIALIZED, false)) {
            val records = allRecords(context).filter { it.countsTowardLifetime }
            val initial = LifetimeStats(records.sumOf { it.distanceKm }, records.sumOf {
                if (it.mergedPartCount >= 2) it.mergedPartCount.toLong() else 1L
            })
            store.edit().putBoolean(LIFETIME_INITIALIZED, true)
                .putFloat(LIFETIME_DISTANCE_KM, initial.distanceKm.toFloat())
                .putLong(LIFETIME_TRIP_COUNT, initial.tripCount).apply()
            return initial
        }
        return LifetimeStats(store.getFloat(LIFETIME_DISTANCE_KM, 0f).toDouble(), store.getLong(LIFETIME_TRIP_COUNT, 0))
    }

    fun delete(context: Context, vin: String, startedAt: Long, endedAt: Long) {
        val kept = JSONArray()
        val records = JSONArray(prefs(context).getString(RECORDS, "[]"))
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: continue
            val same = record.optString("vin") == vin && record.optLong("startedAt") == startedAt &&
                record.optLong("endedAt") == endedAt
            if (!same) kept.put(record)
        }
        prefs(context).edit().putString(RECORDS, kept.toString()).apply()
    }

    /** Complete, lossless record data for the app backup format (including merge/split history). */
    fun backupRecords(context: Context): JSONArray = JSONArray(prefs(context).getString(RECORDS, "[]"))

    /** Adds valid records absent from the device and returns how many were restored. */
    fun restoreRecords(context: Context, backup: JSONArray): Int {
        val stored = JSONArray(prefs(context).getString(RECORDS, "[]"))
        val keys = (0 until stored.length()).mapNotNull { stored.optJSONObject(it) }.map {
            Triple(it.optString("vin"), it.optLong("startedAt"), it.optLong("endedAt"))
        }.toMutableSet()
        var added = 0
        for (index in 0 until backup.length()) {
            val item = backup.optJSONObject(index) ?: continue
            val valid = runCatching { item.getString("vin"); item.getLong("startedAt"); item.getLong("endedAt"); item.getDouble("distanceKm") }.isSuccess
            val key = Triple(item.optString("vin"), item.optLong("startedAt"), item.optLong("endedAt"))
            if (valid && keys.add(key)) { stored.put(item); added++ }
        }
        val sorted = (0 until stored.length()).mapNotNull { stored.optJSONObject(it) }.sortedByDescending { it.optLong("startedAt") }.take(200)
        prefs(context).edit().putString(RECORDS, JSONArray(sorted).toString()).apply()
        return added
    }

    /** Replaces ordinary records with one aggregate while retaining their exact originals for undo. */
    fun merge(context: Context, selected: List<TripRecord>): TripRecord? {
        if (selected.size < 2 || selected.map { Triple(it.vin, it.startedAt, it.endedAt) }.distinct().size != selected.size) return null
        val keys = selected.map { Triple(it.vin, it.startedAt, it.endedAt) }.toSet()
        val all = JSONArray(prefs(context).getString(RECORDS, "[]"))
        val parts = (0 until all.length()).mapNotNull { all.optJSONObject(it) }.filter {
            Triple(it.optString("vin"), it.optLong("startedAt"), it.optLong("endedAt")) in keys
        }
        // A merged card retains its original JSON in mergedParts, so it can safely be merged
        // again with another card.  Splitting the outer card restores that prior merge intact.
        if (parts.size != selected.size) return null
        val ordered = selected.sortedBy { it.startedAt }
        if (ordered.any { it.vin != ordered.first().vin }) return null
        val routes = ordered.flatMap { record ->
            record.routeStartedAts.takeIf { it.isNotEmpty() }
                ?: record.routeStartedAt?.let(::listOf)
                ?: RouteLedger.matchingStart(context, record.startedAt, record.endedAt)?.let(::listOf).orEmpty()
        }.distinct()
        val distance = round(ordered.sumOf { it.distanceKm } * 10) / 10.0
        // A card without measured energy (or with 0 kWh from rounded battery telemetry) must
        // not erase the usable energy reading from another card in the same merged record.
        val measuredParts = ordered.filter { (it.estimatedKwh ?: 0.0) > 0.0 }
        val energy = measuredParts.sumOf { it.estimatedKwh ?: 0.0 }
            .takeIf { it > 0 }?.let { round(it * 10) / 10.0 }
        // Battery percentage is whole-number telemetry.  A 0% short trip has no measurable
        // energy, so including its distance would artificially improve the merged efficiency.
        val efficiencyDistance = measuredParts
            .sumOf { it.efficiencyDistanceKm ?: it.distanceKm }
        val merged = TripRecord(ordered.first().vin, ordered.first().startedAt, ordered.last().endedAt, distance,
            ordered.mapNotNull { it.batteryUsedPercent }.takeIf { it.size == ordered.size }?.sum(), energy,
            energy?.takeIf { it > 0 && efficiencyDistance > 0 }?.let { round(efficiencyDistance / it * 10) / 10.0 }, ordered.first().batteryStartPercent,
            ordered.last().batteryEndPercent, routes.firstOrNull(), routes,
            ordered.sumOf { if (it.mergedPartCount >= 2) it.mergedPartCount else 1 },
            efficiencyDistance.takeIf { it > 0 })
        val kept = JSONArray()
        (0 until all.length()).mapNotNull { all.optJSONObject(it) }.filter {
            Triple(it.optString("vin"), it.optLong("startedAt"), it.optLong("endedAt")) !in keys
        }.forEach { kept.put(it) }
        val mergedJson = toJson(merged).apply { put("mergedParts", JSONArray().apply { parts.forEach { put(it) } }) }
        kept.put(mergedJson)
        while (kept.length() > 200) kept.remove(0)
        prefs(context).edit().putString(RECORDS, kept.toString()).apply()
        return merged
    }

    /** Restores the exact cards that were used to create an aggregate record. */
    fun split(context: Context, trip: TripRecord): Boolean {
        if (trip.mergedPartCount < 2) return false
        val all = JSONArray(prefs(context).getString(RECORDS, "[]"))
        val target = (0 until all.length()).mapNotNull { all.optJSONObject(it) }.firstOrNull {
            it.optString("vin") == trip.vin && it.optLong("startedAt") == trip.startedAt && it.optLong("endedAt") == trip.endedAt && it.has("mergedParts")
        } ?: return false
        val parts = target.optJSONArray("mergedParts") ?: return false
        val kept = JSONArray()
        (0 until all.length()).mapNotNull { all.optJSONObject(it) }.filter { it !== target }.forEach { kept.put(it) }
        for (index in 0 until parts.length()) parts.optJSONObject(index)?.let { kept.put(it) }
        while (kept.length() > 200) kept.remove(0)
        prefs(context).edit().putString(RECORDS, kept.toString()).apply()
        return true
    }

    fun active(context: Context): JSONObject? = prefs(context).getString(ACTIVE, null)?.let(::JSONObject)

    /**
     * Resolves every route belonging to a card.  Older trip cards did not persist the route ID;
     * for those, use the same time-window match that the individual-card screen uses.
     */
    fun routeIdsForMap(context: Context, trip: TripRecord): List<Long> {
        val all = JSONArray(prefs(context).getString(RECORDS, "[]"))
        val stored = (0 until all.length()).mapNotNull(all::optJSONObject).firstOrNull {
            it.optString("vin") == trip.vin && it.optLong("startedAt") == trip.startedAt && it.optLong("endedAt") == trip.endedAt
        }
        val parts = stored?.optJSONArray("mergedParts")
        val sources = if (parts != null) (0 until parts.length()).mapNotNull(parts::optJSONObject)
        else listOfNotNull(stored)
        if (sources.isEmpty()) return trip.routeStartedAts.ifEmpty { trip.routeStartedAt?.let(::listOf).orEmpty() }
        return sources.sortedBy { it.optLong("startedAt") }.flatMap { value ->
            routeStartedAts(value).takeIf { it.isNotEmpty() }
                ?: routeStartedAt(value)?.let(::listOf)
                ?: RouteLedger.matchingStart(context, value.optLong("startedAt"), value.optLong("endedAt"))?.let(::listOf).orEmpty()
        }.distinct()
    }

    fun records(context: Context, vin: String): List<TripRecord> {
        val values = JSONArray(prefs(context).getString(RECORDS, "[]"))
        return (0 until values.length()).mapNotNull { index ->
            val value = values.optJSONObject(index) ?: return@mapNotNull null
            if (value.optString("vin") != vin) return@mapNotNull null
            TripRecord(value.getString("vin"), value.getLong("startedAt"), value.getLong("endedAt"),
                value.getDouble("distanceKm"), if (value.isNull("batteryUsedPercent")) null else value.getInt("batteryUsedPercent"),
                estimatedKwh(value), efficiencyKmPerKwh(value),
                batteryStartPercent(value), batteryEndPercent(value), routeStartedAt(value), routeStartedAts(value), mergedPartCount(value),
                efficiencyDistanceKm(value), countsTowardLifetime(value))
        }.sortedByDescending { it.startedAt }
    }

    /** Rebuilds calculated energy and efficiency while retaining the original trip facts. */
    fun recalculateEfficiency(context: Context, vin: String, capacityKwh: Double): Int {
        require(capacityKwh > 0)
        var changed = 0
        val recalculated = allRecords(context).map { record ->
            if (record.vin != vin || record.batteryUsedPercent == null) return@map record
            val kwh = round(capacityKwh * record.batteryUsedPercent) / 100.0
            val efficiencyDistance = record.efficiencyDistanceKm ?: kwh.takeIf { it > 0 }?.let { record.distanceKm }
            val efficiency = efficiencyDistance?.takeIf { it > 0 }?.let { distance ->
                kwh.takeIf { it > 0 }?.let { round(distance / it * 10) / 10.0 }
            }
            changed++
            record.copy(estimatedKwh = kwh, kmPerKwh = efficiency, efficiencyDistanceKm = efficiencyDistance)
        }
        if (changed > 0) saveAll(context, recalculated)
        return changed
    }

    fun toCsv(context: Context): String = buildString {
        appendLine("vin,started_at,ended_at,distance_km,battery_used_percent,estimated_kwh,km_per_kwh")
        allRecords(context).forEach { record ->
            appendLine(listOf(record.vin, record.startedAt, record.endedAt, record.distanceKm,
                record.batteryUsedPercent ?: "", record.estimatedKwh ?: "", record.kmPerKwh ?: "").joinToString(",") { csv(it.toString()) })
        }
    }

    /** Merges a previously exported ledger; identical VIN/start/end entries are skipped. */
    fun importCsv(context: Context, reader: BufferedReader): Int {
        val rows = reader.readLines().drop(1)
        val existing = allRecords(context).toMutableList()
        var added = 0
        rows.forEach { line -> runCatching {
            val row = parseCsv(line)
            require(row.size == 7)
            val value = TripRecord(row[0], row[1].toLong(), row[2].toLong(), row[3].toDouble(),
                row[4].toIntOrNull(), row[5].toDoubleOrNull(), row[6].toDoubleOrNull(), null, null)
            if (existing.none { it.vin == value.vin && it.startedAt == value.startedAt && it.endedAt == value.endedAt }) {
                existing.add(value); added++
            }
        } }
        saveAll(context, existing)
        return added
    }

    private fun allRecords(context: Context): List<TripRecord> {
        val values = JSONArray(prefs(context).getString(RECORDS, "[]"))
        return (0 until values.length()).mapNotNull { index ->
            val value = values.optJSONObject(index) ?: return@mapNotNull null
            runCatching { TripRecord(value.getString("vin"), value.getLong("startedAt"), value.getLong("endedAt"),
                value.getDouble("distanceKm"), if (value.isNull("batteryUsedPercent")) null else value.getInt("batteryUsedPercent"),
                estimatedKwh(value), efficiencyKmPerKwh(value),
                batteryStartPercent(value), batteryEndPercent(value), routeStartedAt(value), routeStartedAts(value), mergedPartCount(value),
                efficiencyDistanceKm(value), countsTowardLifetime(value)) }.getOrNull()
        }
    }
    private fun append(context: Context, record: TripRecord) {
        // Read/initialize before appending so a first-run migration does not count this record twice.
        val lifetimeBeforeAppend = record.countsTowardLifetime.takeIf { it }?.let { lifetime(context) }
        val records = JSONArray(prefs(context).getString(RECORDS, "[]"))
        records.put(toJson(record))
        while (records.length() > 200) records.remove(0)
        prefs(context).edit().putString(RECORDS, records.toString()).apply()
        lifetimeBeforeAppend?.let { current ->
            lifetimePrefs(context).edit()
                .putFloat(LIFETIME_DISTANCE_KM, (current.distanceKm + record.distanceKm).toFloat())
                .putLong(LIFETIME_TRIP_COUNT, current.tripCount + 1).apply()
        }
    }
    private fun toJson(record: TripRecord) = JSONObject().apply {
        put("vin", record.vin); put("startedAt", record.startedAt); put("endedAt", record.endedAt)
        put("distanceKm", record.distanceKm); put("batteryUsedPercent", record.batteryUsedPercent)
        put("estimatedKwh", record.estimatedKwh); put("kmPerKwh", record.kmPerKwh)
        put("batteryStartPercent", record.batteryStartPercent); put("batteryEndPercent", record.batteryEndPercent)
        put("routeStartedAt", record.routeStartedAt)
        put("routeStartedAts", JSONArray(record.routeStartedAts)); put("mergedPartCount", record.mergedPartCount)
        put("efficiencyDistanceKm", record.efficiencyDistanceKm)
        put("countsTowardLifetime", record.countsTowardLifetime)
    }
    private fun saveAll(context: Context, values: List<TripRecord>) {
        val json = JSONArray()
        values.sortedByDescending { it.startedAt }.take(200).forEach { record -> json.put(toJson(record)) }
        prefs(context).edit().putString(RECORDS, json.toString()).apply()
    }
    private fun csv(value: String) = "\"${value.replace("\"", "\"\"")}\""
    private fun parseCsv(line: String): List<String> {
        val fields = mutableListOf<String>(); val value = StringBuilder(); var quoted = false; var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> { value.append(char); index++ }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> { fields.add(value.toString()); value.clear() }
                else -> value.append(char)
            }; index++
        }
        fields.add(value.toString()); return fields
    }

    /**
     * Tesla's vehicle data provides model and trim but not usable pack kWh. Keep the
     * selected value with the trip, so later software updates never change old records.
     */
    fun batteryCapacity(vin: String, model: String?, trim: String?): BatteryCapacity {
        val car = model.orEmpty().lowercase()
        val badge = trim.orEmpty().lowercase()
        val isAwd = badge.contains("awd") || badge.endsWith("d") || badge.contains("dual")
        return when {
            car.startsWith("modely") && badge.isBlank() -> BatteryCapacity(75.0, "Model Y 트림 확인 대기")
            car.startsWith("modely") && isAwd ->
                BatteryCapacity(78.4, "Model Y Long Range/AWD")
            car.startsWith("modely") && !isAwd && vin.getOrNull(9)?.uppercaseChar() == 'S' ->
                BatteryCapacity(60.0, "2025 Model Y Juniper RWD")
            car.startsWith("modely") && !isAwd -> BatteryCapacity(60.0, "Model Y RWD")
            car.startsWith("model3") && isAwd -> BatteryCapacity(75.0, "Model 3 Long Range/AWD")
            car.startsWith("model3") -> BatteryCapacity(60.0, "Model 3 RWD")
            car.startsWith("models") || car.startsWith("modelx") -> BatteryCapacity(95.0, "Model S/X")
            else -> BatteryCapacity(75.0, "기본값")
        }
    }
    private fun estimatedKwh(value: JSONObject): Double? {
        if (!value.isNull("estimatedKwh")) return value.getDouble("estimatedKwh")
        // Older merged cards could lose their aggregate if one source card had no measurement.
        // Their originals are retained, so recover the positive measurements from those parts.
        val parts = value.optJSONArray("mergedParts") ?: return null
        return (0 until parts.length()).mapNotNull(parts::optJSONObject).sumOf { estimatedKwh(it) ?: 0.0 }
            .takeIf { it > 0 }
    }

    /** Reads pre-update records that stored the inverse Wh/km efficiency. */
    private fun efficiencyKmPerKwh(value: JSONObject): Double? = when {
        !value.isNull("kmPerKwh") -> value.getDouble("kmPerKwh")
        !value.isNull("whPerKm") -> value.getInt("whPerKm").takeIf { it > 0 }?.let { round(1000.0 / it * 10) / 10.0 }
        else -> estimatedKwh(value)?.takeIf { it > 0 }?.let { energy ->
            efficiencyDistanceKm(value)?.takeIf { it > 0 }?.let { distance ->
                round(distance / energy * 10) / 10.0
            }
        }
    }
    private fun batteryStartPercent(value: JSONObject): Int? =
        if (value.isNull("batteryStartPercent")) null else value.getInt("batteryStartPercent")
    private fun batteryEndPercent(value: JSONObject): Int? =
        if (value.isNull("batteryEndPercent")) null else value.getInt("batteryEndPercent")
    private fun routeStartedAt(value: JSONObject): Long? =
        if (value.isNull("routeStartedAt")) null else value.getLong("routeStartedAt")
    private fun routeStartedAts(value: JSONObject): List<Long> = value.optJSONArray("routeStartedAts")?.let { array ->
        (0 until array.length()).mapNotNull { index -> array.optLong(index, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE } }
    }?.takeIf { it.isNotEmpty() } ?: routeStartedAt(value)?.let(::listOf).orEmpty()
    private fun mergedPartCount(value: JSONObject): Int = value.optInt("mergedPartCount", 0)
    private fun countsTowardLifetime(value: JSONObject): Boolean = value.optBoolean("countsTowardLifetime", true)
    private fun efficiencyDistanceKm(value: JSONObject): Double? {
        if (!value.isNull("efficiencyDistanceKm")) return value.optDouble("efficiencyDistanceKm").takeIf { it > 0 }
        // Repair the calculation for merged cards created before efficiencyDistanceKm existed.
        val parts = value.optJSONArray("mergedParts") ?: return value.optDouble("estimatedKwh").takeIf { it > 0 }
            ?.let { value.optDouble("distanceKm") }
        val distance = (0 until parts.length()).mapNotNull(parts::optJSONObject).sumOf { part ->
            efficiencyDistanceKm(part) ?: 0.0
        }
        return distance.takeIf { it > 0 }
    }
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun lifetimePrefs(context: Context) = context.getSharedPreferences(LIFETIME_PREFS, Context.MODE_PRIVATE)
}
