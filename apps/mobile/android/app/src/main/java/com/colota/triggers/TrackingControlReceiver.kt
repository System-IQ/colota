/**
 * Copyright (C) 2026 Max Dietrich
 * Licensed under the GNU AGPLv3. See LICENSE in the project root for details.
 */

package com.Colota.triggers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.Colota.util.AppLanguage
import com.Colota.R
import com.Colota.service.NotificationHelper
import com.Colota.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Exported broadcast receiver that starts and stops tracking from automation apps,
 * plus remote GPS control actions.
 */
class TrackingControlReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TrackingControlReceiver"
        const val ACTION_START = "com.Colota.action.START_TRACKING"
        const val ACTION_STOP = "com.Colota.action.STOP_TRACKING"
        const val ACTION_ENABLE_GPS = "com.Colota.action.ENABLE_GPS"
        const val ACTION_DISABLE_GPS = "com.Colota.action.DISABLE_GPS"
        const val ACTION_ENABLE_AUTO_GPS = "com.Colota.action.ENABLE_AUTO_GPS"
        const val ACTION_DISABLE_AUTO_GPS = "com.Colota.action.DISABLE_AUTO_GPS"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val appContext = context.applicationContext
        val pending = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_START -> handleStart(appContext)
                    ACTION_STOP -> handleStop(appContext)
                    ACTION_ENABLE_GPS -> {
                        GpsController.setGps(appContext, true)
                    }
                    ACTION_DISABLE_GPS -> {
                        GpsController.setGps(appContext, false)
                    }
                    ACTION_ENABLE_AUTO_GPS -> {
                        GpsController.setGps(appContext, true)
                        GpsStateWatcher.setAutoEnabled(appContext, true)
                    }
                    ACTION_DISABLE_AUTO_GPS -> {
                        GpsStateWatcher.setAutoEnabled(appContext, false)
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error handling broadcast $action", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleStart(context: Context) {
        AppLogger.d(TAG, "Broadcast: start tracking")
        TrackingControl.start(context, "Started via automation intent")
        toastOnMain(context, AppLanguage.context(context).getString(R.string.toast_tracking_started))
    }

    private fun handleStop(context: Context) {
        AppLogger.d(TAG, "Broadcast: stop tracking")
        TrackingControl.stop(context, NotificationHelper.StopReason.AUTOMATION)
        toastOnMain(context, AppLanguage.context(context).getString(R.string.toast_tracking_stopped))
    }

    private fun toastOnMain(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
