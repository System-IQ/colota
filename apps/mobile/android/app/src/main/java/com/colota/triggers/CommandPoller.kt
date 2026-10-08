/**
 * Copyright (C) 2026 Max Dietrich
 * Licensed under the GNU AGPLv3. See LICENSE in the project root for details.
 *
 * Polls the server for remote commands (GPS on/off, tracking start/stop)
 * and reports device info (battery, GPS state, tracking state).
 */

package com.Colota.triggers

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import com.Colota.data.DatabaseHelper
import com.Colota.service.LocationForegroundService
import com.Colota.service.NotificationHelper
import com.Colota.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object CommandPoller {
    private const val TAG = "CommandPoller"
    private const val POLL_INTERVAL_MS = 30_000L
    private const val TIMEOUT_MS = 15_000
    private const val SETTING_CACHED_ID = "colota_poller_device_id"

    @Volatile private var scope: CoroutineScope? = null

    fun start(context: Context) {
        if (scope != null) return
        val app = context.applicationContext
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = s
        s.launch {
            delay(5_000)
            while (isActive) {
                try {
                    poll(app)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "poll cycle failed: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        AppLogger.i(TAG, "CommandPoller started (interval=${POLL_INTERVAL_MS / 1000}s)")
    }

    fun stop() {
        scope?.cancel()
        scope = null
        AppLogger.i(TAG, "CommandPoller stopped")
    }

    /**
     * Resolve device_id from the same source Colota sends in its payload:
     *   1. Cached value (fast path)
     *   2. customFields JSON → "device_id" key
     *   3. ANDROID_ID (fallback)
     */
    private fun deviceId(ctx: Context): String {
        return try {
            val db = DatabaseHelper.getInstance(ctx)
            val cached = db.getSetting(SETTING_CACHED_ID, null)
            if (!cached.isNullOrBlank()) return cached

            val cfRaw = db.getSetting("customFields", null)
            if (!cfRaw.isNullOrBlank()) {
                try {
                    val obj = JSONObject(cfRaw)
                    val did = obj.optString("device_id", "").trim()
                    if (did.isNotEmpty()) {
                        db.saveSetting(SETTING_CACHED_ID, did)
                        AppLogger.i(TAG, "device_id from customFields: $did")
                        return did
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "customFields parse failed: ${e.message}")
                }
            }

            val fallback = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
                ?: "unknown"
            db.saveSetting(SETTING_CACHED_ID, fallback)
            AppLogger.w(TAG, "device_id fallback to ANDROID_ID: $fallback (set customFields.device_id for match)")
            fallback
        } catch (e: Exception) {
            AppLogger.e(TAG, "deviceId resolution failed", e)
            "unknown"
        }
    }

    private fun endpoint(ctx: Context): String? {
        return try {
            val db = DatabaseHelper.getInstance(ctx)
            val ep = db.getSetting("endpoint", null)
            ep?.takeIf { it.isNotBlank() }?.trimEnd('/')
        } catch (e: Exception) {
            null
        }
    }

    private fun poll(ctx: Context) {
        val ep = endpoint(ctx) ?: run {
            AppLogger.w(TAG, "no endpoint configured")
            return
        }
        val did = deviceId(ctx)

        // 1. Fetch pending commands
        val enc = try { URLEncoder.encode(did, "UTF-8") } catch (e: Exception) { did }
        val url = "$ep/command/pending?device_id=$enc"
        val response = httpGet(url) ?: return
        val cmds: JSONArray = try {
            JSONArray(response)
        } catch (e: Exception) {
            AppLogger.w(TAG, "parse failed: ${e.message}")
            return
        }

        for (i in 0 until cmds.length()) {
            val c = cmds.getJSONObject(i)
            val cmdId = c.getInt("id")
            val cmdName = c.getString("command")
            AppLogger.i(TAG, "executing command #$cmdId: $cmdName")
            val ok = execute(ctx, cmdName)
            sendAck(ep, cmdId, ok, if (ok) "ok" else "failed")
        }

        // 2. Report device info
        sendInfo(ctx, ep, did)
    }

    private fun execute(ctx: Context, cmd: String): Boolean {
        return try {
            when (cmd) {
                "gps_on" -> GpsController.setGps(ctx, true)
                "gps_off" -> GpsController.setGps(ctx, false)
                "track_on" -> {
                    TrackingControl.start(ctx, "Remote command")
                    true
                }
                "track_off" -> {
                    TrackingControl.stop(ctx, NotificationHelper.StopReason.AUTOMATION)
                    true
                }
                else -> {
                    AppLogger.w(TAG, "unknown command: $cmd")
                    false
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "execute $cmd failed", e)
            false
        }
    }

    private fun sendAck(ep: String, cmdId: Int, ok: Boolean, result: String) {
        try {
            val body = JSONObject().apply {
                put("command_id", cmdId)
                put("success", ok)
                put("result", result)
            }.toString()
            httpPost("$ep/command/ack", body)
        } catch (e: Exception) {
            AppLogger.w(TAG, "ack failed: ${e.message}")
        }
    }

    private fun sendInfo(ctx: Context, ep: String, did: String) {
        try {
            var bat = -1.0
            var charging = false
            try {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                bat = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toDouble()
                charging = bm.isCharging
            } catch (_: Exception) {}

            val body = JSONObject().apply {
                put("device_id", did)
                put("battery", bat)
                put("is_charging", charging)
                put("gps_enabled", GpsController.isGpsEnabled(ctx))
                put("tracking_enabled", LocationForegroundService.isRunning)
                put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("android_version", Build.VERSION.RELEASE)
            }.toString()
            httpPost("$ep/device/info", body)
        } catch (e: Exception) {
            AppLogger.w(TAG, "info failed: ${e.message}")
        }
    }

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                AppLogger.w(TAG, "GET $url -> $code")
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            AppLogger.w(TAG, "GET failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun httpPost(url: String, body: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) {
                AppLogger.w(TAG, "POST $url -> $code")
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            AppLogger.w(TAG, "POST failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
