// SessionStats.kt
package com.app.smartform.session

import com.app.smartform.reps.RepQuality

data class SessionStats(
    val reps: Int,
    val avgScore: Int,
    val good: Int,
    val shallow: Int,
    val fast: Int,
    val repTimeline: List<RepQuality>
)
