package com.appsleuth.argus.sdk

import android.app.Activity
import android.app.Application
import android.util.Log
import com.appsleuth.argus.sdk.crash.ArgusCrashHandler
import com.appsleuth.argus.sdk.diagnosis.ArgusDiagnosticEngine
import com.appsleuth.argus.sdk.diagnosis.DiagnosisResult
import com.appsleuth.argus.sdk.graph.ArgusGraphBuilder
import com.appsleuth.argus.sdk.graph.JourneyGraph
import com.appsleuth.argus.sdk.lifecycle.ArgusLifecycleTracker
import com.appsleuth.argus.sdk.lifecycle.ArgusShakeDetector
import com.appsleuth.argus.sdk.model.ArgusEvent
import com.appsleuth.argus.sdk.model.EventType
import com.appsleuth.argus.sdk.model.Outcome
import com.appsleuth.argus.sdk.network.ArgusOkHttpInterceptor
import com.appsleuth.argus.sdk.recorder.ArgusFlightRecorder
import com.appsleuth.argus.sdk.ui.ArgusDiagnosticDialog
import java.lang.ref.WeakReference

/**
 * Argus Android SDK: In-App Behavioral Self-Diagnosis Framework (Approach D).
 *
 * Zero permissions required. Simply call Argus.init() in your Application.onCreate().
 */
object Argus {

    private const val TAG = "ArgusSDK"

    @Volatile
    private var isInitialized: Boolean = false

    private lateinit var config: ArgusConfig
    private var shakeDetector: ArgusShakeDetector? = null
    private var currentActivityRef: WeakReference<Activity>? = null

    /**
     * Initializes the Argus SDK. Call this in Application.onCreate().
     */
    fun init(application: Application, configuration: ArgusConfig = ArgusConfig.Builder().build()) {
        if (isInitialized) return
        config = configuration
        ArgusFlightRecorder.maxEvents = config.maxEvents

        // 1. Session start event
        ArgusFlightRecorder.record(
            ArgusEvent(
                type = EventType.SESSION_START,
                label = "SessionStart",
                source = application.packageName,
                outcome = Outcome.SUCCESS,
                metadata = mapOf("appVersion" to config.appVersion)
            )
        )

        // 2. Lifecycle tracking (Screens, Fragments, Touches)
        if (config.enableLifecycleTracking) {
            val tracker = ArgusLifecycleTracker(
                enableFragmentTracking = config.enableFragmentTracking,
                enableTouchTracking = config.enableTouchTracking
            )
            application.registerActivityLifecycleCallbacks(tracker)
        }

        // Keep track of current foreground activity for shake-to-diagnose
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                currentActivityRef = WeakReference(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                if (currentActivityRef?.get() == activity) {
                    currentActivityRef = null
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        // 3. Uncaught Crash Handler
        if (config.enableCrashReporting) {
            val crashHandler = ArgusCrashHandler(application.applicationContext, config.appVersion)
            Thread.setDefaultUncaughtExceptionHandler(crashHandler)
        }

        // 4. Shake to Diagnose
        if (config.enableShakeToDiagnose) {
            shakeDetector = ArgusShakeDetector(application.applicationContext) {
                val act = currentActivityRef?.get()
                if (act != null && !act.isFinishing && !act.isDestroyed) {
                    showDiagnosticDialog(act)
                }
            }
            shakeDetector?.start()
        }

        isInitialized = true
        Log.i(TAG, "Argus SDK initialized successfully (v${config.appVersion})")
    }

    /**
     * Returns a new OkHttp Interceptor to instrument your network client.
     */
    fun createOkHttpInterceptor(): ArgusOkHttpInterceptor = ArgusOkHttpInterceptor()

    /**
     * Manually record a user action (e.g. for custom Compose buttons).
     */
    fun trackAction(actionName: String, metadata: Map<String, Any?> = emptyMap()) {
        ArgusFlightRecorder.record(
            ArgusEvent(
                type = EventType.USER_ACTION,
                label = if (actionName.startsWith("Tap:")) actionName else "Tap:$actionName",
                source = "App",
                outcome = Outcome.SUCCESS,
                metadata = metadata
            )
        )
    }

    /**
     * Manually record a screen transition (e.g. for Compose Navigation destinations).
     */
    fun trackScreen(screenName: String, metadata: Map<String, Any?> = emptyMap()) {
        ArgusFlightRecorder.record(
            ArgusEvent(
                type = EventType.SCREEN_VIEW,
                label = if (screenName.startsWith("Screen:")) screenName else "Screen:$screenName",
                source = "App",
                outcome = Outcome.SUCCESS,
                metadata = metadata
            )
        )
    }

    /**
     * Builds and returns the Cumulative Session Journey DAG.
     */
    fun buildJourneyGraph(): JourneyGraph {
        return ArgusGraphBuilder.build(
            ArgusFlightRecorder.getSnapshot(),
            if (::config.isInitialized) config.appVersion else "1.0.0"
        )
    }

    /**
     * Programmatically runs diagnosis on the current session.
     */
    suspend fun diagnose(userComplaint: String): DiagnosisResult {
        val graph = buildJourneyGraph()
        val apiKey = if (::config.isInitialized) config.apiKey else null
        val provider = if (::config.isInitialized) config.aiProvider else "gemini"
        return ArgusDiagnosticEngine.diagnose(userComplaint, graph, provider, apiKey)
    }

    /**
     * Displays the in-app interactive diagnostic dialog.
     */
    fun showDiagnosticDialog(activity: Activity) {
        val version = if (::config.isInitialized) config.appVersion else "1.0.0"
        val apiKey = if (::config.isInitialized) config.apiKey else null
        val provider = if (::config.isInitialized) config.aiProvider else "gemini"

        ArgusDiagnosticDialog(
            activity = activity,
            appVersion = version,
            apiKey = apiKey,
            aiProvider = provider
        ).show()
    }
}
