/**
 * Copyright (C) 2026 Max Dietrich
 * Licensed under the GNU AGPLv3. See LICENSE in the project root for details.
 */

package com.Colota.triggers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.Colota.util.AppLogger

/**
 * Watches GPS state. When auto-mode is on and GPS gets disabled, schedules a re-enable after 60s.
 * Designed for minimal battery impact: no polling, only reacts to system broadcasts.
 */
object GpsStateWatcher {
    private const val TAG = "GpsStateWatcher"
    private const val PREFS = "colota_gps_watcher"
    private const val KEY_AUTO = "auto_enabled"
    private const val DELAY_MS = 60_000L
    private const val REQUEST_CODE = 7919

    @Volatile private var registered = false

    fun setAutoEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO, enabled).apply()
        if (enabled) {
            register(context)
        } else {
            cancelAlarm(context)
        }
        AppLogger.i(TAG, "Auto-GPS ${if (enabled) "ENABLED" else "DISABLED"}")
    }

    fun isAutoEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, false)

    @Synchronized
    fun register(context: Context) {
        if (registered) return
        val filter = IntentFilter("android.location.PROVIDERS_CHANGED")
        try {
            ContextCompat.registerReceiver(
                context, receiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            registered = true
            AppLogger.i(TAG, "Provider change receiver registered")
        } catch (e: Exception) {
            AppLogger.w(TAG, "register failed: ${e.message}")
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent?) {
            if (!isAutoEnabled(ctx)) return
            if (!GpsController.isGpsEnabled(ctx)) {
                AppLogger.i(TAG, "GPS turned off, scheduling re-enable in 60s")
                scheduleReenable(ctx)
            }
        }
    }

    private fun scheduleReenable(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AutoReenableReceiver::class.java)
        val pi = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, piFlags())
        val at = SystemClock.elapsedRealtime() + DELAY_MS
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        } catch (_: SecurityException) {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
    }

    private fun cancelAlarm(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AutoReenableReceiver::class.java)
        am.cancel(PendingIntent.getBroadcast(context, REQUEST_CODE, intent, piFlags()))
    }

    private fun piFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
}
