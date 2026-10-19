Synthetic stereo 440 Hz tones (one second), generated locally with FFmpeg:
ffmpeg -f lavfi -i sine=frequency=440:duration=1:sample_rate=44100 -ac 2 tone.EXT
Formats: MP3, FLAC, OGG Vorbis. No recorded or third-party musical content.
Only packaged in the instrumentation test APK, not in the application APK.

Opus fixtures: tone.opus (1 second) and preview.opus (60 seconds), generated
with the same 440 Hz source at 48 kHz, stereo, using FFmpeg's libopus encoder.
