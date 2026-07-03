package com.app.smartform.session

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.app.smartform.calibration.CalibrationState
import com.app.smartform.calibration.CalibrationStep
import com.app.smartform.calibration.CalibrationStore
import com.app.smartform.hand.HandFrame
import com.app.smartform.pose.PoseFrame
import com.app.smartform.pose.PostureEvaluator
import com.app.smartform.pose.PostureFeedback
import com.app.smartform.reps.CalibrationProfile
import com.app.smartform.reps.ExerciseMode
import com.app.smartform.reps.RepCounter
import com.app.smartform.reps.RepQuality
import com.app.smartform.reps.RepThresholds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Immutable snapshot the workout UI renders from. */
data class SessionUiState(
    val mode: ExerciseMode = ExerciseMode.CURL,
    val modeName: String = "Curl",
    val poseFrame: PoseFrame? = null,
    val isRunning: Boolean = false,
    val reps: Int = 0,
    val phase: String = "IDLE",
    val angle: Double? = null,
    val posture: PostureFeedback = PostureFeedback("Detecting...", "Hold still for a moment", 0),
    val formOk: Boolean = false,
    val effectiveRunning: Boolean = false,
    val thresholds: RepThresholds = CalibrationProfile().curl,
    val lastQuality: RepQuality? = null,
    val avgScore: Int = 0,
    val sessionGood: Int = 0,
    val sessionShallow: Int = 0,
    val sessionFast: Int = 0,
    val repTimeline: List<RepQuality> = emptyList(),
    val repDebug: String = "",
)

/**
 * Owns all in-session state that used to live inside the `CameraScreen` composable:
 * rep counting, quality/session tallies, posture evaluation, and calibration.
 *
 * Why this exists:
 *  - Survives configuration changes (rotation) — the session is no longer lost.
 *  - Pose frames drive rep counting via a plain method call instead of a per-frame
 *    `LaunchedEffect`, removing coroutine churn at camera frame rate.
 *  - Hand frames arrive on a background analyzer thread; they land in a thread-safe
 *    [StateFlow] here instead of a Compose state written off the main thread.
 */
class SessionViewModel(
    app: Application,
    private val saved: SavedStateHandle
) : AndroidViewModel(app) {

    private val store = CalibrationStore(app)
    private val repCounter = RepCounter()
    private val tracker = SessionTracker()

    private var profile = CalibrationProfile()
    private var lastPoseFrame: PoseFrame? = null
    private var lastRepAtMs = 0L
    private var running = false

    private var modeIndex = saved[KEY_MODE] ?: 0

    private val _uiState = mutableStateOf(SessionUiState())
    val uiState: State<SessionUiState> = _uiState

    private val _calib = mutableStateOf(CalibrationState())
    val calib: State<CalibrationState> = _calib

    // Hand frames come from a background analyzer thread -> keep them off Compose state.
    private val _handFrame = MutableStateFlow<HandFrame?>(null)
    val handFrame: StateFlow<HandFrame?> = _handFrame.asStateFlow()

    init {
        val mode = currentMode()
        _uiState.value = _uiState.value.copy(
            mode = mode,
            modeName = modeName(mode),
            thresholds = thresholdsFor(mode)
        )
        viewModelScope.launch {
            store.profileFlow.collect { p ->
                profile = p
                _uiState.value = _uiState.value.copy(thresholds = thresholdsFor(currentMode()))
            }
        }
    }

    // ---- Frame ingestion -------------------------------------------------------

    /** Called on the main thread (ML Kit success callback). */
    fun submitPoseFrame(frame: PoseFrame) {
        lastPoseFrame = frame
        val mode = currentMode()

        val posture = PostureEvaluator.evaluate(frame, mode)
        val formOk = posture.status == "Good form"
        val effectiveRunning = running && formOk

        val result = repCounter.update(mode, frame, effectiveRunning, profile)
        val thresholds = thresholdsFor(mode)

        tracker.trackAngle(effectiveRunning, result.angle)

        val nowMs = SystemClock.uptimeMillis()
        val tempo = if (lastRepAtMs == 0L) 0L else nowMs - lastRepAtMs
        if (tracker.onRep(mode, result.reps, thresholds, tempo)) {
            lastRepAtMs = nowMs
        }

        _uiState.value = SessionUiState(
            mode = mode,
            modeName = modeName(mode),
            poseFrame = frame,
            isRunning = running,
            reps = result.reps,
            phase = result.phase,
            angle = result.angle,
            posture = posture,
            formOk = formOk,
            effectiveRunning = effectiveRunning,
            thresholds = thresholds,
            lastQuality = tracker.lastQuality,
            avgScore = tracker.avgScore(result.reps),
            sessionGood = tracker.good,
            sessionShallow = tracker.shallow,
            sessionFast = tracker.fast,
            repTimeline = tracker.timeline.toList(),
            repDebug = result.debug,
        )
    }

    /** May be called from any thread (background analyzer). Null frames are ignored. */
    fun submitHandFrame(frame: HandFrame?) {
        if (frame != null) _handFrame.value = frame
    }

    // ---- Session controls ------------------------------------------------------

    fun setRunning(run: Boolean) {
        running = run
        _uiState.value = _uiState.value.copy(
            isRunning = run,
            effectiveRunning = run && _uiState.value.formOk
        )
    }

    fun toggleRunning() = setRunning(!running)

    fun cycleMode() {
        modeIndex = (modeIndex + 1) % 3
        saved[KEY_MODE] = modeIndex
        resetSession()
    }

    /** Select a specific mode from an on-screen control. No-op if already selected. */
    fun selectMode(mode: ExerciseMode) {
        val target = when (mode) {
            ExerciseMode.CURL -> 0
            ExerciseMode.SQUAT -> 1
            ExerciseMode.PUSHUP -> 2
        }
        if (target == modeIndex) return
        modeIndex = target
        saved[KEY_MODE] = modeIndex
        resetSession()
    }

    /** Clears rep count + session tallies; leaves running state untouched. */
    fun resetSession() {
        repCounter.reset()
        tracker.reset()
        lastRepAtMs = 0L
        val mode = currentMode()
        _uiState.value = _uiState.value.copy(
            mode = mode,
            modeName = modeName(mode),
            reps = 0,
            phase = "IDLE",
            angle = null,
            lastQuality = null,
            avgScore = 0,
            sessionGood = 0,
            sessionShallow = 0,
            sessionFast = 0,
            repTimeline = emptyList(),
            repDebug = "",
            thresholds = thresholdsFor(mode),
        )
    }

    fun endSession(): SessionStats {
        setRunning(false)
        return tracker.snapshot(_uiState.value.reps)
    }

    // ---- Calibration -----------------------------------------------------------

    fun startCalibration() {
        if (running) return
        val mode = currentMode()
        _calib.value = CalibrationState(
            isActive = true,
            mode = mode,
            step = CalibrationStep.BASELINE_UP,
            message = "Do UP pose for ${modeName(mode)} and pinch-hold to capture."
        )
    }

    fun resetCalibration() {
        viewModelScope.launch { store.resetToDefaults() }
        _calib.value = _calib.value.copy(isActive = false, message = "Reset calibration to defaults.")
    }

    fun captureCalibration() {
        val c = _calib.value
        if (!c.isActive) return

        val a = repCounter.currentPrimaryAngle(currentMode(), lastPoseFrame)
        if (a == null) {
            _calib.value = c.copy(message = "No angle detected (ensure joints visible).")
            return
        }

        _calib.value = when (c.step) {
            CalibrationStep.BASELINE_UP -> c.copy(
                step = CalibrationStep.BASELINE_DOWN,
                capturedUpAngle = a,
                message = "Captured UP (${a.toInt()}°). Now do DOWN pose and pinch-hold."
            )

            CalibrationStep.BASELINE_DOWN -> {
                val up = c.capturedUpAngle
                if (up != null) {
                    val newProfile = buildCalibratedProfile(profile, c.mode, up, a)
                    viewModelScope.launch { store.saveProfile(newProfile) }
                    c.copy(
                        isActive = false,
                        capturedDownAngle = a,
                        message = "Saved: UP=${up.toInt()}°, DOWN=${a.toInt()}°"
                    )
                } else {
                    c.copy(message = "Missing UP capture, restart calibration.")
                }
            }
        }
    }

    private fun buildCalibratedProfile(
        existing: CalibrationProfile,
        mode: ExerciseMode,
        up: Double,
        down: Double
    ): CalibrationProfile {
        val hi = maxOf(up, down)
        val lo = minOf(up, down)
        val range = maxOf(15.0, abs(hi - lo))
        val margin = range * 0.15

        return when (mode) {
            ExerciseMode.CURL -> {
                val upThresh = (minOf(up, down) + margin).coerceIn(20.0, 140.0)
                val downThresh = (maxOf(up, down) - margin).coerceIn(80.0, 180.0)
                existing.copy(curl = RepThresholds(downThresh, upThresh))
            }

            ExerciseMode.SQUAT -> {
                val downThresh = (minOf(up, down) + margin).coerceIn(40.0, 160.0)
                val upThresh = (maxOf(up, down) - margin).coerceIn(80.0, 180.0)
                existing.copy(squat = RepThresholds(downThresh, upThresh))
            }

            ExerciseMode.PUSHUP -> {
                val downThresh = (minOf(up, down) + margin).coerceIn(40.0, 160.0)
                val upThresh = (maxOf(up, down) - margin).coerceIn(80.0, 180.0)
                existing.copy(pushup = RepThresholds(downThresh, upThresh))
            }
        }
    }

    // ---- Helpers ---------------------------------------------------------------

    fun currentMode(): ExerciseMode = when (modeIndex) {
        0 -> ExerciseMode.CURL
        1 -> ExerciseMode.SQUAT
        else -> ExerciseMode.PUSHUP
    }

    private fun modeName(mode: ExerciseMode): String = when (mode) {
        ExerciseMode.CURL -> "Curl"
        ExerciseMode.SQUAT -> "Squat"
        ExerciseMode.PUSHUP -> "Push-up"
    }

    private fun thresholdsFor(mode: ExerciseMode): RepThresholds = when (mode) {
        ExerciseMode.CURL -> profile.curl
        ExerciseMode.SQUAT -> profile.squat
        ExerciseMode.PUSHUP -> profile.pushup
    }

    private companion object {
        const val KEY_MODE = "modeIndex"
    }
}
