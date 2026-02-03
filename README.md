# SmartForm 🏋️‍♂️📱

**SmartForm** is a real-time fitness assistant Android app that uses **on-device pose detection and hand tracking** to analyze exercise form and enable **gesture-based controls** — no buttons, no wearables.

Built fully on-device using modern Android tech (CameraX + ML Kit + MediaPipe + Jetpack Compose).

---

## ✨ Features

### 🧍‍♂️ Real-time Pose Tracking
- Full body pose detection using **ML Kit Pose Detection**
- Live skeleton overlay aligned with the camera preview
- Optimized for front camera, portrait mode

### ✋ Hand Tracking
- 21-point hand landmark detection using **MediaPipe Hands**
- Smooth, stabilized hand overlay
- Works alongside pose detection (single camera stream)

### 🤏 Gesture-Based Controls (Hands-Free UI)
| Gesture | Action |
|------|------|
| **Pinch (thumb + index)** | Start / Pause tracking |
| **Open palm** | Switch exercise mode |

✔ Debounced  
✔ Stable (multi-frame confirmation)  
✔ Resistant to accidental triggers

### 🧠 Smart Logic
- Gesture arm/disarm system (no repeated firing)
- Frame freshness gating to prevent lag artifacts
- Confidence + visibility checks to reduce false positives

---

## 📸 Demo (example)
> Green = pose skeleton  
> Blue = hand skeleton  
> Gesture chip appears when a gesture is recognized

*(Add screenshots / GIFs here later)*

---

## 🧱 Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose
- **Camera:** CameraX
- **Pose Detection:** ML Kit (STREAM_MODE)
- **Hand Tracking:** MediaPipe Tasks (HandLandmarker)
- **Architecture:** Unidirectional state + composables
- **Min SDK:** 26
- **Target SDK:** 34+

---

## 📂 Project Structure

```text
app/src/main/java/com/app/smartform/
├── camera/
│   └── CameraPreview.kt
├── pose/
│   ├── PoseProcessor.kt
│   ├── PoseFrame.kt
│   ├── SkeletonOverlay.kt
│   └── PostureEvaluator.kt
├── hand/
│   ├── HandProcessor.kt
│   ├── HandModels.kt
│   └── HandOverlay.kt
├── gesture/
│   └── GestureDetector.kt
└── MainActivity.kt
```
## ▶️ Build & Run

### Requirements
- Android Studio (Giraffe+ recommended)
- Android device with camera (emulator not recommended for pose/hand tracking)
- Java 11+ (Android Studio bundled JBR works)

### Install & Run
```bash
./gradlew :app:installDebug
```
### Clean Reinstall (recommended if behavior looks stale)
```bash
./gradlew :app:uninstallDebug
./gradlew :app:installDebug
```
## 🔐 Permissions
```bash
<uses-permission android:name="android.permission.CAMERA" />
```

## ⚠️ Known Limitations
- Best gesture accuracy at ~1–2 meters from the camera
- Very close distances may reduce hand landmark stability
- Low lighting conditions can affect detection quality
- Emulator camera does not provide reliable results for MediaPipe Hands

## 🛣️ Roadmap
- Exercise-specific rep counting
- Hold-to-run gesture mode
- Left / right hand preference
- On-screen posture coaching feedback
- Session history and analytics
- Export workout summaries

## 🤝 Contributing
This project is experimental and evolving.
Bug reports, performance improvements, and feature ideas are welcome.