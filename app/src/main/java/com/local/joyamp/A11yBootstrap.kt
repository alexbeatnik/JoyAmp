package com.local.joyamp

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Unisoc / E5 often clears accessibility after an install. If WRITE_SECURE_SETTINGS was granted
 * (`adb shell pm grant com.local.joyamp android.permission.WRITE_SECURE_SETTINGS`), re-enable ours.
 */
object A11yBootstrap {
    private const val TAG = "JoyAmpA11y"

    private fun component(ctx: Context) = ComponentName(ctx, JoystickKeyService::class.java)

    fun ensureEnabled(ctx: Context) {
        try {
            if (isEnabled(ctx)) return
            val cr = ctx.contentResolver
            val mine = component(ctx).flattenToString()
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.filter { it.isNotBlank() && it != "null" }.orEmpty()
            val next = (cur.filterNot { same(it, ctx) } + mine).joinToString(":")
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "re-enabled accessibility service")
        } catch (_: SecurityException) {
            // Permission not granted: the user turns it on in Settings.
        } catch (t: Throwable) {
            Log.w(TAG, "cannot re-enable accessibility", t)
        }
    }

    /** Exact match on our component (other apps also have a "JoystickKeyService"). */
    fun isEnabled(ctx: Context): Boolean {
        val on = Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        if (!on) return false
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.split(':').any { same(it, ctx) }
    }

    private fun same(entry: String, ctx: Context): Boolean {
        val cn = ComponentName.unflattenFromString(entry) ?: return false
        val mine = component(ctx)
        return cn.packageName == mine.packageName && cn.className == mine.className
    }
}
