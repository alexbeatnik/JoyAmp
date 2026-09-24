package com.local.joyamp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PAGE_HOME = 0
        private const val PAGE_SETTINGS = 1
        /** After the user browses, don't yank the cursor back to the playing track for a while. */
        private const val BROWSE_HOLD_MS = 8000L
        /** One stick click = one step; ignore contact chatter. */
        private const val NAV_DEBOUNCE_MS = 140L
    }

    private lateinit var pager: ViewFlipper
    private lateinit var headerTitle: TextView
    private lateinit var indShuffle: ImageView
    private lateinit var indRepeat: ImageView
    private lateinit var artImage: ImageView
    private lateinit var trackState: TextView
    private lateinit var trackTitle: TextView
    private lateinit var trackSub: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var timePos: TextView
    private lateinit var timeDur: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var statusText: TextView
    private lateinit var songList: RecyclerView
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var emptyView: View
    private lateinit var emptyText: TextView
    private lateinit var rowFolder: SettingRow
    private lateinit var rowRefresh: SettingRow
    private lateinit var rowShuffle: SettingRow
    private lateinit var rowRepeat: SettingRow
    private lateinit var rowTouch: SettingRow
    private lateinit var rowJoystick: SettingRow

    private val adapter = SongAdapter()
    private var songs: List<Song> = emptyList()
    private var service: MusicService? = null
    private var bound = false
    private var userSeeking = false
    private var scanning = false
    private var lastNavAt = 0L
    private var browseUntil = 0L
    private var lastPlayingIndex = -1
    private var shownArt: Any? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scanExecutor = Executors.newSingleThreadExecutor()

    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as MusicService.LocalBinder).service()
            updateNowPlaying()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    // Posted (not run inline) so a rescan can swap [songs] before the UI re-reads indices.
    private val changeListener: () -> Unit = { handler.post { updateNowPlaying() } }

    private val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        val name = DocumentFile.fromTreeUri(this, uri)?.name
            ?: uri.lastPathSegment?.substringAfterLast(':')
            ?: "folder"
        Prefs.setMusicFolder(this, uri.toString(), name)
        refreshSettings()
        loadLibrary(announce = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pager = findViewById(R.id.pager)
        headerTitle = findViewById(R.id.headerTitle)
        indShuffle = findViewById(R.id.indShuffle)
        indRepeat = findViewById(R.id.indRepeat)
        artImage = findViewById(R.id.artImage)
        artImage.clipToOutline = true
        trackState = findViewById(R.id.trackState)
        trackTitle = findViewById(R.id.trackTitle)
        trackTitle.isSelected = true // run the marquee
        trackSub = findViewById(R.id.trackSub)
        seekBar = findViewById(R.id.seekBar)
        timePos = findViewById(R.id.timePos)
        timeDur = findViewById(R.id.timeDur)
        btnPlay = findViewById(R.id.btnPlay)
        statusText = findViewById(R.id.statusText)
        emptyView = findViewById(R.id.emptyView)
        emptyText = findViewById(R.id.emptyText)

        songList = findViewById(R.id.songList)
        layoutManager = LinearLayoutManager(this)
        songList.layoutManager = layoutManager
        songList.adapter = adapter
        songList.itemAnimator = null
        songList.setHasFixedSize(true)
        // RecyclerView makes itself focusable; keys are handled in dispatchKeyEvent instead.
        songList.isFocusable = false
        songList.isFocusableInTouchMode = false

        setupSettings()

        val btnPrev = findViewById<ImageButton>(R.id.btnPrev)
        val btnNext = findViewById<ImageButton>(R.id.btnNext)
        btnPrev.setOnClickListener { control(MusicService.ACTION_PREV) { it.prev() } }
        btnNext.setOnClickListener { control(MusicService.ACTION_NEXT) { it.next() } }
        btnPlay.setOnClickListener { control(MusicService.ACTION_TOGGLE) { it.toggle() } }
        // ImageButton forces itself focusable, which paints a focus ring; the stick is handled globally.
        for (b in listOf(btnPrev, btnPlay, btnNext)) b.isFocusable = false
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) timePos.text = fmtTime(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar) { userSeeking = true }

            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                service?.seekTo(sb.progress)
            }
        })

        // Show the last known library instantly, then rescan in the background.
        if (MusicService.playlist.isEmpty()) {
            MusicService.playlist = Prefs.loadPlaylist(this)
            MusicService.currentIndex = Prefs.lastIndex(this).coerceIn(0, MusicService.playlist.lastIndex.coerceAtLeast(0))
        }
        applySongs(MusicService.playlist)

        showPage(PAGE_HOME)
        requestNotificationPermission()
        loadLibrary(announce = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showPage(PAGE_HOME)
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, MusicService::class.java), conn, Context.BIND_AUTO_CREATE)
        MusicService.addListener(changeListener)
        handler.post(tick)
    }

    override fun onResume() {
        super.onResume()
        A11yBootstrap.ensureEnabled(this)
        refreshSettings() // accessibility may have been toggled meanwhile
        updateNowPlaying()
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        MusicService.removeListener(changeListener)
        if (bound) {
            unbindService(conn)
            bound = false
        }
        service = null
        super.onStop()
    }

    override fun onDestroy() {
        scanExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (Prefs.ignoreTouch(this)) return true
        return super.dispatchTouchEvent(ev)
    }

    // ---- Keys --------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode

        // * = flashlight on this phone: never steal it.
        if (code == KeyEvent.KEYCODE_STAR) return super.dispatchKeyEvent(event)

        // # (or Menu) flips between the library and settings.
        if (code == KeyEvent.KEYCODE_POUND || code == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                showPage(if (pager.displayedChild == PAGE_HOME) PAGE_SETTINGS else PAGE_HOME)
            }
            return true
        }

        if (pager.displayedChild == PAGE_SETTINGS) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) showPage(PAGE_HOME)
                return true
            }
            return super.dispatchKeyEvent(event) // DPAD moves focus between rows
        }

        val handled = when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_5 -> true
            else -> false
        }
        if (!handled) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
        val now = SystemClock.uptimeMillis()
        if (now - lastNavAt < NAV_DEBOUNCE_MS) return true
        lastNavAt = now

        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> moveCursor(-1, wrap = true)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveCursor(1, wrap = true)
            KeyEvent.KEYCODE_2 -> moveCursor(-pageSize(), wrap = false)
            KeyEvent.KEYCODE_8 -> moveCursor(pageSize(), wrap = false)
            KeyEvent.KEYCODE_5 -> { browseUntil = 0L; syncCursorToPlaying(force = true) }
            KeyEvent.KEYCODE_DPAD_LEFT -> control(MusicService.ACTION_PREV) { it.prev() }
            KeyEvent.KEYCODE_DPAD_RIGHT -> control(MusicService.ACTION_NEXT) { it.next() }
            else -> activateCursor()
        }
        return true
    }

    /** Center: start the highlighted track, or play/pause if it is already the current one. */
    private fun activateCursor() {
        val pos = adapter.cursor
        if (pos in songs.indices && pos != MusicService.currentIndex) {
            startPlayback(pos)
        } else {
            control(MusicService.ACTION_TOGGLE) { it.toggle() }
        }
    }

    private fun pageSize(): Int = (layoutManager.childCount - 1).coerceAtLeast(1)

    // ---- Pages -------------------------------------------------------------------------

    private fun showPage(page: Int) {
        if (pager.displayedChild != page) pager.displayedChild = page
        val home = page == PAGE_HOME
        headerTitle.setText(if (home) R.string.app_name else R.string.settings)
        indShuffle.visibility = if (home) View.VISIBLE else View.GONE
        indRepeat.visibility = if (home) View.VISIBLE else View.GONE
        setHints(home)
        if (home) {
            currentFocus?.clearFocus()
            updateNowPlaying()
            syncCursorToPlaying(force = true)
        } else {
            refreshSettings()
            findViewById<View>(R.id.settingsScroll).scrollTo(0, 0)
            rowFolder.root.requestFocus()
        }
    }

    private fun setHints(home: Boolean) {
        findViewById<TextView>(R.id.keyA).text = if (home) "◂ ▸" else "▴ ▾"
        findViewById<TextView>(R.id.labelA).setText(if (home) R.string.hint_track else R.string.hint_move)
        findViewById<TextView>(R.id.labelB).setText(if (home) R.string.hint_play else R.string.hint_select)
        findViewById<TextView>(R.id.labelC).setText(if (home) R.string.hint_settings else R.string.hint_back)
    }

    // ---- Settings ----------------------------------------------------------------------

    private fun setupSettings() {
        rowFolder = SettingRow(findViewById(R.id.rowFolder), R.drawable.ic_folder, R.string.setting_folder)
        rowRefresh = SettingRow(findViewById(R.id.rowRefresh), R.drawable.ic_refresh, R.string.setting_refresh)
        rowShuffle = SettingRow(findViewById(R.id.rowShuffle), R.drawable.ic_shuffle, R.string.setting_shuffle)
        rowRepeat = SettingRow(findViewById(R.id.rowRepeat), R.drawable.ic_repeat, R.string.setting_repeat)
        rowTouch = SettingRow(findViewById(R.id.rowTouch), R.drawable.ic_phone, R.string.setting_touch)
        rowJoystick = SettingRow(findViewById(R.id.rowJoystick), R.drawable.ic_gamepad, R.string.setting_joystick)

        // Let DPAD focus wrap from the last row to the first and back.
        rowFolder.root.nextFocusUpId = R.id.rowJoystick
        rowJoystick.root.nextFocusDownId = R.id.rowFolder

        rowFolder.chevron.visibility = View.VISIBLE
        rowFolder.root.setOnClickListener {
            try {
                openTree.launch(null)
            } catch (_: Exception) {
            }
        }
        rowFolder.root.setOnLongClickListener {
            Prefs.clearMusicFolder(this)
            loadLibrary(announce = false)
            refreshSettings()
            Toast.makeText(this, R.string.folder_cleared, Toast.LENGTH_SHORT).show()
            true
        }
        rowRefresh.root.setOnClickListener { loadLibrary(announce = true) }
        rowShuffle.setSubtitle(getString(R.string.setting_shuffle_sub))
        rowShuffle.root.setOnClickListener {
            Prefs.setShuffle(this, !Prefs.shuffle(this))
            refreshSettings()
        }
        rowRepeat.setSubtitle(getString(R.string.setting_repeat_sub))
        rowRepeat.root.setOnClickListener {
            Prefs.setRepeatAll(this, !Prefs.repeatAll(this))
            refreshSettings()
        }
        rowTouch.root.setOnClickListener {
            Prefs.setIgnoreTouch(this, !Prefs.ignoreTouch(this))
            refreshSettings()
        }
        rowJoystick.setSubtitle(getString(R.string.setting_joystick_sub))
        rowJoystick.chevron.visibility = View.VISIBLE
        rowJoystick.root.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {
            }
            Toast.makeText(this, R.string.joystick_keys_hint, Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshSettings() {
        val folder = Prefs.musicFolderName(this)
        rowFolder.setSubtitle(folder.ifBlank { getString(R.string.folder_none) })
        rowRefresh.setSubtitle(if (scanning) getString(R.string.scanning) else getString(R.string.tracks_count, songs.size))
        rowShuffle.setToggle(Prefs.shuffle(this))
        rowRepeat.setToggle(Prefs.repeatAll(this))
        val touch = !Prefs.ignoreTouch(this)
        rowTouch.setToggle(touch)
        rowTouch.setSubtitle(getString(if (touch) R.string.setting_touch_on else R.string.setting_touch_off))
        val joystickOn = A11yBootstrap.isEnabled(this)
        rowJoystick.setBadge(joystickOn)
        findViewById<View>(R.id.a11yWarning).visibility = if (joystickOn) View.GONE else View.VISIBLE
        tintIndicator(indShuffle, Prefs.shuffle(this))
        tintIndicator(indRepeat, Prefs.repeatAll(this))
    }

    private fun tintIndicator(v: ImageView, on: Boolean) {
        val color = ContextCompat.getColor(this, if (on) R.color.ja_accent else R.color.ja_text_3)
        ImageViewCompat.setImageTintList(v, android.content.res.ColorStateList.valueOf(color))
        v.alpha = if (on) 1f else 0.6f
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    // ---- Library -----------------------------------------------------------------------

    private fun loadLibrary(announce: Boolean) {
        if (scanning) return
        scanning = true
        refreshSettings()
        updateStatus()
        val app = applicationContext
        scanExecutor.execute {
            val result = try { MusicLibrary.scan(app) } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                scanning = false
                MusicService.updatePlaylist(this, result)
                applySongs(result)
                refreshSettings()
                if (announce) {
                    Toast.makeText(this, getString(R.string.refreshed, result.size), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun applySongs(list: List<Song>) {
        val browsing = songs.isNotEmpty() && SystemClock.uptimeMillis() < browseUntil
        songs = list
        if (!browsing || adapter.cursor !in list.indices) {
            adapter.cursor = MusicService.currentIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        }
        adapter.playing = if (list.isEmpty()) -1 else MusicService.currentIndex
        adapter.notifyDataSetChanged()
        lastPlayingIndex = MusicService.currentIndex
        updateStatus()
        if (list.isNotEmpty() && !browsing) {
            songList.post { layoutManager.scrollToPositionWithOffset(adapter.cursor, songList.height / 3) }
        }
        updateNowPlaying()
    }

    private fun updateStatus() {
        statusText.text = when {
            scanning && songs.isEmpty() -> getString(R.string.scanning)
            songs.isEmpty() -> ""
            else -> getString(R.string.tracks_count, songs.size)
        }
        val empty = songs.isEmpty() && !scanning
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        songList.visibility = if (empty) View.INVISIBLE else View.VISIBLE
        emptyText.setText(if (Prefs.musicFolderUri(this).isBlank()) R.string.no_folder else R.string.no_songs)
    }

    // ---- Playback ----------------------------------------------------------------------

    private fun startPlayback(index: Int) {
        if (index !in songs.indices) return
        browseUntil = 0L
        val svc = service
        if (svc != null) {
            svc.playIndex(index)
        } else {
            ContextCompat.startForegroundService(
                this,
                Intent(this, MusicService::class.java)
                    .setAction(MusicService.ACTION_PLAY_INDEX)
                    .putExtra(MusicService.EXTRA_INDEX, index)
            )
        }
    }

    /** Run [direct] on the bound service, or deliver [action] by intent while binding is pending. */
    private fun control(action: String, direct: (MusicService) -> Unit) {
        val svc = service
        if (svc != null) {
            direct(svc)
        } else {
            ContextCompat.startForegroundService(this, Intent(this, MusicService::class.java).setAction(action))
        }
    }

    private fun updateNowPlaying() {
        val svc = service
        val idx = MusicService.currentIndex
        val song = svc?.currentSong() ?: songs.getOrNull(idx)
        val meta = svc?.currentMeta()
        val playing = svc?.isPlaying() == true
        val session = svc?.hasSession() == true

        trackTitle.text = meta?.title ?: song?.title ?: getString(R.string.nothing_playing)
        val parts = ArrayList<String>(2)
        meta?.artist?.let(parts::add)
        if (song != null && songs.isNotEmpty()) parts.add(getString(R.string.track_pos, idx + 1, songs.size))
        trackSub.text = if (song == null) getString(R.string.pick_track) else parts.joinToString("  ·  ")
        trackState.setText(if (playing) R.string.playing else R.string.paused)
        trackState.visibility = if (session) View.VISIBLE else View.INVISIBLE

        val art = meta?.art
        if (art !== shownArt) {
            shownArt = art
            artImage.setImageBitmap(art)
            artImage.visibility = if (art != null) View.VISIBLE else View.GONE
        }

        btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlay.contentDescription = getString(if (playing) R.string.pause else R.string.play)

        val newPlaying = if (songs.isEmpty()) -1 else idx
        if (newPlaying != adapter.playing || playing != adapter.isPlaying || session != adapter.hasSession) {
            val old = adapter.playing
            adapter.playing = newPlaying
            adapter.isPlaying = playing
            adapter.hasSession = session
            if (old in songs.indices) adapter.notifyItemChanged(old)
            if (newPlaying in songs.indices && newPlaying != old) adapter.notifyItemChanged(newPlaying)
        }
        updateProgress()
        syncCursorToPlaying(force = false)
    }

    private fun updateProgress() {
        if (userSeeking) return
        val svc = service
        val dur = svc?.duration() ?: 0
        val pos = svc?.position() ?: 0
        seekBar.max = dur.coerceAtLeast(1)
        seekBar.progress = if (dur > 0) pos.coerceIn(0, dur) else 0
        timePos.text = fmtTime(pos)
        timeDur.text = if (dur > 0) fmtTime(dur) else "–:––"
    }

    // ---- Cursor (DPAD highlight) -------------------------------------------------------

    private fun moveCursor(delta: Int, wrap: Boolean) {
        val count = songs.size
        if (count == 0) return
        browseUntil = SystemClock.uptimeMillis() + BROWSE_HOLD_MS
        val cur = adapter.cursor.coerceIn(0, count - 1)
        val next = if (wrap) ((cur + delta) % count + count) % count else (cur + delta).coerceIn(0, count - 1)
        setCursor(next)
    }

    /** Follow the playing track with the highlight unless the user is browsing. */
    private fun syncCursorToPlaying(force: Boolean) {
        if (songs.isEmpty()) return
        val idx = MusicService.currentIndex.coerceIn(0, songs.lastIndex)
        val changed = idx != lastPlayingIndex
        lastPlayingIndex = idx
        if (!force && (!changed || SystemClock.uptimeMillis() < browseUntil)) return
        setCursor(idx)
    }

    private fun setCursor(pos: Int) {
        val old = adapter.cursor
        if (old != pos) {
            adapter.cursor = pos
            if (old in songs.indices) adapter.notifyItemChanged(old)
            adapter.notifyItemChanged(pos)
        }
        ensureVisible(pos)
    }

    /** Scroll the least amount needed so the highlighted row (plus a peek of its neighbour) is visible. */
    private fun ensureVisible(pos: Int) {
        songList.post(object : Runnable {
            var tries = 0
            override fun run() {
                if (songList.isLayoutRequested && tries++ < 4) {
                    songList.post(this)
                    return
                }
                val v = layoutManager.findViewByPosition(pos)
                if (v == null) {
                    layoutManager.scrollToPosition(pos)
                    return
                }
                val peek = (resources.displayMetrics.density * 18).toInt()
                val top = songList.paddingTop + if (pos > 0) peek else 0
                val bottom = songList.height - songList.paddingBottom - if (pos < songs.lastIndex) peek else 0
                val vTop = layoutManager.getDecoratedTop(v)
                val vBottom = layoutManager.getDecoratedBottom(v)
                val dy = when {
                    vBottom > bottom -> minOf(vBottom - bottom, vTop - songList.paddingTop)
                    vTop < top -> vTop - top
                    else -> 0
                }
                if (dy != 0) songList.smoothScrollBy(0, dy, null, 90)
            }
        })
    }

    // ---- Views -------------------------------------------------------------------------

    private inner class SongAdapter : RecyclerView.Adapter<SongHolder>() {
        var cursor = 0
        var playing = -1
        var isPlaying = false
        var hasSession = false

        override fun getItemCount(): Int = songs.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongHolder =
            SongHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false))

        override fun onBindViewHolder(holder: SongHolder, position: Int) {
            holder.bind(songs[position], position == cursor, position == playing)
        }
    }

    private inner class SongHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.songTitle)
        private val eq: EqualizerView = view.findViewById(R.id.songEq)

        init {
            view.setOnClickListener {
                val p = bindingAdapterPosition
                if (p == RecyclerView.NO_POSITION) return@setOnClickListener
                if (p == MusicService.currentIndex && adapter.hasSession) control(MusicService.ACTION_TOGGLE) { it.toggle() }
                else startPlayback(p)
            }
        }

        fun bind(song: Song, isCursor: Boolean, isCurrent: Boolean) {
            title.text = song.label
            itemView.isActivated = isCursor
            // Highlighted row shows the full title; the rest stay one line.
            title.maxLines = if (isCursor) 6 else 1
            val color = when {
                isCurrent -> R.color.ja_accent
                isCursor -> R.color.ja_text
                else -> R.color.ja_text_2
            }
            title.setTextColor(ContextCompat.getColor(this@MainActivity, color))
            title.typeface = if (isCursor || isCurrent) MEDIUM else REGULAR
            val showEq = isCurrent && adapter.hasSession
            eq.visibility = if (showEq) View.VISIBLE else View.GONE
            eq.setPlaying(showEq && adapter.isPlaying)
        }
    }

    private class SettingRow(val root: View, iconRes: Int, titleRes: Int) {
        private val subtitle: TextView = root.findViewById(R.id.settingSubtitle)
        private val switch: SwitchCompat = root.findViewById(R.id.settingSwitch)
        private val value: TextView = root.findViewById(R.id.settingValue)
        val chevron: ImageView = root.findViewById(R.id.settingChevron)

        init {
            root.findViewById<ImageView>(R.id.settingIcon).setImageResource(iconRes)
            root.findViewById<TextView>(R.id.settingTitle).setText(titleRes)
        }

        fun setSubtitle(text: CharSequence?) {
            subtitle.text = text
            subtitle.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        fun setToggle(on: Boolean) {
            switch.visibility = View.VISIBLE
            if (switch.isChecked != on) switch.isChecked = on
        }

        fun setBadge(on: Boolean) {
            val ctx = root.context
            value.visibility = View.VISIBLE
            value.setText(if (on) R.string.on else R.string.off)
            value.setBackgroundResource(if (on) R.drawable.bg_pill_on else R.drawable.bg_pill_off)
            value.setTextColor(ContextCompat.getColor(ctx, if (on) R.color.ja_green else R.color.ja_text_2))
        }
    }
}

private val MEDIUM: android.graphics.Typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
private val REGULAR: android.graphics.Typeface = android.graphics.Typeface.DEFAULT
