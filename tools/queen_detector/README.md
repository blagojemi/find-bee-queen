# Queen detector: training data

Collects openly licensed photos for the queen detector (pipeline step 3).
Everything lands in `queen_data/` (git-ignored); `queen_data/attribution.csv`
lists author, licence and source page per downloaded photo.

## Setup
```bash
cd tools/queen_detector
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
```
Keys come only from the environment. Keep them in a private file, not in the shell history:
```bash
mkdir -p ~/.config/find-queen && chmod 700 ~/.config/find-queen
nano ~/.config/find-queen/keys.env   # ROBOFLOW_API_KEY=...  (FLICKR_API_KEY=... optional)
chmod 600 ~/.config/find-queen/keys.env
set -a; . ~/.config/find-queen/keys.env; set +a
```

## Download
```bash
.venv/bin/python download_queen_data.py --dry-run          # counts only
.venv/bin/python download_queen_data.py                    # all sources
.venv/bin/python download_queen_data.py --only commons,inaturalist
```
Reruns skip files that are already there. Without `FLICKR_API_KEY`, Flickr is skipped.

| Source | Goes to | Labels |
|---|---|---|
| Roboflow Universe (15 projects, latest version, yolov8) | `roboflow/<project>_v<N>/` | yes |
| Wikimedia Commons (3 queen categories, no NC licences) | `to_label/commons/<category>/` | no |
| iNaturalist (Apis mellifera, "queen"/"Königin"/"matica", CC0/BY/BY-SA) | `to_label/inaturalist/` | no |
| Flickr (CC BY, BY-SA, CC0, PD) | `to_label/flickr/` | no |

No YouTube, Vimeo, stock photos or non-commercial licences.

## Build one YOLO set from the Roboflow exports
```bash
.venv/bin/python build_queen_dataset.py dupes      # -> queen_data/duplicates.csv (nothing deleted)
.venv/bin/python build_queen_dataset.py classes    # -> queen_data/mapping_proposed.json
# review it, set unwanted classes to null, save as queen_data/mapping.json
.venv/bin/python build_queen_dataset.py merge --mapping queen_data/mapping.json
.venv/bin/python build_queen_dataset.py stats
```
`merge` writes `queen_data/merged/` (classes `queen`, `worker`, `drone`,
`queen_cell`; polygons become boxes). It splits 80/10/10 by whole project, so
one project never lands in two splits, and it leaves out near-duplicates.
Images are hard-linked, not copied.

Watch for projects whose only class is a generic "bee". Queens in them may be
labelled as plain bees. `classes` marks these with CHECK.

## Train (RTX A4000, GPU 2 in `nvidia-smi` order)
The training venv also needs PyTorch for CUDA 11.8 (driver 470 on this server) and Ultralytics:
```bash
.venv/bin/pip install torch torchvision --index-url https://download.pytorch.org/whl/cu118
.venv/bin/pip install ultralytics
CUDA_DEVICE_ORDER=PCI_BUS_ID CUDA_VISIBLE_DEVICES=2 .venv/bin/python train_queen.py --name v1_416
```
Results go to `runs/<name>/` (weights, curves). Ultralytics analytics are switched off.

## Export to TFLite (separate CPU venv)
Ultralytics' LiteRT export needs a newer PyTorch than CUDA 11.8 allows, so export runs in
its own CPU-only venv:
```bash
python3 -m venv .venv-export
.venv-export/bin/pip install torch torchvision --index-url https://download.pytorch.org/whl/cpu
.venv-export/bin/pip install ultralytics litert-torch ai-edge-litert
.venv-export/bin/python -c "from ultralytics import YOLO; YOLO('runs/v1_416/best.pt').export(format='tflite', imgsz=416)"
```
Model input `[1, 3, H, W]` float32 RGB 0–1 (channels first), output `[1, 4+classes, N]`
(cx, cy, w, h as 0–1 fractions of the input, then one score per class; needs NMS in the app).
`quantize=8` (+ `data=`) gives int8; there is no float16 option any more.

## Test on a video
```bash
CUDA_DEVICE_ORDER=PCI_BUS_ID CUDA_VISIBLE_DEVICES=2 .venv/bin/python eval_video.py \
    ../../test_videos/inspection_h264.mp4 --model runs/v1_416/best.pt --imgsz 640
```
Writes `out_video/<name>/index.html` with every frame that got a queen box, for a person
to judge (there is no ground truth).

## Results so far
v1 (YOLOv8n, 416 px, 2026-10-19): unseen web close-ups queen recall 0.81 / precision
0.81; on the 12-min inspection video 0 real queens and 4 false boxes. Web queens fill
~26% of the photo, in the app ~4–5%, so the next model needs the user's own frames.
