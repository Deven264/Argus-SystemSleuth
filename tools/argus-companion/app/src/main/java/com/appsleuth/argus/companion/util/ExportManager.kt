package com.appsleuth.argus.companion.util

import android.content.Context
import com.appsleuth.argus.companion.diagnosis.GeminiDiagnosticEngine
import com.appsleuth.argus.companion.graph.JourneyGraphBuilder
import com.appsleuth.argus.companion.model.FlightEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports captured session data to files in:
 *   /sdcard/Android/data/com.appsleuth.argus.companion/files/argus_exports/
 *
 * Accessible via USB (MTP) on any Android device without extra permissions.
 * Share these files with a developer for offline analysis.
 */
object ExportManager {

    private const val EXPORT_DIR = "argus_exports"
    private val timestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    data class ExportResult(
        val traceFile: File?,
        val graphFile: File?,
        val exportDir: String
    )

    /**
     * Exports the current flight trace and journey graph to JSON files.
     * Returns paths to the created files.
     */
    fun exportSession(
        context: Context,
        events: List<FlightEvent>,
        diagnosisResult: GeminiDiagnosticEngine.DiagnosisResult? = null
    ): ExportResult {
        val dir = File(context.getExternalFilesDir(null), EXPORT_DIR)
        if (!dir.exists()) dir.mkdirs()

        val ts = timestampFormat.format(Date())
        var traceFile: File? = null
        var graphFile: File? = null

        // 1. Export raw flight trace as JSON
        try {
            val traceArray = JSONArray()
            events.forEach { event ->
                traceArray.put(JSONObject().apply {
                    put("timestamp", event.timestamp)
                    put("type", event.type.name)
                    put("sourceApp", event.sourceApp)
                    put("label", event.label)
                    put("outcome", event.outcome.name)
                    put("host", event.host ?: JSONObject.NULL)
                    put("status", event.status ?: JSONObject.NULL)
                    put("durationMs", event.durationMs ?: JSONObject.NULL)
                })
            }
            val traceJson = JSONObject().apply {
                put("exportedAt", Date().toString())
                put("eventCount", events.size)
                put("events", traceArray)
            }
            traceFile = File(dir, "trace_$ts.json")
            traceFile.writeText(traceJson.toString(2))
            ArgusLogger.i("ExportManager", "Trace exported: ${traceFile.absolutePath}")
        } catch (e: Exception) {
            ArgusLogger.e("ExportManager", "Failed to export trace", e)
        }

        // 2. Export journey graph as JSON
        try {
            val graph = JourneyGraphBuilder.build(events)
            val screensArray = JSONArray()
            graph.screenViews.forEach { screensArray.put(it) }

            val actionsArray = JSONArray()
            graph.userActions.forEach { actionsArray.put(it) }

            val apisArray = JSONArray()
            graph.apiCalls.forEach { api ->
                apisArray.put(JSONObject().apply {
                    put("host", api.host)
                    put("method", api.method ?: JSONObject.NULL)
                    put("url", api.url ?: JSONObject.NULL)
                    put("status", api.status ?: JSONObject.NULL)
                    put("durationMs", api.durationMs ?: JSONObject.NULL)
                    put("outcome", api.outcome.name)
                    put("triggeredByAction", api.triggeredByAction ?: JSONObject.NULL)
                })
            }

            val faultsArray = JSONArray()
            graph.faultPoints.forEach { fault ->
                faultsArray.put(JSONObject().apply {
                    put("stepIndex", fault.stepIndex)
                    put("label", fault.label)
                    put("faultType", fault.faultType)
                    put("plainEnglishCause", fault.plainEnglishCause)
                    put("triggeredBy", fault.triggeredBy ?: JSONObject.NULL)
                })
            }

            val graphJson = JSONObject().apply {
                put("exportedAt", Date().toString())
                put("appName", graph.appName)
                put("totalEvents", graph.totalEvents)
                put("hasFailures", graph.hasFailures)
                put("happyPathSummary", graph.happyPathSummary)
                put("screenViews", screensArray)
                put("userActions", actionsArray)
                put("apiCalls", apisArray)
                put("faultPoints", faultsArray)

                // Include diagnosis if available
                diagnosisResult?.let { r ->
                    put("diagnosis", JSONObject().apply {
                        put("engine", r.engine)
                        put("userFacingExplanation", r.userFacingExplanation)
                        put("technicalRootCause", r.technicalRootCause)
                        put("divergedAtStep", r.divergedAtStep ?: JSONObject.NULL)
                        put("confidence", r.confidence)
                    })
                }
            }
            graphFile = File(dir, "graph_$ts.json")
            graphFile.writeText(graphJson.toString(2))
            ArgusLogger.i("ExportManager", "Graph exported: ${graphFile.absolutePath}")
        } catch (e: Exception) {
            ArgusLogger.e("ExportManager", "Failed to export graph", e)
        }

        return ExportResult(traceFile, graphFile, dir.absolutePath)
    }
}
