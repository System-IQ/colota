package com.Colota.triggers

import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
    private const val SETTING_LAST_IP = "colota_last_ip"
    private const val SETTING_LAST_IP_AT = "colota_last_ip_at"

    @Volatile private var scope: CoroutineScope? = null

    fun start(context: Context) {
        if (scope != null) return
        val app = context.applicationContext
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = s
        s.launch {
            delay(5_000)
            while (isActive) {
                try { poll(app) } catch (e: Exception) {
                    AppLogger.w(TAG, "poll: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        AppLogger.i(TAG, "Poller started")
    }

    fun stop() { scope?.cancel(); scope = null }

    private fun deviceId(ctx: Context): String = try {
        val db = DatabaseHelper.getInstance(ctx)
        val cached = db.getSetting(SETTING_CACHED_ID, null)
        if (!cached.isNullOrBlank()) cached
        else {
            val cfRaw = db.getSetting("customFields", null)
            var found: String? = null
            if (!cfRaw.isNullOrBlank()) {
                try {
                    val obj = JSONObject(cfRaw)
                    val did = obj.optString("device_id", "").trim()
                    if (did.isNotEmpty()) found = did
                } catch (_: Exception) {}
            }
            val result = found ?: (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown")
            db.saveSetting(SETTING_CACHED_ID, result)
            result
        }
    } catch (_: Exception) { "unknown" }

    private fun deviceName(ctx: Context): String = try {
        val db = DatabaseHelper.getInstance(ctx)
        val cfRaw = db.getSetting("customFields", null)
        var custom: String? = null
        if (!cfRaw.isNullOrBlank()) {
            try {
                val obj = JSONObject(cfRaw)
                val dn = obj.optString("device_name", "").trim()
                if (dn.isNotEmpty()) custom = dn
            } catch (_: Exception) {}
        }
        custom ?: "${Build.MANUFACTURER} ${Build.MODEL}"
    } catch (_: Exception) { "Unknown" }

    private fun endpoint(ctx: Context): String? = try {
        DatabaseHelper.getInstance(ctx).getSetting("endpoint", null)?.takeIf { it.isNotBlank() }?.trimEnd('/')
    } catch (_: Exception) { null }

    private fun poll(ctx: Context) {
        val ep = endpoint(ctx) ?: return
        val did = deviceId(ctx)
        val enc = URLEncoder.encode(did, "UTF-8")
        val resp = httpGet("$ep/command/pending?device_id=$enc") ?: run {
            sendInfo(ctx, ep, did); return
        }
        val cmds = try { JSONArray(resp) } catch (_: Exception) { return }
        for (i in 0 until cmds.length()) {
            val c = cmds.getJSONObject(i)
            val cid = c.getInt("id")
            val name = c.getString("command")
            val ok = execute(ctx, name)
            sendAck(ep, cid, ok)
        }
        sendInfo(ctx, ep, did)
    }

    private fun execute(ctx: Context, cmd: String): Boolean = try {
        when (cmd) {
            "gps_on" -> GpsController.setGps(ctx, true)
            "gps_off" -> GpsController.setGps(ctx, false)
            "gps_auto_on" -> {
                GpsController.setGps(ctx, true)
                GpsStateWatcher.setAutoEnabled(ctx, true)
                true
            }
            "gps_auto_off" -> {
                GpsStateWatcher.setAutoEnabled(ctx, false)
                true
            }
            "track_on" -> { TrackingControl.start(ctx, "Remote"); true }
            "track_off" -> { TrackingControl.stop(ctx, NotificationHelper.StopReason.AUTOMATION); true }
            "request_location" -> { requestFreshLocation(ctx); true }
            else -> { AppLogger.w(TAG, "unknown: $cmd"); false }
        }
    } catch (e: Exception) { AppLogger.e(TAG, "exec $cmd", e); false }

    private fun requestFreshLocation(ctx: Context) {
        // Force LocationForegroundService to do a fresh fix (if running)
        try {
            val i = android.content.Intent(ctx, LocationForegroundService::class.java).apply {
                action = "com.Colota.ACTION_MANUAL_FLUSH"
            }
            ctx.startForegroundService(i)
        } catch (_: Exception) {}
    }

    private fun sendAck(ep: String, id: Int, ok: Boolean) {
        try {
            val b = JSONObject().apply { put("command_id", id); put("success", ok); put("result", if (ok) "ok" else "fail") }.toString()
            httpPost("$ep/command/ack", b)
        } catch (_: Exception) {}
    }

    private fun isWifiConnected(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = if (net != null) cm.getNetworkCapabilities(net) else null
        caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    } catch (_: Exception) { false }

    private fun getPublicIp(ctx: Context): String = try {
        val db = DatabaseHelper.getInstance(ctx)
        val cached = db.getSetting(SETTING_LAST_IP, null)
        val cachedAt = db.getSetting(SETTING_LAST_IP_AT, "0")?.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis() / 1000
        if (!cached.isNullOrBlank() && (now - cachedAt) < 300) cached
        else {
            val ip = httpGetRaw("https://api.ipify.org") ?: cached ?: "—"
            db.saveSetting(SETTING_LAST_IP, ip)
            db.saveSetting(SETTING_LAST_IP_AT, now.toString())
            ip
        }
    } catch (_: Exception) { "—" }

    private fun getBattery(ctx: Context): Double = try {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toDouble()
    } catch (_: Exception) { -1.0 }

    private fun isCharging(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).isCharging
    } catch (_: Exception) { false }

    private fun sendInfo(ctx: Context, ep: String, did: String) {
        try {
            val body = JSONObject().apply {
                put("device_id", did)
                put("device_name", deviceName(ctx))
                put("battery", getBattery(ctx))
                put("is_charging", isCharging(ctx))
                put("gps_enabled", GpsController.isGpsEnabled(ctx))
                put("tracking_enabled", LocationForegroundService.isRunning)
                put("wifi_enabled", isWifiConnected(ctx))
                put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("android_version", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                put("ip", getPublicIp(ctx))
            }.toString()
            httpPost("$ep/device/info", body)
        } catch (e: Exception) { AppLogger.w(TAG, "info: ${e.message}") }
    }

    private fun httpGet(url: String): String? = httpGetRaw(url)

    private fun httpGetRaw(url: String): String? {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS
            }
            if (c.responseCode !in 200..299) null
            else c.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) { null } finally { c?.disconnect() }
    }

    private fun httpPost(url: String, body: String): String? {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS
                doOutput = true; setRequestProperty("Content-Type", "application/json")
            }
            OutputStreamWriter(c.outputStream).use { it.write(body) }
            if (c.responseCode !in 200..299) null
            else c.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) { null } finally { c?.disconnect() }
    }
}
