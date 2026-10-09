/**
 * Copyright (C) 2026 Max Dietrich
 * Licensed under the GNU AGPLv3. See LICENSE in the project root for details.
 *
 * Watches connectivity. When offline AND tracking is enabled → auto-enable GPS
 * so locations keep being recorded locally until the network returns.
 */

package com.Colota.triggers

import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.Colota.data.DatabaseHelper
import com.Colota.data.SettingsKeys
import com.Colota.util.AppLogger

object NetworkMonitor {
    private const val TAG = "NetworkMonitor"
    @Volatile private var registered = false
    @Volatile private var lastOnline: Boolean? = null

    fun start(ctx: Context) {
        if (registered) return
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    handleChange(ctx.applicationContext, true)
                }
                override fun onLost(network: Network) {
                    handleChange(ctx.applicationContext, false)
                }
            })
            registered = true
            AppLogger.i(TAG, "NetworkMonitor started")
        } catch (e: Exception) {
            AppLogger.e(TAG, "register failed", e)
        }
    }

    private fun handleChange(ctx: Context, online: Boolean) {
        val prev = lastOnline
        lastOnline = online
        if (prev == online) return
        AppLogger.i(TAG, "Network → ${if (online) "ONLINE" else "OFFLINE"}")

        if (!online) {
            try {
                val db = DatabaseHelper.getInstance(ctx)
                val tracking = db.getSetting(SettingsKeys.TRACKING_ENABLED, "false") == "true"
                if (!tracking) return

                val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    AppLogger.i(TAG, "Offline + tracking on → enabling GPS")
                    GpsController.setGps(ctx, true)
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "auto-gps offline failed: ${e.message}")
            }
        } else {
            // Back online: CommandPoller will catch up automatically
            AppLogger.i(TAG, "Back online — Poller will sync")
        }
    }
}
