package kr.co.tesla.cameraalert.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kr.co.tesla.cameraalert.model.OverspeedToneStyle
import kotlin.math.PI
import kotlin.math.sin

/** Generates short alert sounds in this app's process, so per-app audio routing can apply. */
class AppSoundPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private var release: Runnable? = null

    fun playOverspeed(style: OverspeedToneStyle) = when (style) {
        OverspeedToneStyle.SHORT_BEEP -> play(listOf(Pulse(1_050, 180)))
        OverspeedToneStyle.DOUBLE_BEEP -> play(listOf(Pulse(1_000, 110), Pulse(0, 70), Pulse(1_000, 110)))
        OverspeedToneStyle.ACK -> play(listOf(Pulse(820, 190)))
        OverspeedToneStyle.URGENT -> play(listOf(Pulse(1_180, 100), Pulse(0, 55), Pulse(1_180, 100), Pulse(0, 55), Pulse(1_180, 100)))
    }

    fun playCameraPassed() = play(listOf(Pulse(880, 170)))
    fun playFallbackWarning() = play(listOf(Pulse(1_050, 220), Pulse(0, 80), Pulse(1_050, 220)))

    private fun play(pulses: List<Pulse>) {
        val samples = pcm(pulses)
        handler.post {
            release?.let(handler::removeCallbacks)
            track?.release()
            val player = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(samples.size)
                    .build()
            }.getOrNull() ?: return@post
            if (player.write(samples, 0, samples.size) <= 0) {
                player.release(); return@post
            }
            track = player
            player.play()
            release = Runnable {
                if (track === player) track = null
                player.release()
            }.also { handler.postDelayed(it, durationMs(pulses) + 150L) }
        }
    }

    fun release() {
        handler.post {
            release?.let(handler::removeCallbacks); release = null
            track?.release(); track = null
        }
    }

    private fun pcm(pulses: List<Pulse>): ByteArray {
        val values = ArrayList<Short>()
        pulses.forEach { pulse ->
            val count = pulse.durationMs * SAMPLE_RATE / 1_000
            repeat(count) { index ->
                val amplitude = if (pulse.frequencyHz == 0) 0 else
                    (sin(2 * PI * pulse.frequencyHz * index / SAMPLE_RATE) * 0.35 * Short.MAX_VALUE).toInt()
                values += amplitude.toShort()
            }
        }
        return ByteArray(values.size * 2).also { bytes ->
            values.forEachIndexed { index, sample ->
                bytes[index * 2] = (sample.toInt() and 0xff).toByte()
                bytes[index * 2 + 1] = ((sample.toInt() shr 8) and 0xff).toByte()
            }
        }
    }

    private fun durationMs(pulses: List<Pulse>) = pulses.sumOf { it.durationMs }.toLong()

    private data class Pulse(val frequencyHz: Int, val durationMs: Int)
    private companion object { const val SAMPLE_RATE = 22_050 }
}
