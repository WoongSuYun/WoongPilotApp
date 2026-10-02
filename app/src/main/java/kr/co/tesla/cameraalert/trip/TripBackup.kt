package kr.co.tesla.cameraalert.trip

import android.content.Context
import kr.co.tesla.cameraalert.route.RouteLedger
import kr.co.tesla.cameraalert.voice.GeminiTts
import org.json.JSONObject

/** Portable driving-data backup. Gemini audio has its own separately managed backup file. */
object TripBackup {
    private const val FORMAT = "woongpilot-trip-backup"
    private const val VERSION = 4

    data class RestoreResult(val trips: Int, val routes: Int, val maintenance: Int, val audioCache: Int)

    fun export(context: Context): String = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("createdAt", System.currentTimeMillis())
        put("trips", TripLedger.backupRecords(context))
        put("routes", RouteLedger.backupRecords(context))
        put("maintenance", MaintenanceLedger.backupRecords(context))
    }.toString()

    /** Merges backup data with the device so current records are never overwritten. */
    fun restore(context: Context, source: String): RestoreResult {
        val backup = JSONObject(source)
        require(backup.optString("format") == FORMAT) { "웅파일럿 전체 백업 파일이 아닙니다." }
        val version = backup.optInt("version")
        require(version in 1..VERSION) { "지원하지 않는 백업 버전입니다." }
        val trips = backup.optJSONArray("trips") ?: error("운행기록 데이터가 없습니다.")
        val routes = backup.optJSONArray("routes") ?: error("GPS 경로 데이터가 없습니다.")
        // Version 2 briefly stored audio inside the driving backup. Keep that file restorable.
        val legacyAudio = if (version == 2) GeminiTts(context).restoreCachedAudio(backup.optJSONArray("geminiAudioCache") ?: org.json.JSONArray()) else 0
        val maintenance = if (version >= 4) MaintenanceLedger.restoreRecords(context, backup.optJSONArray("maintenance") ?: org.json.JSONArray()) else 0
        return RestoreResult(TripLedger.restoreRecords(context, trips), RouteLedger.restoreRecords(context, routes), maintenance, legacyAudio)
    }
}
