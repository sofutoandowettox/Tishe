# SoLoud source subset

Headers, core, WAV/MP3/FLAC/Vorbis streaming decoders, Freeverb, Bassboost,
BiquadResonant, Echo, Lofi, Flanger and the shared FFT filter,
MiniAudio and NULL backends from https://github.com/jarikomppa/soloud
at commit `e82fd32c1f62183922f08c14c814a02b58db1873`.
Only those engine features are compiled. See LICENSE and embedded source notices.
The FFT transformation source retains Stephan M. Bernsee's Wide Open License
notice; it is also included in the application's third-party notices asset.

Local modification: `WavStreamInstance::seek` selects the active codec by file
type instead of inspecting the union pointer, uses decoder seek functions in
source frames, and clears buffered OGG frames. The upstream implementation
incorrectly interprets every non-null codec pointer as OGG. Covered by native
Android integration tests; preserve this fix when updating the source subset.

Local modification: MiniAudio devices are owned per engine rather than held in
one global variable; device start failures are returned. This preserves safe
teardown when a new Activity session starts while the previous one closes.
