package app.tishe.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class AudioEngineTest {
    private val directory get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    private fun tone() = createTone(directory)

    private fun samples(file: File): ShortArray {
        val bytes = file.readBytes()
        assertEquals("RIFF", bytes.copyOfRange(0, 4).decodeToString())
        assertEquals(bytes.size - 8, ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        val pcm = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(pcm.remaining()).also { pcm.get(it) }
    }

    @Test fun slowedExportChangesDurationAndPitch() {
        val input = tone()
        val output = File.createTempFile("slow-", ".wav", directory)
        try {
            AudioEngine.renderFile(input.path, output.path, .5f, 0f, .7f)
            val pcm = samples(output)
            assertEquals(5 * 44100 * 2, pcm.size)
            val left = (4410 until 44100).map { pcm[it * 2].toInt() }
            val crossings = left.zipWithNext().count { (a, b) -> a <= 0 && b > 0 }
            assertEquals(220.0, crossings / .9, 3.0)
            assertTrue(pcm.drop(2 * 44100 * 2 + 2048).all { it == 0.toShort() })
        } finally { input.delete(); output.delete() }
    }

    @Test fun freeverbProducesAnAudibleTail() {
        val input = tone()
        val dry = File.createTempFile("dry-", ".wav", directory)
        val wet = File.createTempFile("wet-", ".wav", directory)
        try {
            AudioEngine.renderFile(input.path, dry.path, 1f, 0f, .8f)
            AudioEngine.renderFile(input.path, wet.path, 1f, .5f, .8f)
            val drySamples = samples(dry)
            val wetSamples = samples(wet)
            assertEquals(drySamples.size, wetSamples.size)
            val start = (1.1 * 44100 * 2).toInt()
            val end = (1.5 * 44100 * 2).toInt()
            assertTrue(drySamples.slice(start until end).all { it == 0.toShort() })
            assertTrue(wetSamples.slice(start until end).sumOf { abs(it.toInt()).toLong() } > 10000)
            assertFalse(drySamples.contentEquals(wetSamples))
        } finally { input.delete(); dry.delete(); wet.delete() }
    }

    @Test fun liveControlsSeekAndRejectedImportPreserveTrack() {
        val input = tone()
        val invalid = File.createTempFile("invalid-", ".audio", directory).apply { writeText("not audio") }
        try {
            AudioEngine(offline = true).use { engine ->
                assertEquals(1.0, engine.open(input.path), .001)
                engine.effects(.7f, .5f, .8f)
                engine.seekTo(.4)
                assertEquals(.4, engine.currentPosition(), .03)
                engine.play(true)
                assertTrue(engine.playing())
                engine.effects(.9f, .15f, .6f)
                engine.play(false)
                assertFalse(engine.playing())
                assertThrows(IllegalStateException::class.java) { engine.open(invalid.path) }
                engine.seekTo(.2)
                assertEquals(.2, engine.currentPosition(), .03)
                assertThrows(IllegalStateException::class.java) { engine.effects(Float.NaN, 0f, .5f) }
            }
        } finally { input.delete(); invalid.delete() }
    }

    @Test fun exportRejectsInvalidParametersWithoutWritingDestination() {
        val input = tone()
        val output = File(directory, "should-not-exist.wav").apply { delete() }
        try {
            assertThrows(IllegalStateException::class.java) { AudioEngine.renderFile(input.path, output.path, 0f, .3f, .7f) }
            assertFalse(output.exists())
        } finally { input.delete(); output.delete() }
    }

    @Test fun deviceBackendAdvancesAndStopsAfterReverbTail() {
        val input = tone()
        try {
            AudioEngine().use { engine ->
                engine.open(input.path)
                engine.effects(1.25f, .3f, .7f)
                engine.play(true)
                val start = SystemClock.uptimeMillis()
                while (engine.playing() && SystemClock.uptimeMillis() - start < 6000) Thread.sleep(50)
                assertFalse("Playback did not finish after the tail", engine.playing())
                assertEquals(1.0, engine.currentPosition(), .01)
                assertTrue(SystemClock.uptimeMillis() - start >= 3000)
            }
        } finally { input.delete() }
    }

    @Test fun compressedFormatsDecodeSeekAndExport() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        for (extension in listOf("mp3", "flac", "ogg")) {
            val input = File.createTempFile("codec-", ".$extension", directory)
            val output = File.createTempFile("codec-export-", ".wav", directory)
            try {
                assets.open("tone.$extension").use { stream -> input.outputStream().use { stream.copyTo(it) } }
                AudioEngine(offline = true).use { engine ->
                    val duration = engine.open(input.path)
                    assertTrue("Invalid $extension duration: $duration", duration in .95..1.1)
                    engine.effects(.7f, .3f, .7f)
                    engine.seekTo(.4)
                    assertEquals(.4, engine.currentPosition(), .03)
                    engine.seekTo(.1)
                    assertEquals(.1, engine.currentPosition(), .03)
                }
                AudioEngine.renderFile(input.path, output.path, .8f, .3f, .7f)
                assertTrue(samples(output).any { abs(it.toInt()) > 100 })
            } finally { input.delete(); output.delete() }
        }
    }

    @Test fun allExtraEffectsChangeAudioAndValidateTheirRanges() {
        val input = createTone(directory, frequency = 80.0)
        val output = File.createTempFile("effect-", ".wav", directory)
        try {
            AudioEngine.renderFile(input.path, output.path, 1f, 0f, .7f)
            val dry = samples(output)
            val settings = listOf(ExtraEffects(bass = .8f), ExtraEffects(echo = .8f),
                ExtraEffects(lowpass = .8f), ExtraEffects(lofi = .8f), ExtraEffects(flanger = .8f))
            for (extra in settings) {
                AudioEngine.renderFile(input.path, output.path, 1f, 0f, .7f, extra)
                val wet = samples(output)
                assertEquals(dry.size, wet.size)
                val difference = dry.indices.sumOf { abs(dry[it].toInt() - wet[it].toInt()).toLong() }
                assertTrue("Effect has no measurable output: $extra", difference > 10000)
                assertTrue("Effect output is silent: $extra", wet.any { abs(it.toInt()) > 100 })
            }
            AudioEngine(offline = true).use { engine ->
                engine.open(input.path)
                for (invalid in listOf(ExtraEffects(bass = Float.NaN), ExtraEffects(echo = -1f),
                    ExtraEffects(lowpass = Float.POSITIVE_INFINITY), ExtraEffects(lofi = 1.1f), ExtraEffects(flanger = -1f))) {
                    assertThrows(IllegalStateException::class.java) { engine.effects(1f, 0f, .7f, invalid) }
                    output.delete()
                    assertThrows(IllegalStateException::class.java) {
                        AudioEngine.renderFile(input.path, output.path, 1f, 0f, .7f, invalid)
                    }
                    assertFalse(output.exists())
                }
            }
        } finally { input.delete(); output.delete() }
    }

    @Test fun bassBoostRaisesLowFrequenciesAndLowpassAttenuatesHighFrequencies() {
        for ((frequency, extra) in listOf(80.0 to ExtraEffects(bass = 1f), 8000.0 to ExtraEffects(lowpass = 1f))) {
            val input = createTone(directory, frequency = frequency)
            val output = File.createTempFile("frequency-", ".wav", directory)
            try {
                fun energy(): Double = samples(output).slice(10000 until 80000).sumOf { it.toDouble() * it }
                AudioEngine.renderFile(input.path, output.path, 1f, 0f, .7f)
                val dry = energy()
                AudioEngine.renderFile(input.path, output.path, 1f, 0f, .7f, extra)
                if (frequency < 100) assertTrue(energy() > dry * 1.2)
                else assertTrue(energy() < dry * .1)
            } finally { input.delete(); output.delete() }
        }
    }

    @Test fun repeatWrapsAllFormatsAndExportRemainsOnePass() = runBlocking(Dispatchers.IO) {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        AudioEngine().use { engine ->
            for (extension in listOf("wav", "mp3", "flac", "ogg", "opus")) {
                val input = if (extension == "wav") tone() else File.createTempFile("loop-", ".$extension", directory).apply {
                    assets.open("tone.$extension").use { stream -> outputStream().use { stream.copyTo(it) } }
                }
                val decoded = if (extension == "opus") OpusDecoder.decodeIfOpus(input) else null
                val output = File.createTempFile("loop-export-", ".wav", directory)
                try {
                    engine.play(false)
                    engine.open((decoded ?: input).path)
                    engine.effects(1.25f, .2f, .7f, ExtraEffects(echo = .3f))
                    engine.repeat(true)
                    engine.play(true)
                    var previous = engine.currentPosition()
                    var wrapped = false
                    val start = SystemClock.uptimeMillis()
                    while (!wrapped && SystemClock.uptimeMillis() - start < 3500) {
                        Thread.sleep(20)
                        val current = engine.currentPosition()
                        wrapped = previous > .5 && current < previous - .3
                        previous = current
                    }
                    assertTrue("No repeat wrap for $extension", wrapped)
                    assertTrue(engine.playing())
                    AudioEngine.renderFile((decoded ?: input).path, output.path, 1f, 0f, .7f)
                    assertTrue("Export must contain a single pass", samples(output).size in (3.9 * 88200).toInt()..(4.2 * 88200).toInt())
                } finally { engine.play(false); decoded?.delete(); input.delete(); output.delete() }
            }
            val input = tone()
            try {
                engine.open(input.path)
                engine.repeat(false)
                engine.seekTo(.9)
                engine.play(true)
                val end = SystemClock.uptimeMillis() + 5000
                while (engine.playing() && SystemClock.uptimeMillis() < end) Thread.sleep(30)
                assertFalse("Disabling repeat must restore normal completion", engine.playing())
            } finally { input.delete() }
        }
    }
}

internal fun createTone(directory: File, seconds: Int = 1, frequency: Double = 440.0): File {
    val count = 44100 * seconds
    val buffer = ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray()).putInt(buffer.capacity() - 8).put("WAVEfmt ".toByteArray())
        .putInt(16).putShort(1).putShort(1).putInt(44100).putInt(88200).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(count * 2)
    repeat(count) { buffer.putShort((sin(2 * PI * frequency * it / 44100) * 8000).toInt().toShort()) }
    return File.createTempFile("tone-", ".wav", directory).apply { writeBytes(buffer.array()) }
}

