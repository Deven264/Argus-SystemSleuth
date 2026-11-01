package com.appsleuth.argus.companion.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.appsleuth.argus.companion.model.EventType
import com.appsleuth.argus.companion.model.FlightEvent
import com.appsleuth.argus.companion.model.Outcome
import com.appsleuth.argus.companion.recorder.FlightRecorder
import com.appsleuth.argus.companion.util.ArgusLogger

/**
 * Android Accessibility Service that non-invasively captures UI interaction events
 * from ANY installed application on the device.
 *
 * This is the Android counterpart of the browser extension's DOM event interceptor.
 * It intercepts:
 * - SCREEN_VIEW: Activity/screen transitions & pane changes
 * - USER_ACTION: Button, card, item, or tab taps
 * - APP_SWITCH: Switching foreground applications
 */
class ArgusAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "ArgusAccessibility"

        @Volatile
        var isRunning: Boolean = false
            private set

        /** Packages to filter out as ambient OS noise */
        private val IGNORED_PACKAGES = setOf(
            "com.appsleuth.argus.companion",
            "com.android.systemui",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.touchtype.swiftkey",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            "android"
        )
    }

    private var lastScreenLabel = ""
    private var lastPackage = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        ArgusLogger.i(TAG, "Argus Accessibility Service connected and active")

        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_SELECTED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 100
        // Read-only observation of system events
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in IGNORED_PACKAGES) return

        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleScreenChange(event, pkg)
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_SELECTED -> handleViewClick(event, pkg)
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> handleContentChanged(event, pkg)
                else -> Unit
            }
        } catch (e: Exception) {
            ArgusLogger.e(TAG, "Error handling accessibility event: ${e.message}", e)
        }
    }

    private fun handleContentChanged(event: AccessibilityEvent, pkg: String) {
        // Detect single-activity / Jetpack Compose pane changes (like Profile, Orders, Order Details)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val types = event.contentChangeTypes
            val isPaneChange = (types and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE != 0) ||
                    (types and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED != 0)
            if (isPaneChange) {
                handleScreenChange(event, pkg)
                return
            }
        }

        // If event has non-empty text representing a new screen or sub-view header
        val textList = event.text
        if (textList.isNotEmpty()) {
            val candidate = textList.joinToString(" / ") { it.toString() }.trim().take(80)
            if (candidate.isNotBlank() && candidate != lastScreenLabel && candidate.length > 2) {
                handleScreenChange(event, pkg)
            }
        }
    }

    private fun handleScreenChange(event: AccessibilityEvent, pkg: String) {
        val texts = event.text
        val screenTitle = when {
            texts.isNotEmpty() -> texts.joinToString(" / ") { it.toString() }.trim()
            event.className != null -> event.className.toString().substringAfterLast(".")
            else -> pkg.substringAfterLast(".")
        }.take(80)

        if (screenTitle.isBlank()) return
        if (screenTitle == lastScreenLabel && pkg == lastPackage) return

        // Detect app switch
        if (pkg != lastPackage && lastPackage.isNotEmpty()) {
            FlightRecorder.record(
                FlightEvent(
                    type = EventType.APP_SWITCH,
                    sourceApp = pkg,
                    label = "Switch → ${pkg.substringAfterLast(".")}",
                    outcome = Outcome.SUCCESS
                )
            )
            ArgusLogger.d(TAG, "[$pkg] AppSwitch → $pkg")
        }

        lastScreenLabel = screenTitle
        lastPackage = pkg

        FlightRecorder.record(
            FlightEvent(
                type = EventType.SCREEN_VIEW,
                sourceApp = pkg,
                label = "Screen:$screenTitle",
                outcome = Outcome.SUCCESS
            )
        )
        ArgusLogger.d(TAG, "[$pkg] ScreenView: $screenTitle")
    }

    private fun handleViewClick(event: AccessibilityEvent, pkg: String) {
        val label = buildString {
            val desc = event.contentDescription?.toString()
            val text = event.text.joinToString(" ") { it.toString() }.trim()

            when {
                !desc.isNullOrBlank() -> append(desc)
                !text.isNullOrBlank() -> append(text)
                else -> append(event.className?.toString()?.substringAfterLast(".") ?: "ActionElement")
            }
        }.take(60)

        FlightRecorder.record(
            FlightEvent(
                type = EventType.USER_ACTION,
                sourceApp = pkg,
                label = "Tap:$label",
                outcome = Outcome.SUCCESS
            )
        )
        ArgusLogger.d(TAG, "[$pkg] UserAction: Tap:$label")
    }

    override fun onInterrupt() {
        ArgusLogger.w(TAG, "Argus Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        ArgusLogger.w(TAG, "Argus Accessibility Service destroyed")
    }
}
