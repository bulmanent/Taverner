package ie.neil.taverner

import android.content.Context
import android.net.Uri

class PlaybackStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveFolder(uri: Uri?) {
        prefs.edit().putString(KEY_FOLDER_URI, uri?.toString()).apply()
    }

    fun loadFolder(): Uri? {
        return prefs.getString(KEY_FOLDER_URI, null)?.let { Uri.parse(it) }
    }

    fun saveState(index: Int, position: Long, playWhenReady: Boolean) {
        prefs.edit()
            .putInt(KEY_INDEX, index)
            .putLong(KEY_POSITION, position)
            .putBoolean(KEY_PLAY_WHEN_READY, playWhenReady)
            .apply()
    }

    fun loadState(): PlaybackState {
        return PlaybackState(
            index = prefs.getInt(KEY_INDEX, 0),
            position = prefs.getLong(KEY_POSITION, 0L),
            playWhenReady = prefs.getBoolean(KEY_PLAY_WHEN_READY, false)
        )
    }

    data class PlaybackState(
        val index: Int,
        val position: Long,
        val playWhenReady: Boolean
    )

    companion object {
        private const val PREFS_NAME = "taverner_playback"
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val KEY_INDEX = "track_index"
        private const val KEY_POSITION = "track_position"
        private const val KEY_PLAY_WHEN_READY = "play_when_ready"
    }
}
