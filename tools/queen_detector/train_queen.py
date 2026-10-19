"""Train the queen detector (YOLO) on queen_data/merged and export TFLite.

Run on the RTX A4000 (GPU 2 in nvidia-smi order):
    CUDA_DEVICE_ORDER=PCI_BUS_ID CUDA_VISIBLE_DEVICES=2 \
        .venv/bin/python train_queen.py --name v1_416
"""
import argparse
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
    # float16 and int8 TFLite for the phone; int8 calibrates on the train images.
    trained.export(format="tflite", imgsz=args.imgsz, half=True)
    trained.export(format="tflite", imgsz=args.imgsz, int8=True, data=args.data)


if __name__ == "__main__":
    main()
