package ie.neil.taverner

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.provider.MediaStore
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

object AudioScanner {

    /**
     * Asks Android to re-index the folder before a refresh query.
     * Only called from the refresh path — normal loads skip this entirely.
     */
    suspend fun triggerMediaScan(context: Context, treeUri: Uri) {
        try {
            val segment = treeUri.lastPathSegment ?: return
            if (!segment.startsWith("primary:")) return
            val folderPath = segment.substringAfter(':').trimEnd('/')
            if (folderPath.isEmpty()) return
            @Suppress("DEPRECATION")
            val base = Environment.getExternalStorageDirectory().absolutePath
            val fullPath = "$base/$folderPath"

            // scanFile() is designed for individual files; passing a directory is unreliable on
            // Android 10+.  List files directly so every MP3 in the folder gets a scan entry,
            // which forces MediaStore to pick up any newly added files.
            val dir = java.io.File(fullPath)
            val paths: Array<String> = if (dir.canRead()) {
                dir.listFiles { f -> f.name.lowercase().endsWith(".mp3") }
                    ?.takeIf { it.isNotEmpty() }
                    ?.map { it.absolutePath }
                    ?.toTypedArray()
                    ?: arrayOf(fullPath)
            } else {
                arrayOf(fullPath)
            }

            // Guard against the callback never firing (can happen on some Android versions when
            // the path is inaccessible) so we don't hang the refresh forever.
            withTimeoutOrNull(15_000L) {
                suspendCancellableCoroutine<Unit> { cont ->
                    var remaining = paths.size
                    MediaScannerConnection.scanFile(context, paths, null) { _, _ ->
                        if (--remaining <= 0 && cont.isActive) cont.resume(Unit)
                    }
                }
            }
        } catch (_: Exception) { }
    }

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
                null,
                signal
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
        } catch (e: android.os.OperationCanceledException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }
}
