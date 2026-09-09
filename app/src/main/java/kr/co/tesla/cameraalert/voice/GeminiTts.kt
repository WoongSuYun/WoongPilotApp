package kr.co.tesla.cameraalert.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kr.co.tesla.cameraalert.model.SafetyAlertType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.ceil

/**
 * Optional cloud speech for a user-supplied Gemini API key.
 *
 * The key is encrypted with an Android Keystore key before it is saved in app
 * preferences. It is never included in an APK, displayed again, or logged.
 */
class GeminiTts(context: Context) {
    data class VoiceOption(val name: String, val label: String) {
        override fun toString(): String = label
    }
    data class CachedAudioItem(
        val key: String,
        val voiceName: String,
        val text: String,
        val savedAtMillis: Long
    )

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val playbackLock = Any()
    private var player: AudioTrack? = null
    private var releasePlayer: Runnable? = null
    private val audioCache = GeminiAudioCache(appContext)
    @Volatile private var lastFailure = ""
    @Volatile private var lastPlaybackFromCache = false
    @Volatile private var lastHttpStatus: Int? = null

    private data class GeneratedAudio(
        val bytes: ByteArray,
        val mimeType: String?,
        val sampleRate: Int?,
        val channels: Int?
    )
    private data class PcmAudio(val bytes: ByteArray, val sampleRate: Int, val channels: Int)
    private data class CacheEntry(
        val audio: GeneratedAudio,
        val voiceName: String,
        val text: String
    )

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)
    fun savedVoiceName(): String = prefs.getString(KEY_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE
    fun hasApiKey(): Boolean = GeminiApiKeyStore.hasKey(appContext)
    fun hasAnyApiKey(): Boolean = hasApiKey() || hasSecondaryApiKey()
    fun savedApiKey(): String? = GeminiApiKeyStore.read(appContext)
    fun hasSecondaryApiKey(): Boolean = GeminiApiKeyStore.readSecondary(appContext) != null
    fun savedSecondaryApiKey(): String? = GeminiApiKeyStore.readSecondary(appContext)
    fun saveSecondaryApiKey(apiKey: String): Boolean = GeminiApiKeyStore.saveSecondary(appContext, apiKey)
    fun clearSecondaryApiKey() = GeminiApiKeyStore.clearSecondary(appContext)
    fun lastFailureMessage(): String = lastFailure
    fun lastPlaybackUsedCache(): Boolean = lastPlaybackFromCache

    fun save(enabled: Boolean, voiceName: String) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_VOICE, voiceName).apply()
    }

    fun saveApiKey(apiKey: String): Boolean = GeminiApiKeyStore.save(appContext, apiKey)

    fun clearApiKey() = GeminiApiKeyStore.clear(appContext)
    fun clearCachedAudio() = audioCache.clear()
    fun cachedAudioItems(): List<CachedAudioItem> = audioCache.list()

    /** Replays a cached item only; it never makes a Gemini API request. */
    suspend fun playCachedAudio(cacheKey: String): Boolean = withContext(Dispatchers.IO) {
        lastFailure = ""
        lastPlaybackFromCache = false
        val entry = audioCache.load(cacheKey) ?: run {
            lastFailure = "저장된 음성을 찾지 못했습니다."
            return@withContext false
        }
        lastPlaybackFromCache = true
        val played = playAudio(entry.audio)
        if (!played && lastFailure.isBlank()) lastFailure = "저장된 음성을 재생하지 못했습니다."
        played
    }

    /** Returns false when Gemini is disabled, unavailable, or does not return playable audio. */
    suspend fun speakCameraWarning(distanceMeters: Int, limitKph: Int): Boolean {
        return speakSafetyWarning(SafetyAlertType.SPEED_CAMERA, distanceMeters, limitKph)
    }

    suspend fun speakSafetyWarning(type: SafetyAlertType, distanceMeters: Int, limitKph: Int?): Boolean {
        if (!isEnabled()) return false
        return speak(type.spokenText(distanceMeters, limitKph), savedVoiceName())
    }

    /** Plays the currently selected Gemini voice even before the enable switch is saved. */
    suspend fun preview(voiceName: String = savedVoiceName()): Boolean =
        speak("전방 500미터, 제한속도 60킬로미터, 과속 단속 카메라입니다.", voiceName)

    private suspend fun speak(text: String, voiceName: String): Boolean = withContext(Dispatchers.IO) {
        lastFailure = ""
        lastPlaybackFromCache = false
        val apiKeys = listOfNotNull(GeminiApiKeyStore.read(appContext), GeminiApiKeyStore.readSecondary(appContext))
        if (apiKeys.isEmpty()) return@withContext false
        val cacheKey = cacheKey(text, voiceName)
        val cached = audioCache.load(cacheKey)
        val audio = cached?.audio ?: run {
            var generated: GeneratedAudio? = null
            for ((index, apiKey) in apiKeys.withIndex()) {
                lastHttpStatus = null
                generated = try { requestAudio(apiKey, text, voiceName) } catch (e: Exception) {
                    lastFailure = networkFailure(e); null
                }
                if (generated != null || lastHttpStatus != 429 || index == apiKeys.lastIndex) break
            }
            generated
        } ?: return@withContext false
        if (cached != null) lastPlaybackFromCache = true else audioCache.store(cacheKey, voiceName, text, audio)
        val played = playAudio(audio)
        if (!played && lastFailure.isBlank()) lastFailure = "받은 음성 형식을 기기에서 재생하지 못했습니다."
        played
    }

    private fun requestAudio(apiKey: String, text: String, voiceName: String): GeneratedAudio? {
        val requestBody = JSONObject()
            .put("model", MODEL)
            .put("input", "다음 문장을 자연스럽고 또렷한 한국어 운전 안내 음성으로 읽으세요. 다른 말은 덧붙이지 마세요.\n$text")
            .put("store", false)
            .put("response_format", JSONObject().put("type", "audio"))
            .put("generation_config", JSONObject().put("speech_config", JSONArray().put(
                JSONObject().put("voice", voiceName)
            )))
            .toString()
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("x-goog-api-key", apiKey)
            connection.setRequestProperty("Api-Revision", "2026-05-20")
            connection.outputStream.use { stream ->
                stream.write(requestBody.toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            lastHttpStatus = code
            val payload = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                lastFailure = apiFailure(code)
                return null
            }
            if (payload.isBlank()) {
                lastFailure = "Gemini가 비어 있는 응답을 보냈습니다."
                return null
            }
            extractAudio(payload) ?: run {
                lastFailure = "Gemini 응답에서 음성 데이터를 찾지 못했습니다."
                null
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Supports current REST `steps[].content[]` audio and the older SDK-shaped response. */
    private fun extractAudio(payload: String): GeneratedAudio? {
        val root = JSONObject(payload)
        val block = root.optJSONObject("output_audio") ?: root.optJSONObject("outputAudio")
            ?: firstAudioBlock(root.optJSONArray("steps"))
            ?: firstAudioBlock(root.optJSONArray("outputs"))
            ?: return null
        val data = block.optString("data")
        if (data.isBlank()) return null
        val bytes = runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull() ?: return null
        return GeneratedAudio(
            bytes = bytes,
            mimeType = block.optString("mime_type").ifBlank { block.optString("mimeType") }.ifBlank { null },
            sampleRate = block.optInt("sample_rate", block.optInt("sampleRate", 0)).takeIf { it > 0 },
            channels = block.optInt("channels", 0).takeIf { it > 0 }
        )
    }

    private fun firstAudioBlock(steps: JSONArray?): JSONObject? {
        if (steps == null) return null
        for (index in 0 until steps.length()) {
            val step = steps.optJSONObject(index) ?: continue
            if (step.optString("type") == "audio" && step.optString("data").isNotBlank()) return step
            val content = step.optJSONArray("content") ?: continue
            for (contentIndex in 0 until content.length()) {
                val item = content.optJSONObject(contentIndex) ?: continue
                if (item.optString("type") == "audio" && item.optString("data").isNotBlank()) return item
            }
        }
        return null
    }

    /** Gemini TTS normally returns 24 kHz, 16-bit, mono PCM; WAV responses are also accepted. */
    private fun playAudio(audio: GeneratedAudio): Boolean {
        val pcm = if (audio.mimeType.orEmpty().contains("wav", ignoreCase = true) || looksLikeWav(audio.bytes)) {
            wavToPcm(audio.bytes) ?: run {
                lastFailure = "지원하지 않는 WAV 음성 형식입니다."
                return false
            }
        } else PcmAudio(audio.bytes, audio.sampleRate ?: SAMPLE_RATE, audio.channels ?: 1)
        return playPcm(pcm)
    }

    private fun playPcm(audio: PcmAudio): Boolean = synchronized(playbackLock) {
        if (audio.bytes.isEmpty()) return false
        val sampleRate = audio.sampleRate.coerceIn(8_000, 192_000)
        val channels = if (audio.channels == 2) 2 else 1
        val channelMask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        stopPlayerLocked()
        val minimumBuffer = AudioTrack.getMinBufferSize(
            sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimumBuffer <= 0) return false
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(maxOf(minimumBuffer, audio.bytes.size))
                .build()
        }.getOrNull() ?: return false
        val written = runCatching { track.write(audio.bytes, 0, audio.bytes.size) }.getOrDefault(0)
        if (written <= 0) {
            track.release()
            return false
        }
        player = track
        track.play()
        val durationMs = ceil((written / (2.0 * channels)) * 1000.0 / sampleRate).toLong() + 400L
        val release = Runnable {
            synchronized(playbackLock) {
                if (player === track) stopPlayerLocked()
            }
        }
        releasePlayer = release
        mainHandler.postDelayed(release, durationMs)
        true
    }

    private fun looksLikeWav(bytes: ByteArray): Boolean = bytes.size >= 12 &&
        bytes[0].toInt() == 'R'.code && bytes[1].toInt() == 'I'.code &&
        bytes[2].toInt() == 'F'.code && bytes[3].toInt() == 'F'.code &&
        bytes[8].toInt() == 'W'.code && bytes[9].toInt() == 'A'.code &&
        bytes[10].toInt() == 'V'.code && bytes[11].toInt() == 'E'.code

    private fun wavToPcm(bytes: ByteArray): PcmAudio? {
        if (!looksLikeWav(bytes)) return null
        var offset = 12
        var channels = 0
        var sampleRate = 0
        var bitDepth = 0
        var pcmData: ByteArray? = null
        while (offset + 8 <= bytes.size) {
            val size = littleEndianInt(bytes, offset + 4)
            val dataStart = offset + 8
            val dataEnd = dataStart + size
            if (size < 0 || dataEnd > bytes.size) return null
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            when (id) {
                "fmt " -> if (size >= 16) {
                    if (littleEndianShort(bytes, dataStart) != 1) return null
                    channels = littleEndianShort(bytes, dataStart + 2)
                    sampleRate = littleEndianInt(bytes, dataStart + 4)
                    bitDepth = littleEndianShort(bytes, dataStart + 14)
                }
                "data" -> pcmData = bytes.copyOfRange(dataStart, dataEnd)
            }
            offset = dataEnd + (size and 1)
        }
        val data = pcmData ?: return null
        if (channels !in 1..2 || sampleRate <= 0 || bitDepth != 16) return null
        return PcmAudio(data, sampleRate, channels)
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun apiFailure(code: Int): String = when (code) {
        400 -> "Gemini 요청이 거부되었습니다 (400). 앱을 최신 버전으로 업데이트한 뒤 다시 시도하세요."
        401 -> "Gemini API 키를 인증하지 못했습니다 (401). 키를 다시 저장해 주세요."
        403 -> "Gemini TTS 사용 권한이 없습니다 (403). 이 키의 프로젝트·결제·API 제한을 확인하세요."
        404 -> "Gemini TTS 모델을 찾지 못했습니다 (404)."
        429 -> "Gemini 호출 한도에 도달했습니다 (429). 잠시 후 다시 시도하세요."
        in 500..599 -> "Gemini 서버가 일시적으로 응답하지 않습니다 ($code)."
        else -> "Gemini API 오류 ($code)가 발생했습니다."
    }

    private fun networkFailure(error: Exception): String = when (error) {
        is java.net.SocketTimeoutException -> "Gemini 서버 응답 시간이 초과되었습니다."
        is java.net.UnknownHostException -> "인터넷 연결 또는 DNS를 확인하세요."
        is javax.net.ssl.SSLException -> "Gemini 보안 연결에 실패했습니다."
        else -> "Gemini 연결 중 오류가 발생했습니다."
    }

    private fun cacheKey(text: String, voiceName: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$MODEL|$voiceName|$text".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** App-private bounded disk cache: no API key or user account data is written here. */
    private class GeminiAudioCache(context: Context) {
        private val directory = File(context.cacheDir, "gemini_tts_cache")

        fun load(key: String): CacheEntry? {
            val file = File(directory, "$key.bin")
            if (!file.isFile) return null
            return readEntry(file).also { entry ->
                if (entry != null) file.setLastModified(System.currentTimeMillis())
            } ?: run {
                runCatching { file.delete() }
                null
            }
        }

        fun store(key: String, voiceName: String, text: String, audio: GeneratedAudio) {
            if (audio.bytes.isEmpty() || audio.bytes.size > MAX_AUDIO_BYTES) return
            runCatching {
                if (!directory.exists()) require(directory.mkdirs() || directory.isDirectory)
                DataOutputStream(BufferedOutputStream(File(directory, "$key.bin").outputStream())).use { output ->
                    output.writeInt(MAGIC)
                    output.writeUTF(audio.mimeType.orEmpty())
                    output.writeInt(audio.sampleRate ?: 0)
                    output.writeInt(audio.channels ?: 0)
                    output.writeUTF(voiceName)
                    output.writeUTF(text)
                    output.writeInt(audio.bytes.size)
                    output.write(audio.bytes)
                }
                trim()
            }
        }

        fun list(): List<CachedAudioItem> = directory.listFiles()?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { file -> readMetadata(file) ?: run { runCatching { file.delete() }; null } }
            .orEmpty()

        fun clear() {
            directory.listFiles()?.forEach { file -> runCatching { file.delete() } }
        }

        private fun trim() {
            var bytes = 0L
            var entries = 0
            directory.listFiles()?.filter { it.isFile }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { file ->
                    if (entries >= MAX_ENTRIES || bytes + file.length() > MAX_CACHE_BYTES) {
                        runCatching { file.delete() }
                    } else {
                        entries++
                        bytes += file.length()
                    }
                }
        }

        private fun readEntry(file: File): CacheEntry? = runCatching {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                require(input.readInt() == MAGIC)
                val mimeType = input.readUTF().ifBlank { null }
                val sampleRate = input.readInt().takeIf { it > 0 }
                val channels = input.readInt().takeIf { it > 0 }
                val voiceName = input.readUTF()
                val text = input.readUTF()
                val size = input.readInt()
                require(size in 1..MAX_AUDIO_BYTES)
                val bytes = ByteArray(size)
                input.readFully(bytes)
                CacheEntry(GeneratedAudio(bytes, mimeType, sampleRate, channels), voiceName, text)
            }
        }.getOrNull()

        private fun readMetadata(file: File): CachedAudioItem? = runCatching {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                require(input.readInt() == MAGIC)
                input.readUTF() // MIME type
                input.readInt() // sample rate
                input.readInt() // channels
                val voiceName = input.readUTF()
                val text = input.readUTF()
                require(text.isNotBlank())
                CachedAudioItem(file.nameWithoutExtension, voiceName, text, file.lastModified())
            }
        }.getOrNull()

        private companion object {
            const val MAGIC = 0x57505432 // WPT2
            const val MAX_ENTRIES = 40
            const val MAX_AUDIO_BYTES = 1_500_000
            const val MAX_CACHE_BYTES = 12L * 1024 * 1024
        }
    }

    fun shutdown() = synchronized(playbackLock) { stopPlayerLocked() }

    private fun stopPlayerLocked() {
        releasePlayer?.let(mainHandler::removeCallbacks)
        releasePlayer = null
        player?.let { track ->
            runCatching { track.stop() }
            track.release()
        }
        player = null
    }

    companion object {
        private const val PREFS = "voice_settings"
        private const val KEY_ENABLED = "gemini_enabled"
        private const val KEY_VOICE = "gemini_voice"
        private const val DEFAULT_VOICE = "Kore"
        private const val SAMPLE_RATE = 24_000
        private const val MODEL = "gemini-3.1-flash-tts-preview"
        private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"

        fun voiceOptions(): List<VoiceOption> = listOf(
            VoiceOption("Kore", "Kore · 단단한"),
            VoiceOption("Zephyr", "Zephyr · 밝은"),
            VoiceOption("Puck", "Puck · 경쾌한"),
            VoiceOption("Charon", "Charon · 깊은"),
            VoiceOption("Fenrir", "Fenrir · 힘있는"),
            VoiceOption("Leda", "Leda · 부드러운"),
            VoiceOption("Orus", "Orus · 또렷한"),
            VoiceOption("Aoede", "Aoede · 따뜻한"),
            VoiceOption("Callirrhoe", "Callirrhoe · 차분한"),
            VoiceOption("Autonoe", "Autonoe · 편안한"),
            VoiceOption("Enceladus", "Enceladus · 맑은"),
            VoiceOption("Iapetus", "Iapetus · 낮은"),
            VoiceOption("Umbriel", "Umbriel · 안정적인"),
            VoiceOption("Algieba", "Algieba · 친근한"),
            VoiceOption("Despina", "Despina · 선명한"),
            VoiceOption("Erinome", "Erinome · 생기있는"),
            VoiceOption("Algenib", "Algenib · 단정한"),
            VoiceOption("Rasalgethi", "Rasalgethi · 신뢰감 있는"),
            VoiceOption("Laomedeia", "Laomedeia · 온화한"),
            VoiceOption("Achernar", "Achernar · 빠른"),
            VoiceOption("Alnilam", "Alnilam · 침착한"),
            VoiceOption("Schedar", "Schedar · 또렷한"),
            VoiceOption("Gacrux", "Gacrux · 산뜻한"),
            VoiceOption("Pulcherrima", "Pulcherrima · 부드러운"),
            VoiceOption("Achird", "Achird · 명료한"),
            VoiceOption("Zubenelgenubi", "Zubenelgenubi · 중후한"),
            VoiceOption("Vindemiatrix", "Vindemiatrix · 편안한"),
            VoiceOption("Sadachbia", "Sadachbia · 활기찬"),
            VoiceOption("Sadaltager", "Sadaltager · 차분한"),
            VoiceOption("Sulafat", "Sulafat · 밝고 선명한")
        )
    }
}

/** Encrypts the per-device Gemini key with a non-exportable Android Keystore AES key. */
private object GeminiApiKeyStore {
    private const val PREFS = "voice_settings"
    private const val KEY_IV = "gemini_key_iv"
    private const val KEY_CIPHER = "gemini_key_cipher"
    private const val KEY_SECONDARY_IV = "gemini_secondary_key_iv"
    private const val KEY_SECONDARY_CIPHER = "gemini_secondary_key_cipher"
    private const val KEY_ALIAS = "woongpilot_gemini_key_v1"

    fun hasKey(context: Context): Boolean = read(context) != null

    fun save(context: Context, rawKey: String): Boolean = save(context, rawKey, KEY_IV, KEY_CIPHER)
    fun saveSecondary(context: Context, rawKey: String): Boolean = save(context, rawKey, KEY_SECONDARY_IV, KEY_SECONDARY_CIPHER)
    private fun save(context: Context, rawKey: String, ivKey: String, cipherKey: String): Boolean = runCatching {
        val apiKey = rawKey.trim()
        require(apiKey.isNotEmpty())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ivKey, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(cipherKey, Base64.encodeToString(cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
            .apply()
    }.isSuccess

    fun read(context: Context): String? = read(context, KEY_IV, KEY_CIPHER)
    fun readSecondary(context: Context): String? = read(context, KEY_SECONDARY_IV, KEY_SECONDARY_CIPHER)
    private fun read(context: Context, ivKey: String, cipherKey: String): String? = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val iv = prefs.getString(ivKey, null) ?: return null
        val encrypted = prefs.getString(cipherKey, null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.DEFAULT)))
        String(cipher.doFinal(Base64.decode(encrypted, Base64.DEFAULT)), Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    }.getOrElse {
        clear(context, ivKey, cipherKey)
        null
    }

    fun clear(context: Context) {
        clear(context, KEY_IV, KEY_CIPHER)
    }
    fun clearSecondary(context: Context) = clear(context, KEY_SECONDARY_IV, KEY_SECONDARY_CIPHER)
    private fun clear(context: Context, ivKey: String, cipherKey: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(ivKey).remove(cipherKey).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }
}
