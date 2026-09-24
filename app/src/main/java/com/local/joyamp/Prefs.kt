package com.local.joyamp

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

object Prefs {
    private const val NAME = "joyamp"
    private const val KEY_IGNORE_TOUCH = "ignore_touch"
    private const val KEY_REPEAT_ALL = "repeat_all"
    private const val KEY_SHUFFLE = "shuffle"
    private const val KEY_LAST_INDEX = "last_index"
    private const val KEY_LAST_POS = "last_pos"
    private const val KEY_LAST_URI = "last_uri"
    private const val KEY_PLAYLIST = "playlist"
    private const val KEY_SUFFIXES = "suffixes"
    private const val KEY_MUSIC_FOLDER = "music_folder_uri"
    private const val KEY_MUSIC_FOLDER_NAME = "music_folder_name"
    private const val DEFAULT_SUFFIXES = "mp3,m4a,aac,ogg,wav,flac,opus,amr,3gp"

    private fun sp(ctx: Context): SharedPreferences = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun ignoreTouch(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_IGNORE_TOUCH, true)
    fun setIgnoreTouch(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_IGNORE_TOUCH, v).apply()

    fun repeatAll(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_REPEAT_ALL, true)
    fun setRepeatAll(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_REPEAT_ALL, v).apply()

    fun shuffle(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_SHUFFLE, false)
    fun setShuffle(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_SHUFFLE, v).apply()

    fun lastIndex(ctx: Context): Int = sp(ctx).getInt(KEY_LAST_INDEX, 0)
    fun setLastIndex(ctx: Context, v: Int) = sp(ctx).edit().putInt(KEY_LAST_INDEX, v).apply()

    /** Resume position; only meaningful for the track stored in [lastUri]. */
    fun lastPos(ctx: Context): Int = sp(ctx).getInt(KEY_LAST_POS, 0)
    fun setLastPos(ctx: Context, v: Int) = sp(ctx).edit().putInt(KEY_LAST_POS, v).apply()

    fun lastUri(ctx: Context): String = sp(ctx).getString(KEY_LAST_URI, "") ?: ""
    fun setLastUri(ctx: Context, v: String) = sp(ctx).edit().putString(KEY_LAST_URI, v).apply()

    fun savePlaylist(ctx: Context, songs: List<Song>) {
        val joined = songs.joinToString("\n") { it.uri.toString() + "\t" + it.title + "\t" + it.artist }
        sp(ctx).edit().putString(KEY_PLAYLIST, joined).apply()
    }

    fun loadPlaylist(ctx: Context): List<Song> {
        val raw = sp(ctx).getString(KEY_PLAYLIST, null) ?: return emptyList()
        return raw.lines().filter { it.isNotBlank() }.mapIndexed { idx, line ->
            val p = line.split("\t")
            Song(
                id = idx.toLong(),
                title = p.getOrElse(1) { "track" },
                artist = p.getOrElse(2) { "" },
                uri = Uri.parse(p[0]),
            )
        }
    }

    fun suffixes(ctx: Context): String = sp(ctx).getString(KEY_SUFFIXES, DEFAULT_SUFFIXES) ?: DEFAULT_SUFFIXES

    fun suffixSet(ctx: Context): Set<String> =
        suffixes(ctx).split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    /** Empty = no folder chosen; library stays empty until user picks one. */
    fun musicFolderUri(ctx: Context): String = sp(ctx).getString(KEY_MUSIC_FOLDER, "") ?: ""
    fun musicFolderName(ctx: Context): String = sp(ctx).getString(KEY_MUSIC_FOLDER_NAME, "") ?: ""

    fun setMusicFolder(ctx: Context, uri: String, name: String) {
        sp(ctx).edit()
            .putString(KEY_MUSIC_FOLDER, uri)
            .putString(KEY_MUSIC_FOLDER_NAME, name)
            .apply()
    }

    fun clearMusicFolder(ctx: Context) = setMusicFolder(ctx, "", "")
}
