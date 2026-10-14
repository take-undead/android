package app.mt5mon.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 急な値動きのチャイム（PicoCalc の alert.c と同じ音）。
 * 上がる 2 音 A5→E6、下がる 2 音 E6→A5。鐘っぽい音（基音に 2 倍・3 倍を少し混ぜ、指数で減衰）。
 * reps 回（1〜5）繰り返す。音量 1〜10 は 1 段 3dB。
 */
object Chime {
    const val MAX_REPS = 5
    private const val RATE = 22050
    private const val NOTE_LEN = RATE * 6 / 10
    private const val NOTE_GAP = RATE * 22 / 100
    private const val CYCLE = RATE * 55 / 100

    private fun voice(f: Float, t: Int): Float {
        if (t < 0 || t >= NOTE_LEN) return 0f
        val s = t.toFloat() / RATE
        var env = exp(-s * 5.0f)
        if (t < RATE / 250) env *= t.toFloat() / (RATE / 250)
        val w = 2.0f * PI.toFloat() * f * s
        return env * (sin(w) + 0.35f * sin(2 * w) + 0.12f * sin(3 * w))
    }

    /** PCM を作る。dir: +1 上がる、-1 下がる */
    fun render(dir: Int, reps: Int, volume: Int): ShortArray {
        val r = reps.coerceIn(1, MAX_REPS)
        val vol = volume.coerceIn(1, 10)
        // 10 で 16000（2 音が重なっても割れない大きさ）、1 段 3dB（PicoCalc と同じ）
        val amp = 16000.0 * Math.pow(10.0, (vol - 10) * 3.0 / 20.0)
        val notes = if (dir > 0) floatArrayOf(880.0f, 1318.5f) else floatArrayOf(1318.5f, 880.0f)
        val starts = intArrayOf(0, NOTE_GAP)
        val total = (r - 1) * CYCLE + NOTE_GAP + NOTE_LEN
        val out = ShortArray(total)
        for (i in 0 until total) {
            var v = 0f
            for (rep in 0 until r) for (k in 0..1) v += voice(notes[k], i - rep * CYCLE - starts[k])
            val x = (v * amp).coerceIn(-32767.0, 32767.0)
            out[i] = x.toInt().toShort()
        }
        return out
    }

    /** 鳴らす（終わるまで戻らない。Dispatchers.IO で動かす） */
    suspend fun play(dir: Int, reps: Int, volume: Int) = withContext(Dispatchers.IO) {
        val pcm = render(dir, reps, volume)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                // 用途は「アラーム」。「通知」だとおやすみモード（優先度の高い通知のみ）で止められる
                // （2026-10-14 実機で鳴らず、dumpsys notification の mZenMode=IMPORTANT_INTERRUPTIONS・許可は ALARMS/MEDIA のみと確認）
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            // 鳴り終わるまで待つ（STATIC モードは stop までトラックが残る）
            kotlinx.coroutines.delay(pcm.size * 1000L / RATE + 100)
            track.stop()
        } finally {
            track.release()
        }
    }
}
