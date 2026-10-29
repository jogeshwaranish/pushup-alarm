#!/usr/bin/env bash
# Boots one headless emulator per Android version, installs the APK and walks
# the app's critical path: home screen -> practice screen -> test alarm ->
# rotation. Collects screenshots, UI dumps and logcat per API and prints a
# PASS/FAIL table.
#
#   scripts/emulator-smoke.sh <apk> [api...]      # default APIs: 26 29 31 33 35 36
#
# The APK must contain x86_64 natives (the emulators are x86_64), e.g. built with
# abiFilters "arm64-v8a", "x86_64".
#
# Emulators come from scripts/create-avds.sh (AVD names pushup-api<N>). They run
# one at a time, cold-booted with wiped data, and are killed afterwards.
# Every adb call targets the emulator's serial explicitly; other connected
# devices (e.g. a real phone over wireless adb) are never touched, and the adb
# server is never restarted.
#
# Output: scripts/out/api<N>/  (screenshots *.png, UI dumps *.xml, logcat.txt,
#         logcat-full.txt, crash.txt, services.txt, run.log), scripts/out/summary.txt
#
# FAIL = install failure, boot failure, any crash/ANR of com.pushupalarm, the
#        alarm screen not shown after the test alarm, or AlarmService not running.
#
# Env knobs:
#   MINIMAL_GRANTS=1  only grant CAMERA (+POST_NOTIFICATIONS on 33+). By default the
#                     harness also grants what the in-app Setup list asks for
#                     (overlay, full-screen intents on 34+, battery whitelist), like
#                     a phone that finished setup.
#   SKIP_TEST_RING=1  skip the in-app "Test ring" button (scheduled alarm path).
#   BOOT_TIMEOUT=420  seconds to wait for sys.boot_completed.
#   EMU_HEADFUL=1     show the emulator window (debugging).
#   EMU_GPUS="swangle_indirect swiftshader_indirect"
#                     -gpu modes tried in order if the emulator dies while booting
#                     (swiftshader_indirect segfaults on emulator 36.2 + this host).
#   OUT_DIR=...       output root (default scripts/out).
set -uo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
ADB=$SDK/platform-tools/adb
EMULATOR=$SDK/emulator/emulator
AVD_HOME=${ANDROID_AVD_HOME:-$HOME/.android/avd}
OUT_ROOT=${OUT_DIR:-$SCRIPT_DIR/out}
PKG=com.pushupalarm
BOOT_TIMEOUT=${BOOT_TIMEOUT:-420}
ALARM_WAIT=${ALARM_WAIT:-5}
EMU_GPUS=${EMU_GPUS:-swangle_indirect swiftshader_indirect}
DEFAULT_APIS=(26 29 31 33 35 36)

usage() { sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
[[ $# -ge 1 ]] || usage
APK=$(realpath -e "$1" 2>/dev/null) || { echo "APK not found: $1" >&2; exit 2; }
shift
APIS=("$@")
[[ ${#APIS[@]} -gt 0 ]] || APIS=("${DEFAULT_APIS[@]}")
for t in "$ADB" "$EMULATOR"; do [[ -x $t ]] || { echo "missing $t" >&2; exit 2; }; done

mkdir -p "$OUT_ROOT"

# ---------------------------------------------------------------- helpers ---
SERIAL=""
EMU_PID=""
API=""
OUT=""

log() { printf '[%s] api%s: %s\n' "$(date +%T)" "$API" "$*" | tee -a "$OUT/run.log" >&2; }

# Every adb call goes through here, pinned to the emulator we started.
a() {
  [[ $SERIAL =~ ^emulator-[0-9]+$ ]] || { echo "refusing adb without an emulator serial" >&2; return 99; }
  timeout "${ADB_TIMEOUT:-60}" "$ADB" -s "$SERIAL" "$@"
}
ash() { a shell "$@" | tr -d '\r'; }
join() { local s; s=$(printf '%s; ' "$@"); s=${s%; }; printf '%s' "${s#; }"; }

pick_port() {
  local p devs
  devs=$("$ADB" devices 2>/dev/null)
  for p in $(seq 5554 2 5584); do
    grep -q "^emulator-$p[[:space:]]" <<<"$devs" && continue
    ss -ltn 2>/dev/null | grep -Eq "[:.]($p|$((p + 1)))[[:space:]]" && continue
    echo "$p"
    return 0
  done
  return 1
}

kill_emulator() {
  [[ -n $EMU_PID ]] || return 0
  if kill -0 "$EMU_PID" 2>/dev/null; then
    a emu kill >/dev/null 2>&1
    for _ in $(seq 1 30); do kill -0 "$EMU_PID" 2>/dev/null || break; sleep 1; done
    kill -0 "$EMU_PID" 2>/dev/null && kill "$EMU_PID" 2>/dev/null
    sleep 2
    kill -0 "$EMU_PID" 2>/dev/null && kill -9 "$EMU_PID" 2>/dev/null
  fi
  wait "$EMU_PID" 2>/dev/null
  EMU_PID=""
}
trap 'kill_emulator; exit 130' INT TERM
trap 'kill_emulator' EXIT

ui_dump() { # name -> $OUT/name.xml, also echoes the xml
  # A live camera preview never goes idle, so uiautomator can stall; cap it.
  ADB_TIMEOUT=25 a shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
  a exec-out cat /data/local/tmp/ui.xml 2>/dev/null | tee "$OUT/$1.xml"
}

shot() { # name
  a exec-out screencap -p >"$OUT/$1.png" 2>/dev/null
  [[ -s $OUT/$1.png ]] || log "screenshot $1 failed"
  ui_dump "$1" >/dev/null
}

# The <node ...> element whose text is exactly $1 (Compose merges a Button's label into it).
find_node() { # text [xml]
  local xml=${2:-$(ui_dump _last)}
  grep -o "<node [^>]*text=\"$1\"[^>]*>" <<<"$xml" | head -1
}

tap_node() { # node-element
  local b x1 y1 x2 y2
  b=$(grep -o 'bounds="[^"]*"' <<<"$1" | grep -o '[0-9]\+' | tr '\n' ' ')
  read -r x1 y1 x2 y2 <<<"$b"
  [[ -n ${y2:-} ]] || return 1
  ash input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
}

tap_text() { # text -> 0 tapped, 1 not found, 2 disabled
  local node
  node=$(find_node "$1")
  [[ -n $node ]] || return 1
  grep -q 'enabled="false"' <<<"$node" && return 2
  tap_node "$node"
}

# What's in front: focused window + resumed activity (format differs per API).
front() {
  {
    ash dumpsys window windows | grep -E 'mCurrentFocus=' | tail -1
    ash dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity:' | head -2
  } | sed 's/^ *//'
}
display_rotation() { # 0..3 for the default display
  local r
  r=$(ash dumpsys input | grep -m1 -oE 'SurfaceOrientation: [0-9]' | grep -oE '[0-9]$')
  [[ -n $r ]] || r=$(ash dumpsys window displays | grep -m1 -oE 'mRotation=[0-9]|mCurrentRotation=[^ ]+' |
    sed -E 's/.*ROTATION_0$/0/; s/.*ROTATION_90$/1/; s/.*ROTATION_180$/2/; s/.*ROTATION_270$/3/; s/.*=//')
  echo "${r:-?}"
}
front_is() { front | grep -q "$PKG/[^ ]*$1"; }

wait_front() { # activity seconds
  local i
  for i in $(seq 1 "$2"); do front_is "$1" && return 0; sleep 1; done
  return 1
}

# Crashes/ANRs of our package in a threadtime logcat dump -> stdout (empty if none).
crashes_in() { # logfile
  awk -v pkg="$PKG" '
    /AndroidRuntime: FATAL EXCEPTION/ { cand = $3; next }
    cand != "" && $3 == cand && /AndroidRuntime: Process: / {
      if (index($0, "Process: " pkg ",")) { bad[cand] = 1; print "---- java crash (pid " cand ")" }
      cand = ""
    }
    ($3 in bad) && / AndroidRuntime: / { print }
    / ANR in / && index($0, pkg) { print "---- ANR"; print }
    />>> / && index($0, ">>> " pkg) { print "---- native crash"; print }
  ' "$1"
}

# One line per distinct crash: "<exception (trimmed)> @ <first app frame>  (xN)", in order seen.
crash_signatures() { # crash.txt
  awk '
    function flush() {
      if (sig != "") { if (!(sig in n)) order[++k] = sig; n[sig]++ }
      sig = ""; ex = ""; fr = ""
    }
    /^---- (ANR|native)/ { flush(); kind = $0; next }
    /^---- / { flush(); kind = ""; next }
    kind != "" { sig = kind ": " substr($0, index($0, ": ") + 2); kind = ""; next }
    / AndroidRuntime: / {
      line = substr($0, index($0, "AndroidRuntime: ") + 16)
      if (ex == "" && line ~ /^[A-Za-z0-9_.$]+(Exception|Error)/) { ex = substr(line, 1, 110) }
      else if (fr == "" && line ~ /^[ \t]*at com\.pushupalarm\./) { sub(/^[ \t]*at /, "", line); fr = line }
      if (ex != "") sig = ex (fr != "" ? " @ " fr : "")
    }
    END { flush(); for (i = 1; i <= k; i++) printf "%s  (x%d)\n", order[i], n[order[i]] }
  ' "$1"
}

check_crash_now() { # step-label: notes a crash seen so far (non-fatal for the run)
  a logcat -d -v threadtime -b main -b system -b crash >"$OUT/.logcat-now.txt" 2>/dev/null
  if [[ -n $(crashes_in "$OUT/.logcat-now.txt") ]] && [[ $CRASH_SEEN_AT == "" ]]; then
    CRASH_SEEN_AT=$1
    log "!! crash/ANR detected by step: $1"
  fi
}

# --------------------------------------------------------------- one API ---
declare -A RESULT REASONS TIMES TOP NOTES

run_api() {
  API=$1
  OUT=$OUT_ROOT/api$API
  rm -rf "$OUT"
  mkdir -p "$OUT"
  local avd=pushup-api$API start=$SECONDS reasons=() notes=() root=0 port
  CRASH_SEEN_AT=""

  if [[ ! -d $AVD_HOME/$avd.avd ]]; then
    RESULT[$API]=FAIL; REASONS[$API]="AVD $avd missing (run scripts/create-avds.sh $API)"; TIMES[$API]=0
    return
  fi

  local avail
  avail=$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)
  ((avail < 2600)) && log "warning: only ${avail} MB RAM available"

  port=$(pick_port) || { RESULT[$API]=FAIL; REASONS[$API]="no free emulator port"; TIMES[$API]=0; return; }
  SERIAL=emulator-$port
  local window=(-no-window)
  [[ ${EMU_HEADFUL:-0} == 1 ]] && window=()

  # ---- boot (each GPU mode in turn: swiftshader_indirect segfaults on some
  #      image/emulator combos, e.g. android-36.1 on emulator 36.2, so fall back)
  local booted=0 t0=$SECONDS gpu
  for gpu in $EMU_GPUS; do
    log "booting $avd on $SERIAL (cold, wiped, -gpu $gpu)"
    "$EMULATOR" -avd "$avd" -port "$port" "${window[@]}" -no-audio -no-boot-anim \
      -gpu "$gpu" -no-snapshot -wipe-data -camera-front emulated \
      -memory 2048 -no-metrics >>"$OUT/emulator.log" 2>&1 &
    EMU_PID=$!
    t0=$SECONDS
    while ((SECONDS - t0 < BOOT_TIMEOUT)); do
      kill -0 "$EMU_PID" 2>/dev/null || break
      [[ $(ADB_TIMEOUT=10 ash getprop sys.boot_completed 2>/dev/null) == 1 ]] && { booted=1; break; }
      sleep 3
    done
    ((booted)) && break
    if kill -0 "$EMU_PID" 2>/dev/null; then
      break # alive but slow: a timeout, not a GPU crash
    fi
    wait "$EMU_PID" 2>/dev/null
    log "emulator exited during boot with -gpu $gpu (exit $?)"
    EMU_PID=""
  done
  if ((booted == 0)); then
    log "boot failed/timed out (see emulator.log)"
    RESULT[$API]=FAIL; REASONS[$API]="emulator did not boot"; kill_emulator; TIMES[$API]=$((SECONDS - start))
    return
  fi
  log "booted in $((SECONDS - t0)) s"
  # Package manager can lag boot_completed slightly.
  for _ in $(seq 1 30); do ash pm path android 2>/dev/null | grep -q package: && break; sleep 2; done

  # ---- root (google_apis images); Play images can't, so the UI is driven instead
  a root >"$OUT/root.txt" 2>&1
  sleep 2
  a wait-for-device >/dev/null 2>&1
  for _ in $(seq 1 15); do
    [[ $(ADB_TIMEOUT=10 ash id -u 2>/dev/null) == 0 ]] && { root=1; break; }
    sleep 1
  done
  log "adb root: $([[ $root == 1 ]] && echo yes || echo "no ($(head -c 80 "$OUT/root.txt"))")"

  # ---- device prep: awake, unlocked, no animations, portrait
  ash svc power stayon true
  ash settings put system screen_off_timeout 1800000
  ash settings put global window_animation_scale 0
  ash settings put global transition_animation_scale 0
  ash settings put global animator_duration_scale 0
  # Don't let the one-time "Viewing full screen" hint cover the immersive alarm screen.
  ash settings put secure immersive_mode_confirmations confirmed
  ash settings put system accelerometer_rotation 0
  ash settings put system user_rotation 0
  ash input keyevent KEYCODE_WAKEUP
  ash wm dismiss-keyguard >/dev/null 2>&1
  ash input keyevent 82
  ash input keyevent KEYCODE_HOME
  a logcat -G 16M >/dev/null 2>&1
  a logcat -c >/dev/null 2>&1

  # ---- install
  log "installing $(basename "$APK")"
  ADB_TIMEOUT=300 a install -r -t "$APK" >"$OUT/install.txt" 2>&1
  if ! grep -q '^Success' "$OUT/install.txt"; then
    log "install failed: $(grep -m1 -E 'Failure|INSTALL_' "$OUT/install.txt")"
    reasons+=("install failed: $(grep -m1 -oE 'INSTALL_[A-Z_]+' "$OUT/install.txt" || tail -1 "$OUT/install.txt")")
  else
    # ---- permissions
    ash pm grant $PKG android.permission.CAMERA
    ((API >= 33)) && ash pm grant $PKG android.permission.POST_NOTIFICATIONS
    if [[ ${MINIMAL_GRANTS:-0} != 1 ]]; then
      ash appops set $PKG SYSTEM_ALERT_WINDOW allow
      ((API >= 34)) && ash appops set $PKG USE_FULL_SCREEN_INTENT allow
      # 31-32 have no USE_EXACT_ALARM; the app asks for "Alarms & reminders" instead.
      ((API >= 31 && API <= 32)) && ash appops set $PKG SCHEDULE_EXACT_ALARM allow
      ash dumpsys deviceidle whitelist +$PKG >/dev/null
    fi
    ash dumpsys package $PKG | grep -E 'granted=true' | sed 's/^ *//' >"$OUT/granted.txt"

    # ---- 1. home screen
    log "launching MainActivity"
    ash am start -W -n $PKG/.MainActivity >"$OUT/am-main.txt" 2>&1
    sleep 4
    shot 01-main
    front >"$OUT/front-main.txt"
    front_is MainActivity || notes+=("MainActivity not in front after launch")
    check_crash_now "launch MainActivity"
    # Scroll to see the Setup list / rest of the screen.
    ash input swipe 540 1900 540 500 300
    sleep 1
    shot 01b-main-scrolled
    ash input swipe 540 500 540 1900 300

    # ---- 2. practice screen
    log "opening practice screen"
    if ((root)); then
      ash am start -W -n $PKG/.AlarmActivity --es mode PRACTICE >"$OUT/am-practice.txt" 2>&1
    else
      ash am start -W -n $PKG/.MainActivity >/dev/null 2>&1
      sleep 2
      tap_text Practice || notes+=("Practice button not tappable")
    fi
    sleep 5 # camera + pose detector start-up
    shot 02-practice
    front >"$OUT/front-practice.txt"
    front_is AlarmActivity || notes+=("practice screen not shown")
    check_crash_now "practice screen"
    ash input keyevent KEYCODE_BACK
    sleep 2

    # ---- 3a. in-app "Test ring" (schedules a real alarm 10 s out via AlarmManager)
    local alarm_shown=0 rang_via=""
    if [[ ${SKIP_TEST_RING:-0} == 1 ]]; then
      :
    elif [[ -n $CRASH_SEEN_AT ]] && ! grep -q MainActivity "$OUT/front-main.txt"; then
      # Relaunching a home screen that already crashed would just crash again, and two
      # quick crashes make Android mark the app "bad", blocking the alarm broadcast below.
      notes+=("Test ring skipped: home screen crashed")
    else
      ash am start -W -n $PKG/.MainActivity >/dev/null 2>&1
      sleep 3
      tap_text "Test ring"
      case $? in
        0)
          sleep 2
          if tap_text "Ring it"; then
            log "Test ring scheduled from the UI; going home and waiting"
            sleep 1
            shot 03a-test-ring-toast
            ash input keyevent KEYCODE_HOME
            if wait_front AlarmActivity 30; then
              alarm_shown=1; rang_via="Test ring button"
            else
              reasons+=("in-app Test ring did not bring up the alarm screen")
            fi
          else
            notes+=("Test ring confirm dialog not found")
          fi
          ;;
        1) notes+=("Test ring button not found on home screen") ;;
        2) notes+=("Test ring button disabled (Setup not complete on this API)") ;;
      esac
      check_crash_now "test ring (scheduled)"
    fi

    # ---- 3b. fire the alarm directly through the receiver (root), as the alarm clock would
    if ((root)); then
      log "firing AlarmReceiver kind=test"
      ash input keyevent KEYCODE_HOME
      sleep 1
      ash am broadcast -n $PKG/.AlarmReceiver --es kind test >"$OUT/am-broadcast.txt" 2>&1
      sleep "$ALARM_WAIT"
      if wait_front AlarmActivity 15; then
        alarm_shown=1; rang_via=${rang_via:-broadcast}
      fi
    elif [[ $alarm_shown == 0 ]]; then
      notes+=("no root: alarm only testable via Test ring button")
    fi
    shot 03-alarm
    front >"$OUT/front-alarm.txt"
    ash dumpsys activity services $PKG >"$OUT/services.txt" 2>&1
    local service=0
    grep -q "$PKG/.AlarmService" "$OUT/services.txt" && service=1
    ash dumpsys notification --noredact 2>/dev/null | grep -A3 "pkg=$PKG" | sed 's/^ *//' >"$OUT/notifications.txt"
    ((alarm_shown)) || reasons+=("alarm screen not shown")
    ((service)) || reasons+=("AlarmService not running")
    log "alarm screen: $alarm_shown (via ${rang_via:-none}), service running: $service"
    check_crash_now "alarm fired"

    # ---- 4. rotation while ringing
    # AlarmActivity is screenOrientation=fullSensor, which ignores the user
    # rotation lock and follows the accelerometer, so tilt the virtual sensor too.
    log "rotating"
    ash settings put system accelerometer_rotation 0
    ash settings put system user_rotation 1
    a emu sensor set acceleration 9.81:0:0 >/dev/null 2>&1
    sleep 4
    echo "landscape: $(display_rotation)" >"$OUT/rotation.txt"
    shot 04-alarm-landscape
    ash settings put system user_rotation 0
    a emu sensor set acceleration 0:9.81:0 >/dev/null 2>&1
    sleep 4
    echo "portrait: $(display_rotation)" >>"$OUT/rotation.txt"
    shot 05-alarm-portrait
    log "display rotation: $(tr '\n' ' ' <"$OUT/rotation.txt")"
    grep -q 'landscape: [13]' "$OUT/rotation.txt" || notes+=("display did not rotate to landscape")
    front >"$OUT/front-after-rotation.txt"
    if ((alarm_shown)) && ! front_is AlarmActivity; then
      notes+=("alarm screen gone after rotation")
    fi
    check_crash_now "rotation"
  fi

  # ---- logs
  a logcat -d -v threadtime -b main -b system -b crash >"$OUT/logcat-full.txt" 2>/dev/null
  grep -E "FATAL EXCEPTION|AndroidRuntime|com\.pushupalarm" "$OUT/logcat-full.txt" >"$OUT/logcat.txt"
  crashes_in "$OUT/logcat-full.txt" >"$OUT/crash.txt"
  rm -f "$OUT/.logcat-now.txt" "$OUT/_last.xml"
  if [[ -s $OUT/crash.txt ]]; then
    local n
    n=$(grep -c '^---- ' "$OUT/crash.txt")
    reasons+=("$n crash/ANR of $PKG (first by step: ${CRASH_SEEN_AT:-?})")
    TOP[$API]=$(crash_signatures "$OUT/crash.txt")
  fi
  grep -q "Unable to launch app $PKG/.*process is bad" "$OUT/logcat-full.txt" &&
    notes+=("Android refused to restart the app after repeated crashes (later steps blocked)")

  log "killing emulator"
  kill_emulator
  TIMES[$API]=$((SECONDS - start))

  if ((${#reasons[@]})); then
    RESULT[$API]=FAIL
  else
    RESULT[$API]=PASS
  fi
  REASONS[$API]=$(join "${reasons[@]}")
  NOTES[$API]=$(join "${notes[@]}")
  log "${RESULT[$API]} in ${TIMES[$API]} s ${REASONS[$API]:+- ${REASONS[$API]}}"
}

for api in "${APIS[@]}"; do
  run_api "$api"
done

# ---------------------------------------------------------------- summary ---
{
  echo
  echo "APK: $APK"
  printf '%-6s %-5s %6s  %s\n' API RESULT TIME DETAILS
  for api in "${APIS[@]}"; do
    printf '%-6s %-5s %5ss  %s\n' "$api" "${RESULT[$api]:-?}" "${TIMES[$api]:-?}" "${REASONS[$api]:-}"
    [[ -n ${TOP[$api]:-} ]] && while IFS= read -r l; do printf '%20s crash: %s\n' '' "$l"; done <<<"${TOP[$api]}"
    [[ -n ${NOTES[$api]:-} ]] && printf '%20s notes: %s\n' '' "${NOTES[$api]}"
  done
  echo "Artifacts: $OUT_ROOT/api<N>/"
} | tee "$OUT_ROOT/summary.txt"

for api in "${APIS[@]}"; do [[ ${RESULT[$api]:-} == PASS ]] || exit 1; done
exit 0
