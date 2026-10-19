# Verification — 2026-10-19

## Current version: 0.3.0

Debug APK built for arm64-v8a/x86_64; signature and 16 KiB ZIP alignment
verified. Android lint reports zero errors and 10 warnings.

All 12 instrumentation tests passed on Android 16 / API 36 x86_64.
Console evidence: `docs/verification-0.3.0.txt`. The nine previous checks
remain covered. Three new native tests verify:

- Each of the five additional effects changes synthetic audio; invalid
  effect values are rejected by playback and export before writing a file.
- Bass boost increases low-frequency energy; low-pass attenuates high frequencies.
- Repeat wraps playback position for WAV, MP3, FLAC, OGG and decoded Opus,
  keeps playing, and restores normal completion when disabled. Concurrent
  WAV export remains a single pass while preview repeats.

The Compose workflow additionally checks repeat toggling and retention after
Activity recreation, all five live sliders, original comparison, and export
with repeat enabled. Screenshots in `docs/screenshots/` were refreshed and
visually inspected. APK: `dist/Tishe-0.3.0-debug.apk`.

Build and instrumentation commands are unchanged from 0.2.0 below. Synthetic
and emulator results do not establish physical sound quality, Bluetooth/OEM
behavior or uninterrupted multi-hour playback. Wake-lock renewal is implemented
but was not tested over multiple hours. Export retains the fixed three-second
effect tail; repeat is a playback setting only.

## Historical verification: 0.2.0

Debug APK compiled for arm64-v8a/x86_64; signature and 16 KiB ZIP alignment
verified. Android lint completed with zero errors (10 warnings).

Nine instrumentation tests passed on Android 16 / API 36 x86_64; console
evidence is saved in `docs/verification-0.2.0.txt`. The six native-engine tests
listed below still pass. Two additional checks cover real synthetic Ogg Opus
decoding with Android MediaCodec, seeking/export of the decoded PCM, and WAV
pass-through without transcoding. The updated Compose workflow imports a
60-second Opus fixture and verifies English controls, live effects, WAV export,
continued playback while stopped and after Activity destruction, reopening with
the same editor state, and Pause from the media notification with notification
removal. No notification runtime permission was granted for these checks.

Build command: `gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`.
Tests were run with `adb -s emulator-5580 shell am instrument -w
app.tishe.audio.test/androidx.test.runner.AndroidJUnitRunner` after installing
both APKs on that emulator. Updated screenshots were captured and visually
inspected. Static source inspection found no Cyrillic strings in app sources.

Opus import uses the platform decoder and needs temporary PCM storage. The
launcher icon includes normal adaptive and Android 13+ monochrome layers.
Physical-device/Bluetooth listening, OEM battery policies and Android 8 runtime
compatibility remain unverified. System picker navigation is not covered by
the injected-URI tests. Force-stop/process-death recovery is not promised.

## Historical verification: 0.1.0

MVP 0.1.0, application ID `app.tishe.audio`.

## Verified locally

- Debug APK compiled for arm64-v8a and x86_64, minimum API 26, target API 36.
- Android lint: no errors; warnings concern pinned dependency/tool versions
  and targeting API 36 rather than the latest platform.
- Seven instrumentation tests passed on the Android 16 / API 36 x86_64 emulator:
  1. Slowdown doubles a one-second tone to two seconds, lowers 440 Hz to 220 Hz,
     and creates a valid stereo WAV with the configured three-second tail.
  2. Freeverb creates nonzero samples after source audio ends; dry output does not.
  3. Live effect calls, pause, seek and preservation of the previous track after
     an invalid import, including rejection of NaN effect parameters.
  4. Export rejects invalid parameters before opening the destination.
  5. MiniAudio playback advances and stops after the configured reverb tail.
  6. Synthetic stereo MP3/FLAC/OGG files decode, seek both ways and export.
  7. Compose UI: import a synthetic local tone, play, adjust speed/reverb while
     playing, compare the original, export, pause on Activity backgrounding.
- APK manifest has no Internet, microphone or broad storage permission.
- Player and effects screenshots were captured from the running emulator and
  visually inspected: `docs/screenshots/`.

Test command: `gradlew.bat :app:connectedDebugAndroidTest` with
`ANDROID_SERIAL=emulator-5580`. Unit test task has no tests; audio checks run
against the compiled native library through Android instrumentation.

## Boundaries

These are synthetic and emulator checks. They do not establish physical-device
latency, subjective sound quality, Bluetooth routing, long-track behavior,
real provider/picker integration, behavior under process termination, or
bit-for-bit equality between preview and export. UI tests inject file URIs;
they do not navigate a system document provider. Physical listening and
speaker/wired/Bluetooth validation remain open. No personal audio was used.

Expressive is a pinned alpha API. The native source has two documented local
fixes; preserve them during updates. Reverb tails are capped at three seconds.
