package com.neopal.pet.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/** One short sound effect, written as (note, milliseconds) pairs in a tiny chiptune language. */
enum class Sfx(val notes: List<Pair<Float, Int>>, val wave: Wave = Wave.SQUARE) {
    SELECT(listOf(880f to 40)),
    BACK(listOf(520f to 45)),
    CONFIRM(listOf(660f to 45, 880f to 70)),
    DENY(listOf(220f to 90, 165f to 120)),
    EAT(listOf(520f to 45, 620f to 45, 740f to 60)),
    HAPPY(listOf(660f to 60, 880f to 60, 1046f to 90)),
    CLEAN(listOf(1046f to 40, 880f to 40, 1174f to 60), Wave.TRIANGLE),
    HEAL(listOf(740f to 60, 988f to 60, 1318f to 120), Wave.TRIANGLE),
    SLEEP(listOf(392f to 90, 330f to 120, 262f to 180), Wave.TRIANGLE),
    LEVEL_UP(listOf(523f to 70, 659f to 70, 784f to 70, 1046f to 160)),
    EVOLVE(listOf(392f to 90, 523f to 90, 659f to 90, 784f to 90, 1046f to 240)),
    HATCH(listOf(659f to 60, 784f to 60, 988f to 60, 1318f to 200)),
    COIN(listOf(988f to 45, 1318f to 110)),
    GAME_HIT(listOf(784f to 35)),
    GAME_MISS(listOf(196f to 110)),
    DEATH(listOf(392f to 200, 330f to 220, 262f to 260, 196f to 500), Wave.TRIANGLE);

    enum class Wave { SQUARE, TRIANGLE }
}

/**
 * A four-kilobyte substitute for a sound pack: every effect is synthesised on the fly, so the
 * app ships with no audio assets at all. Playback is fire-and-forget on a background thread.
 */
object ChiptuneEngine {

    private const val SAMPLE_RATE = 22_050
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    var enabled: Boolean = true

    fun play(sfx: Sfx) {
        if (!enabled) return
        scope.launch { runCatching { render(sfx) } }
    }

    private fun render(sfx: Sfx) {
        val samples = buildSamples(sfx)
        val track = buildTrack(samples.size * 2)
        track.write(samples, 0, samples.size)
        track.play()
        // AudioTrack in STATIC mode plays the whole buffer; release once it is done.
        val durationMs = (samples.size * 1000L) / SAMPLE_RATE + 60
        Thread.sleep(durationMs)
        runCatching {
            track.stop()
            track.release()
        }
    }

    private fun buildSamples(sfx: Sfx): ShortArray {
        val total = sfx.notes.sumOf { (_, ms) -> ms * SAMPLE_RATE / 1000 }
        val out = ShortArray(total)
        var offset = 0
        for ((frequency, ms) in sfx.notes) {
            val count = ms * SAMPLE_RATE / 1000
            for (i in 0 until count) {
                val t = i.toDouble() / SAMPLE_RATE
                val phase = (t * frequency) % 1.0
                val raw = when (sfx.wave) {
                    Sfx.Wave.SQUARE -> if (phase < 0.5) 1.0 else -1.0
                    Sfx.Wave.TRIANGLE -> 4.0 * kotlin.math.abs(phase - 0.5) - 1.0
                }
                // Short attack/release envelope keeps the blips from clicking.
                val progress = i.toDouble() / count
                val envelope = when {
                    progress < 0.08 -> progress / 0.08
                    progress > 0.75 -> (1.0 - progress) / 0.25
                    else -> 1.0
                }
                // A touch of sine softens the harshest harmonics of the square wave.
                val shaped = raw * 0.75 + sin(2 * PI * phase) * 0.25
                out[offset + i] = (shaped * envelope * 0.28 * Short.MAX_VALUE).toInt().toShort()
            }
            offset += count
        }
        return out
    }

    private fun buildTrack(bufferBytes: Int): AudioTrack {
        val safeBuffer = maxOf(
            bufferBytes,
            AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ),
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(safeBuffer)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                safeBuffer,
                AudioTrack.MODE_STATIC,
            )
        }
    }
}
