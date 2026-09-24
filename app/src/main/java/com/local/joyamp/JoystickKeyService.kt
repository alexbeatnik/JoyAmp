package com.local.joyamp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import android.widget.TextView

/**
 * Lets the joystick drive JoyAmp on the lock screen:
 * Left / Right = previous / next, Center = play / pause, Up / Down = seek (hold to scrub).
 *
 * Keys are intercepted only while the keyguard is showing on a lit display, a JoyAmp
 * session exists (playing *or* paused) and nothing more urgent (call, alarm) is in front.
 * Everywhere else the stick behaves normally.
 */
class JoystickKeyService : AccessibilityService() {

    companion object {
        private const val SEEK_MS = 5_000
        private const val SEEK_REPEAT_MS = 250L
        private const val CHATTER_MS = 120L
        private const val OSD_MS = 2_000L
        /** After typing on a secure keyguard (PIN bouncer), leave the stick alone this long. */
        private const val UNLOCK_GRACE_MS = 15_000L

        private val STICK_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
        )

        /** Something that owns the screen over the keyguard is making noise. */
        private val URGENT_USAGES = setOf(
            AudioAttributes.USAGE_ALARM,
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
            AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    /** Keys whose DOWN we swallowed; their repeats and UP must be swallowed too. */
    private val consumed = HashSet<Int>()
    private var lastPressAt = 0L
    private var lastSeekAt = 0L
    private var unlockingUntil = 0L

    private var osd: View? = null
    private var osdIconRes = 0
    private val osdListener: () -> Unit = { handler.post { bindOsd() } }
    private val removeOsdNow = Runnable { removeOsd() }
    private val fadeOsd = Runnable {
        osd?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction { removeOsd() }?.start()
        handler.postDelayed(removeOsdNow, 600) // in case the animation never ends
    }

    override fun onServiceConnected() {
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        removeOsd()
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code !in STICK_KEYS) {
            if (event.action == KeyEvent.ACTION_DOWN) noteOtherKey()
            return false
        }
        when (event.action) {
            KeyEvent.ACTION_UP -> return consumed.remove(code)
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) {
                    if (code !in consumed) return false
                    if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastSeekAt >= SEEK_REPEAT_MS) {
                            lastSeekAt = now
                            MusicService.instance?.let { seek(it, code) }
                        }
                    }
                    return true
                }
                consumed.remove(code)
                val svc = MusicService.instance ?: return false
                if (!shouldCapture(svc)) return false
                consumed.add(code)
                val now = SystemClock.uptimeMillis()
                if (now - lastPressAt < CHATTER_MS) return true
                lastPressAt = now
                handle(svc, code)
                return true
            }
        }
        return false
    }

    private fun handle(svc: MusicService, code: Int) {
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { svc.prev(); showOsd(R.drawable.ic_skip_prev) }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { svc.next(); showOsd(R.drawable.ic_skip_next) }
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                svc.toggle()
                showOsd(if (svc.isPlaying()) R.drawable.ic_play else R.drawable.ic_pause)
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                lastSeekAt = SystemClock.uptimeMillis()
                seek(svc, code)
            }
        }
    }

    private fun seek(svc: MusicService, code: Int) {
        val forward = code == KeyEvent.KEYCODE_DPAD_UP
        svc.seekBy(if (forward) SEEK_MS else -SEEK_MS)
        showOsd(if (forward) R.drawable.ic_fast_forward else R.drawable.ic_fast_rewind)
    }

    /** Digits / Menu on a PIN-protected keyguard mean the user is unlocking: don't eat OK/arrows. */
    private fun noteOtherKey() {
        val km = getSystemService(KeyguardManager::class.java) ?: return
        if (km.isKeyguardLocked && km.isDeviceSecure) {
            unlockingUntil = SystemClock.uptimeMillis() + UNLOCK_GRACE_MS
        }
    }

    private fun shouldCapture(svc: MusicService): Boolean {
        if (!svc.hasSession()) return false
        // JoyBook also listens to the stick; whoever took the audio last owns it.
        if (!svc.holdsAudioFocus()) return false
        if (SystemClock.uptimeMillis() < unlockingUntil) return false
        val pm = getSystemService(PowerManager::class.java) ?: return false
        if (!pm.isInteractive) return false
        val km = getSystemService(KeyguardManager::class.java) ?: return false
        if (!km.isKeyguardLocked) return false
        val am = getSystemService(AudioManager::class.java) ?: return false
        if (am.mode != AudioManager.MODE_NORMAL) return false // ringing or in a call
        if (svc.focusLostTransiently) return false
        return am.activePlaybackConfigurations.none { it.audioAttributes.usage in URGENT_USAGES }
    }

    // ---- On-screen feedback over the keyguard ------------------------------------------

    private fun showOsd(iconRes: Int) {
        osdIconRes = iconRes
        val v = osd ?: addOsd() ?: return
        bindOsd()
        v.animate().cancel()
        v.animate().alpha(1f).setDuration(120).start()
        handler.removeCallbacks(removeOsdNow)
        handler.removeCallbacks(fadeOsd)
        handler.postDelayed(fadeOsd, OSD_MS)
    }

    private fun addOsd(): View? {
        val wm = getSystemService(WindowManager::class.java) ?: return null
        val v = LayoutInflater.from(ContextThemeWrapper(this, R.style.Theme_JoyAmp)).inflate(R.layout.osd, null)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                // Swallowed keys don't count as user activity; keep the lock screen lit meanwhile.
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (resources.displayMetrics.density * 56).toInt()
        }
        try {
            wm.addView(v, lp)
        } catch (_: Exception) {
            return null
        }
        v.alpha = 0f
        osd = v
        MusicService.addListener(osdListener)
        return v
    }

    private fun bindOsd() {
        val v = osd ?: return
        val svc = MusicService.instance
        val song = svc?.currentSong()
        val meta = svc?.currentMeta()
        v.findViewById<ImageView>(R.id.osdIcon).setImageResource(osdIconRes)
        v.findViewById<TextView>(R.id.osdTitle).text = meta?.title ?: song?.title ?: getString(R.string.app_name)
        val state = getString(if (svc?.isPlaying() == true) R.string.playing else R.string.paused)
        val dur = svc?.duration() ?: 0
        val detail = if (dur > 0) {
            getString(R.string.time_of, fmtTime(svc?.position() ?: 0), fmtTime(dur))
        } else {
            getString(R.string.track_pos, MusicService.currentIndex + 1, MusicService.playlist.size)
        }
        v.findViewById<TextView>(R.id.osdSub).text = getString(R.string.osd_sub, state, detail)
    }

    private fun removeOsd() {
        handler.removeCallbacks(fadeOsd)
        handler.removeCallbacks(removeOsdNow)
        MusicService.removeListener(osdListener)
        val v = osd ?: return
        osd = null
        try {
            getSystemService(WindowManager::class.java)?.removeViewImmediate(v)
        } catch (_: Exception) {
        }
    }
}
