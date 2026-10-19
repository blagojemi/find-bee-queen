"""Run the queen detector over a video and save the frames where it sees a queen.

    CUDA_DEVICE_ORDER=PCI_BUS_ID CUDA_VISIBLE_DEVICES=2 .venv/bin/python eval_video.py \
        ../../test_videos/inspection_h264.mp4 --model runs/v1_416/best.pt --imgsz 416 --out out_video/v1_416

Writes <out>/detections.csv (one row per sampled frame), frame_<t>.jpg for every
frame with a queen box (box drawn), and index.html to look through them locally.
There is no ground truth here: the page is for a person to judge hits and misses.
"""
import argparse
import csv
import html
from pathlib import Path

import cv2
from ultralytics import YOLO, settings

settings.update({"sync": False})
QUEEN = 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("video")
    ap.add_argument("--model", default="runs/v1_416/best.pt")
    ap.add_argument("--imgsz", type=int, default=416)
    ap.add_argument("--fps", type=float, default=1.0)
    ap.add_argument("--conf", type=float, default=0.25)
    ap.add_argument("--out", default="out_video/v1_416")
    args = ap.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    model = YOLO(args.model, task="detect")
    cap = cv2.VideoCapture(args.video)
    src_fps = cap.get(cv2.CAP_PROP_FPS) or 30
    step = max(1, round(src_fps / args.fps))
    rows, hits, idx = [], [], 0
    while True:
        ok = cap.grab()
        if not ok:
            break
        if idx % step == 0:
            ok, frame = cap.retrieve()
            if not ok:
                break
            t = idx / src_fps
            r = model.predict(frame, imgsz=args.imgsz, conf=args.conf, classes=[QUEEN],
                              verbose=False)[0]
            confs = r.boxes.conf.tolist()
            best = max(confs, default=0.0)
            name = ""
            if confs:
                name = f"frame_{int(t):05d}.jpg"
                img = frame.copy()
                h, w = img.shape[:2]
                for (x0, y0, x1, y1), c in zip(r.boxes.xyxy.tolist(), confs):
                    cv2.rectangle(img, (int(x0), int(y0)), (int(x1), int(y1)), (140, 40, 255), 4)
                    cv2.putText(img, f"queen {c:.2f}", (int(x0), max(30, int(y0) - 10)),
                                cv2.FONT_HERSHEY_SIMPLEX, 1.2, (140, 40, 255), 3)
                scale = 960 / max(h, w)
                img = cv2.resize(img, (int(w * scale), int(h * scale)))
                cv2.imwrite(str(out / name), img, [cv2.IMWRITE_JPEG_QUALITY, 80])
                hits.append((t, best, len(confs), name))
            rows.append({"t_s": f"{t:.1f}", "queens": len(confs), "best_conf": f"{best:.3f}",
                         "file": name, "inference_ms": f"{r.speed['inference']:.1f}"})
        idx += 1
    cap.release()
    if not rows:
        raise SystemExit(f"No frames read from {args.video} (codec not supported by OpenCV? "
                         "convert with ffmpeg to H.264 first)")

    with (out / "detections.csv").open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)

    cards = "".join(
        f'<figure><img src="{n}" loading="lazy"><figcaption>{int(t)//60}:{int(t)%60:02d} '
        f'· best {c:.2f} · {k} box{"es" if k > 1 else ""}</figcaption></figure>'
        for t, c, k, n in sorted(hits, key=lambda h: -h[1]))
    (out / "index.html").write_text(f"""<!doctype html><meta charset="utf-8">
<title>Queen detections · {html.escape(Path(args.video).name)}</title>
<style>body{{font:14px system-ui;margin:16px;background:#111;color:#eee}}
.g{{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:10px}}
figure{{margin:0}}img{{width:100%}}figcaption{{font:12px monospace;color:#aaa}}</style>
<h1>{len(hits)} of {len(rows)} frames with a queen box (conf ≥ {args.conf})</h1>
<p>{html.escape(args.model)} · imgsz {args.imgsz} · {args.fps} fps · sorted by confidence</p>
<div class="g">{cards}</div>""")

    confs = sorted((h[1] for h in hits), reverse=True)
    print(f"{len(rows)} frames sampled, {len(hits)} with a queen box (conf >= {args.conf})")
    for th in (0.25, 0.4, 0.5, 0.7):
        print(f"  conf >= {th}: {sum(c >= th for c in confs)} frames")
    print(f"  saved to {out}/index.html")


if __name__ == "__main__":
    main()
