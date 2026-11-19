"""Train the queen detector (YOLO) on queen_data/merged and export TFLite.

Run on the RTX A4000 (GPU 2 in nvidia-smi order):
    CUDA_DEVICE_ORDER=PCI_BUS_ID CUDA_VISIBLE_DEVICES=2 \
        .venv/bin/python train_queen.py --name v1_416
"""
import argparse
import os
import subprocess
from pathlib import Path

from ultralytics import YOLO, settings

# Keep everything local: no analytics.
settings.update({"sync": False})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="queen_data/merged/data.yaml")
    ap.add_argument("--model", default="yolov8n.pt")
    ap.add_argument("--imgsz", type=int, default=416)
    ap.add_argument("--epochs", type=int, default=60)
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--name", default="v1_416")
    args = ap.parse_args()

    model = YOLO(args.model)
    model.train(
        data=args.data, imgsz=args.imgsz, epochs=args.epochs, batch=args.batch,
        device=0, workers=16, cache="ram", amp=True, project=str(Path("runs").resolve()), name=args.name,
        exist_ok=True, patience=20, seed=0,
        # Zoom-out: most web photos are close-ups (median queen ~26% of the
        # image) but in the app she is ~4-5%. scale=0.9 resizes 0.1-1.9x and
        # mosaic tiles 4 images, so the model also sees small queens.
        scale=0.9, mosaic=1.0, close_mosaic=10,
        degrees=10, flipud=0.5, fliplr=0.5,  # comb has no "up" in a photo
    )
    best = Path(model.trainer.save_dir) / "weights" / "best.pt"
    trained = YOLO(best)
    print(trained.val(data=args.data, imgsz=args.imgsz, split="test", device=0))
    export_tflite(best, args.imgsz, args.data)


def export_tflite(best: Path, imgsz: int, data: str):
    """float32 and int8 TFLite next to best.pt.

    Ultralytics' LiteRT export needs a newer PyTorch than the CUDA 11.8 build
    used for training, so it runs in the CPU-only .venv-export (see README).
    int8 calibrates on the train images.
    """
    python = Path(__file__).resolve().parent / ".venv-export" / "bin" / "python"
    if not python.exists():
        print(f"No {python}; skipping TFLite export (see README, 'Export to TFLite').")
        return
    code = (
        "import sys; from ultralytics import YOLO, settings; settings.update({'sync': False})\n"
        "best, imgsz, data = sys.argv[1], int(sys.argv[2]), sys.argv[3]\n"
        "YOLO(best).export(format='tflite', imgsz=imgsz, device='cpu')\n"
        "YOLO(best).export(format='tflite', imgsz=imgsz, device='cpu', quantize=8, data=data)\n"
    )
    env = {**os.environ, "CUDA_VISIBLE_DEVICES": ""}
    subprocess.run([str(python), "-c", code, str(best), str(imgsz), data], check=True, env=env)


if __name__ == "__main__":
    main()
