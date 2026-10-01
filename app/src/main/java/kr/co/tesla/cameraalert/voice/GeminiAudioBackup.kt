package kr.co.tesla.cameraalert.voice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Separate portable backup for generated Gemini audio. API keys are never included. */
object GeminiAudioBackup {
    private const val FORMAT = "woongpilot-gemini-audio-backup"
    private const val VERSION = 1

    fun export(context: Context): String = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("createdAt", System.currentTimeMillis())
        put("audioCache", GeminiTts(context).backupCachedAudio())
    }.toString()

    fun restore(context: Context, source: String): Int {
        val backup = JSONObject(source)
        require(backup.optString("format") == FORMAT) { "Gemini 음성 캐시 백업 파일이 아닙니다." }
        require(backup.optInt("version") == VERSION) { "지원하지 않는 음성 캐시 백업 버전입니다." }
        return GeminiTts(context).restoreCachedAudio(backup.optJSONArray("audioCache") ?: JSONArray())
    }
}
