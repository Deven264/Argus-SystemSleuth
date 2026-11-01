package com.appsleuth.argus.sdk.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.appsleuth.argus.sdk.diagnosis.DiagnosisResult
import com.appsleuth.argus.sdk.graph.JourneyGraph
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ArgusExportHelper {

    private val timeFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun exportToJsonFile(
        context: Context,
        graph: JourneyGraph,
        diagnosis: DiagnosisResult? = null
    ): File {
        val root = graph.toJson()
        diagnosis?.let {
            root.put("diagnosis", JSONObject().apply {
                put("engine", it.engine)
                put("userFacingExplanation", it.userFacingExplanation)
                put("technicalRootCause", it.technicalRootCause)
                put("divergedAtStep", it.divergedAtStep ?: JSONObject.NULL)
                put("confidence", it.confidence)
            })
        }

        val exportDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "argus_exports")
        if (!exportDir.exists()) exportDir.mkdirs()

        val file = File(exportDir, "argus_session_${timeFormat.format(Date())}.json")
        file.writeText(root.toString(2))
        return file
    }

    fun shareSession(
        activity: Activity,
        graph: JourneyGraph,
        diagnosis: DiagnosisResult? = null
    ) {
        val file = exportToJsonFile(activity, graph, diagnosis)
        val uri = try {
            FileProvider.getUriForFile(activity, "${activity.packageName}.argus.fileprovider", file)
        } catch (_: Exception) {
            null
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "Argus Diagnostic Session - ${graph.appVersion}")
            val summaryText = buildString {
                appendLine("Argus Behavioral Diagnosis Report")
                appendLine("App Version: ${graph.appVersion}")
                appendLine("Total Events: ${graph.totalEvents}")
                diagnosis?.let {
                    appendLine("\nDiagnosis (${it.engine}):")
                    appendLine(it.userFacingExplanation)
                    appendLine("\nTechnical Root Cause:")
                    appendLine(it.technicalRootCause)
                    it.divergedAtStep?.let { step -> appendLine("\nDiverged At: $step") }
                }
            }
            putExtra(Intent.EXTRA_TEXT, summaryText)
            if (uri != null) {
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        activity.startActivity(Intent.createChooser(shareIntent, "Share Argus Diagnostic Report"))
    }
}
