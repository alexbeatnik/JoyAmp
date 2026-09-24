package com.local.joyamp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import androidx.core.content.ContextCompat

/** Forwards headset / media keys to MusicService (also restarts it after the process died). */
class MediaKeyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val key: KeyEvent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
        val i = Intent(context, MusicService::class.java).apply {
            action = Intent.ACTION_MEDIA_BUTTON
            if (key != null) putExtra(Intent.EXTRA_KEY_EVENT, key)
        }
        try {
            ContextCompat.startForegroundService(context, i)
        } catch (_: Exception) {
            try { context.startService(i) } catch (_: Exception) {}
        }
    }
}
