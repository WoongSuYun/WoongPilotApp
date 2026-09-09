package kr.co.tesla.cameraalert.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kr.co.tesla.cameraalert.model.SafetyAlertType
import java.io.File
import java.util.Locale

/** Uses the phone's installed Korean TTS engine, so camera warnings work without mobile data. */
class AlertSpeaker(context: Context) : TextToSpeech.OnInitListener {
    data class VoiceOption(val name: String?, val label: String) {
        override fun toString(): String = label
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var engine: TextToSpeech? = null
    private var player: MediaPlayer? = null
    private var synthesizedFile: File? = null
    @Volatile private var activeUtteranceId: String? = null
    private var utteranceSequence = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var ready = false
    private val readyActions = mutableListOf<() -> Unit>()

    init {
        runCatching { engine = TextToSpeech(appContext, this) }
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            engine?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = Unit
                override fun onDone(utteranceId: String) {
                    if (utteranceId == activeUtteranceId) mainHandler.post { playSynthesizedFile(utteranceId) }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) {
                    if (utteranceId == activeUtteranceId) discardSynthesizedFile()
                }
            })
            applySavedSettings()
        }
        val actions = readyActions.toList()
        readyActions.clear()
        actions.forEach { it() }
    }

    fun whenReady(action: () -> Unit) {
        if (ready) action() else readyActions += action
    }

    fun voiceOptions(): List<VoiceOption> {
        val korean = engine?.voices.orEmpty()
            .filter { it.locale.language.equals("ko", ignoreCase = true) }
            .sortedWith(compareBy<Voice> { it.isNetworkConnectionRequired }.thenBy { it.name })
            .mapIndexed { index, voice -> VoiceOption(voice.name, voiceLabel(voice, index + 1)) }
        return listOf(VoiceOption(null, "기본 한국어 음성")) + korean
    }

    fun savedVoiceName(): String? = prefs.getString(KEY_VOICE, null)
    fun savedRate(): Float = prefs.getFloat(KEY_RATE, 1.0f)
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun save(enabled: Boolean, voiceName: String?, rate: Float) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).putString(KEY_VOICE, voiceName)
            .putFloat(KEY_RATE, rate.coerceIn(0.6f, 1.4f)).apply()
        applySavedSettings()
    }

    fun preview(voiceName: String?, rate: Float): Boolean =
        speak(PREVIEW, voiceName, rate.coerceIn(0.6f, 1.4f), force = true)

    fun speakCameraWarning(distanceMeters: Int, limitKph: Int): Boolean {
        return speakSafetyWarning(SafetyAlertType.SPEED_CAMERA, distanceMeters, limitKph)
    }

    fun speakSafetyWarning(type: SafetyAlertType, distanceMeters: Int, limitKph: Int?): Boolean {
        if (!isEnabled()) return true
        return speak(type.spokenText(distanceMeters, limitKph),
            savedVoiceName(), savedRate(), force = false)
    }

    private fun speak(text: String, voiceName: String?, rate: Float, force: Boolean): Boolean {
        val tts = engine ?: return false
        if (!ready) return false
        if (!force && !isEnabled()) return true
        configure(voiceName, rate)
        tts.stop()
        stopPlayback()
        val output = runCatching { File.createTempFile("camera-warning-", ".wav", appContext.cacheDir) }
            .getOrNull() ?: return false
        synthesizedFile = output
        val utteranceId = "$UTTERANCE_ID-${++utteranceSequence}"
        activeUtteranceId = utteranceId
        // The TTS engine creates only the audio data. Playback is then owned by this app, which
        // lets Samsung's Separate app sound route it like Gemini's AudioTrack output.
        val queued = tts.synthesizeToFile(text, Bundle(), output, utteranceId) == TextToSpeech.SUCCESS
        if (!queued) discardSynthesizedFile()
        return queued
    }

    private fun applySavedSettings() = configure(savedVoiceName(), savedRate())

    private fun configure(voiceName: String?, rate: Float) {
        val tts = engine ?: return
        tts.language = Locale.KOREAN
        val selected = tts.voices.orEmpty().firstOrNull { it.name == voiceName }
        if (selected != null) tts.voice = selected
        tts.setSpeechRate(rate.coerceIn(0.6f, 1.4f))
        tts.setPitch(1.0f)
    }

    fun shutdown() {
        readyActions.clear()
        engine?.stop()
        engine?.shutdown()
        stopPlayback()
        engine = null
        ready = false
    }

    private fun playSynthesizedFile(utteranceId: String) {
        if (utteranceId != activeUtteranceId) return
        val output = synthesizedFile ?: return
        activeUtteranceId = null
        val next = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                setDataSource(output.absolutePath)
                setOnCompletionListener { releasePlayback(it) }
                setOnErrorListener { media, _, _ -> releasePlayback(media); true }
                prepare()
            }
        }.getOrNull() ?: run {
            discardSynthesizedFile(); return
        }
        player = next
        next.start()
    }

    private fun stopPlayback() {
        val stop = {
            player?.let { media -> runCatching { media.stop() }; media.release() }
            player = null
            discardSynthesizedFile()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) stop() else mainHandler.post(stop)
    }

    private fun releasePlayback(media: MediaPlayer) {
        val current = player === media
        if (current) player = null
        media.release()
        if (current) discardSynthesizedFile()
    }

    private fun discardSynthesizedFile() {
        activeUtteranceId = null
        synthesizedFile?.delete()
        synthesizedFile = null
    }

    private fun voiceLabel(voice: Voice, number: Int): String {
        val availability = if (voice.isNetworkConnectionRequired) "인터넷 필요" else "오프라인"
        return "한국어 목소리 $number · $availability"
    }

    companion object {
        private const val PREFS = "voice_settings"
        private const val KEY_ENABLED = "voice_enabled"
        private const val KEY_VOICE = "voice_name"
        private const val KEY_RATE = "voice_rate"
        private const val UTTERANCE_ID = "camera_warning"
        private const val PREVIEW = "전방 500미터, 제한속도 60킬로미터, 과속 단속 카메라입니다."
    }
}
