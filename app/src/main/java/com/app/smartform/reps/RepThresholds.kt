package com.app.smartform.reps

data class RepThresholds(
    val downThresh: Double,
    val upThresh: Double
)

data class CalibrationProfile(
    val curl: RepThresholds = RepThresholds(downThresh = 150.0, upThresh = 70.0),
    val squat: RepThresholds = RepThresholds(downThresh = 115.0, upThresh = 165.0),
    val pushup: RepThresholds = RepThresholds(downThresh = 100.0, upThresh = 165.0)
)