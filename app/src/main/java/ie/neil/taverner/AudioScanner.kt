package ie.neil.taverner

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract

object AudioScanner {
    fun scan(context: Context, treeUri: Uri, signal: CancellationSignal? = null): List<Track> {
        return try {
            val documentId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)

            val tracks = mutableListOf<Pair<String, Track>>()
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null, null, null,
                signal
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idCol) ?: continue
                    val name = cursor.getString(nameCol) ?: continue
                    if (!name.lowercase().endsWith(".mp3")) continue
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    tracks.add(name to Track(fileUri, name))
                }
            }

            tracks.sortedBy { it.first.lowercase() }.map { it.second }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
