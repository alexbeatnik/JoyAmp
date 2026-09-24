package com.local.joyamp

import android.net.Uri

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val uri: Uri,
) {
    /** Row label in the library list. */
    val label: String get() = if (artist.isNotBlank()) "$title — $artist" else title
}
