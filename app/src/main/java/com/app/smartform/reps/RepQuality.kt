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

        // Depth model:
        // thresholds.downThresh = "deep enough", thresholds.upThresh = "fully up"
        val down = thresholds.downThresh
        val up = thresholds.upThresh

        val depthPct = if (repAngleMin == null) {
            0
        } else {
            val denom = (up - down).coerceAtLeast(1.0)
            val pct = ((up - repAngleMin) / denom) * 100.0
            pct.coerceIn(0.0, 100.0).toInt()
        }

        // ✅ Stricter, per-mode minimum rep duration (ms).
        // If rep-to-rep is faster than this => TOO FAST.
        val minTempoMs = when (mode) {
            ExerciseMode.SQUAT -> 1700L   // stricter so "fast squats" get flagged
            ExerciseMode.PUSHUP -> 1400L
            ExerciseMode.CURL -> 1100L
        }

        val hasTempo = tempoMs > 0L
        val tooFast = hasTempo && tempoMs < minTempoMs
        val okTempo = !hasTempo || !tooFast

        val deepEnough = depthPct >= 70
        val excellentDepth = depthPct >= 90

        val score = buildScore(depthPct = depthPct, okTempo = okTempo, tooFast = tooFast)

        val verdict = when {
            repAngleMin == null -> "NO DATA"
            tooFast && !deepEnough -> "TOO FAST + SHALLOW"
            tooFast -> "TOO FAST"
            !deepEnough -> "SHALLOW"
            excellentDepth && okTempo -> "EXCELLENT"
            deepEnough && okTempo -> "GOOD"
            else -> "GOOD"
        }

        val tips = when (verdict) {
            "NO DATA" -> "Keep more joints in frame."
            "TOO FAST + SHALLOW" -> "You're going too fast — slow down and go deeper."
            "TOO FAST" -> "You're going too fast — slow the tempo."
            "SHALLOW" -> "Go deeper for a full rep."
            "EXCELLENT" -> "Perfect rep — keep it controlled."
            else -> "Nice rep — keep it controlled."
        }

        return RepQuality(
            depthPct = depthPct,
            tempoMs = tempoMs,
            score = score,
            verdict = verdict,
            tips = tips
        )
    }

    private fun buildScore(depthPct: Int, okTempo: Boolean, tooFast: Boolean): Int {
        var s = depthPct

        // Strong penalty so fast reps don't look "perfect"
        if (!okTempo) s -= 45
        if (tooFast) s -= 15

        // Small bonus for very deep reps (won't override tempo penalties)
        if (depthPct >= 90) s += 5

        return s.coerceIn(0, 100)
    }
}