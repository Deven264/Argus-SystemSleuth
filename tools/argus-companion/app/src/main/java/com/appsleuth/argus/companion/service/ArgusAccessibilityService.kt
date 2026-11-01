package com.appsleuth.argus.companion.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.appsleuth.argus.companion.model.EventType
import com.appsleuth.argus.companion.model.FlightEvent
import com.appsleuth.argus.companion.model.Outcome
import com.appsleuth.argus.companion.recorder.FlightRecorder
import com.appsleuth.argus.companion.util.ArgusLogger

/**
 * Android Accessibility Service that non-invasively captures UI interaction events
 * from ANY installed application on the device.
 *
 * It intercepts:
 * - SCREEN_VIEW: Activity/screen transitions, pane appearances, and header changes
 * - USER_ACTION: Button, card, row, tab, or container taps (with child text extraction)
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
        // Enables interactive window retrieval for extracting child text & view IDs
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
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
        var screenTitle = when {
            texts.isNotEmpty() -> texts.joinToString(" / ") { it.toString() }.trim()
            event.className != null -> event.className.toString().substringAfterLast(".")
            else -> pkg.substringAfterLast(".")
        }.take(80)

        // If screen title is a generic container or identical to last screen, inspect active window for a toolbar/header
        if (screenTitle == lastScreenLabel || screenTitle == "FrameLayout" || screenTitle == "ViewGroup") {
            val windowTitle = findScreenHeaderFromWindow()
            if (!windowTitle.isNullOrBlank() && windowTitle != lastScreenLabel) {
                screenTitle = windowTitle
            }
        }

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
                else -> {
                    // Inspect event.source and its children (e.g. child TextView inside a tapped React Native/Compose ViewGroup)
                    val extracted = extractLabelFromNode(event.source, depth = 0)
                    if (!extracted.isNullOrBlank()) {
                        append(extracted)
                    } else {
                        append(event.className?.toString()?.substringAfterLast(".") ?: "ActionElement")
                    }
                }
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

    /**
     * Recursively traverses a clicked node's subtree up to depth 3 to find
     * the actual readable text or contentDescription (e.g. "Orders", "Checkout", "Help").
     */
    private fun extractLabelFromNode(node: AccessibilityNodeInfo?, depth: Int = 0): String? {
        if (node == null || depth > 3) return null

        // 1. Direct text on node
        val directText = node.text?.toString()?.trim()
        if (!directText.isNullOrBlank() && directText.length > 1) {
            return directText
        }

        // 2. Direct content description
        val directDesc = node.contentDescription?.toString()?.trim()
        if (!directDesc.isNullOrBlank()) {
            return directDesc
        }

        // 3. Drill down into child nodes (e.g., child TextViews inside a ViewGroup row)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childText = extractLabelFromNode(child, depth + 1)
            if (!childText.isNullOrBlank()) {
                return childText
            }
        }

        // 4. Resource ID entry name as last resort
        val resId = node.viewIdResourceName?.substringAfterLast("/")
        if (!resId.isNullOrBlank() && !resId.contains("container", ignoreCase = true)) {
            return resId
        }

        return null
    }

    /**
     * Inspects the active window hierarchy to locate toolbar, action bar, or header text.
     */
    private fun findScreenHeaderFromWindow(): String? {
        val root = rootInActiveWindow ?: return null
        return findHeaderInNode(root, 0)
    }

    private fun findHeaderInNode(node: AccessibilityNodeInfo?, depth: Int): String? {
        if (node == null || depth > 4) return null
        val resId = node.viewIdResourceName?.lowercase() ?: ""
        if (resId.contains("toolbar") || resId.contains("title") || resId.contains("header")) {
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrBlank() && text.length > 2) return text
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val header = findHeaderInNode(child, depth + 1)
            if (!header.isNullOrBlank()) return header
        }
        return null
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
