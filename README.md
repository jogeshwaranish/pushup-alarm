# Push-up Alarm

An Android alarm that only stops after you do 15 real push-ups in front of the camera. Works on any Android phone running Android 8.0 or newer. Install it straight from the [releases](../../releases/latest); it isn't on the Play Store.

## Why

Normal alarms are too easy to beat. Snooze is one tap, dismiss is one swipe, and you can do both without ever really waking up. I built this so the only way to turn the alarm off is to get out of bed, get on the floor and do 15 real push-ups. By the time the alarm stops, you're awake.

So every easy way out is closed off: no snooze button, volume that can't be muted, a screen that comes back if you leave it, and an alarm that survives being killed or the phone restarting. The camera checks for real push-ups, so half reps don't count.

## How it works

- **Counting:** the front camera (or the back one, on phones without a front camera) and ML Kit pose detection watch you from the side or the front (your choice under **Camera angle**, switchable on the push-up screen). A rep counts only when:
  - your body is in a plank with your hands below your shoulders
  - **side view:** your elbows bend to under 100°, then lock back out past 150°
  - **front view:** your shoulders drop below 70% of their locked-out height above your hands, then rise back past 90% (the elbow angle can't be seen from the front, so the lockout height is measured at the start of each set)

  Half reps, standing curls and one-frame glitches don't count. Going out of view for 1.5 s cancels a half-done rep.
- **While it rings:**
  - The volume starts at your chosen level. The volume keys (or −/+ on screen) move it in 10% steps, but never below 20% or to mute.
  - The back button is blocked.
  - Leaving the screen brings it back within half a second.
  - If the app is killed, it comes back. If the phone restarts, the alarm resumes after you unlock it.
- **Safety valves:**
  - An **Emergency call** button opens the dialer and pauses the takeover for 30 s.
  - The alarm goes quiet during phone calls.

## Features

- **Multiple alarms:** each repeats on chosen days, or rings once and then turns itself off.
- **Locked alarms:** turning off or deleting an alarm that's on takes 15 push-ups. Editing its time or days is free.
- **Volume:** a slider from 20% to 100%, with a short preview. It can't go silent.
- **Other alarm apps:** if another app (e.g. Samsung Clock) owns the phone's next alarm, a warning shows with a button to open that app. Android only exposes the *next* alarm, so check that app for others.
- **Practice mode:** try the counter without an alarm.
- **Test ring:** a real alarm 10 seconds from now.
- **Deleting the app:** 30 push-ups, then a 1-hour wait, then "Delete app now" unlocks for 24 hours.

## Setup on the phone

Open the app and grant everything in the **Setup** list:

| Permission | Why |
|---|---|
| Camera | Counts reps. Nothing is recorded. |
| Notifications | Lets the alarm ring from the background. |
| Full-screen alerts | Takes over the lock screen. |
| Display over other apps | Pulls the alarm back if you leave. |
| Alarms & reminders *(Android 12 / 12L only)* | Lets the alarm ring at the exact minute. |
| Unrestricted battery *(recommended)* | Stops the phone from putting the app to sleep. |
| Auto-start *(recommended, some brands only)* | On Xiaomi, Huawei, Oppo, Vivo, OnePlus, Asus and similar phones, lets the alarm start in the background. Only shows on phones that have this setting. |
| Device admin *(recommended)* | Disables Force stop and Uninstall. |

For best counting, put the phone on the floor about 2 m to your side, so your whole side is in frame. For the front view, put it about 1.5 m in front of your head, facing you, with your shoulders and hands in frame. The push-up screen works in portrait and landscape and follows the phone's rotation.

## Install

Download one APK from the [latest release](../../releases/latest):

| File | For |
|---|---|
| `pushup-alarm-<version>-arm64-v8a.apk` | Almost every phone from the last several years. Pick this if unsure. |
| `pushup-alarm-<version>-armeabi-v7a.apk` | Older 32-bit phones |
| `pushup-alarm-<version>-universal.apk` | Any phone (bigger download) |
| `pushup-alarm-<version>-x86_64.apk` / `-x86.apk` | Emulators and Chromebooks |

Needs Android 8.0 or newer. Open the APK on the phone and allow installing from that source, or from a computer run `adb install -r <file>.apk`. Then open the app and work through the Setup list. New versions install over the old one without uninstalling, because every release is signed with the same key.

## Build from source

Requires Java 17 and the Android SDK at `~/Android/Sdk`.

```bash
# On the phone: Settings → Developer options → Wireless debugging (note the IP:port)
adb connect <phone-ip>:<port>

./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If `adb connect` hangs, restart the server with `adb kill-server && adb start-server` and try again.

### Release builds

```bash
./gradlew testDebugUnitTest lintDebug assembleRelease
ls app/build/dist/   # pushup-alarm-<version>-<abi>.apk and -universal.apk
```

Bump `pushup.versionCode` and `pushup.versionName` in `gradle.properties` first. Release APKs are signed with `~/.android/debug.keystore` unless `PUSHUP_KEYSTORE`, `PUSHUP_KEYSTORE_PASSWORD`, `PUSHUP_KEY_ALIAS` and `PUSHUP_KEY_PASSWORD` are set. Always sign with the same key as the installed app, or Android refuses the update; check with `apksigner verify --print-certs`.

CI (GitHub Actions) runs the tests, lint and a release build on every push. Its APKs use a throwaway key, so they can't update an existing install.

### Testing on emulators

`scripts/emulator-smoke.sh <apk>` boots an emulator for each supported Android version in turn. On each one it installs the APK, opens practice mode, fires a real test alarm and rotates the screen, then reports PASS/FAIL.

## Escape hatch

If something breaks (for example, the camera fails while the alarm is ringing), run this from a computer:

```bash
adb shell dpm remove-active-admin com.pushupalarm/.AdminReceiver
adb uninstall com.pushupalarm
```

Booting the phone into Safe Mode also stops the app.

## Code map

| File | What it does |
|---|---|
| `PushupCounter.kt` | Rep-counting state machine for side and front views (pure Kotlin, unit-tested) |
| `PoseReading.kt` | Converts ML Kit landmarks into the counter's input for the chosen camera angle |
| `AlarmActivity.kt` / `AlarmScreen.kt` | Camera and push-up screen for alarm, disarm, practice and delete-unlock |
| `AlarmService.kt` | Foreground service: sound, vibration, volume lock, relaunch loop |
| `Alarm.kt` / `AlarmScheduler.kt` | Alarm storage and exact `setAlarmClock` scheduling |
| `Receivers.kt` | Alarm fire, boot/time-change rescheduling, device admin |
| `DeleteGate.kt` | 30 push-ups → 1 h cooldown → uninstall |
| `OtherAlarms.kt` | Detects another app's upcoming alarm |
| `MainScreen.kt` / `Setup.kt` | Home screen, alarm list, permission checklist (adapts to Android version and phone brand) |

Tunable thresholds (elbow angles, front-view percentages, torso tilt, timings) are at the top of `PushupCounter.kt`.
