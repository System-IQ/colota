/**
 * Copyright (C) 2026 Max Dietrich
 * Licensed under the GNU AGPLv3. See LICENSE in the project root for details.
 */

package com.Colota.triggers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.Colota.util.AppLogger

/**
 * Alarm receiver that fires 60s after GPS was detected off, and re-enables it.
 */
class AutoReenableReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val ctx = context.applicationContext
        if (!GpsStateWatcher.isAutoEnabled(ctx)) return
        if (GpsController.isGpsEnabled(ctx)) return
        AppLogger.i("AutoReenableReceiver", "Re-enabling GPS after 60s delay")
        GpsController.setGps(ctx, true)
    }
}
