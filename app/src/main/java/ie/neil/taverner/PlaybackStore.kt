package ie.neil.taverner

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

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

    fun saveTracks(folder: Uri, tracks: List<Track>) {
        val json = JSONArray().apply {
            tracks.forEach { track ->
                put(
                    JSONObject()
                        .put("uri", track.uri.toString())
                        .put("name", track.name)
                )
            }
        }
        prefs.edit()
            .putString(KEY_TRACKS_FOLDER, folder.toString())
            .putString(KEY_TRACKS, json.toString())
            .apply()
    }

    fun loadTracks(folder: Uri): List<Track> {
        val cachedFolder = prefs.getString(KEY_TRACKS_FOLDER, null) ?: return emptyList()
        if (cachedFolder != folder.toString()) {
            return emptyList()
        }
        val raw = prefs.getString(KEY_TRACKS, null) ?: return emptyList()
        return try {
            val json = JSONArray(raw)
            val tracks = ArrayList<Track>(json.length())
            for (i in 0 until json.length()) {
                val item = json.optJSONObject(i) ?: continue
                val uri = item.optString("uri", null) ?: continue
                val name = item.optString("name", "Unknown")
                tracks.add(Track(Uri.parse(uri), name))
            }
            tracks
        } catch (ex: Exception) {
            emptyList()
        }
    }

    fun clearTracks() {
        prefs.edit()
            .remove(KEY_TRACKS)
            .remove(KEY_TRACKS_FOLDER)
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
        private const val KEY_TRACKS = "tracks_cache"
        private const val KEY_TRACKS_FOLDER = "tracks_folder"
    }
}
