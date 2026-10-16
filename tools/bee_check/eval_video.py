"""Run the app's Start pipeline (frame check + bee check) over a video, offline.

Mirrors the app as closely as possible:
  - FrameStats.kt: luma mean and Laplacian variance on a 160 px wide copy,
    too dark < 40, too bright > 220, blurry < 50 -> bee check skipped
  - BeeChecker.kt: shrink to 448 px long side, score the whole frame and its centre 60%,
    keep the higher score; model input resized to 224x224, /255
  - BeeVerdict.kt: bees >= 0.65 -> BEES, < 0.35 -> NO_BEES, else UNSURE

The video must be readable by OpenCV (convert AV1 to H.264 first).

    python eval_video.py VIDEO --model ../../app/src/main/assets/bee_check.tflite --fps 2 --out out_video
Writes per-frame results.csv, a timeline in summary.txt, and contact sheets
(sheet_*.jpg) with each sampled frame labelled, for checking by eye.
"""

import argparse
import csv
from pathlib import Path

import cv2
import numpy as np
import tensorflow as tf

DARK_LIMIT, BRIGHT_LIMIT, BLUR_LIMIT = 40.0, 220.0, 50.0
STATS_WIDTH, MAX_INPUT_SIDE = 160, 448
BEES_ABOVE, NO_BEES_BELOW = 0.65, 0.35
CROP_FRACTION = 0.6


def frame_stats(rgb: np.ndarray) -> tuple[float, float]:
    h, w = rgb.shape[:2]
    small = cv2.resize(rgb, (STATS_WIDTH, max(1, h * STATS_WIDTH // w)), interpolation=cv2.INTER_LINEAR)
    luma = small[..., 0] * 0.299 + small[..., 1] * 0.587 + small[..., 2] * 0.114
    lap = luma[:-2, 1:-1] + luma[2:, 1:-1] + luma[1:-1, :-2] + luma[1:-1, 2:] - 4 * luma[1:-1, 1:-1]
    return float(luma.mean()), float(lap.var())


def problem(brightness: float, sharpness: float) -> str | None:
    if brightness < DARK_LIMIT:
        return "too dark"
    if brightness > BRIGHT_LIMIT:
        return "too bright"
    if sharpness < BLUR_LIMIT:
        return "blurry"
    return None


class BeeModel:
    def __init__(self, path: Path):
        self.interp = tf.lite.Interpreter(model_path=str(path), num_threads=2)
        self.interp.allocate_tensors()
        self.inp = self.interp.get_input_details()[0]
        self.out = self.interp.get_output_details()[0]
        _, self.h, self.w, _ = self.inp["shape"]

    def bee_score(self, rgb: np.ndarray) -> float:
        h, w = rgb.shape[:2]
        if max(h, w) > MAX_INPUT_SIDE:
            r = MAX_INPUT_SIDE / max(h, w)
            rgb = cv2.resize(rgb, (max(1, int(w * r)), max(1, int(h * r))), interpolation=cv2.INTER_LINEAR)
        h, w = rgb.shape[:2]
        cw, ch = max(1, int(w * CROP_FRACTION)), max(1, int(h * CROP_FRACTION))
        crop = rgb[(h - ch) // 2:(h - ch) // 2 + ch, (w - cw) // 2:(w - cw) // 2 + cw]
        return max(self._score(rgb), self._score(crop))

    def _score(self, rgb: np.ndarray) -> float:
        x = cv2.resize(rgb, (self.w, self.h), interpolation=cv2.INTER_LINEAR).astype(np.float32) / 255.0
        self.interp.set_tensor(self.inp["index"], x[None])
        self.interp.invoke()
        return float(self.interp.get_tensor(self.out["index"])[0][0])  # labels: bees, no_bees


def verdict(score: float) -> str:
    if score >= BEES_ABOVE:
        return "BEES"
    if score < NO_BEES_BELOW:
        return "NO_BEES"
    return "UNSURE"


def mmss(t: float) -> str:
    return f"{int(t // 60):02d}:{t % 60:04.1f}"


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("video", type=Path)
    p.add_argument("--model", type=Path, default=Path("../../app/src/main/assets/bee_check.tflite"))
    p.add_argument("--fps", type=float, default=2.0, help="frames per second to sample")
    p.add_argument("--out", type=Path, default=Path("out_video"))
    args = p.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    model = BeeModel(args.model)
    cap = cv2.VideoCapture(str(args.video))
    src_fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    step = max(1, round(src_fps / args.fps))

    rows, thumbs, idx = [], [], 0
    while True:
        ok = cap.grab()
        if not ok:
            break
        if idx % step == 0:
            ok, bgr = cap.retrieve()
            if not ok:
                break
            rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
            t = idx / src_fps
            bright, sharp = frame_stats(rgb)
            prob = problem(bright, sharp)
            score = None if prob else model.bee_score(rgb)
            result = f"SKIP ({prob})" if prob else verdict(score)
            rows.append({"time_s": round(t, 2), "time": mmss(t), "brightness": round(bright, 1),
                         "sharpness": round(sharp, 1), "bee_score": "" if score is None else round(score, 3),
                         "result": result})
            thumbs.append((t, result, score, cv2.resize(bgr, (320, int(320 * bgr.shape[0] / bgr.shape[1])))))
        idx += 1

    with open(args.out / "results.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)

    # Timeline: merge consecutive samples with the same result.
    segments = []
    for r in rows:
        if segments and segments[-1][2] == r["result"]:
            segments[-1][1] = r["time_s"]
        else:
            segments.append([r["time_s"], r["time_s"], r["result"]])
    counts = {}
    for r in rows:
        counts[r["result"]] = counts.get(r["result"], 0) + 1
    with open(args.out / "summary.txt", "w") as f:
        f.write(f"{args.video.name}: {len(rows)} frames sampled at {args.fps} fps\n")
        for k, v in sorted(counts.items(), key=lambda kv: -kv[1]):
            f.write(f"  {k:18} {v:5}  {100 * v / len(rows):5.1f}%\n")
        f.write("\nTimeline (segments >= 2 s):\n")
        for a, b, res in segments:
            if b - a >= 2:
                f.write(f"  {mmss(a)} - {mmss(b)}  {res}\n")

    # Contact sheets: 6 x 8 labelled thumbnails each.
    colors = {"BEES": (0, 200, 0), "NO_BEES": (0, 0, 230), "UNSURE": (0, 200, 255)}
    per_sheet, cols = 48, 6
    for s in range(0, len(thumbs), per_sheet):
        tiles = []
        for t, res, score, img in thumbs[s:s + per_sheet]:
            img = img.copy()
            label = f"{mmss(t)} {res}" + ("" if score is None else f" {score:.2f}")
            cv2.rectangle(img, (0, 0), (img.shape[1], 22), colors.get(res, (128, 128, 128)), -1)
            cv2.putText(img, label, (4, 16), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 1, cv2.LINE_AA)
            tiles.append(img)
        while len(tiles) % cols:
            tiles.append(np.zeros_like(tiles[0]))
        grid = np.vstack([np.hstack(tiles[i:i + cols]) for i in range(0, len(tiles), cols)])
        cv2.imwrite(str(args.out / f"sheet_{s // per_sheet:03d}.jpg"), grid, [cv2.IMWRITE_JPEG_QUALITY, 80])

    print((args.out / "summary.txt").read_text())


if __name__ == "__main__":
    main()
