package app.tishe.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object OpusDecoder {
    suspend fun decodeIfOpus(input: File): File? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var output: File? = null
        var success = false
        try {
            try { extractor.setDataSource(input.absolutePath) } catch (_: java.io.IOException) { return null }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_OPUS
            } ?: return null
            val format = extractor.getTrackFormat(track)
            require(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) in 1..2) { "Only mono and stereo tracks are supported" }
            if (format.containsKey(MediaFormat.KEY_DURATION))
                require(format.getLong(MediaFormat.KEY_DURATION) <= 3_600_000_000L) { "Tracks must be shorter than one hour" }
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            extractor.selectTrack(track)
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).also { decoder = it }
            codec.configure(format, null, null, 0)
            codec.start()
            output = File.createTempFile("opus-", ".wav", input.parentFile)
            RandomAccessFile(output, "rw").use { wav ->
                wav.write(ByteArray(44))
                var sampleRate = 0
                var channels = 0
                var encoding = AudioFormat.ENCODING_PCM_16BIT
                var bytes = 0L
                var inputEnded = false
                var outputEnded = false
                var progress = SystemClock.elapsedRealtime()
                val info = MediaCodec.BufferInfo()
                while (!outputEnded) {
                    currentCoroutineContext().ensureActive()
                    check(SystemClock.elapsedRealtime() - progress < 10000) { "Opus decoder timed out" }
                    if (!inputEnded) {
                        val index = codec.dequeueInputBuffer(10000)
                        if (index >= 0) {
                            val buffer = checkNotNull(codec.getInputBuffer(index))
                            buffer.clear()
                            val count = extractor.readSampleData(buffer, 0)
                            inputEnded = count < 0
                            codec.queueInputBuffer(index, 0, count.coerceAtLeast(0),
                                if (inputEnded) 0 else extractor.sampleTime,
                                if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            if (!inputEnded) extractor.advance()
                            progress = SystemClock.elapsedRealtime()
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info, 10000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val actual = codec.outputFormat
                        val rate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val count = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        require(rate in 8000..192000 && count in 1..2 && (bytes == 0L || rate == sampleRate && count == channels)) {
                            "Unsupported Opus output format"
                        }
                        sampleRate = rate; channels = count
                        encoding = if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING)) actual.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            else AudioFormat.ENCODING_PCM_16BIT
                        require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            "Unsupported Opus PCM format"
                        }
                        progress = SystemClock.elapsedRealtime()
                    } else if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(sampleRate > 0 && channels > 0) { "Missing Opus output format" }
                                val buffer = checkNotNull(codec.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                val pcm = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                    ByteBuffer.allocate(info.size / 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                                        while (buffer.remaining() >= 4) {
                                            val value = buffer.float
                                            require(value.isFinite()) { "Invalid Opus PCM sample" }
                                            putShort((value.coerceIn(-1f, 1f) * 32767).toInt().toShort())
                                        }
                                    }.array()
                                } else ByteArray(info.size).also { buffer.get(it) }
                                bytes += pcm.size
                                require(bytes <= sampleRate.toLong() * channels * 2 * 3600) { "Tracks must be shorter than one hour" }
                                wav.write(pcm)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            progress = SystemClock.elapsedRealtime()
                        } finally { codec.releaseOutputBuffer(index, false) }
                    }
                }
                require(bytes > 0 && bytes % (channels * 2) == 0L) { "Opus track has no valid audio" }
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                    .put("RIFF".toByteArray()).putInt(bytes.toInt() + 36).put("WAVEfmt ".toByteArray())
                    .putInt(16).putShort(1).putShort(channels.toShort()).putInt(sampleRate)
                    .putInt(sampleRate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
                    .put("data".toByteArray()).putInt(bytes.toInt()).array()
                wav.seek(0); wav.write(header)
            }
            success = true
            return output
        } finally {
            decoder?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
            if (!success) output?.delete()
        }
    }
}
