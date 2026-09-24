package com.local.joyamp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import java.util.ArrayDeque
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Playback engine. A "session" exists while a track is loaded (playing or paused);
 * during a session the service stays in the foreground so the lock-screen joystick
 * can pause *and* resume. The session ends via the notification's close action.
 */
class MusicService : Service() {

    companion object {
        private const val TAG = "JoyAmp"
        const val CHANNEL_ID = "joyamp_playback"
        const val NOTIF_ID = 42
        const val ACTION_PLAY = "com.local.joyamp.PLAY"
        const val ACTION_PAUSE = "com.local.joyamp.PAUSE"
        const val ACTION_TOGGLE = "com.local.joyamp.TOGGLE"
        const val ACTION_NEXT = "com.local.joyamp.NEXT"
        const val ACTION_PREV = "com.local.joyamp.PREV"
        const val ACTION_SEEK = "com.local.joyamp.SEEK"
        const val ACTION_PLAY_INDEX = "com.local.joyamp.PLAY_INDEX"
        const val ACTION_CLOSE = "com.local.joyamp.CLOSE"
        const val EXTRA_INDEX = "index"
        const val EXTRA_POS = "pos"
        const val EXTRA_DELTA = "delta"

        private const val RESTART_THRESHOLD_MS = 3000
        private const val DUCK_VOLUME = 0.3f

        private val AUDIO_ATTRS: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        @Volatile var playlist: List<Song> = emptyList()
        @Volatile var currentIndex: Int = 0

        /** Running service instance. Read and written on the main thread only. */
        var instance: MusicService? = null
            private set

        private val listeners = mutableSetOf<() -> Unit>()
        fun addListener(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
        fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
        fun notifyListeners() {
            val copy = synchronized(listeners) { listeners.toList() }
            copy.forEach { it.invoke() }
        }

        /**
         * Swap in a freshly scanned library while keeping [currentIndex] on the same file.
         * Must be called on the main thread.
         */
        fun updatePlaylist(ctx: Context, list: List<Song>) {
            val curUri = playlist.getOrNull(currentIndex)?.uri
                ?: Prefs.lastUri(ctx).takeIf { it.isNotBlank() }?.let(Uri::parse)
            val found = if (curUri == null) -1 else list.indexOfFirst { it.uri == curUri }
            playlist = list
            currentIndex = if (found >= 0) found else 0
            Prefs.savePlaylist(ctx, list)
            Prefs.setLastIndex(ctx, currentIndex)
            instance?.onPlaylistReplaced(stillHasCurrent = found >= 0)
            notifyListeners()
        }
    }

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val metaExecutor = Executors.newSingleThreadExecutor()
    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private lateinit var focusRequest: AudioFocusRequest

    private var player: MediaPlayer? = null
    private var prepared = false
    /** What the user wants: true while playing or about to play (preparing). */
    private var playWhenReady = false
    private var started = false
    private var hasFocus = false
    private var resumeOnFocusGain = false
    private var noisyRegistered = false
    private var consecutiveFailures = 0
    private val shuffleHistory = ArrayDeque<Int>()
    private var meta: TrackMeta? = null
    private var lastMetaKey = ""

    /** Another app (call, alarm, navigation...) holds audio focus for a moment. */
    var focusLostTransiently = false
        private set

    inner class LocalBinder : Binder() {
        fun service(): MusicService = this@MusicService
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                focusLostTransiently = false
                resumeOnFocusGain = false
                pauseInternal()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasFocus = false
                focusLostTransiently = true
                if (playWhenReady) {
                    resumeOnFocusGain = true
                    pauseInternal()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setVolume(DUCK_VOLUME)
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                focusLostTransiently = false
                setVolume(1f)
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    play()
                }
            }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        A11yBootstrap.ensureEnabled(this)
        if (playlist.isEmpty()) {
            playlist = Prefs.loadPlaylist(this)
            currentIndex = Prefs.lastIndex(this).coerceIn(0, playlist.lastIndex.coerceAtLeast(0))
        }
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AUDIO_ATTRS)
            .setOnAudioFocusChangeListener(focusListener, handler)
            .build()
        createChannel()

        val mediaButtonIntent = PendingIntent.getBroadcast(
            this, 0,
            Intent(Intent.ACTION_MEDIA_BUTTON).setClass(this, MediaKeyReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        session = MediaSessionCompat(this, "JoyAmp").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = prev()
                override fun onStop() = pause()
                override fun onSeekTo(pos: Long) = seekTo(pos.toInt())
                override fun onFastForward() = seekBy(10_000)
                override fun onRewind() = seekBy(-10_000)
                override fun onCustomAction(action: String?, extras: android.os.Bundle?) {
                    if (action == ACTION_CLOSE) endSession()
                }
            })
            setMediaButtonReceiver(mediaButtonIntent)
            isActive = false
        }
        updatePlaybackState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        started = true
        val action = intent?.action
        if (action == ACTION_CLOSE) {
            endSession()
            return START_NOT_STICKY
        }
        // Callers may have used startForegroundService(): honour that contract first.
        startInForeground()
        when (action) {
            Intent.ACTION_MEDIA_BUTTON -> androidx.media.session.MediaButtonReceiver.handleIntent(session, intent)
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_TOGGLE -> toggle()
            ACTION_NEXT -> next()
            ACTION_PREV -> prev()
            ACTION_SEEK -> {
                val delta = intent.getIntExtra(EXTRA_DELTA, 0)
                if (delta != 0) seekBy(delta) else seekTo(intent.getIntExtra(EXTRA_POS, 0))
            }
            ACTION_PLAY_INDEX -> playIndex(intent.getIntExtra(EXTRA_INDEX, 0))
        }
        if (!hasSession()) endSession()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping JoyAmp away from recents while paused means "I'm done".
        if (!playWhenReady) endSession()
        super.onTaskRemoved(rootIntent)
    }

    // ---- State -------------------------------------------------------------------------

    fun hasSession(): Boolean = player != null
    fun isPlaying(): Boolean = playWhenReady
    fun holdsAudioFocus(): Boolean = hasFocus
    fun position(): Int = if (prepared) try { player?.currentPosition ?: 0 } catch (_: Exception) { 0 } else 0
    fun duration(): Int = if (prepared) try { player?.duration?.coerceAtLeast(0) ?: 0 } catch (_: Exception) { 0 } else 0
    fun currentSong(): Song? = playlist.getOrNull(currentIndex)
    fun currentMeta(): TrackMeta? = meta?.takeIf { it.uri == currentSong()?.uri }

    // ---- Controls ----------------------------------------------------------------------

    fun playIndex(index: Int) {
        if (playlist.isEmpty()) return
        startTrack(index.coerceIn(0, playlist.lastIndex), resumePos = 0)
    }

    fun play() {
        if (playlist.isEmpty()) return
        val p = player
        if (p == null) {
            val idx = currentIndex.coerceIn(0, playlist.lastIndex)
            val resume = if (playlist[idx].uri.toString() == Prefs.lastUri(this)) Prefs.lastPos(this) else 0
            startTrack(idx, resume)
            return
        }
        if (!requestFocus()) return
        playWhenReady = true
        resumeOnFocusGain = false
        if (prepared) {
            try { p.start() } catch (_: Exception) {}
        }
        onStateChanged()
    }

    fun pause() {
        resumeOnFocusGain = false
        pauseInternal()
    }

    private fun pauseInternal() {
        playWhenReady = false
        if (prepared) {
            try { if (player?.isPlaying == true) player?.pause() } catch (_: Exception) {}
        }
        savePosition()
        onStateChanged()
    }

    fun toggle() {
        if (playWhenReady) pause() else play()
    }

    fun next() = skip(forward = true, auto = false)

    fun prev() {
        if (playlist.isEmpty()) return
        if (position() > RESTART_THRESHOLD_MS) {
            seekTo(0)
            return
        }
        skip(forward = false, auto = false)
    }

    fun seekTo(ms: Int) {
        if (!prepared) return
        val dur = duration()
        val target = if (dur > 0) ms.coerceIn(0, (dur - 500).coerceAtLeast(0)) else ms.coerceAtLeast(0)
        try { player?.seekTo(target) } catch (_: Exception) {}
        savePosition(target)
        onStateChanged()
    }

    fun seekBy(delta: Int) = seekTo(position() + delta)

    /** Stop playback, drop the notification and let the service die once unbound. */
    fun endSession() {
        if (hasSession()) savePosition()
        playWhenReady = false
        resumeOnFocusGain = false
        releasePlayer()
        abandonFocus()
        updateNoisyReceiver()
        session.isActive = false
        updatePlaybackState()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (started) {
            started = false
            stopSelf()
        }
        notifyListeners()
    }

    internal fun onPlaylistReplaced(stillHasCurrent: Boolean) {
        shuffleHistory.clear()
        if (!stillHasCurrent && hasSession()) endSession()
    }

    // ---- Track handling ----------------------------------------------------------------

    private fun skip(forward: Boolean, auto: Boolean) {
        if (playlist.isEmpty()) return
        val target = if (forward) nextIndex(wrap = !auto || Prefs.repeatAll(this)) else prevIndex()
        if (target == null) {
            // End of the list with repeat off: park at the start of the last track.
            playWhenReady = false
            if (prepared) try { player?.seekTo(0) } catch (_: Exception) {}
            savePosition(0)
            onStateChanged()
            return
        }
        startTrack(target, resumePos = 0)
    }

    private fun nextIndex(wrap: Boolean): Int? {
        val size = playlist.size
        if (Prefs.shuffle(this) && size > 1) {
            shuffleHistory.addLast(currentIndex)
            while (shuffleHistory.size > 64) shuffleHistory.removeFirst()
            return randomOtherIndex(size)
        }
        return when {
            currentIndex < size - 1 -> currentIndex + 1
            wrap -> 0
            else -> null
        }
    }

    private fun prevIndex(): Int {
        val size = playlist.size
        if (Prefs.shuffle(this) && size > 1) {
            while (shuffleHistory.isNotEmpty()) {
                val i = shuffleHistory.removeLast()
                if (i in 0 until size) return i
            }
            return randomOtherIndex(size)
        }
        return if (currentIndex > 0) currentIndex - 1 else size - 1
    }

    private fun randomOtherIndex(size: Int): Int {
        val n = Random.nextInt(size - 1)
        return if (n >= currentIndex) n + 1 else n
    }

    private fun startTrack(index: Int, resumePos: Int) {
        val song = playlist.getOrNull(index) ?: return
        releasePlayer()
        currentIndex = index
        Prefs.setLastIndex(this, index)
        Prefs.setLastUri(this, song.uri.toString())
        playWhenReady = requestFocus()
        resumeOnFocusGain = false
        beginSession()

        val mp = MediaPlayer()
        player = mp
        try {
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setAudioAttributes(AUDIO_ATTRS)
            mp.setOnPreparedListener { p ->
                if (p !== player) return@setOnPreparedListener
                prepared = true
                consecutiveFailures = 0
                if (resumePos > 0) p.seekTo(resumePos)
                if (playWhenReady) p.start()
                onStateChanged()
            }
            mp.setOnCompletionListener { p ->
                if (p !== player) return@setOnCompletionListener
                savePosition(0)
                skip(forward = true, auto = true)
            }
            mp.setOnErrorListener { p, what, extra ->
                Log.w(TAG, "MediaPlayer error $what/$extra for ${song.title}")
                if (p === player) {
                    prepared = false
                    handler.post { if (player === p) skipFailedTrack() }
                }
                true
            }
            mp.setDataSource(this, song.uri)
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open ${song.title}", e)
            handler.post { if (player === mp) skipFailedTrack() }
        }
        loadMeta(song)
        onStateChanged()
    }

    private fun skipFailedTrack() {
        consecutiveFailures++
        if (consecutiveFailures >= playlist.size) {
            consecutiveFailures = 0
            endSession()
        } else {
            skip(forward = true, auto = false)
        }
    }

    private fun releasePlayer() {
        val p = player ?: return
        player = null
        prepared = false
        try {
            p.setOnPreparedListener(null)
            p.setOnCompletionListener(null)
            p.setOnErrorListener(null)
            p.reset()
        } catch (_: Exception) {
        }
        try { p.release() } catch (_: Exception) {}
    }

    private fun setVolume(v: Float) {
        try { player?.setVolume(v, v) } catch (_: Exception) {}
    }

    private fun savePosition(pos: Int = position()) {
        Prefs.setLastPos(this, pos)
    }

    private fun loadMeta(song: Song) {
        if (meta?.uri == song.uri) return
        val uri = song.uri
        val app = applicationContext
        metaExecutor.execute {
            val m = MusicLibrary.readMeta(app, uri)
            handler.post {
                if (currentSong()?.uri == uri) {
                    meta = m
                    onStateChanged()
                }
            }
        }
    }

    // ---- Audio focus / noisy -----------------------------------------------------------

    private fun requestFocus(): Boolean {
        if (hasFocus) return true
        hasFocus = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (hasFocus) focusLostTransiently = false
        return hasFocus
    }

    private fun abandonFocus() {
        if (!hasFocus) return
        audioManager.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
    }

    private fun updateNoisyReceiver() {
        if (playWhenReady && !noisyRegistered) {
            ContextCompat.registerReceiver(
                this, noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            noisyRegistered = true
        } else if (!playWhenReady && noisyRegistered) {
            try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
            noisyRegistered = false
        }
    }

    // ---- Session / notification --------------------------------------------------------

    private fun beginSession() {
        if (!started) {
            started = true
            try {
                ContextCompat.startForegroundService(this, Intent(this, MusicService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "startForegroundService failed", e)
            }
        }
        if (!session.isActive) session.isActive = true
        startInForeground()
    }

    private fun onStateChanged() {
        updateMetadata()
        updatePlaybackState()
        updateNoisyReceiver()
        if (hasSession() && started) {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        }
        notifyListeners()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun startInForeground() {
        try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
        }
    }

    private fun buildNotification(): Notification {
        val song = currentSong()
        val m = currentMeta()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playing = playWhenReady
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(m?.title ?: song?.title ?: getString(R.string.app_name))
            .setContentText(m?.artist ?: song?.artist?.ifBlank { null } ?: getString(R.string.app_name))
            .setLargeIcon(m?.art)
            .setContentIntent(open)
            .setDeleteIntent(actionPending(ACTION_CLOSE, 4))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)
            .addAction(R.drawable.ic_skip_prev, getString(R.string.prev), actionPending(ACTION_PREV, 1))
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (playing) R.string.pause else R.string.play),
                actionPending(ACTION_TOGGLE, 2)
            )
            .addAction(R.drawable.ic_skip_next, getString(R.string.next), actionPending(ACTION_NEXT, 3))
            .addAction(R.drawable.ic_close, getString(R.string.close), actionPending(ACTION_CLOSE, 4))
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun actionPending(action: String, req: Int): PendingIntent {
        val i = Intent(this, MusicService::class.java).setAction(action)
        return PendingIntent.getService(this, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun updateMetadata() {
        val song = currentSong() ?: return
        val m = currentMeta()
        val dur = duration().toLong().takeIf { it > 0 } ?: m?.durationMs ?: 0L
        val key = "${song.uri}|$dur|${m != null}"
        if (key == lastMetaKey) return
        lastMetaKey = key
        val b = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, m?.title ?: song.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, m?.artist ?: song.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, m?.album ?: "")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, dur)
        m?.art?.let { b.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(b.build())
    }

    private fun updatePlaybackState() {
        val state = when {
            !hasSession() -> PlaybackStateCompat.STATE_STOPPED
            playWhenReady && !prepared -> PlaybackStateCompat.STATE_BUFFERING
            playWhenReady -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_FAST_FORWARD or
            PlaybackStateCompat.ACTION_REWIND or
            PlaybackStateCompat.ACTION_STOP
        // Android 13 media controls ignore notification actions; "close" has to be a custom action.
        val close = PlaybackStateCompat.CustomAction.Builder(ACTION_CLOSE, getString(R.string.close), R.drawable.ic_close).build()
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .addCustomAction(close)
                .setState(state, position().toLong(), if (state == PlaybackStateCompat.STATE_PLAYING) 1f else 0f)
                .build()
        )
    }

    override fun onDestroy() {
        if (hasSession()) savePosition()
        playWhenReady = false
        releasePlayer()
        abandonFocus()
        updateNoisyReceiver()
        session.release()
        metaExecutor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        notifyListeners()
        super.onDestroy()
    }
}
