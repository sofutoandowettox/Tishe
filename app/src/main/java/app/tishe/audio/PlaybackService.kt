package app.tishe.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

class PlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val editor get() = (application as TisheApplication).editor
    private lateinit var session: MediaSession
    private lateinit var wakeLock: PowerManager.WakeLock
    private var playback: Job? = null
    private var wakeRenewal: Job? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW))
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tishe:playback")
        wakeLock.setReferenceCounted(false)
        session = MediaSession(this, "Tishe").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { start() }
                override fun onPause() { editor.pause() }
                override fun onStop() { editor.pause(); stopSelf() }
                override fun onSeekTo(pos: Long) { editor.seek(pos / 1000.0) }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PLAY -> start()
            PAUSE -> editor.pause()
            else -> { editor.pause(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    // Media-session notifications are exempt from the notification runtime permission.
    @SuppressLint("NotificationPermission")
    private fun start() {
        startForeground(ID, notification(editor.state.value))
        playback?.cancel()
        playback = scope.launch {
            if (!editor.startPlayback()) { stopSelf(); return@launch }
            wakeRenewal?.cancel()
            wakeRenewal = scope.launch {
                while (true) {
                    wakeLock.acquire(3 * 60 * 60 * 1000L)
                    delay(60 * 60 * 1000L)
                }
            }
            editor.state.distinctUntilChangedBy { listOf(it.name, it.playing, it.position.toLong(), it.speed, it.original) }
                .collect { state ->
                    session.setMetadata(MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, state.name ?: "Tishe")
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, (state.duration * 1000).toLong()).build())
                    session.setPlaybackState(PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO)
                        .setState(if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                            (state.position * 1000).toLong(), if (state.original) 1f else state.speed).build())
                    if (state.playing) getSystemService(NotificationManager::class.java).notify(ID, notification(state))
                    else { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); playback?.cancel() }
                }
        }
    }

    private fun notification(state: EditorState): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        fun action(command: String) = PendingIntent.getService(this, command.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(command), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_audio).setContentTitle(state.name ?: "Tishe")
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1))
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Pause", action(PAUSE)).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop", action(STOP)).build())
            .build()
    }

    override fun onDestroy() {
        playback?.cancel()
        scope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        session.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(ID)
        editor.releasePlayback()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PLAY = "app.tishe.audio.PLAY"
        const val PAUSE = "app.tishe.audio.PAUSE"
        const val STOP = "app.tishe.audio.STOP"
        private const val CHANNEL = "playback"
        private const val ID = 1
    }
}
