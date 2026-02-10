package ie.neil.taverner

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

object AudioScanner {
    fun scan(context: Context, treeUri: Uri): List<Track> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val files = root.listFiles()
            .filter { it.isFile && it.name != null && it.name!!.lowercase().endsWith(".mp3") }
            .sortedBy { it.name!!.lowercase() }

        return files.map { file ->
            Track(file.uri, file.name ?: "Unknown")
        }
    }
}
