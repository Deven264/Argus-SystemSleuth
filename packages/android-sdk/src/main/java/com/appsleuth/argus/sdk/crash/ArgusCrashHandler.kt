package com.appsleuth.argus.sdk.crash

import android.content.Context
import android.util.Log
import com.appsleuth.argus.sdk.graph.ArgusGraphBuilder
import com.appsleuth.argus.sdk.model.ArgusEvent
import com.appsleuth.argus.sdk.model.EventType
import com.appsleuth.argus.sdk.model.Outcome
import com.appsleuth.argus.sdk.recorder.ArgusFlightRecorder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ArgusCrashHandler(
    private val context: Context,
    private val appVersion: String
) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    private val timeFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val exClass = throwable.javaClass.simpleName
            val exMsg = throwable.message ?: "No error message"
            val topFrame = throwable.stackTrace.firstOrNull()?.toString() ?: "unknown"

            ArgusFlightRecorder.record(
                ArgusEvent(
                    type = EventType.CRASH,
                    label = "Crash: $exClass ($exMsg)",
                    source = topFrame,
                    outcome = Outcome.FAILURE,
                    metadata = mapOf(
                        "exceptionClass" to throwable.javaClass.name,
                        "message" to exMsg,
                        "stackTrace" to throwable.stackTraceToString().take(2000),
                        "thread" to thread.name
                    )
                )
            )

            // Flush crash graph to disk immediately
            val graph = ArgusGraphBuilder.build(ArgusFlightRecorder.getSnapshot(), appVersion)
            val crashDir = File(context.filesDir, "argus_crashes")
            if (!crashDir.exists()) crashDir.mkdirs()

            val crashFile = File(crashDir, "crash_${timeFormat.format(Date())}.json")
            crashFile.writeText(graph.toJson().toString(2))
            Log.e("ArgusSDK", "Application crashed! Preserved session journey graph to: ${crashFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("ArgusSDK", "Failed to preserve crash journey: ${e.message}")
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
