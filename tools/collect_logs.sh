#!/usr/bin/env bash
# Collect Find Queen debug logs from the phone over adb and ship them to the build server.
# Run on the laptop. Usage: ./collect_logs.sh live|dump
#   live: enlarge the log buffer, stream logcat to a file while you test, stop with Ctrl-C
#   dump: after a test, take what is still in the log buffer
# Both modes also pull the app's files/debug (session.txt, frames.csv, thumbnails, crash files).
# Env overrides: REMOTE, SSH_PORT, REMOTE_DIR, LOG_ROOT, ANDROID_SERIAL
set -euo pipefail

MODE="${1:-}"
REMOTE="${REMOTE:-blagojem@node1.mbg-lab.com}"
SSH_PORT="${SSH_PORT:-2211}"
REMOTE_DIR="${REMOTE_DIR:-/home/blagojem/find-bee-queen/debug_logs}"
LOG_ROOT="${LOG_ROOT:-$HOME/Downloads/find-queen-logs}"
PKG="lab.mgb.findqueen"
# "FindQueen:I" already includes E. Do not add "FindQueen:E": a second rule for the same tag
# replaces the first, so only E lines would be kept.
FILTER=(-v epoch -s FindQueen:I)

# Key auth only, never prompt for a password or accept an unknown host key.
SSH=(/usr/bin/ssh -p "$SSH_PORT" -o BatchMode=yes -o StrictHostKeyChecking=yes -o PasswordAuthentication=no -o ConnectTimeout=10)

die() { echo "ERROR: $*" >&2; exit 1; }
warn() { echo "WARNING: $*" >&2; }

[[ "$MODE" == live || "$MODE" == dump ]] || die "usage: $0 live|dump"
command -v adb >/dev/null || die "adb not found on PATH"

echo "==> Checking device"
if ! state=$(adb get-state 2>&1) || [[ "$state" != device ]]; then
  echo "adb get-state: $state" >&2
  adb devices -l >&2
  die "need exactly one authorized device. Plug in the phone, enable USB debugging and accept the RSA prompt; unplug or set ANDROID_SERIAL=<serial> if several are connected."
fi
model=$(adb shell getprop ro.product.model | tr -d '\r')
release=$(adb shell getprop ro.build.version.release | tr -d '\r')
echo "    $model, Android $release"

ts=$(date +%Y%m%d_%H%M%S)
OUT="$LOG_ROOT/$ts"
mkdir -p "$OUT"
{
  echo "ro.product.model=$model"
  echo "ro.build.version.release=$release"
  echo "serial=$(adb get-serialno | tr -d '\r')"
  echo "app $(adb shell dumpsys package "$PKG" | grep -m1 versionName | tr -d ' \r')"
  echo "collected=$ts mode=$MODE"
} > "$OUT/device.txt"

# Default buffer holds only 1-2 min of this app's logs and resets on reboot.
adb logcat -G 16M || warn "could not set the log buffer to 16M, older lines may be lost"

echo "==> Logcat ($MODE)"
if [[ "$MODE" == live ]]; then
  echo "    Streaming to $OUT/logcat.txt. Run your test, then press Ctrl-C."
  adb logcat "${FILTER[@]}" > "$OUT/logcat.txt" &
  pid=$!
  # Background jobs ignore SIGINT in scripts, so stop adb from the trap and carry on.
  trap 'kill "$pid" 2>/dev/null || true' INT
  wait "$pid" || true
  wait "$pid" 2>/dev/null || true
  trap - INT
  echo
else
  adb logcat -d "${FILTER[@]}" > "$OUT/logcat.txt"
fi
echo "    $(grep -c 'FindQueen' "$OUT/logcat.txt" || true) FindQueen lines, sessions: $(grep -o 'session_[0-9_]*' "$OUT/logcat.txt" | sort -u | paste -sd' ' || true)"

echo "==> App files (files/debug)"
if ! adb shell run-as "$PKG" true >/dev/null 2>&1; then
  warn "run-as $PKG failed (app missing or not a debug build), files.tar skipped"
elif ! adb shell run-as "$PKG" test -d files/debug; then
  warn "no files/debug in the app yet (no session recorded), files.tar skipped"
else
  adb exec-out run-as "$PKG" tar cf - files/debug > "$OUT/files.tar"
  tar tf "$OUT/files.tar" >/dev/null 2>&1 || warn "files.tar is not a valid tar archive, check it by hand"
  echo "    $(tar tf "$OUT/files.tar" 2>/dev/null | grep -vc '/$' || true) files, $(du -h "$OUT/files.tar" | cut -f1)"
fi

ARCHIVE="$LOG_ROOT/find-queen-logs-$ts.tar.gz"
tar czf "$ARCHIVE" -C "$LOG_ROOT" "$ts"
local_sha=$(sha256sum "$ARCHIVE" | cut -d' ' -f1)
echo "==> Packed $ARCHIVE ($(du -h "$ARCHIVE" | cut -f1))"

echo "==> Uploading to $REMOTE:$REMOTE_DIR/"
if ! "${SSH[@]}" "$REMOTE" "mkdir -p '$REMOTE_DIR'"; then
  echo "Server unreachable. Archive kept locally: $ARCHIVE" >&2
  exit 2
fi
rsync -a --ignore-existing -e "${SSH[*]}" "$ARCHIVE" "$REMOTE:$REMOTE_DIR/" || { echo "Upload failed. Archive kept locally: $ARCHIVE" >&2; exit 2; }
remote_sha=$("${SSH[@]}" "$REMOTE" "sha256sum '$REMOTE_DIR/$(basename "$ARCHIVE")'" | cut -d' ' -f1)
[[ "$remote_sha" == "$local_sha" ]] || { echo "Checksum mismatch (local $local_sha, remote $remote_sha). Archive kept locally: $ARCHIVE" >&2; exit 3; }
echo "    sha256 OK: ${local_sha:0:16}"
echo "Local:  $OUT/"
echo "Remote: $REMOTE:$REMOTE_DIR/$(basename "$ARCHIVE")"
