package ie.neil.taverner

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.common.util.concurrent.ListenableFuture
import ie.neil.taverner.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val store by lazy { PlaybackStore(this) }
    private val adapter = TrackAdapter { index ->
        controller?.seekTo(index, 0)
        controller?.play()
    }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingFolderUri: Uri? = null

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            updateCurrentTrack()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateCurrentTrack()
        }
    }

    private val openTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (ex: SecurityException) {
                // Some providers don't grant persistable permissions; continue with runtime access.
            }
            store.saveFolder(uri)
            updateFolderLabel(uri)
            loadTracks(uri)
            if (controller == null) {
                pendingFolderUri = uri
            } else {
                sendFolderToService(uri)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // SAF access still works even if permission denied.
        maybeStartPlaybackService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.trackList.layoutManager = LinearLayoutManager(this)
        binding.trackList.adapter = adapter

        binding.selectFolderButton.setOnClickListener { openTreeLauncher.launch(null) }
        binding.playButton.setOnClickListener { controller?.play() }
        binding.pauseButton.setOnClickListener { controller?.pause() }
        binding.stopButton.setOnClickListener { controller?.stop() }
        binding.nextButton.setOnClickListener { controller?.seekToNext() }

        ensureRuntimePermissions()

        store.loadFolder()?.let { uri ->
            updateFolderLabel(uri)
            loadTracks(uri)
        }
    }

    override fun onStart() {
        super.onStart()
        maybeStartPlaybackService()
    }

    override fun onStop() {
        super.onStop()
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        controllerFuture = null
    }

    private fun startPlaybackService() {
        val intent = Intent(this, PlaybackService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun maybeStartPlaybackService() {
        if (!hasNotificationPermission() || controller != null) {
            return
        }
        startPlaybackService()
        connectController()
    }

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            controller = controllerFuture?.get()
            controller?.addListener(playerListener)
            updateCurrentTrack()
            pendingFolderUri?.let { pending ->
                sendFolderToService(pending)
                pendingFolderUri = null
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun sendFolderToService(uri: Uri) {
        val currentController = controller ?: return
        val command = SessionCommand(PlaybackService.CMD_SET_FOLDER, Bundle.EMPTY)
        val args = Bundle().apply { putString(PlaybackService.EXTRA_TREE_URI, uri.toString()) }
        currentController.sendCustomCommand(command, args)
    }

    private fun updateFolderLabel(uri: Uri) {
        val name = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.lastPathSegment ?: "Unknown"
        binding.folderText.text = name
    }

    private fun updateCurrentTrack() {
        val title = controller?.currentMediaItem?.mediaMetadata?.title?.toString()
        binding.trackText.text = title ?: getString(R.string.no_track)
    }

    private fun loadTracks(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val tracks = AudioScanner.scan(this@MainActivity, uri)
            withContext(Dispatchers.Main) {
                adapter.tracks = tracks
            }
        }
    }

    private fun ensureRuntimePermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val needsRequest = permissions.any { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (needsRequest) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }
}
