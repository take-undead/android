package app.mt5mon.data

import kotlin.math.abs

/** 急な値動きの判定の結果 */
data class AlertHit(val dir: Int, val pips: Double, val reps: Int, val atMillis: Long)

/**
 * 直近 minutes 分の最安値から pips 以上上がる（急騰）・最高値から下がる（急落）を見つける（PicoCalc の alert.c と同じ）。
 * 新しい現在値（gen_jst が変わり stale でないとき）だけ入れる。鳴らしたら判定をやり直す（同じ動きで鳴り続けない）。
 */
class AlertDetector {
    private class Sample(val t: Long, val bid: Double)
    private val samples = ArrayDeque<Sample>()
    private val maxSamples = 256

    fun reset() = samples.clear()

    fun onPrice(bid: Double, digits: Int, pipsThreshold: Int, minutes: Int, now: Long = System.currentTimeMillis()): AlertHit? {
        if (bid <= 0) return null
        val windowMs = minutes * 60_000L
        while (samples.isNotEmpty() && now - samples.first().t > windowMs) samples.removeFirst()
        if (samples.size >= maxSamples) samples.removeFirst()
        samples.addLast(Sample(now, bid))
        var lo = bid
        var hi = bid
        for (s in samples) {
            if (s.bid < lo) lo = s.bid
            if (s.bid > hi) hi = s.bid
        }
        val pip = if (digits >= 4) 0.0001 else 0.01
        val up = (bid - lo) / pip
        val down = (hi - bid) / pip
        var dir = 0
        var pips = 0.0
        if (up >= pipsThreshold && up >= down) {
            dir = 1; pips = up
        } else if (down >= pipsThreshold) {
            dir = -1; pips = -down
        }
        if (dir == 0) return null
        // 大きく動いたほど長く鳴らす: 幅の何倍動いたかの回数だけ 2 音を繰り返す（1〜5 回）
        val reps = (abs(pips) / pipsThreshold).toInt().coerceIn(1, Chime.MAX_REPS)
        samples.clear() // 鳴らしたら判定をやり直す
        return AlertHit(dir, pips, reps, now)
    }
}
