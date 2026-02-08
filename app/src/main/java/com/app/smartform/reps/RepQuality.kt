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
        // Depth: smaller angle => deeper (for your current design)
        val down = thresholds.downThresh
        val up = thresholds.upThresh

        val depthPct = if (repAngleMin == null) {
            0
        } else {
            val denom = (up - down).coerceAtLeast(1.0)
            val pct = ((up - repAngleMin) / denom) * 100.0
            pct.coerceIn(0.0, 100.0).toInt()
        }

        // -------- Tempo bands (per-mode, conservative) --------
        // These are rep-to-rep times (your app measures time between rep increments).
        // If you want stricter, raise the "tooFast" thresholds.
        val (tooFastMax, goodMin, tooSlowMin) = when (mode) {
            ExerciseMode.SQUAT -> Triple(1400L, 1400L, 5000L)
            ExerciseMode.PUSHUP -> Triple(1700L, 1700L, 6500L)
            ExerciseMode.CURL -> Triple(1200L, 1200L, 4500L)
        }

        val hasTempo = tempoMs > 0L
        val tooFast = hasTempo && tempoMs < tooFastMax
        val tooSlow = hasTempo && tempoMs > tooSlowMin

        val deepEnough = depthPct >= 70
        val excellentDepth = depthPct >= 90

        // Score: depth is base, tempo penalties applied on top
        var score = depthPct
        if (tooFast) score -= 30
        if (tooSlow) score -= 10
        score = score.coerceIn(0, 100)

        val verdict = when {
            repAngleMin == null -> "NO DATA"
            tooFast && !deepEnough -> "TOO FAST + SHALLOW"
            tooFast -> "TOO FAST"
            !deepEnough -> "SHALLOW"
            excellentDepth && !tooSlow -> "EXCELLENT"
            else -> "GOOD"
        }

        val tips = when (verdict) {
            "NO DATA" -> "Keep more joints in frame (hips/knees/ankles visible)."
            "TOO FAST + SHALLOW" -> "You're going too fast and not deep enough—slow down and sit lower."
            "TOO FAST" -> "You're going too fast—slow down and control the movement."
            "SHALLOW" -> "Go deeper for a full rep."
            "EXCELLENT" -> if (tooSlow) "Great depth—try a slightly steadier pace." else "Great depth—keep it consistent."
            else -> if (tooSlow) "Good rep—try a slightly quicker cadence." else "Nice rep."
        }

        return RepQuality(
            depthPct = depthPct,
            tempoMs = tempoMs,
            score = score,
            verdict = verdict,
            tips = tips
        )
    }
}