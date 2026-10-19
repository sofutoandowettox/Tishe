package app.tishe.audio

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TisheApplication : Application() {
    val editor by lazy { EditorController(this) }
    override fun onCreate() {
        super.onCreate()
        File(cacheDir, "tracks").listFiles()?.filter { it.isFile }?.forEach { it.delete() }
    }
}

data class ExtraEffects(
    val bass: Float = 0f,
    val echo: Float = 0f,
    val lowpass: Float = 0f,
    val lofi: Float = 0f,
    val flanger: Float = 0f,
) {
    fun validate() {
        require(listOf(bass, echo, lowpass, lofi, flanger).all { it in 0f..1f })
    }
}

data class EditorState(
    val name: String? = null,
    val duration: Double = 0.0,
    val position: Double = 0.0,
    val speed: Float = .8f,
    val wet: Float = .3f,
    val room: Float = .7f,
    val extra: ExtraEffects = ExtraEffects(),
    val repeat: Boolean = false,
    val original: Boolean = false,
    val playing: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
)

class EditorController(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(EditorState())
    val state = mutable.asStateFlow()
    private val executor = Executors.newSingleThreadExecutor()
    private val audioDispatcher = executor.asCoroutineDispatcher()
    private var engine: AudioEngine? = null
    private var source: File? = null
    private var playbackGeneration = 0
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener { if (it != AudioManager.AUDIOFOCUS_GAIN) pause() }
        .build()
    private val unplugged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { pause() }
    }

    init {
        ContextCompat.registerReceiver(app, unplugged, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            while (true) {
                delay(100)
                if (!mutable.value.busy && mutable.value.playing) {
                    try {
                        val generation = playbackGeneration
                        val (position, playing) = withContext(audioDispatcher) {
                            (engine?.currentPosition() ?: 0.0) to (engine?.playing() == true)
                        }
                        if (generation != playbackGeneration) continue
                        val wasPlaying = mutable.value.playing
                        mutable.value = mutable.value.copy(position = position.coerceIn(0.0, mutable.value.duration), playing = playing)
                        if (wasPlaying && !playing) audioManager.abandonAudioFocusRequest(focus)
                    } catch (e: Exception) { handleError(e) }
                }
            }
        }
    }

    fun importTrack(uri: Uri) {
        if (mutable.value.busy) return
        pause()
        mutable.value = mutable.value.copy(busy = true, message = "Opening track…")
        scope.launch {
            var candidate: File? = null
            try {
                val name = withContext(Dispatchers.IO) {
                    val directory = File(app.cacheDir, "tracks").apply { mkdirs() }
                    candidate = File.createTempFile("track-", ".audio", directory)
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        candidate!!.outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= 512L * 1024 * 1024) { "Tracks must be smaller than 512 MB" }
                                output.write(buffer, 0, count)
                            }
                            require(total > 0) { "This file is empty" }
                        }
                    } ?: error("Could not open the file")
                    OpusDecoder.decodeIfOpus(candidate!!)?.let { decoded ->
                        candidate!!.delete()
                        candidate = decoded
                    }
                    app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: "Audio track"
                }
                val duration = withContext(audioDispatcher) {
                    val player = engine ?: AudioEngine().also { engine = it }
                    player.open(candidate!!.absolutePath).also {
                        val s = mutable.value
                        player.effects(s.speed, s.wet, s.room, s.extra)
                        player.repeat(s.repeat)
                    }
                }
                source?.delete()
                source = candidate
                candidate = null
                mutable.value = mutable.value.copy(name = name, duration = duration, position = 0.0,
                    playing = false, original = false, message = null)
            } catch (e: Exception) {
                handleError(e, "Could not open track. Supported formats: MP3, WAV, FLAC, OGG and Opus.")
            } finally {
                candidate?.delete()
                mutable.value = mutable.value.copy(busy = false)
            }
        }
    }

    fun togglePlayback() {
        if (source == null || mutable.value.busy) return
        if (mutable.value.playing) { pause(); return }
        try {
            ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java).setAction(PlaybackService.PLAY))
        } catch (e: Exception) { handleError(e, "Could not start playback") }
    }

    suspend fun startPlayback(): Boolean {
        val input = source ?: return false
        if (mutable.value.busy) return false
        val generation = ++playbackGeneration
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            mutable.value = mutable.value.copy(message = "Audio is being used by another app")
            return false
        }
        return try {
            val snapshot = mutable.value
            withContext(audioDispatcher) {
                val player = engine ?: AudioEngine().also {
                    try {
                        it.open(input.absolutePath)
                        it.seekTo(snapshot.position.coerceIn(0.0, snapshot.duration))
                    } catch (e: Exception) { it.close(); throw e }
                    engine = it
                }
                player.effects(if (snapshot.original) 1f else snapshot.speed,
                    if (snapshot.original) 0f else snapshot.wet, snapshot.room,
                    if (snapshot.original) ExtraEffects() else snapshot.extra)
                player.repeat(snapshot.repeat)
                player.play(true)
            }
            if (generation != playbackGeneration) return false
            mutable.value = mutable.value.copy(playing = true, message = null)
            true
        } catch (e: Exception) {
            audioManager.abandonAudioFocusRequest(focus)
            handleError(e, "Could not start playback")
            false
        }
    }

    fun pause() {
        playbackGeneration++
        mutable.value = mutable.value.copy(playing = false)
        audioManager.abandonAudioFocusRequest(focus)
        audioAction { it.play(false) }
    }

    fun seek(seconds: Double) {
        if (mutable.value.busy || !seconds.isFinite()) return
        val target = seconds.coerceIn(0.0, mutable.value.duration)
        mutable.value = mutable.value.copy(position = target)
        audioAction { it.seekTo(target) }
    }

    fun effects(speed: Float = mutable.value.speed, wet: Float = mutable.value.wet,
                room: Float = mutable.value.room, original: Boolean = false,
                extra: ExtraEffects = mutable.value.extra) {
        if (mutable.value.busy) return
        require(speed in .5f..1.25f && wet in 0f..1f && room in 0f..0.95f)
        extra.validate()
        mutable.value = mutable.value.copy(speed = speed, wet = wet, room = room, original = original, extra = extra)
        audioAction { it.effects(if (original) 1f else speed, if (original) 0f else wet, room,
            if (original) ExtraEffects() else extra) }
    }

    fun repeat(enabled: Boolean) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(repeat = enabled)
        audioAction { it.repeat(enabled) }
    }

    fun export(uri: Uri) {
        val input = source ?: return
        if (mutable.value.busy) return
        pause()
        val snapshot = mutable.value
        mutable.value = snapshot.copy(busy = true, message = "Exporting WAV…")
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = File.createTempFile("export-", ".wav", input.parentFile)
                    try {
                        AudioEngine.renderFile(input.absolutePath, output.absolutePath, snapshot.speed, snapshot.wet, snapshot.room, snapshot.extra)
                        app.contentResolver.openOutputStream(uri, "wt")?.use { destination ->
                            output.inputStream().use { it.copyTo(destination) }
                        } ?: error("Could not write the file")
                    } catch (e: Exception) {
                        runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }
                        throw e
                    } finally { output.delete() }
                }
                mutable.value = mutable.value.copy(message = "WAV saved")
            } catch (e: Exception) { handleError(e, "Could not save WAV. Check storage space and folder access.") }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }

    private fun audioAction(action: (AudioEngine) -> Unit) {
        val generation = playbackGeneration
        scope.launch {
            try {
                val playing = withContext(audioDispatcher) { engine?.let { action(it); it.playing() } ?: false }
                if (generation == playbackGeneration) mutable.value = mutable.value.copy(playing = playing)
            } catch (e: Exception) { handleError(e) }
        }
    }

    private fun handleError(error: Exception, fallback: String? = null) {
        if (error is CancellationException) throw error
        mutable.value = mutable.value.copy(message = fallback ?: error.message ?: "Audio processing failed", playing = false)
    }

    fun releasePlayback() {
        pause()
        scope.launch {
            withContext(audioDispatcher) {
                engine?.close()
                engine = null
            }
        }
    }
}
