package ie.neil.taverner

import android.net.Uri
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var store: PlaybackStore
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                player.pause()
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val saveRunnable = object : Runnable {
        override fun run() {
            persistNow()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = PlaybackStore(this)
        player = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
        }

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .build()

        setMediaNotificationProvider(DefaultMediaNotificationProvider(this))

        player.addListener(PlayerEventListener())
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        restoreFromStore()
        handler.postDelayed(saveRunnable, SAVE_INTERVAL_MS)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(saveRunnable)
        persistNow()
        unregisterReceiver(noisyReceiver)
        serviceScope.cancel()
        mediaSession.release()
        player.release()
        super.onDestroy()
    }

    private fun restoreFromStore() {
        val folder = store.loadFolder() ?: return
        val state = store.loadState()
        loadPlaylist(folder, state.index, state.position, state.playWhenReady)
    }

    private fun loadPlaylist(folder: Uri, index: Int, position: Long, playWhenReady: Boolean) {
        loadJob?.cancel()
        loadJob = serviceScope.launch {
            val tracks = withContext(Dispatchers.IO) {
                AudioScanner.scan(this@PlaybackService, folder)
            }
            if (tracks.isEmpty()) {
                player.clearMediaItems()
                return@launch
            }

            val items = tracks.map { track ->
                MediaItem.Builder()
                    .setUri(track.uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(track.name)
                            .build()
                    )
                    .build()
            }

            val safeIndex = index.coerceIn(0, items.size - 1)
            val safePosition = position.coerceAtLeast(0L)
            player.setMediaItems(items, safeIndex, safePosition)
            player.prepare()
            player.playWhenReady = playWhenReady
        }
    }

    private fun persistNow() {
        if (player.mediaItemCount == 0) {
            return
        }
        store.saveState(
            index = player.currentMediaItemIndex,
            position = player.currentPosition,
            playWhenReady = player.playWhenReady
        )
    }

    private inner class PlayerEventListener : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            persistNow()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            persistNow()
        }
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: android.os.Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_SET_FOLDER) {
                val uriString = args.getString(EXTRA_TREE_URI)
                if (uriString != null) {
                    val folder = Uri.parse(uriString)
                    store.saveFolder(folder)
                    loadPlaylist(folder, 0, 0, true)
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    companion object {
        const val CMD_SET_FOLDER = "taverner_set_folder"
        const val EXTRA_TREE_URI = "extra_tree_uri"
        private const val SAVE_INTERVAL_MS = 5000L
    }
}
