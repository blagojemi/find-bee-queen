"""Train the Find Queen bee-check classifier and export it for the app.

Reads labelled photos from <samples>/bees and <samples>/no_bees (folder name = label),
trains a small on-device classifier and writes bee_check.tflite (with metadata) to
<out>. Copy that file to app/src/main/assets/.

    python train.py --out out
"""

import argparse
from pathlib import Path

from mediapipe_model_maker import image_classifier

MODELS = {
    # Smallest and fastest; good default for a cheap on-phone check.
    "mobilenet_v2": image_classifier.SupportedModels.MOBILENET_V2,
    # A bit bigger and usually a bit more accurate.
    "efficientnet_lite0": image_classifier.SupportedModels.EFFICIENTNET_LITE0,
}
IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png"}


def count_images(folder: Path) -> int:
    return sum(1 for p in folder.rglob("*") if p.suffix.lower() in IMAGE_SUFFIXES)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--samples", type=Path, default=Path(__file__).resolve().parents[2] / "debug" / "pre-check-samples")
    parser.add_argument("--out", type=Path, default=Path("out"))
    parser.add_argument("--model", choices=MODELS, default="mobilenet_v2")
    parser.add_argument("--epochs", type=int, default=15)
    args = parser.parse_args()

    for label in ("bees", "no_bees"):
        n = count_images(args.samples / label)
        print(f"{label}: {n} images")
        if n < 50:
            raise SystemExit(f"Need at least 50 images in {args.samples / label} (aim for 300+).")

    data = image_classifier.Dataset.from_folder(str(args.samples))
    train_data, rest = data.split(0.8)
    validation_data, test_data = rest.split(0.5)

    options = image_classifier.ImageClassifierOptions(
        supported_model=MODELS[args.model],
        # Model Maker's default augmentation (random crops, flips, brightness) is kept on;
        # it helps with real field light and angles.
        hparams=image_classifier.HParams(export_dir=str(args.out), epochs=args.epochs),
        model_options=image_classifier.ModelOptions(dropout_rate=0.2),
    )
    model = image_classifier.ImageClassifier.create(
        train_data=train_data, validation_data=validation_data, options=options,
    )

    loss, accuracy = model.evaluate(test_data)
    print(f"Test accuracy: {accuracy:.3f} (loss {loss:.3f}) on {test_data.size} held-out images")

    model.export_model("bee_check.tflite")
    print(f"Wrote {args.out / 'bee_check.tflite'}; copy it to app/src/main/assets/")


if __name__ == "__main__":
    main()
