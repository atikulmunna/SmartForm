package com.app.smartform.calibration

import com.app.smartform.reps.ExerciseMode

enum class CalibrationStep {
    BASELINE_UP,     // standing / plank-top / arm-extended
    BASELINE_DOWN    // squat-bottom / pushup-bottom / arm-curled
}

data class CalibrationState(
    val isActive: Boolean = false,
    val mode: ExerciseMode = ExerciseMode.SQUAT,
    val step: CalibrationStep = CalibrationStep.BASELINE_UP,
    val capturedUpAngle: Double? = null,
    val capturedDownAngle: Double? = null,
    val message: String = ""
)