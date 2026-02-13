package ie.neil.taverner

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.res.ColorStateList
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
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import ie.neil.taverner.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val store by lazy { PlaybackStore(this) }
    private val adapter = TrackAdapter { index ->
        playTrack(index)
    }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingFolderUri: Uri? = null
    private var currentFolderUri: Uri? = null
    private var pendingPlayIndex: Int? = null
    private var playButtonDefaultTint: ColorStateList? = null

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            updateCurrentTrack()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateCurrentTrack()
            updatePlayButton(isPlaying)
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
            store.clearTracks()
            updateFolderLabel(uri)
            currentFolderUri = uri
            loadTracks(uri, forceRefresh = true)
            if (controller == null) {
                pendingFolderUri = uri
            } else {
                sendFolderToService(
                    uri = uri,
                    forceRefresh = true,
                    startIndex = 0,
                    playWhenReady = true
                )
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
        binding.refreshButton.setOnClickListener {
            currentFolderUri?.let { uri ->
                loadTracks(uri, forceRefresh = true)
                sendFolderToService(
                    uri = uri,
                    forceRefresh = true,
                    startIndex = 0,
                    playWhenReady = true
                )
            }
        }
        binding.playButton.setOnClickListener {
            val currentController = controller
            val uri = currentFolderUri ?: store.loadFolder()
            if (currentController == null) {
                pendingPlayIndex = null
                maybeStartPlaybackService()
                return@setOnClickListener
            }
            if (currentController.mediaItemCount == 0 && uri != null) {
                val state = store.loadState()
                sendFolderToService(
                    uri = uri,
                    forceRefresh = false,
                    startIndex = state.index.coerceAtLeast(0),
                    playWhenReady = true,
                    startPositionMs = state.position.coerceAtLeast(0L)
                )
                return@setOnClickListener
            }
            currentController.prepare()
            currentController.play()
        }
        binding.pauseButton.setOnClickListener { controller?.pause() }
        binding.stopButton.setOnClickListener { controller?.stop() }
        binding.nextButton.setOnClickListener { controller?.seekToNext() }
        playButtonDefaultTint = binding.playButton.backgroundTintList

        ensureRuntimePermissions()

        store.loadFolder()?.let { uri ->
            updateFolderLabel(uri)
            currentFolderUri = uri
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
        if (controller != null) {
            return
        }
        startPlaybackService()
        connectController()
    }

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            controller = try {
                controllerFuture?.get()
            } catch (ex: Exception) {
                null
            }
            controller?.addListener(playerListener)
            updateCurrentTrack()
            updatePlayButton(controller?.isPlaying == true)
            pendingFolderUri?.let { pending ->
                sendFolderToService(
                    uri = pending,
                    forceRefresh = true,
                    startIndex = 0,
                    playWhenReady = true
                )
                pendingFolderUri = null
            }
            val pendingIndex = pendingPlayIndex
            if (pendingIndex != null) {
                playTrack(pendingIndex)
                pendingPlayIndex = null
            } else if (pendingFolderUri == null) {
                ensurePlaylistLoaded()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun sendFolderToService(
        uri: Uri,
        forceRefresh: Boolean,
        startIndex: Int,
        playWhenReady: Boolean,
        startPositionMs: Long = 0L
    ) {
        val currentController = controller ?: return
        val command = SessionCommand(PlaybackService.CMD_SET_FOLDER, Bundle.EMPTY)
        val args = Bundle().apply {
            putString(PlaybackService.EXTRA_TREE_URI, uri.toString())
            putBoolean(PlaybackService.EXTRA_FORCE_REFRESH, forceRefresh)
            putInt(PlaybackService.EXTRA_START_INDEX, startIndex)
            putLong(PlaybackService.EXTRA_START_POSITION_MS, startPositionMs.coerceAtLeast(0L))
            putBoolean(PlaybackService.EXTRA_PLAY_WHEN_READY, playWhenReady)
        }
        currentController.sendCustomCommand(command, args)
    }

    private fun updateFolderLabel(uri: Uri) {
        val name = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.lastPathSegment ?: "Unknown"
        binding.folderText.text = name
    }

    private fun updateCurrentTrack() {
        val title = controller?.currentMediaItem?.mediaMetadata?.title?.toString()
        binding.trackText.text = title ?: getString(R.string.no_track)
        val index = controller?.currentMediaItemIndex ?: RecyclerView.NO_POSITION
        val changed = adapter.setCurrentIndex(index)
        if (changed && index != RecyclerView.NO_POSITION) {
            (binding.trackList.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(index, 0)
        }
    }

    private fun updatePlayButton(isPlaying: Boolean) {
        val highlight = ContextCompat.getColor(this, R.color.taverner_secondary)
        binding.playButton.backgroundTintList = if (isPlaying) {
            ColorStateList.valueOf(highlight)
        } else {
            playButtonDefaultTint
        }
    }

    private fun playTrack(index: Int) {
        val currentController = controller
        val uri = currentFolderUri ?: store.loadFolder()
        if (currentController == null) {
            pendingPlayIndex = index
            maybeStartPlaybackService()
            return
        }
        if (currentController.mediaItemCount == 0 && uri != null) {
            sendFolderToService(
                uri = uri,
                forceRefresh = false,
                startIndex = index,
                playWhenReady = true
            )
            return
        }
        currentController.seekTo(index, 0)
        currentController.prepare()
        currentController.play()
    }

    private fun ensurePlaylistLoaded() {
        val currentController = controller ?: return
        val uri = currentFolderUri ?: store.loadFolder() ?: return
        if (currentController.mediaItemCount == 0) {
            val state = store.loadState()
            sendFolderToService(
                uri = uri,
                forceRefresh = false,
                startIndex = state.index.coerceAtLeast(0),
                playWhenReady = false,
                startPositionMs = state.position.coerceAtLeast(0L)
            )
        }
    }

    private fun loadTracks(uri: Uri, forceRefresh: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            val cached = if (forceRefresh) emptyList() else store.loadTracks(uri)
            if (cached.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    adapter.tracks = cached
                    updateCurrentTrack()
                }
                if (!forceRefresh) {
                    return@launch
                }
            } else {
                withContext(Dispatchers.Main) {
                    binding.loadingRow.visibility = android.view.View.VISIBLE
                }
            }

            val scanned = AudioScanner.scan(this@MainActivity, uri)
            if (scanned.isNotEmpty()) {
                store.saveTracks(uri, scanned)
            }
            withContext(Dispatchers.Main) {
                binding.loadingRow.visibility = android.view.View.GONE
                adapter.tracks = scanned
                updateCurrentTrack()
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

}
