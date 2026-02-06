package com.app.smartform.reps

data class RepQuality(
    val depthPct: Int,          // 0..100 (how deep the rep went)
    val tempoMs: Long,          // time from last rep to this rep
    val score: Int,             // 0..100
    val verdict: String,        // "GOOD", "SHALLOW", "TOO FAST", etc.
    val tips: String            // short guidance
)

object RepQualityEvaluator {

    fun evaluate(
        mode: ExerciseMode,
        repAngleMin: Double?,
        thresholds: RepThresholds,
        tempoMs: Long
    ): RepQuality {
        // For all modes here: deeper rep => smaller angle.
        // We treat thresholds.downThresh as "deep enough" and upThresh as "fully up".
        val down = thresholds.downThresh
        val up = thresholds.upThresh

        val depthPct = if (repAngleMin == null) {
            0
        } else {
            val denom = (up - down).coerceAtLeast(1.0)
            val pct = ((up - repAngleMin) / denom) * 100.0
            pct.coerceIn(0.0, 100.0).toInt()
        }

        val tooFast = tempoMs in 1..650
        val okTempo = tempoMs == 0L || tempoMs >= 650

        val deepEnough = depthPct >= 70
        val excellentDepth = depthPct >= 90

        val score = buildScore(depthPct, okTempo)
        val verdict = when {
            repAngleMin == null -> "NO DATA"
            tooFast && !deepEnough -> "TOO FAST + SHALLOW"
            tooFast -> "TOO FAST"
            !deepEnough -> "SHALLOW"
            excellentDepth -> "EXCELLENT"
            else -> "GOOD"
        }

        val tips = when (verdict) {
            "NO DATA" -> "Keep more joints in frame."
            "TOO FAST + SHALLOW" -> "Slow down and go deeper."
            "TOO FAST" -> "Slow the tempo a bit."
            "SHALLOW" -> "Go deeper for a full rep."
            "EXCELLENT" -> "Great depth—keep it consistent."
            else -> "Nice rep."
        }

        return RepQuality(depthPct, tempoMs, score, verdict, tips)
    }

    private fun buildScore(depthPct: Int, okTempo: Boolean): Int {
        var s = depthPct
        if (!okTempo) s -= 20
        return s.coerceIn(0, 100)
    }
}
