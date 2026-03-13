package ie.neil.taverner

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.provider.MediaStore

object AudioScanner {
    fun scan(context: Context, treeUri: Uri, signal: CancellationSignal? = null): List<Track> {
        return try {
            val segment = treeUri.lastPathSegment ?: return emptyList()
            if (!segment.startsWith("primary:")) return emptyList()
            val folderPath = segment.substringAfter(':').trimEnd('/')
            if (folderPath.isEmpty()) return emptyList()

            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME
            )

            val selection: String
            val selectionArgs: Array<String>

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                selection = "${MediaStore.Audio.Media.RELATIVE_PATH} = ?"
                selectionArgs = arrayOf("$folderPath/")
            } else {
                @Suppress("DEPRECATION")
                val base = Environment.getExternalStorageDirectory().absolutePath
                selection = "${MediaStore.Audio.Media.DATA} LIKE ?"
                selectionArgs = arrayOf("$base/$folderPath/%.mp3")
            }

            val tracks = mutableListOf<Pair<String, Track>>()
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    if (!name.lowercase().endsWith(".mp3")) continue
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                    )
                    tracks.add(name to Track(uri, name))
                }
            }

            tracks.sortedBy { it.first.lowercase() }.map { it.second }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
