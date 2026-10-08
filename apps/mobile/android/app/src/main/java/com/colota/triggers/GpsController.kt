/**
 * Copyright (C) 2026 Max Dietrich
 * Licensed under the GNU AGPLv3. See LICENSE in the project root for details.
 */

package com.Colota.triggers

import android.content.Context
import android.location.LocationManager
import android.provider.Settings
import com.Colota.util.AppLogger

/**
 * Controls device GPS state via two fallback strategies:
 *   1. WRITE_SECURE_SETTINGS (granted once via ADB - preferred)
 *   2. Accessibility Service (works without any special permission but needs manual activation)
 */
object GpsController {
    private const val TAG = "GpsController"

    fun isGpsEnabled(context: Context): Boolean {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            AppLogger.w(TAG, "isGpsEnabled check failed: ${e.message}")
            false
        }
    }

    fun setGps(context: Context, enable: Boolean): Boolean {
        if (trySecureSetting(context, enable)) {
            AppLogger.i(TAG, "GPS ${if (enable) "ON" else "OFF"} via Secure settings")
            return true
        }
        val svc = GpsAccessibilityService.instance
        if (svc != null) {
            AppLogger.i(TAG, "GPS toggle requested via Accessibility")
            svc.requestToggle(enable)
            return true
        }
        AppLogger.w(TAG, "No GPS control method available")
        return false
    }

    private fun trySecureSetting(context: Context, enable: Boolean): Boolean {
        return try {
            val mode = if (enable) Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
                       else Settings.Secure.LOCATION_MODE_OFF
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.LOCATION_MODE, mode)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}
