# Tishe — Android slowed + reverb

## Scope and dependencies

An offline, single-track editor for Android 8+. Import MP3/WAV/FLAC/OGG/Opus,
adjust speed (0.5–1.25×) and effects while listening, seek, repeat, compare the original,
export a stereo 44.1 kHz / 16-bit WAV. Product name: Tishe.

UI: Kotlin, Jetpack Compose and official Material 3 Expressive components
(`MaterialExpressiveTheme`, expressive motion, large rounded controls).
Audio: SoLoud, pinned to e82fd32c1f62183922f08c14c814a02b58db1873,
with its streaming decoders, resampler, Freeverb, Bassboost, Biquad low-pass,
Lofi, Flanger and Echo filters. No custom DSP algorithms.
The bundled MiniAudio backend provides device output; the NULL backend renders exports.

## Signal path

Private imported file → Opus-only Android PCM decoding when needed →
SoLoud WavStream → speed/pitch resampling → Bass boost → Low-pass → Lo-fi →
Flanger → Echo → Freeverb → fixed output headroom → device or WAV writer.

The speed control changes pitch together with tempo (classic slowed sound).
Reverb wet mix and room size update on the live engine without reloading the
track. Export uses an independent instance of the same engine and a snapshot
of the settings, including three seconds of reverb tail. Controls remain
interactive during listening; import/export lock editing during file operations.

Extra effects start at zero (bypassed); all five amounts are finite values in
[0,1], checked in Kotlin and again at the native boundary. Bass boost mixes
SoLoud's 4× low-band FFT boost; low-pass sweeps 20 kHz to 500 Hz; Lo-fi mixes
8 kHz / 6-bit degradation; Flanger uses 3 ms / 0.35 Hz; Echo uses 300 ms delay
and 0.4 feedback decay. Live parameters fade over 30 ms. Reset and the existing
presets disable the extra effects; original comparison bypasses every effect.

Repeat uses the streaming voice's native looping flag with a zero loop point.
The player reports the current position within the track and skips end/tail
completion while looping. Disabling repeat restores normal end/tail behavior.
The repeat flag never reaches the export engine, which always writes one pass.

## Structure and API contract

- `MainActivity.kt`: English Compose screen and document pickers.
- `EditorController.kt`: application-owned editor state, bounded import, playback/audio focus,
  asynchronous export, cleanup.
- `PlaybackService.kt`: mediaPlayback foreground service, platform MediaSession,
  notification transport actions, bounded wake lock and native playback lifetime.
- `OpusDecoder.kt`: Android MediaExtractor/MediaCodec Opus-to-PCM import, with
  bounded duration, a stall timeout and temporary-file cleanup on failure.
- `AudioEngine.kt`: typed JNI boundary and parameter validation.
- `cpp/audio_engine.cpp`: playback bridge.
- `cpp/render.h`: shared engine setup and streaming WAV export.
- `androidTest`: synthetic impulse/sine native integration checks.

JNI calls: create/destroy; load(path) → duration; play/pause; seek(seconds);
setEffects(speed, wet, room, bass, echo, lowpass, lofi, flanger); setRepeat(enabled);
position/isPlaying; renderNative(input, output, speed, wet, room, extra amounts)
→ success/error, wrapped by Kotlin renderFile. Playback operations use one dedicated
dispatcher; rendering uses Dispatchers.IO and a separate engine. Native exceptions are
translated to Java exceptions. Audio callback work stays inside SoLoud.

No network API or database is needed. State schema: source file + display name,
duration/position in seconds, speed, wet, room, ExtraEffects (five amounts), repeat flag, original-comparison flag,
playing flag, busy flag, error/status. The application owns this state, so
rotation, Activity destruction and reopening retain the active editing session.
Process death starts a new session. Native playback is released when the media
service is destroyed and recreated from the cached file/position on the next play.

## Security and data handling

No Internet, microphone or broad storage permission. The manifest declares
foreground-service/media-playback and wake-lock permissions. Media-session
notifications qualify for Android's notification-permission exemption.
System document pickers
grant access only to the selected source/destination. Import makes a private
copy with a generated name; source files are never modified. Limit import to
512 MiB and duration to one hour. Native decoder validates file contents.
The fixed two-channel pipeline rejects multichannel input. Export is rendered
to private storage first, then copied only after rendering succeeds. Failed
destination writes are reported and best-effort deleted. App backup is off.
Temporary files are removed after operations/session cleanup; crash remnants
are cleared on next fresh session. No track names or audio are logged.

## Roadmap and acceptance

1. Document architecture, repository structure and scope (this document).
2. Build import/player/effects and native synthetic checks.
3. Build Material Expressive editor and WAV export; compile, test and lint.
4. Validate UI and native integration on an emulator when available.
5. Physical listening acceptance: speaker/wired/Bluetooth, slider response,
   seek, focus loss/headphone disconnect, long tracks, preview/export match.

Not included: accounts, server, streaming services, project library, waveform
analysis, AAC/M4A import, MP3 encoding, independent pitch preservation,
automated parameter envelopes. Revisit only on demand.

## References

- https://developer.android.com/jetpack/androidx/releases/compose-material3
- https://android-developers.googleblog.com/2025/05/androidify-building-delightful-ui-with-compose.html
- https://github.com/jarikomppa/soloud
- https://solhsa.com/soloud/wavstream.html
- https://solhsa.com/soloud/freeverbfilter.html
- https://developer.android.com/media/platform/supported-formats
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/develop/ui/compose/notifications/notification-permission
- https://github.com/LawnchairLauncher/lawnicons

SoLoud uses the zlib license; its Freeverb implementation is public domain.
Bundled decoder notices remain in upstream source and are included in assets.
Expressive APIs use a pinned alpha release and may change during upgrades.

## Implementation notes

The vendored streaming source has a small local seek fix: the original union
pointer check treated non-OGG formats as OGG. It now dispatches to the matching
decoder and uses source sample frames, independent of playback speed. The
MiniAudio adapter also owns each device per engine to make overlapping session
teardown safe. Both changes are marked in source and listed in the vendor README.

Preview retains the voice through the same three-second tail used by export,
then mutes and releases audio focus. Pausing mutes the global reverb as well.
The three-second cap may trim long room decays; adaptive tail detection is deferred.

The UI contains English control labels and no promotional/helper captions.
The launcher icon is original vector artwork with an outlined disc/equalizer,
an adaptive background and an Android 13+ monochrome layer, inspired by Lawnicons.

Audio-focus requests happen after the foreground media service starts, allowing
notification/media-session transport commands to meet modern Android background
audio-focus rules. Pausing or reaching the end stops the foreground service and
releases its wake lock, media session and native playback device. Late native
position/action replies are discarded after play/pause generations change, so
they cannot restore a stale playing state after a pause.

During active playback a service coroutine renews the three-hour wake-lock
timeout every hour, so repeat is not capped by the original one-pass wake-lock
timeout. Service destruction cancels renewal and releases the lock.
