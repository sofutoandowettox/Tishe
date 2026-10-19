package app.tishe.audio

internal class AudioEngine(offline: Boolean = false) : AutoCloseable {
    private var pointer = create(offline)
    fun open(path: String) = load(pointer, path)
    fun play(playing: Boolean) = setPlaying(pointer, playing)
    fun seekTo(seconds: Double) = seek(pointer, seconds)
    fun currentPosition() = position(pointer)
    fun playing() = isPlaying(pointer)
    fun effects(speed: Float, wet: Float, room: Float, extra: ExtraEffects = ExtraEffects()) =
        setEffects(pointer, speed, wet, room, extra.bass, extra.echo, extra.lowpass, extra.lofi, extra.flanger)
    fun repeat(enabled: Boolean) = setRepeat(pointer, enabled)
    override fun close() { if (pointer != 0L) { destroy(pointer); pointer = 0 } }

    private external fun create(offline: Boolean): Long
    private external fun destroy(pointer: Long)
    private external fun load(pointer: Long, path: String): Double
    private external fun setPlaying(pointer: Long, playing: Boolean)
    private external fun seek(pointer: Long, seconds: Double)
    private external fun position(pointer: Long): Double
    private external fun isPlaying(pointer: Long): Boolean
    private external fun setEffects(pointer: Long, speed: Float, wet: Float, room: Float,
        bass: Float, echo: Float, lowpass: Float, lofi: Float, flanger: Float)
    private external fun setRepeat(pointer: Long, enabled: Boolean)
    companion object {
        init { System.loadLibrary("tishe") }
        fun renderFile(input: String, output: String, speed: Float, wet: Float, room: Float, extra: ExtraEffects = ExtraEffects()) =
            renderNative(input, output, speed, wet, room, extra.bass, extra.echo, extra.lowpass, extra.lofi, extra.flanger)
        @JvmStatic private external fun renderNative(input: String, output: String, speed: Float, wet: Float, room: Float,
            bass: Float, echo: Float, lowpass: Float, lofi: Float, flanger: Float)
    }
}
