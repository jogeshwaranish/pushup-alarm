#!/usr/bin/env bash
# Creates the pushup-api<N> emulators used by emulator-smoke.sh.
#
#   scripts/create-avds.sh            # all supported APIs
#   scripts/create-avds.sh 26 33      # just these
#   FORCE=1 scripts/create-avds.sh 35 # recreate an existing AVD
#
# Each AVD is a pixel_6 with an emulated front camera (the app requires
# android.hardware.camera.front), 2 GB RAM and 4 cores. Missing system images
# are installed with sdkmanager. Needs cmdline-tools at
# $SDK/cmdline-tools/latest.
set -euo pipefail

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
AVDMANAGER=$SDK/cmdline-tools/latest/bin/avdmanager
SDKMANAGER=$SDK/cmdline-tools/latest/bin/sdkmanager
AVD_HOME=${ANDROID_AVD_HOME:-$HOME/.android/avd}
DEVICE=${DEVICE:-pixel_6}

# google_apis images allow `adb root`; 36 reuses the preinstalled Play image
# (no root there, so the smoke test drives the UI instead).
declare -A IMAGES=(
  [26]="system-images;android-26;google_apis;x86_64"
  [29]="system-images;android-29;google_apis;x86_64"
  [31]="system-images;android-31;google_apis;x86_64"
  [33]="system-images;android-33;google_apis;x86_64"
  [35]="system-images;android-35;google_apis;x86_64"
  [36]="system-images;android-36.1;google_apis_playstore;x86_64"
)
ALL_APIS=(26 29 31 33 35 36)

[[ -x $AVDMANAGER ]] || { echo "avdmanager not found at $AVDMANAGER (install cmdline-tools;latest)" >&2; exit 1; }

set_cfg() { # file key value
  if grep -q "^$2=" "$1"; then
    sed -i "s|^$2=.*|$2=$3|" "$1"
  else
    printf '%s=%s\n' "$2" "$3" >>"$1"
  fi
}

apis=("$@")
[[ ${#apis[@]} -gt 0 ]] || apis=("${ALL_APIS[@]}")

for api in "${apis[@]}"; do
  pkg=${IMAGES[$api]:-}
  [[ -n $pkg ]] || { echo "no system image configured for API $api" >&2; exit 1; }
  name=pushup-api$api
  imgdir=$SDK/${pkg//;//}

  if [[ ! -f $imgdir/system.img ]]; then
    echo "== installing $pkg"
    "$SDKMANAGER" "$pkg"
  fi

  if [[ -d $AVD_HOME/$name.avd ]]; then
    if [[ ${FORCE:-0} == 1 ]]; then
      "$AVDMANAGER" delete avd -n "$name"
    else
      echo "== $name exists (FORCE=1 to recreate)"
      continue
    fi
  fi

  echo "== creating $name from $pkg"
  # avdmanager asks about a custom hardware profile; EOF on stdin means "no".
  "$AVDMANAGER" create avd -n "$name" -k "$pkg" -d "$DEVICE" </dev/null

  cfg=$AVD_HOME/$name.avd/config.ini
  set_cfg "$cfg" hw.camera.front emulated
  set_cfg "$cfg" hw.camera.back none
  set_cfg "$cfg" hw.ramSize 2048
  set_cfg "$cfg" hw.cpu.ncore 4
  set_cfg "$cfg" hw.keyboard yes
  set_cfg "$cfg" hw.accelerometer yes
  set_cfg "$cfg" hw.audioInput no
  set_cfg "$cfg" hw.gpu.enabled yes
  set_cfg "$cfg" hw.gpu.mode swiftshader_indirect
  set_cfg "$cfg" disk.dataPartition.size 4G
  set_cfg "$cfg" fastboot.forceColdBoot yes
  set_cfg "$cfg" fastboot.forceFastBoot no
  set_cfg "$cfg" showDeviceFrame no
done

echo "== AVDs:"
"$AVDMANAGER" list avd -c | grep '^pushup-' || true
