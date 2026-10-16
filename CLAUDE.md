# Find Queen — project intent

## What we are building
An Android app that uses the phone camera to **recognise the queen bee on a hive
frame in real time**. A beekeeper holds the phone over a frame, sees the live
camera preview, and the app draws a box around the queen as soon as she is
visible. The goal is to find the queen faster during hive inspections.

## Scan flow (Start button pipeline)
The app opens straight to the camera preview with a big **Start** button.
Pressing Start runs a staged pipeline on a single frame:

1. **Read frame** — *built.* `ImageCapture` grabs the current camera frame in
   memory (`camera/FrameGrabber.kt`), and the app shows a report: size, rotation,
   capture time, brightness and sharpness (`analysis/FrameStats.kt`). Too dark,
   too bright or blurry frames are flagged.
2. **Bee check** — *built; first model trained 2026-10-15 on 109 web photos
   (MobileNetV2, 8.9 MB float). Pipeline proof only, not field-reliable yet:
   on its own photos 90/109 confident and correct, 4 wrong, 15 unsure. Retrain
   with ~300+ per class of the user's own hive photos. Video test 2026-10-15
   (12 min inspection, 1 fps, `tools/bee_check/eval_video.py`): BEES answers
   ~89% right (false: faces, dry grass, queen excluder); frames full of bees
   only ~half BEES, ~1/3 UNSURE, ~1/5 missed.* "Are there honey bees
   (Apis mellifera carnica) on this comb frame?" (`detection/BeeChecker.kt`,
   decision rule in `analysis/BeeVerdict.kt`). Skipped if step 1 flags the frame.
   It must stay **quick, cheap on battery and reliable**: run only while the
   user has pressed Start (live mode, see below), use a small classifier
   (MobileNetV2 / EfficientNet-Lite0, ~224 px input, 2 threads), shrink the frame
   first, load the model once. Each frame is scored whole and as its centre 60%
   (`analysis/CentreCrop.kt`), the higher score wins: added 2026-10-15 after the
   first phone test (OnePlus N10, filming a monitor) where comb filling only part
   of the view sat at ~0.5; on its thumbnails the crop took green from 1/36 to
   16/36; on the inspection video it added 27 greens (19 real, 3 top-bar views,
   5 false: face, suit, grass, perforated board ×2). Costs a second model run
   (~23 ms on the N10). For reliability it answers BEES / NO_BEES /
   UNSURE: a borderline score asks the user to try another angle rather than
   guess. Model: `assets/bee_check.tflite`, labels `bees` / `no_bees`, trained
   from photos the user puts in `tools/bee_check/samples/<label>/` (the app does
   not save training photos; that was removed on purpose), see
   `tools/bee_check/README.md`.
   A photo cannot reliably identify the subspecies, so this check means
   "honey bees present", tuned to Carnica by training on Carnica colonies.
3. **Queen analysis** — *not built yet.* Only if step 2 finds bees: run the
   heavier queen detector (`detection/QueenDetector`) on the full-resolution
   frame and draw the queen boxes (`ui/DetectionOverlay`) over the frozen frame.

**Live mode (user decision 2026-10-15):** the camera is off (black screen)
until Start; Stop turns it off again. Start binds `camera/BeeAnalyzer.kt` to CameraX `ImageAnalysis` (480×640 frames,
keep-only-latest), which runs steps 1–2 on every frame the phone can keep up
with. A thick border around the stream shows the latest result: green bees / red no
bees / amber not sure / grey unusable frame. Only bees get a badge,
"SCANNING…"; no/unsure get no badge. The stats panel stays, with a small
"Bees NN%" line just above the Start/Stop button. The screen never
dims or sleeps while the app is open (window flag in `MainActivity`), and a beep plays when bees are found (`analysis/BeepGate.kt`). Stop unbinds
the analysis so nothing runs when idle. Emulator: ~10–28 checks/s. Open items:
smooth the result over a short window (it can flicker between frames) and cap
the check rate on the phone if battery or heat is a problem.
The bee check classifies the whole frame, so there are no per-bee boxes; boxes
come with the queen detector. `camera/FrameGrabber.kt` (single still capture)
is no longer used by the screen; keep it for a future "save snapshot".
Emulator test with video: `emulator -avd seequeen_api35 -camera-back
videofile:<h264.mp4>` (the clip loops; `test_videos/clip_test.mp4` is a 43 s cut
of the inspection video).

Later, the same pipeline can run continuously on preview frames (live mode),
built on `camera/QueenAnalyzer`. Each stage shows its status (✓ / … / ✗) in the
report panel (`ui/ScanScreen.kt`).

## Target device: optimise for a mid-range phone
Reference phone is the user's **OnePlus Nord N10 5G**: Snapdragon 690
(2× Cortex-A77 + 6× A55, Adreno 619 GPU), 6 GB RAM, Android 10 → 11
(OxygenOS 11, no newer updates). Everything must run well on this phone.
To do when optimising:
- **Measure on this phone**, not the emulator: capture time, frame-stats time,
  bee-check and queen-analysis inference ms, RAM, battery/heat during a
  10-minute inspection. Add a simple debug readout or log for these.
- **Inference**: default to TFLite on CPU (XNNPACK), 2 threads (the two big A77
  cores). Benchmark the GPU delegate on the Adreno 619 before using it. Avoid
  NNAPI on Android 10/11 unless it measures faster and gives the same results.
- **Models**: keep the bee check ≤224 px MobileNetV2/EfficientNet-Lite0.
  Try int8 / float16 quantisation (smaller, faster on A55/A77) and keep it only
  if accuracy holds. Queen detector: smallest model that finds the queen
  reliably (e.g. 320–416 px input; tiling only if queens are too small).
- **Camera**: the camera preview uses the most power. Don't ask for full 64 MP
  stills. Cap capture resolution (~1280–1920 px long side). Pause or unbind
  the camera after a period of inactivity and when the app is in the background.
- **Memory**: reuse bitmaps and buffers instead of allocating per frame. Recycle
  the shrunk copies. Don't keep full-resolution frames around longer than needed.
- **Compatibility**: APIs must work on Android 10/11 (API 29/30). minSdk 26
  stays.

## Debug logs from the phone
The user tests the APK on the phone away from this server (no USB/adb here). A
script on the user's other computer (written by another Claude) collects logs
over adb and ships them to `debug_logs/` here (git-ignored).
- **Logcat (stdout), tag `FindQueen`** (`debug/DebugLog.kt`): `SESSION,<id>,k=v`
  (phone, Android, app version, thresholds), `HEADER,<id>,<csv header>`,
  `FRAME,<id>,<DebugRecord csv>` (~20/s: time, fps, brightness, sharpness,
  bee score, outcome, timings, thumbnail name), `EVENT,<id>,<t_ms>,beep|stop`,
  `CRASH,...` (error level). Collect with `adb logcat -v epoch -s FindQueen:I`.
- **Files** in app storage `files/debug/<id>/`: `session.txt`, `frames.csv`,
  240×320 `thumb_<t_ms>.jpg` once a second (max 600), `crash_<time>.txt`; last 5
  sessions kept. Pull with `adb exec-out run-as lab.mgb.findqueen tar cf -
  files/debug` (debug builds only). Files are the complete copy: the phone's
  logcat buffer only holds ~1–2 min unless enlarged (`adb logcat -G 16M`).

## Must-haves
- **Real time**: live camera preview with detection boxes drawn over it. Aim for
  ≥10 detections/s on a mid-range phone; never block the preview.
- **On device, offline**: inference runs locally (TFLite). Apiaries often have no
  signal, so no network calls are needed for detection.
- **Queen only**: the model may also know `worker` / `drone`, but the app shows
  only `queen` detections, with a confidence score.
- **Works without a model**: if `assets/queen_detector.tflite` is missing, the app
  still opens the camera and shows a "No model" status instead of crashing.
- **Field friendly**: big, high-contrast overlay that is readable outdoors and
  usable with one hand (and possibly gloves).

## Nice-to-haves (later)
- Haptic/sound alert when the queen is detected.
- Torch toggle, tap-to-focus, and zoom.
- Save a snapshot of a frame with the queen marked.
- Track the queen across frames (smooth the box, reduce flicker).
- Detect marked queens (paint dot colour → year).

## Out of scope for now
- Cloud inference, accounts, or backend sync.
- iOS.

## Technical decisions (keep unless asked to change)
- Kotlin, Jetpack Compose (Material 3), single activity.
- CameraX `Preview` + `ImageAnalysis` (`STRATEGY_KEEP_ONLY_LATEST`).
- TensorFlow Lite Task Vision `ObjectDetector`; model with metadata in
  `app/src/main/assets/queen_detector.tflite`, label `queen`.
- minSdk 26, target/compile SDK 35, JDK 17, package `lab.mgb.findqueen`.
- Code layout: `camera/` (preview + analyzer), `detection/` (detector, models,
  coordinate mapping), `ui/` (screens, overlay). See README.md.

## The model
The model is the hard part and is not in the repo yet. It has to be trained on
labelled photos of hive frames (Roboflow / CVAT for labelling; MediaPipe Model
Maker or YOLO → TFLite with metadata for training/export). Queens are small and
surrounded by look-alike workers, so favour higher input resolution and recall
over speed when choosing a model.

## Working on this repo
- Build: `./gradlew assembleDebug`; tests: `./gradlew test`;
  install: `./gradlew installDebug`.
- Every emulator test also builds the debug APK
  (`app/build/outputs/apk/debug/app-debug.apk`); report its path and time, the
  user sideloads it onto the phone.
- Emulator AVD `seequeen_api35` exists, but real detection needs a real phone
  pointed at a frame (or test images).
- Keep pure logic (e.g. `OverlayMapper`) unit-testable without Android.
