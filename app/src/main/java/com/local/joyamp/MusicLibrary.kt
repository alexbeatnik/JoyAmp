package com.local.joyamp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract

/** Tags read from the file itself (for the now-playing card, notification and lock screen). */
data class TrackMeta(
    val uri: Uri,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val art: Bitmap?,
)

object MusicLibrary {
    private const val MAX_DEPTH = 12
    private const val ART_PX = 256

    /** Walks the chosen SAF folder. Slow on big trees — call off the main thread. */
    fun scan(context: Context): List<Song> {
        val folder = Prefs.musicFolderUri(context)
        if (folder.isBlank()) return emptyList()
        val tree = Uri.parse(folder)
        val exts = Prefs.suffixSet(context)
        val out = ArrayList<Song>()
        try {
            walk(context, tree, DocumentsContract.getTreeDocumentId(tree), exts, out, 0)
        } catch (_: Exception) {
        }
        return out.distinctBy { it.uri }.sortedWith { a, b -> compareNatural(a.title, b.title) }
    }

    private fun walk(
        context: Context,
        treeUri: Uri,
        docId: String,
        exts: Set<String>,
        out: MutableList<Song>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val subDirs = ArrayList<String>()
        context.contentResolver.query(children, projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (c.moveToNext()) {
                val childId = c.getString(idCol) ?: continue
                val name = c.getString(nameCol) ?: continue
                if (c.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    subDirs.add(childId)
                    continue
                }
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in exts) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                out.add(Song(id = uri.hashCode().toLong(), title = name.substringBeforeLast('.'), artist = "", uri = uri))
            }
        }
        for (dir in subDirs) walk(context, treeUri, dir, exts, out, depth + 1)
    }

    fun readMeta(context: Context, uri: Uri): TrackMeta? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            TrackMeta(
                uri = uri,
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.ifBlank { null },
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()?.ifBlank { null },
                album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()?.ifBlank { null },
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                art = r.embeddedPicture?.let(::decodeArt),
            )
        } catch (_: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    private fun decodeArt(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= ART_PX && bounds.outHeight / (sample * 2) >= ART_PX) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** "2 - A" before "10 - B"; case-insensitive elsewhere. */
    private fun compareNatural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca in '0'..'9' && cb in '0'..'9') {
                val si = i
                val sj = j
                while (i < a.length && a[i] in '0'..'9') i++
                while (j < b.length && b[j] in '0'..'9') j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
