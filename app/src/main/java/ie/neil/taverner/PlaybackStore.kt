package ie.neil.taverner

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class PlaybackStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "tracks_cache.json")

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

    // Saves the playback position for a specific folder so it can be restored when returning to it.
    fun saveFolderState(folder: Uri, index: Int, position: Long) {
        val key = "fs_${folder.hashCode()}"
        prefs.edit()
            .putInt("${key}_i", index)
            .putLong("${key}_p", position)
            .apply()
    }

    // Returns the saved position for a folder, or null if never saved.
    fun loadFolderState(folder: Uri): PlaybackState? {
        val key = "fs_${folder.hashCode()}"
        if (!prefs.contains("${key}_i")) return null
        return PlaybackState(
            index = prefs.getInt("${key}_i", 0),
            position = prefs.getLong("${key}_p", 0L),
            playWhenReady = false
        )
    }

    // Stored as a plain file to avoid SharedPreferences QueuedWork stalls on shutdown.
    // Must be called from a background thread.
    fun saveTracks(folder: Uri, tracks: List<Track>) {
        memCacheFolder = folder.toString()
        memCacheTracks = tracks
        val tracksArr = JSONArray()
        tracks.forEach { track ->
            tracksArr.put(
                JSONObject()
                    .put("uri", track.uri.toString())
                    .put("name", track.name)
            )
        }
        val json = JSONObject()
            .put("folder", folder.toString())
            .put("tracks", tracksArr)
        cacheFile.writeText(json.toString())
    }

    // Must be called from a background thread.
    fun loadTracks(folder: Uri): List<Track> {
        val folderStr = folder.toString()
        if (memCacheFolder == folderStr && memCacheTracks.isNotEmpty()) return memCacheTracks
        if (!cacheFile.exists()) return migrateFromPrefs(folder)
        return try {
            val json = JSONObject(cacheFile.readText())
            if (json.optString("folder") != folderStr) return emptyList()
            val arr = json.optJSONArray("tracks") ?: return emptyList()
            val tracks = ArrayList<Track>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val uri = item.optString("uri").takeIf { it.isNotEmpty() } ?: continue
                val name = item.optString("name", "Unknown")
                tracks.add(Track(Uri.parse(uri), name))
            }
            memCacheFolder = folderStr
            memCacheTracks = tracks
            tracks
        } catch (ex: Exception) {
            emptyList()
        }
    }

    fun clearTracks() {
        memCacheFolder = null
        memCacheTracks = emptyList()
        cacheFile.delete()
    }

    // One-time migration: reads tracks from the old SharedPreferences storage and writes
    // them to the new file, so users don't lose their cache on the first run after the update.
    private fun migrateFromPrefs(folder: Uri): List<Track> {
        val savedFolder = prefs.getString("tracks_folder", null) ?: return emptyList()
        if (savedFolder != folder.toString()) return emptyList()
        val raw = prefs.getString("tracks_cache", null) ?: return emptyList()
        return try {
            val json = JSONArray(raw)
            val tracks = ArrayList<Track>(json.length())
            for (i in 0 until json.length()) {
                val item = json.optJSONObject(i) ?: continue
                val uri = item.optString("uri").takeIf { it.isNotEmpty() } ?: continue
                val name = item.optString("name", "Unknown")
                tracks.add(Track(Uri.parse(uri), name))
            }
            if (tracks.isNotEmpty()) {
                saveTracks(folder, tracks)
                prefs.edit().remove("tracks_cache").remove("tracks_folder").apply()
            }
            tracks
        } catch (ex: Exception) {
            emptyList()
        }
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

        // Process-wide in-memory cache so repeated reads within the same session are instant.
        @Volatile private var memCacheFolder: String? = null
        @Volatile private var memCacheTracks: List<Track> = emptyList()
    }
}
