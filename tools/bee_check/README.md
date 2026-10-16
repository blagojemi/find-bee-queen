# Bee check: samples and training

The bee check (pipeline step 2) is a small image classifier that answers one
question: **are there honey bees (Apis mellifera carnica) on this comb frame?**
It runs once per Start press on a shrunk frame, so it is fast (tens of ms) and
barely touches the battery. It is only as reliable as the photos it learns from.

## 1. Collect samples
Take photos with any camera (your phone's normal camera app is fine) and sort
them into the two folders (git-ignored):

    debug/pre-check-samples/bees/
    debug/pre-check-samples/no_bees/

Keep `debug/pre-check-samples/bee_dataset_log.csv` up to date for photos from the web
(source URL, author, licence).

### What to photograph
| `bees` (bees on the comb) | `no_bees` (no bees on the comb) |
|---|---|
| Frames with few, some and many bees | Empty drawn comb, capped honey, capped brood, foundation |
| Brood frames, honey frames, edges of the frame | Frame with bees brushed off |
| Bees partly hidden, moving, clustered | Hive box, lid, ground, hands, gloves, smoker, sky |
| Close-ups and whole-frame shots | Other insects (wasps, flies) if you meet them |

Two classes are enough. Folder name = label; the app expects the label `bees`.

### So it is reliable
- **Amount**: start with ≥300 photos per class; 1000+ is better. The script
  refuses to train with fewer than 50.
- **Balance**: roughly the same number in each class.
- **Variety beats volume**: several hives, apiaries, days, times of day, sun and
  shade, different phones, distances (20–60 cm), angles and both frame sides.
  20 near-identical shots of one frame teach almost nothing.
- **Real conditions**: take them the way the app will be used — handheld, at the
  hive, in your normal light. Keep some slightly blurry ones too.
- **Clean labels**: a single bee on the comb is still `bees`. If unsure,
  don't save it. A few wrong labels hurt more than missing photos.
- **Hold out**: the script keeps 10% aside for an honest accuracy number.
  Before relying on it, also try the app on a hive it has never seen.

### About Carnica specifically
Training only on your Carnica colonies tunes the model to their dark grey-brown
look. A photo **cannot** reliably tell Carnica from other honey-bee subspecies
(even experts use wing measurements), so the check means "honey bees present",
not "this colony is pure Carnica".

## 2. Train
Needs Python 3.9–3.11.
```bash
cd tools/bee_check
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
python train.py --out out             # MobileNetV2, smallest/fastest
python train.py --model efficientnet_lite0 ...          # a bit bigger, often more accurate
cp out/bee_check.tflite ../../app/src/main/assets/
```
Then rebuild and install the app. Step 2 shows "Bees on the frame",
"No bees", or "Not sure, try another angle" for borderline scores.

## Test on a video
Runs the app's frame check + bee check on every Nth frame of a video, offline,
with the same thresholds. Writes `out_video/results.csv`, a timeline in
`summary.txt` and labelled contact sheets to check by eye.
```bash
. .venv/bin/activate
python eval_video.py ../../test_videos/clip.mp4 --fps 1 --out out_video
```
OpenCV here cannot decode AV1 (YouTube) videos; convert first with the ffmpeg
bundled in `imageio-ffmpeg`:
```bash
FF=$(python -c "import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())")
$FF -c:v libdav1d -i in.mp4 -an -vf scale=1280:-2 -c:v libx264 -crf 23 out.mp4
```

## Tuning
- Thresholds: `BEES_ABOVE` / `NO_BEES_BELOW` in
  `app/src/main/java/lab/mgb/findqueen/analysis/BeeVerdict.kt`.
- Frame quality gate (skips dark/blurry frames before the model runs):
  `app/src/main/java/lab/mgb/findqueen/analysis/FrameStats.kt`.
- If the app is wrong on a photo, take a similar photo, add it to the right folder and retrain.
