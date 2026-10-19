package app.tishe.audio

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OpusDecoderTest {
    @Test fun opusDecodesToSeekableAudioAndExportsEffects() = runBlocking(Dispatchers.IO) {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val input = File(instrument.targetContext.cacheDir, "fixture.opus")
        val output = File.createTempFile("opus-export-", ".wav", input.parentFile)
        var decoded: File? = null
        try {
            instrument.context.assets.open("tone.opus").use { stream -> input.outputStream().use { stream.copyTo(it) } }
            decoded = OpusDecoder.decodeIfOpus(input)
            assertNotNull(decoded)
            AudioEngine(offline = true).use { engine ->
                assertEquals(1.0, engine.open(decoded!!.path), .05)
                engine.effects(.75f, .4f, .7f)
                engine.seekTo(.5)
                assertEquals(.5, engine.currentPosition(), .03)
            }
            AudioEngine.renderFile(decoded!!.path, output.path, .75f, .4f, .7f)
            AudioEngine(offline = true).use { engine -> assertEquals(1.0 / .75 + 3, engine.open(output.path), .07) }
        } finally { input.delete(); output.delete(); decoded?.delete() }
    }

    @Test fun wavPassesThroughWithoutTranscoding() = runBlocking(Dispatchers.IO) {
        val input = createTone(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            val originalBytes = input.readBytes()
            assertNull(OpusDecoder.decodeIfOpus(input))
            assertArrayEquals(originalBytes, input.readBytes())
        } finally { input.delete() }
    }
}
