package kr.co.tesla.cameraalert.trip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class MaintenanceRecord(
    val id: String,
    val occurredAt: Long,
    val description: String,
    val costWon: Long,
    val odometerKm: Double?,
    val shop: String,
    val note: String
)

/** App-private, manually entered vehicle-maintenance history. */
object MaintenanceLedger {
    private const val PREFS = "maintenance_ledger"
    private const val RECORDS = "records"
    private const val MAX_RECORDS = 500

    fun records(context: Context): List<MaintenanceRecord> = JSONArray(prefs(context).getString(RECORDS, "[]"))
        .let { values -> (0 until values.length()).mapNotNull { values.optJSONObject(it)?.let(::fromJson) } }
        .sortedByDescending { it.occurredAt }

    fun backupRecords(context: Context): JSONArray = JSONArray(prefs(context).getString(RECORDS, "[]"))

    fun restoreRecords(context: Context, backup: JSONArray): Int {
        val stored = JSONArray(prefs(context).getString(RECORDS, "[]"))
        val ids = (0 until stored.length()).mapNotNull { stored.optJSONObject(it)?.optString("id") }.toMutableSet()
        var added = 0
        for (index in 0 until backup.length()) {
            val item = backup.optJSONObject(index) ?: continue
            val record = fromJson(item) ?: continue
            if (ids.add(record.id)) { stored.put(item); added++ }
        }
        val sorted = (0 until stored.length()).mapNotNull(stored::optJSONObject)
            .sortedByDescending { it.optLong("occurredAt") }.take(MAX_RECORDS)
        prefs(context).edit().putString(RECORDS, JSONArray(sorted).toString()).apply()
        return added
    }

    fun add(context: Context, occurredAt: Long, description: String, costWon: Long, odometerKm: Double?, shop: String, note: String) {
        require(description.isNotBlank())
        require(costWon >= 0)
        val stored = JSONArray(prefs(context).getString(RECORDS, "[]"))
        stored.put(toJson(MaintenanceRecord(UUID.randomUUID().toString(), occurredAt, description.trim(), costWon,
            odometerKm, shop.trim(), note.trim())))
        while (stored.length() > MAX_RECORDS) stored.remove(0)
        prefs(context).edit().putString(RECORDS, stored.toString()).apply()
    }

    fun delete(context: Context, id: String) {
        val stored = JSONArray(prefs(context).getString(RECORDS, "[]"))
        val kept = JSONArray()
        for (index in 0 until stored.length()) {
            val item = stored.optJSONObject(index) ?: continue
            if (item.optString("id") != id) kept.put(item)
        }
        prefs(context).edit().putString(RECORDS, kept.toString()).apply()
    }

    fun update(context: Context, record: MaintenanceRecord) {
        require(record.description.isNotBlank())
        require(record.costWon >= 0)
        val stored = JSONArray(prefs(context).getString(RECORDS, "[]"))
        for (index in 0 until stored.length()) {
            if (stored.optJSONObject(index)?.optString("id") == record.id) {
                stored.put(index, toJson(record))
                prefs(context).edit().putString(RECORDS, stored.toString()).apply()
                return
            }
        }
    }

    private fun toJson(record: MaintenanceRecord) = JSONObject().apply {
        put("id", record.id); put("occurredAt", record.occurredAt); put("description", record.description)
        put("costWon", record.costWon); put("odometerKm", record.odometerKm); put("shop", record.shop); put("note", record.note)
    }

    private fun fromJson(value: JSONObject): MaintenanceRecord? = runCatching {
        MaintenanceRecord(value.getString("id"), value.getLong("occurredAt"), value.getString("description"),
            value.getLong("costWon"), if (value.isNull("odometerKm")) null else value.getDouble("odometerKm"),
            value.optString("shop"), value.optString("note"))
    }.getOrNull()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
