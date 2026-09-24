package com.local.joyamp

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
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

    /**
     * Enabled in Settings but never connected (happens after app updates on this Unisoc build):
     * take ourselves out of the list and put us back, which makes the system bind again.
     * Needs WRITE_SECURE_SETTINGS; without it the UI asks the user to toggle the service.
     */
    fun healIfStuck(ctx: Context) {
        if (JoystickKeyService.running || !isEnabled(ctx)) return
        val cr = ctx.contentResolver
        val mine = component(ctx).flattenToString()
        val others = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.filter { it.isNotBlank() && it != "null" && !same(it, ctx) }.orEmpty()
        try {
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others + mine).joinToString(":"))
                    Log.i(TAG, "re-bound stuck accessibility service")
                } catch (_: Exception) {
                }
            }, 800)
        } catch (_: SecurityException) {
        } catch (t: Throwable) {
            Log.w(TAG, "cannot re-bind accessibility", t)
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
