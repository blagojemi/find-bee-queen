"""Download openly licensed training material for the queen detector.

Sources: Roboflow Universe (labelled, YOLO), Wikimedia Commons, iNaturalist and
optionally Flickr (unlabelled photos, saved under <out>/to_label/<source>/).
Keys come only from ROBOFLOW_API_KEY / FLICKR_API_KEY and are never printed.

    python download_queen_data.py --dry-run
    python download_queen_data.py --only commons,inaturalist
"""
import argparse
import csv
import html
import os
import re
import sys
import time
from pathlib import Path

import requests

USER_AGENT = "FindQueen-dataset/0.1 (https://github.com/blagojemi/find-bee-queen)"
SOURCES = ("roboflow", "commons", "inaturalist", "flickr")

ROBOFLOW_PROJECTS = [
    "vincents-workspace-zlfac/queen-bee-detection-mjyh0",
    "beehelpers-workspace/bee-queen-scaner",
    "workspace1-wtvn6/queen-bee-wdsb3",
    "beetrack/v2-bee-monitor",
    "tainuis-workspace/bee-project-upcez",
    "sabian/beesbeesbees",
    "wolfcoded/bee-tx0q4",
    "hendricks_ricky-hotmail-de/bee-project",
    "bees-hkgn0/bee-detector-y0cpu",
    "rocket-ts144/queen-bee-c9kzm",
    "anannya-c7n8q/bee-classification-dd6lv",
    "beekeeper4/queen_drone_bee-detection",
    "queenbee/queen_bee_detection-k2nw7",
    "maher-odeh-zglci/queen_yolo",
    "lars-workspace-1/find-the-queen",
]

COMMONS_CATEGORIES = {
    "Category:Apis mellifera queen with workers on a honeycomb": "queen_on_comb",
    "Category:Apis mellifera queen": "queen_misc",
    "Category:Apis mellifera queen cells": "hard_negative_queen_cells",
}
COMMONS_API = "https://commons.wikimedia.org/w/api.php"
COMMONS_DELAY = 0.3
COMMONS_MAX_PX = 2560  # long side; Commons scales bigger originals down

INAT_API = "https://api.inaturalist.org/v1/observations"
INAT_TAXON = 47219  # Apis mellifera
INAT_LICENSES = {"cc0", "cc-by", "cc-by-sa"}
INAT_QUERIES = ["queen", "Königin", "matica"]
INAT_DELAY = 1.0

FLICKR_API = "https://api.flickr.com/services/rest/"
FLICKR_LICENSES = {"4": "CC BY 2.0", "5": "CC BY-SA 2.0", "9": "CC0 1.0", "10": "Public Domain Mark"}
FLICKR_QUERIES = ["queen bee frame", "queen bee hive", "bienenkönigin", "carnica queen"]
FLICKR_DELAY = 1.0

ATTRIBUTION_COLUMNS = ["file", "source", "author", "licence", "page_url", "image_url", "notes"]


class Downloader:
    def __init__(self, out: Path, dry_run: bool):
        self.out = out
        self.dry_run = dry_run
        self.session = requests.Session()
        self.session.headers["User-Agent"] = USER_AGENT
        self.counts = {}
        self.failures = []
        self._last = {}

    # --- helpers -------------------------------------------------------
    def wait(self, key: str, delay: float):
        """Keep at least `delay` seconds between requests sharing `key`."""
        last = self._last.get(key, 0.0)
        pause = delay - (time.monotonic() - last)
        if pause > 0:
            time.sleep(pause)
        self._last[key] = time.monotonic()

    def get_json(self, url, params, key, delay):
        for attempt in range(3):
            self.wait(key, delay)
            r = self.session.get(url, params=params, timeout=60)
            if r.status_code == 429 or r.status_code >= 500:
                time.sleep(10 * (attempt + 1))
                continue
            r.raise_for_status()
            return r.json()
        r.raise_for_status()
        return r.json()

    def attribution(self, row: dict):
        path = self.out / "attribution.csv"
        new = not path.exists()
        with path.open("a", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=ATTRIBUTION_COLUMNS)
            if new:
                w.writeheader()
            w.writerow({k: row.get(k, "") for k in ATTRIBUTION_COLUMNS})

    def fetch_file(self, source, url, dest: Path, key, delay, meta: dict):
        """Download one image unless it exists. Returns True if downloaded."""
        self.counts.setdefault(source, {"found": 0, "downloaded": 0, "skipped": 0})
        self.counts[source]["found"] += 1
        if self.dry_run:
            return False
        if dest.exists():
            self.counts[source]["skipped"] += 1
            return False
        try:
            for attempt in range(4):
                self.wait(key, delay)
                r = self.session.get(url, timeout=120)
                if r.status_code != 429 and r.status_code < 500:
                    break
                retry_after = r.headers.get("Retry-After", "")
                time.sleep(int(retry_after) if retry_after.isdigit() else 15 * (attempt + 1))
            r.raise_for_status()
            if not r.headers.get("Content-Type", "").startswith("image/"):
                raise ValueError(f"not an image: {r.headers.get('Content-Type')}")
            dest.parent.mkdir(parents=True, exist_ok=True)
            tmp = dest.with_suffix(dest.suffix + ".part")
            tmp.write_bytes(r.content)
            tmp.rename(dest)
        except Exception as e:  # keep going on per-file errors
            self.failures.append((source, url, str(e)))
            return False
        self.counts[source]["downloaded"] += 1
        self.attribution({"file": str(dest.relative_to(self.out)), "source": source,
                          "image_url": url, **meta})
        return True

    # --- Roboflow ------------------------------------------------------
    def roboflow(self):
        key = os.environ.get("ROBOFLOW_API_KEY")
        if not key:
            sys.exit("ROBOFLOW_API_KEY is not set. Set it, or leave roboflow out with --only.")
        import roboflow

        def scrub(msg):
            return str(msg).replace(key, "***")

        rf = roboflow.Roboflow(api_key=key)
        stats = self.counts.setdefault("roboflow", {"found": 0, "downloaded": 0, "skipped": 0})
        for ws_proj in ROBOFLOW_PROJECTS:
            ws, proj = ws_proj.split("/")
            try:
                project = rf.workspace(ws).project(proj)
                versions = project.versions()
                if not versions:
                    print(f"  roboflow {ws_proj}: no version, skipped")
                    continue
                latest = max(versions, key=lambda v: int(v.version))
                n = int(latest.version)
                print(f"  roboflow {ws_proj}: v{n}, {latest.images} images, "
                      f"classes {sorted(project.classes)}")
                stats["found"] += int(latest.images)
                if self.dry_run:
                    continue
                dest = self.out / "roboflow" / f"{proj}_v{n}"
                if (dest / "data.yaml").exists():
                    stats["skipped"] += int(latest.images)
                    continue
                latest.download("yolov8", location=str(dest), overwrite=False)
                images = [p for p in dest.rglob("*") if p.suffix.lower() in (".jpg", ".jpeg", ".png")]
                stats["downloaded"] += len(images)
                page = f"https://universe.roboflow.com/{ws_proj}/dataset/{n}"
                for img in images:
                    self.attribution({"file": str(img.relative_to(self.out)),
                                      "source": f"roboflow:{ws_proj}", "author": ws,
                                      "licence": "see Universe page", "page_url": page,
                                      "notes": f"version {n}, yolov8 export"})
            except Exception as e:
                msg = scrub(e)
                print(f"  roboflow {ws_proj}: FAILED {msg}")
                self.failures.append(("roboflow", ws_proj, msg))

    # --- Wikimedia Commons ---------------------------------------------
    def commons(self):
        for category, folder in COMMONS_CATEGORIES.items():
            params = {
                "action": "query", "format": "json", "generator": "categorymembers",
                "gcmtitle": category, "gcmtype": "file", "gcmlimit": "50",
                "prop": "imageinfo", "iiprop": "url|extmetadata|mime",
                "iiurlwidth": str(COMMONS_MAX_PX),
            }
            before = self.counts.get("commons", {}).get("found", 0)
            while True:
                try:
                    data = self.get_json(COMMONS_API, params, "commons", COMMONS_DELAY)
                except Exception as e:
                    self.failures.append(("commons", category, str(e)))
                    break
                for page in data.get("query", {}).get("pages", {}).values():
                    self._commons_file(page, folder)
                if "continue" not in data:
                    break
                params.update(data["continue"])
            n = self.counts.get("commons", {}).get("found", 0) - before
            print(f"  commons {category}: {n} files")

    def _commons_file(self, page, folder):
        info = (page.get("imageinfo") or [{}])[0]
        mime = info.get("mime", "")
        if not mime.startswith("image/") or mime == "image/svg+xml":
            return
        meta = info.get("extmetadata", {})
        licence = meta.get("LicenseShortName", {}).get("value", "")
        if re.search(r"\bNC\b", licence, re.I) or not licence:
            return
        # thumburl is the original when that is already small enough
        url = info.get("thumburl") or info.get("url")
        name = page["title"].split(":", 1)[1]
        name = re.sub(r"[^\w.\-]+", "_", name)
        if not Path(name).suffix:
            name += ".jpg"
        dest = self.out / "to_label" / "commons" / folder / name
        artist = html.unescape(re.sub(r"<[^>]+>", "", meta.get("Artist", {}).get("value", ""))).strip()
        self.fetch_file("commons", url, dest, "commons", COMMONS_DELAY, {
            "author": artist, "licence": licence,
            "page_url": info.get("descriptionurl", ""),
            "notes": f"commons category {folder}",
        })

    # --- iNaturalist ---------------------------------------------------
    def inaturalist(self):
        seen = set()
        for q in INAT_QUERIES:
            page, found_q = 1, 0
            while True:
                params = {
                    "taxon_id": INAT_TAXON, "photos": "true", "q": q,
                    "photo_license": ",".join(sorted(INAT_LICENSES)),
                    "per_page": 200, "page": page, "order_by": "id", "order": "asc",
                }
                try:
                    data = self.get_json(INAT_API, params, "inat", INAT_DELAY)
                except Exception as e:
                    self.failures.append(("inaturalist", f"q={q} page={page}", str(e)))
                    break
                results = data.get("results", [])
                for obs in results:
                    for photo in obs.get("photos", []):
                        lic = (photo.get("license_code") or "").lower()
                        if lic not in INAT_LICENSES or photo["id"] in seen:
                            continue
                        seen.add(photo["id"])
                        found_q += 1
                        url = photo["url"].replace("square", "original")
                        ext = Path(url.split("?")[0]).suffix or ".jpg"
                        dest = self.out / "to_label" / "inaturalist" / f"inat_{photo['id']}{ext}"
                        self.fetch_file("inaturalist", url, dest, "inat", INAT_DELAY, {
                            "author": (obs.get("user") or {}).get("login", ""),
                            "licence": lic,
                            "page_url": f"https://www.inaturalist.org/observations/{obs['id']}",
                            "notes": f"q={q}; {photo.get('attribution', '')}",
                        })
                # iNat stops paging at 10,000 results
                if len(results) < 200 or page * 200 >= 10000:
                    break
                page += 1
            print(f"  inaturalist q={q}: {found_q} new photos")

    # --- Flickr ----------------------------------------------------------
    def flickr(self):
        key = os.environ.get("FLICKR_API_KEY")
        if not key:
            print("  flickr: FLICKR_API_KEY not set, skipped")
            return
        seen = set()
        for text in FLICKR_QUERIES:
            page, found_q = 1, 0
            while True:
                params = {
                    "method": "flickr.photos.search", "api_key": key, "text": text,
                    "license": ",".join(FLICKR_LICENSES), "content_type": 1,
                    "extras": "license,owner_name,url_o,url_l", "per_page": 500,
                    "page": page, "format": "json", "nojsoncallback": 1,
                }
                try:
                    data = self.get_json(FLICKR_API, params, "flickr", FLICKR_DELAY)
                    if data.get("stat") != "ok":
                        raise RuntimeError(data.get("message", "flickr error"))
                except Exception as e:
                    self.failures.append(("flickr", f"text={text}", str(e).replace(key, "***")))
                    break
                photos = data["photos"]
                for p in photos["photo"]:
                    lic = str(p.get("license"))
                    url = p.get("url_o") or p.get("url_l")
                    if lic not in FLICKR_LICENSES or not url or p["id"] in seen:
                        continue
                    seen.add(p["id"])
                    found_q += 1
                    ext = Path(url).suffix or ".jpg"
                    dest = self.out / "to_label" / "flickr" / f"flickr_{p['id']}{ext}"
                    self.fetch_file("flickr", url, dest, "flickr", FLICKR_DELAY, {
                        "author": p.get("ownername", ""), "licence": FLICKR_LICENSES[lic],
                        "page_url": f"https://www.flickr.com/photos/{p['owner']}/{p['id']}",
                        "notes": f"text={text}",
                    })
                if page >= int(photos.get("pages", 1)):
                    break
                page += 1
            print(f"  flickr text={text}: {found_q} new photos")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default="queen_data")
    ap.add_argument("--only", help=f"comma list of {','.join(SOURCES)}")
    ap.add_argument("--dry-run", action="store_true", help="count only, download nothing")
    args = ap.parse_args()

    only = args.only.split(",") if args.only else list(SOURCES)
    bad = [s for s in only if s not in SOURCES]
    if bad:
        ap.error(f"unknown source(s): {bad}")
    if "roboflow" in only and not os.environ.get("ROBOFLOW_API_KEY"):
        sys.exit("ROBOFLOW_API_KEY is not set. Set it in your shell first, "
                 "or run with --only commons,inaturalist,flickr.")

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    d = Downloader(out, args.dry_run)
    for source in SOURCES:
        if source in only:
            print(f"[{source}]")
            getattr(d, source)()

    print("\nSummary" + (" (dry run, nothing downloaded)" if args.dry_run else ""))
    for source, c in d.counts.items():
        print(f"  {source:12s} found {c['found']:6d}  downloaded {c['downloaded']:6d}  "
              f"already there {c['skipped']:6d}")
    if d.failures:
        print(f"\n{len(d.failures)} failure(s):")
        for source, what, err in d.failures[:50]:
            print(f"  {source}: {what}: {err[:200]}")
        with (out / "failures.csv").open("w", newline="", encoding="utf-8") as f:
            csv.writer(f).writerows([("source", "item", "error"), *d.failures])


if __name__ == "__main__":
    main()
