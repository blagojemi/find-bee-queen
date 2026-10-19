"""Post-process the Roboflow exports into one YOLO dataset.

    python build_queen_dataset.py dupes     # near-duplicates -> duplicates.csv
    python build_queen_dataset.py classes   # class names + mapping_proposed.json
    python build_queen_dataset.py merge --mapping mapping.json   # -> <out>/merged
    python build_queen_dataset.py stats     # box counts and queen sizes in merged

Nothing in the downloads is deleted or changed; merge hard-links images.
"""
import argparse
import csv
import json
import os
import re
import shutil
import statistics
import subprocess
from pathlib import Path

import imagehash
import yaml
from PIL import Image

CLASSES = ["queen", "worker", "drone", "queen_cell"]
IMAGE_EXT = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
SPLITS = {"train": 0.8, "val": 0.1, "test": 0.1}
MAX_DISTANCE = 4
SMALL_QUEEN_PX = 40


def exports(out: Path):
    return sorted(p for p in (out / "roboflow").glob("*") if (p / "data.yaml").exists())


def export_images(export: Path):
    """(image, label file) pairs of one YOLO export, all splits."""
    pairs = []
    for img in sorted(export.rglob("*")):
        if img.suffix.lower() in IMAGE_EXT and img.parent.name == "images":
            label = img.parent.parent / "labels" / (img.stem + ".txt")
            pairs.append((img, label))
    return pairs


def class_names(export: Path):
    names = yaml.safe_load((export / "data.yaml").read_text())["names"]
    if isinstance(names, dict):
        names = [names[k] for k in sorted(names, key=int)]
    return [str(n) for n in names]


# --- duplicates ------------------------------------------------------------
def find_duplicates(out: Path):
    """Pairs of images with phash distance <= MAX_DISTANCE, cached in phash_cache.json."""
    cache_path = out / "phash_cache.json"
    cache = json.loads(cache_path.read_text()) if cache_path.exists() else {}
    hashes = {}
    for export in exports(out):
        for img, _ in export_images(export):
            key = str(img.relative_to(out))
            if key not in cache:
                try:
                    with Image.open(img) as im:
                        cache[key] = str(imagehash.phash(im))
                except Exception as e:
                    print(f"  cannot hash {key}: {e}")
                    continue
            hashes[key] = int(cache[key], 16)
    cache_path.write_text(json.dumps(cache))

    # Pigeonhole: two 64-bit hashes within distance 4 agree exactly on at
    # least one of 5 chunks, so only compare hashes sharing a chunk.
    chunks = [(0, 13), (13, 26), (26, 39), (39, 52), (52, 64)]
    buckets = {}
    for key, h in hashes.items():
        for i, (a, b) in enumerate(chunks):
            buckets.setdefault((i, (h >> a) & ((1 << (b - a)) - 1)), []).append(key)
    pairs = {}
    for members in buckets.values():
        for i in range(len(members)):
            for j in range(i + 1, len(members)):
                a, b = sorted((members[i], members[j]))
                if (a, b) not in pairs:
                    d = bin(hashes[a] ^ hashes[b]).count("1")
                    if d <= MAX_DISTANCE:
                        pairs[(a, b)] = d
    return sorted(hashes), pairs


def duplicate_groups(files, pairs):
    """Union-find over duplicate pairs -> {file: group root}."""
    parent = {f: f for f in files}

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for a, b in pairs:
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[max(ra, rb)] = min(ra, rb)
    return {f: find(f) for f in files}


def cmd_dupes(out: Path):
    files, pairs = find_duplicates(out)
    with (out / "duplicates.csv").open("w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["file_a", "file_b", "distance", "same_project"])
        for (a, b), d in sorted(pairs.items()):
            w.writerow([a, b, d, a.split("/")[1] == b.split("/")[1]])
    groups = duplicate_groups(files, pairs)
    dropped = sum(1 for f, root in groups.items() if f != root)
    cross = sum(1 for a, b in pairs if a.split("/")[1] != b.split("/")[1])
    print(f"{len(files)} images hashed, {len(pairs)} duplicate pairs "
          f"({cross} across projects), merge would leave out {dropped} images")
    print(f"written {out / 'duplicates.csv'}")


# --- class mapping ---------------------------------------------------------
def propose(name: str, project_names):
    n = name.lower().strip()
    if re.search(r"queen|k[oö]nigin|matica|reina", n):
        if re.search(r"cell|cup|zelle|cupula", n):
            return "queen_cell", ""
        return "queen", ""
    if re.search(r"drone|drohne|trut", n):
        return "drone", ""
    if re.search(r"worker|arbeiter", n):
        return "worker", ""
    if re.fullmatch(r"(honey ?)?bees?|abeja|biene|pcela|pčela", n):
        has_queen = any(re.search(r"queen", p.lower()) for p in project_names)
        if has_queen:
            return "worker", "generic bee next to a queen class"
        return "worker", "CHECK: generic bee, project has no queen class, queens may hide in it"
    return None, ""


def cmd_classes(out: Path):
    mapping = {}
    for export in exports(out):
        names = class_names(export)
        n_img = len(export_images(export))
        print(f"\n{export.name}  ({n_img} images)")
        mapping[export.name] = {}
        for name in names:
            target, note = propose(name, names)
            mapping[export.name][name] = target
            print(f"  {name!r:30} -> {target or 'DROP'}  {note}")
    path = out / "mapping_proposed.json"
    path.write_text(json.dumps(mapping, indent=2, ensure_ascii=False))
    print(f"\nwritten {path}; edit it (null = drop) and save as mapping.json to merge")


# --- merge -----------------------------------------------------------------
def to_box(values):
    """YOLO label values after the class id -> (x, y, w, h); polygons become their bbox."""
    if len(values) == 4:
        return tuple(values)
    if len(values) >= 6 and len(values) % 2 == 0:
        xs, ys = values[0::2], values[1::2]
        x0, x1 = max(0.0, min(xs)), min(1.0, max(xs))
        y0, y1 = max(0.0, min(ys)), min(1.0, max(ys))
        return ((x0 + x1) / 2, (y0 + y1) / 2, x1 - x0, y1 - y0)
    return None


def assign_splits(sizes: dict):
    """Whole projects to splits, biggest first, each to the split furthest below target."""
    total = sum(sizes.values()) or 1
    filled = {s: 0 for s in SPLITS}
    split_of = {}
    for project in sorted(sizes, key=sizes.get, reverse=True):
        s = max(SPLITS, key=lambda s: SPLITS[s] - filled[s] / total)
        split_of[project] = s
        filled[s] += sizes[project]
    return split_of


def link(src: Path, dst: Path):
    try:
        os.link(src, dst)
    except OSError:
        shutil.copy2(src, dst)


def cmd_merge(out: Path, mapping_path: Path):
    mapping = json.loads(mapping_path.read_text())
    files, pairs = find_duplicates(out)
    groups = duplicate_groups(files, pairs)
    dup_skip = {f for f, root in groups.items() if f != root}

    plan, unmapped, skipped = {}, set(), {"duplicate": 0, "only_dropped_classes": 0, "bad_label": 0}
    for export in exports(out):
        if export.name not in mapping:
            print(f"  {export.name}: not in mapping, left out")
            continue
        names = class_names(export)
        items = []
        for img, label in export_images(export):
            if str(img.relative_to(out)) in dup_skip:
                skipped["duplicate"] += 1
                continue
            lines_in = label.read_text().split("\n") if label.exists() else []
            lines_in = [l for l in lines_in if l.strip()]
            lines_out, bad = [], False
            for line in lines_in:
                parts = line.split()
                try:
                    cls = int(parts[0])
                    box = to_box([float(v) for v in parts[1:]])
                except (ValueError, IndexError):
                    box = None
                if box is None or cls >= len(names):
                    bad = True
                    continue
                target = mapping[export.name].get(names[cls])
                if names[cls] not in mapping[export.name]:
                    unmapped.add((export.name, names[cls]))
                if target is None:
                    continue
                lines_out.append(f"{CLASSES.index(target)} " + " ".join(f"{v:.6f}" for v in box))
            if bad and not lines_out:
                skipped["bad_label"] += 1
                continue
            if lines_in and not lines_out:
                skipped["only_dropped_classes"] += 1
                continue
            items.append((img, lines_out))
        plan[export.name] = items

    split_of = assign_splits({p: len(items) for p, items in plan.items()})
    merged = out / "merged"
    if merged.exists():
        shutil.rmtree(merged)
    for s in SPLITS:
        (merged / "images" / s).mkdir(parents=True)
        (merged / "labels" / s).mkdir(parents=True)
    for project, items in plan.items():
        s = split_of[project]
        for img, lines in items:
            stem = f"{project}__{img.stem}"
            link(img, merged / "images" / s / (stem + img.suffix.lower()))
            (merged / "labels" / s / (stem + ".txt")).write_text("\n".join(lines) + ("\n" if lines else ""))
    (merged / "data.yaml").write_text(yaml.safe_dump({
        "path": str(merged.resolve()), "train": "images/train", "val": "images/val",
        "test": "images/test", "names": dict(enumerate(CLASSES)),
    }, sort_keys=False))
    (merged / "splits.json").write_text(json.dumps(split_of, indent=2))

    for s in SPLITS:
        projects = [p for p in plan if split_of[p] == s]
        n = sum(len(plan[p]) for p in projects)
        print(f"  {s:5s} {n:6d} images  {projects}")
    print(f"  left out: {skipped}")
    if unmapped:
        print(f"  classes missing from mapping (dropped): {sorted(unmapped)}")
    cmd_stats(out)


# --- stats -----------------------------------------------------------------
def cmd_stats(out: Path):
    merged = out / "merged"
    counts = {c: 0 for c in CLASSES}
    queen_px, n_images, empty = [], 0, 0
    for s in SPLITS:
        for label in sorted((merged / "labels" / s).glob("*.txt")):
            n_images += 1
            lines = [l.split() for l in label.read_text().splitlines() if l.strip()]
            if not lines:
                empty += 1
            size = None
            for parts in lines:
                cls = CLASSES[int(parts[0])]
                counts[cls] += 1
                if cls == "queen":
                    if size is None:
                        img = next((merged / "images" / s).glob(label.stem + ".*"))
                        with Image.open(img) as im:
                            size = im.size
                    w, h = float(parts[3]) * size[0], float(parts[4]) * size[1]
                    queen_px.append(max(w, h))
    print(f"\nmerged: {n_images} images ({empty} with no boxes)")
    for c in CLASSES:
        print(f"  {c:10s} {counts[c]:7d} boxes")
    if queen_px:
        small = sum(1 for p in queen_px if p < SMALL_QUEEN_PX)
        print(f"  queen box longer side: median {statistics.median(queen_px):.0f} px, "
              f"{small} of {len(queen_px)} under {SMALL_QUEEN_PX} px")
    du = subprocess.run(["du", "-sh", str(out)], capture_output=True, text=True).stdout.split()
    print(f"  disk: {du[0] if du else '?'} in {out}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=["dupes", "classes", "merge", "stats"])
    ap.add_argument("--out", default="queen_data")
    ap.add_argument("--mapping", help="approved mapping JSON (merge)")
    args = ap.parse_args()
    out = Path(args.out)
    if args.command == "dupes":
        cmd_dupes(out)
    elif args.command == "classes":
        cmd_classes(out)
    elif args.command == "merge":
        if not args.mapping:
            ap.error("merge needs --mapping mapping.json (approved copy of mapping_proposed.json)")
        cmd_merge(out, Path(args.mapping))
    else:
        cmd_stats(out)


if __name__ == "__main__":
    main()
