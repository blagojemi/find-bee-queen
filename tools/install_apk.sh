#!/usr/bin/env bash
# Fetch the debug APK from the build server and install it on a USB/Wi-Fi adb device.
# Run on the laptop. Usage: ./install_apk.sh [adb-serial]
# Env overrides: REMOTE, SSH_PORT, REMOTE_APK, LOCAL_DIR, ANDROID_USER (default 0 = personal profile, "all" = every profile)
set -euo pipefail

REMOTE="${REMOTE:-blagojem@node1.mbg-lab.com}"
SSH_PORT="${SSH_PORT:-2211}"
REMOTE_APK="${REMOTE_APK:-/home/blagojem/find-bee-queen/app/build/outputs/apk/debug/app-debug.apk}"
LOCAL_DIR="${LOCAL_DIR:-$HOME/Downloads/find-queen-apk}"
ANDROID_USER="${ANDROID_USER:-0}"
PKG="lab.mgb.findqueen"
ACTIVITY=".MainActivity"
SERIAL="${1:-${ANDROID_SERIAL:-}}"

# Key auth only, never prompt for a password or accept an unknown host key.
SSH=(/usr/bin/ssh -p "$SSH_PORT" -o BatchMode=yes -o StrictHostKeyChecking=yes -o PasswordAuthentication=no -o ConnectTimeout=10)
LOCAL_APK="$LOCAL_DIR/app-debug.apk"

die() { echo "ERROR: $*" >&2; exit 1; }

command -v adb >/dev/null || die "adb not found (sudo apt install adb)"
command -v rsync >/dev/null || die "rsync not found"

echo "==> Remote build info"
"${SSH[@]}" "$REMOTE" "test -f '$REMOTE_APK'" || die "no APK at $REMOTE:$REMOTE_APK (run ./gradlew assembleDebug on the server)"
"${SSH[@]}" "$REMOTE" "stat -c '    built: %y  size: %s bytes' '$REMOTE_APK'"
remote_sha=$("${SSH[@]}" "$REMOTE" "sha256sum '$REMOTE_APK'" | cut -d' ' -f1)

echo "==> Downloading to $LOCAL_APK"
mkdir -p "$LOCAL_DIR"
rsync -a -e "${SSH[*]}" "$REMOTE:$REMOTE_APK" "$LOCAL_APK"
local_sha=$(sha256sum "$LOCAL_APK" | cut -d' ' -f1)
[[ "$local_sha" == "$remote_sha" ]] || die "checksum mismatch: local $local_sha vs remote $remote_sha"
echo "    sha256 OK: ${local_sha:0:16}"

echo "==> Checking device"
if [[ -z "$SERIAL" ]]; then
  mapfile -t devices < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  case ${#devices[@]} in
    0) die "no authorized device. Enable USB debugging, plug in the phone, accept the RSA prompt, then retry. ($(adb devices | awk 'NR>1 && NF {print $1": "$2}' | paste -sd' '))" ;;
    1) SERIAL="${devices[0]}" ;;
    *) die "several devices connected, pass one as argument: ${devices[*]}" ;;
  esac
fi
ADB=(adb -s "$SERIAL")
echo "    $SERIAL ($("${ADB[@]}" shell getprop ro.product.model | tr -d '\r'), Android $("${ADB[@]}" shell getprop ro.build.version.release | tr -d '\r'))"

echo "==> Installing (user: $ANDROID_USER)"
user_args=()
[[ "$ANDROID_USER" != "all" ]] && user_args=(--user "$ANDROID_USER")
if ! out=$("${ADB[@]}" install -r "${user_args[@]}" "$LOCAL_APK" 2>&1); then
  echo "$out" >&2
  if grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE <<<"$out"; then
    die "installed app has a different signing key. Uninstalling deletes its data: adb -s $SERIAL uninstall $PKG"
  fi
  die "install failed"
fi
echo "    $out" | tail -1
"${ADB[@]}" shell dumpsys package "$PKG" | grep -m2 -E 'versionName|lastUpdateTime' | sed 's/^ */    /'

echo "==> Launching"
"${ADB[@]}" shell am start ${user_args[@]+"${user_args[@]}"} -n "$PKG/$ACTIVITY" >/dev/null
echo "Done."
