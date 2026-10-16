# Find Queen

Android app that spots the **queen bee** on a hive frame through the phone camera,
in real time, on the device.

## Stack
- Kotlin, Jetpack Compose (Material 3)
- CameraX: preview plus `ImageAnalysis` frames
- TensorFlow Lite Task Vision: `ObjectDetector`, run on-device
- minSdk 26, target/compile SDK 35, AGP 8.7, Gradle 8.11 (wrapper), JDK 17

## Layout
```
app/src/main/java/lab/mgb/findqueen/
├── MainActivity.kt
├── analysis/    FrameStats + FrameReport (step 1), BeeVerdict (step 2 decision)
├── camera/      CameraPreview, FrameGrabber (Start → one frame), QueenAnalyzer (live)
├── detection/   BeeChecker (step 2), QueenDetector (step 3), OverlayMapper
└── ui/          FindQueenApp (permissions), ScanScreen (Start pipeline), DetectionOverlay
app/src/main/assets/bee_check.tflite        ← train with tools/bee_check/
app/src/main/assets/queen_detector.tflite   ← not committed yet, add your model
```

## Model
Put a TFLite object-detection model **with metadata** at
`app/src/main/assets/queen_detector.tflite`. It needs a `queen` label (other labels
such as `worker` or `drone` are ignored). Without the model the app still runs and
shows the camera with a "No model" status.

To train one, label queen bees on frame photos (Roboflow, CVAT, …) and then either
- train with MediaPipe Model Maker and export it to TFLite (metadata is included), or
- train YOLO and export it to TFLite, then attach metadata with `tflite_support`.

## Build
```bash
# needs JDK 17 and the Android SDK (set sdk.dir in local.properties or ANDROID_HOME)
./gradlew assembleDebug
./gradlew test
./gradlew installDebug
```
Or open the folder in Android Studio.
