package kr.co.tesla.cameraalert.trip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import kotlin.math.round

data class TripRecord(
    val vin: String, val startedAt: Long, val endedAt: Long,
    val distanceKm: Double, val batteryUsedPercent: Int?, val estimatedKwh: Double?, val kmPerKwh: Double?,
    val batteryStartPercent: Int?, val batteryEndPercent: Int?
)

/** Small app-private trip ledger. Odometer is the source of truth for trip distance. */
object TripLedger {
    private const val PREFS = "trip_ledger"
    private const val RECORDS = "records"
    private const val ACTIVE = "active"

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

    fun finish(context: Context, odometerKm: Double, batteryPercent: Int, now: Long): TripRecord? {
        val start = active(context) ?: return null
        val distance = round((odometerKm - start.getDouble("odometerKm")) * 10) / 10
        prefs(context).edit().remove(ACTIVE).apply()
        if (distance < 0.1) return null
        val batteryUsed = (start.getInt("batteryPercent") - batteryPercent).takeIf { it >= 0 }
        val capacity = start.optDouble("batteryCapacityKwh", Double.NaN).takeIf { it.isFinite() && it > 0 }
            ?: batteryCapacity(start.getString("vin"), start.optString("model"), start.optString("trim")).kwh
        val kwh = batteryUsed?.let { percent -> round(capacity * percent) / 100.0 }
        val kmPerKwh = kwh?.takeIf { it > 0 }?.let { round(distance / it * 10) / 10.0 }
        val record = TripRecord(start.getString("vin"), start.getLong("startedAt"), now, distance, batteryUsed, kwh, kmPerKwh,
            start.getInt("batteryPercent"), batteryPercent)
        val records = JSONArray(prefs(context).getString(RECORDS, "[]"))
        records.put(JSONObject().apply {
            put("vin", record.vin); put("startedAt", record.startedAt); put("endedAt", record.endedAt)
            put("distanceKm", record.distanceKm); put("batteryUsedPercent", record.batteryUsedPercent)
            put("estimatedKwh", record.estimatedKwh); put("kmPerKwh", record.kmPerKwh)
            put("batteryStartPercent", record.batteryStartPercent); put("batteryEndPercent", record.batteryEndPercent)
        })
        while (records.length() > 200) records.remove(0)
        prefs(context).edit().putString(RECORDS, records.toString()).apply()
        return record
    }

    fun active(context: Context): JSONObject? = prefs(context).getString(ACTIVE, null)?.let(::JSONObject)
    fun records(context: Context, vin: String): List<TripRecord> {
        val values = JSONArray(prefs(context).getString(RECORDS, "[]"))
        return (0 until values.length()).mapNotNull { index ->
            val value = values.optJSONObject(index) ?: return@mapNotNull null
            if (value.optString("vin") != vin) return@mapNotNull null
            TripRecord(value.getString("vin"), value.getLong("startedAt"), value.getLong("endedAt"),
                value.getDouble("distanceKm"), if (value.isNull("batteryUsedPercent")) null else value.getInt("batteryUsedPercent"),
                if (value.isNull("estimatedKwh")) null else value.getDouble("estimatedKwh"), efficiencyKmPerKwh(value),
                batteryStartPercent(value), batteryEndPercent(value))
        }.sortedByDescending { it.startedAt }
    }

    /** Rebuilds calculated energy and efficiency while retaining the original trip facts. */
    fun recalculateEfficiency(context: Context, vin: String, capacityKwh: Double): Int {
        require(capacityKwh > 0)
        var changed = 0
        val recalculated = allRecords(context).map { record ->
            if (record.vin != vin || record.batteryUsedPercent == null) return@map record
            val kwh = round(capacityKwh * record.batteryUsedPercent) / 100.0
            val efficiency = kwh.takeIf { it > 0 }?.let { round(record.distanceKm / it * 10) / 10.0 }
            changed++
            record.copy(estimatedKwh = kwh, kmPerKwh = efficiency)
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
                if (value.isNull("estimatedKwh")) null else value.getDouble("estimatedKwh"), efficiencyKmPerKwh(value),
                batteryStartPercent(value), batteryEndPercent(value)) }.getOrNull()
        }
    }
    private fun saveAll(context: Context, values: List<TripRecord>) {
        val json = JSONArray()
        values.sortedByDescending { it.startedAt }.take(200).forEach { record -> json.put(JSONObject().apply {
            put("vin", record.vin); put("startedAt", record.startedAt); put("endedAt", record.endedAt)
            put("distanceKm", record.distanceKm); put("batteryUsedPercent", record.batteryUsedPercent)
            put("estimatedKwh", record.estimatedKwh); put("kmPerKwh", record.kmPerKwh)
            put("batteryStartPercent", record.batteryStartPercent); put("batteryEndPercent", record.batteryEndPercent)
        }) }
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
    /** Reads pre-update records that stored the inverse Wh/km efficiency. */
    private fun efficiencyKmPerKwh(value: JSONObject): Double? = when {
        !value.isNull("kmPerKwh") -> value.getDouble("kmPerKwh")
        !value.isNull("whPerKm") -> value.getInt("whPerKm").takeIf { it > 0 }?.let { round(1000.0 / it * 10) / 10.0 }
        else -> null
    }
    private fun batteryStartPercent(value: JSONObject): Int? =
        if (value.isNull("batteryStartPercent")) null else value.getInt("batteryStartPercent")
    private fun batteryEndPercent(value: JSONObject): Int? =
        if (value.isNull("batteryEndPercent")) null else value.getInt("batteryEndPercent")
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
