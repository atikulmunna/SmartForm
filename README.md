# SmartForm

[![Download App](https://img.shields.io/badge/Download%20App-Uptodown-00A6FF?style=for-the-badge)](https://smartform.en.uptodown.com/android)

SmartForm is an Android fitness app that performs on-device pose detection and hand tracking to help users train with better form, count reps, and control parts of the experience with gestures. The app is built fully on-device with CameraX, ML Kit, MediaPipe, and Jetpack Compose.

It currently supports guided tracking for curls, squats, and push-ups, with posture-aware rep counting, per-exercise calibration, quality scoring, and a session summary flow.

## Overview

SmartForm combines three core capabilities in a single camera pipeline:

- Real-time body pose detection for exercise analysis
- Real-time hand landmark tracking for gesture input
- Live rep counting and form evaluation for supported exercises

The current app experience is designed around a hands-free workout loop:

1. Grant camera access
2. Choose an exercise mode
3. Start a session with a gesture
4. Perform reps while SmartForm evaluates posture and rep quality
5. End the session and review the summary

## Current Features

### Real-Time Pose Tracking

- Full-body pose detection using ML Kit pose detection
- Live skeleton overlay aligned to the camera preview
- Continuous frame processing optimized for on-device use

### Hand Tracking and Gesture Controls

- 21-point hand landmark detection using MediaPipe Tasks
- Gesture recognition from the live camera stream
- Debounced, hold-based interactions to reduce accidental triggers

Current gesture behavior:

- `Pinch-hold`: start or stop a workout session
- `Open-palm-hold`: switch exercise mode when a session is not running
- `Pinch-hold during calibration`: capture calibration poses

### Exercise Modes

SmartForm currently supports:

- Bicep curls
- Squats
- Push-ups

Each mode uses its own rep thresholds and can be calibrated independently.

### Rep Counting and Form Gating

- Tracks rep phases and counts completed reps in real time
- Pauses effective counting when posture quality drops below acceptable form
- Prevents low-quality movement from being counted as valid reps

### Rep Quality Feedback

- Computes rep quality based on movement depth and tempo
- Flags shallow reps and overly fast reps
- Maintains a recent rep timeline for session feedback
- Calculates an average session score

### Calibration

- Supports per-exercise calibration to adapt thresholds to the user
- Stores calibration data locally using DataStore
- Includes reset support for returning to default thresholds

### Session Summary

- Displays total reps
- Shows average score
- Breaks down good, shallow, and too-fast reps

### Debug and Development Aids

- Built-in debug panel for inspecting thresholds, angles, posture state, and calibration flow
- Useful for tuning exercise logic during development and testing

## Tech Stack

- Language: Kotlin
- UI: Jetpack Compose
- Camera: CameraX
- Pose Detection: ML Kit Pose Detection
- Hand Tracking: MediaPipe Tasks Vision
- Local Storage: DataStore Preferences
- Architecture Style: state-driven Compose UI with exercise-specific processing modules

## Project Structure

```text
app/src/main/java/com/app/smartform/
├── calibration/
│   ├── CalibrationModels.kt
│   └── CalibrationStore.kt
├── camera/
│   └── CameraPreview.kt
├── gesture/
│   └── GestureDetector.kt
├── hand/
│   ├── HandModels.kt
│   ├── HandOverlay.kt
│   ├── HandProcessor.kt
│   └── YuvToRgbConverter.kt
├── pose/
│   ├── PoseFrame.kt
│   ├── PoseProcessor.kt
│   ├── PostureEvaluator.kt
│   └── SkeletonOverlay.kt
├── reps/
│   ├── ExerciseMode.kt
│   ├── RepCounter.kt
│   ├── RepQuality.kt
│   └── RepThresholds.kt
├── session/
│   └── SessionStats.kt
├── ui/
│   ├── RepTimeline.kt
│   └── SessionSummaryScreen.kt
├── ui/theme/
│   ├── Color.kt
│   ├── Theme.kt
│   └── Type.kt
└── MainActivity.kt
```

## Requirements

- Android Studio with current Android SDK tooling
- Java 17+
- Android device with a working camera

An emulator may build and launch the app, but it is not reliable for realistic pose and hand tracking validation.

## Build and Run

From the project root:

```bash
./gradlew :app:installDebug
```

If you want a clean reinstall:

```bash
./gradlew :app:uninstallDebug
./gradlew :app:installDebug
```

## Android Configuration

- Min SDK: 26
- Target SDK: 34
- Compile SDK: 34

Required permission:

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

The project also includes the MediaPipe hand landmark task asset at:

```text
app/src/main/assets/hand_landmarker.task
```

## User Experience Flow

### 1. Grant Permission

The app starts with a camera permission screen. Once permission is granted, the live camera view and overlays become active.

### 2. Select Exercise

Exercise mode can be cycled with an open-palm hold while the app is idle.

### 3. Start Session

Use a pinch-hold gesture to start tracking. The UI changes to show running state, current reps, phase, and quality metrics.

### 4. Perform Reps

While the session is running:

- Pose landmarks drive posture checks
- Rep logic tracks the active exercise
- Rep quality analysis scores each completed rep
- Counting is effectively paused if form is not acceptable

### 5. End Session

The session can be ended from the in-app control, after which a summary screen presents the workout results.

## Limitations

- Best gesture accuracy is typically achieved when the user is clearly visible and centered in frame
- Very close distances can reduce hand landmark stability
- Low-light conditions can reduce both pose and hand detection quality
- Rep counting depends on landmark visibility and exercise-specific posture assumptions
- Emulator camera input is not a reliable substitute for real-device testing

## Troubleshooting

### Camera preview does not start

- Confirm camera permission has been granted
- Test on a physical Android device
- Verify the device camera is available and not in use by another app

### Gestures are not recognized reliably

- Keep the hand inside the frame and clearly visible
- Improve lighting
- Avoid holding the hand too close to the camera
- Hold the gesture steadily long enough for the debounce window

### Reps are not being counted

- Check whether posture feedback indicates poor form
- Make sure the selected exercise mode matches the movement being performed
- Use calibration if thresholds do not match the user's range of motion

### Calibration feels off

- Re-run calibration from the debug tools
- Reset calibration to defaults and capture cleaner top and bottom poses

## Roadmap

- Richer coaching cues during active reps
- More detailed session history and analytics
- Additional exercise modes
- Exportable workout summaries
- More robust onboarding and in-app guidance

## Contributing

SmartForm is still evolving. Contributions that improve detection quality, exercise logic, UI clarity, performance, and documentation are welcome.
