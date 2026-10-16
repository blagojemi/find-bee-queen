Models the app loads from here (both optional; the app says "no model" if missing):

- `bee_check.tflite` — step 2, bee check. TFLite image classifier with metadata,
  labels `bees` / `no_bees`. Train it with `tools/bee_check/` (see its README).

- `queen_detector.tflite` — step 3, queen analysis. TFLite object-detection model
  with metadata (e.g. exported from MediaPipe Model Maker, or YOLO → TFLite with
  metadata attached), with at least a `queen` label.
