/**
 * Copyright (C) 2026 Max Dietrich
 * Licensed under the GNU AGPLv3. See LICENSE in the project root for details.
 */

package com.Colota.triggers

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.Colota.util.AppLogger

/**
 * Fallback for GPS control when WRITE_SECURE_SETTINGS is unavailable.
 * Opens system Location settings and toggles the switch via accessibility APIs.
 */
class GpsAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GpsAccessibilityService"
        @Volatile var instance: GpsAccessibilityService? = null
        @Volatile private var pendingEnable: Boolean? = null
        @Volatile private var pendingAt: Long = 0
        private const val TIMEOUT_MS = 8000L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        AppLogger.i(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    fun requestToggle(enable: Boolean) {
        if (pendingEnable != null) return
        pendingEnable = enable
        pendingAt = System.currentTimeMillis()
        try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Cannot open location settings", e)
            pendingEnable = null
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (System.currentTimeMillis() - pendingAt >= TIMEOUT_MS - 200) {
                pendingEnable = null
            }
        }, TIMEOUT_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val enable = pendingEnable ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (!pkg.contains("settings")) return
        val root = rootInActiveWindow ?: return

        if (clickLocationSwitch(root, enable)) {
            pendingEnable = null
            Handler(Looper.getMainLooper()).postDelayed({
                try { performGlobalAction(GLOBAL_ACTION_BACK) } catch (_: Exception) {}
            }, 400)
        }
    }

    private fun clickLocationSwitch(root: AccessibilityNodeInfo, enable: Boolean): Boolean {
        val ids = listOf(
            "android:id/switch_widget",
            "com.android.settings:id/switch_widget",
            "android:id/checkbox"
        )
        for (id in ids) {
            val nodes = try {
                root.findAccessibilityNodeInfosByViewId(id)
            } catch (_: Exception) { null } ?: continue
            for (node in nodes) {
                if (node.isCheckable || node.className?.contains("Switch") == true) {
                    if (node.isChecked != enable) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    return true
                }
            }
        }
        val labels = listOf("Use location", "Location", "استخدام الموقع", "الموقع")
        for (label in labels) {
            val nodes = try {
                root.findAccessibilityNodeInfosByText(label)
            } catch (_: Exception) { null } ?: continue
            if (nodes.isNotEmpty()) {
                val parent = nodes[0].parent ?: continue
                val siblings = parent.childCount
                for (i in 0 until siblings) {
                    val child = parent.getChild(i) ?: continue
                    if (child.isCheckable) {
                        if (child.isChecked != enable) {
                            child.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                        return true
                    }
                }
            }
        }
        return false
    }
}
