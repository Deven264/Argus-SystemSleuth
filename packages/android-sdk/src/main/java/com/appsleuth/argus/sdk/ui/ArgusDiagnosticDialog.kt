package com.appsleuth.argus.sdk.ui

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.appsleuth.argus.sdk.R
import com.appsleuth.argus.sdk.diagnosis.ArgusDiagnosticEngine
import com.appsleuth.argus.sdk.diagnosis.DiagnosisResult
import com.appsleuth.argus.sdk.graph.ArgusGraphBuilder
import com.appsleuth.argus.sdk.graph.JourneyGraph
import com.appsleuth.argus.sdk.recorder.ArgusFlightRecorder
import com.appsleuth.argus.sdk.util.ArgusExportHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ArgusDiagnosticDialog(
    private val activity: Activity,
    private val appVersion: String,
    private val apiKey: String? = null,
    private val aiProvider: String = "gemini"
) {

    private var dialog: Dialog? = null
    private var lastResult: DiagnosisResult? = null
    private var currentGraph: JourneyGraph = ArgusGraphBuilder.build(ArgusFlightRecorder.getSnapshot(), appVersion)

    fun show() {
        if (activity.isFinishing || activity.isDestroyed) return

        val view = activity.layoutInflater.inflate(R.layout.argus_dialog_diagnostic, null)
        dialog = AlertDialog.Builder(activity)
            .setView(view)
            .create()

        currentGraph = ArgusGraphBuilder.build(ArgusFlightRecorder.getSnapshot(), appVersion)

        val tvEventsCount = view.findViewById<TextView>(R.id.tvSessionEventsCount)
        val tvSummary = view.findViewById<TextView>(R.id.tvSessionSummary)
        val tvFault = view.findViewById<TextView>(R.id.tvFaultBadge)
        val etComplaint = view.findViewById<TextInputEditText>(R.id.etUserComplaint)
        val btnDiagnose = view.findViewById<MaterialButton>(R.id.btnRunDiagnosis)
        val btnShare = view.findViewById<MaterialButton>(R.id.btnShareSession)
        val pb = view.findViewById<ProgressBar>(R.id.pbDiagnosis)
        val cardResult = view.findViewById<View>(R.id.cardDiagnosisResult)
        val tvEngine = view.findViewById<TextView>(R.id.tvEngineBadge)
        val tvExplanation = view.findViewById<TextView>(R.id.tvUserExplanation)
        val layoutTech = view.findViewById<View>(R.id.layoutTechnicalRootCause)
        val tvTech = view.findViewById<TextView>(R.id.tvTechnicalRootCause)
        val tvDiverged = view.findViewById<TextView>(R.id.tvDivergedStep)

        tvEventsCount.text = "Session Events: ${currentGraph.totalEvents}"
        tvSummary.text = currentGraph.happyPathSummary

        if (currentGraph.faultPoints.isNotEmpty()) {
            val f = currentGraph.faultPoints.first()
            tvFault.text = "⚠️ Detected Fault: ${f.label} (${f.faultType})"
            tvFault.setTextColor(activity.getColor(R.color.argus_fault))
        } else {
            tvFault.text = "✅ No network or crash faults detected."
            tvFault.setTextColor(activity.getColor(R.color.argus_success))
        }

        btnDiagnose.setOnClickListener {
            val complaint = etComplaint.text?.toString()?.trim() ?: ""
            btnDiagnose.isEnabled = false
            btnDiagnose.text = activity.getString(R.string.argus_btn_diagnosing)
            pb.visibility = View.VISIBLE
            cardResult.visibility = View.GONE

            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val result = ArgusDiagnosticEngine.diagnose(
                        userComplaint = complaint,
                        graph = currentGraph,
                        providerId = aiProvider,
                        apiKey = apiKey
                    )
                    lastResult = result

                    tvEngine.text = result.engine
                    tvExplanation.text = result.userFacingExplanation

                    if (result.technicalRootCause.isNotBlank()) {
                        layoutTech.visibility = View.VISIBLE
                        tvTech.text = result.technicalRootCause
                    } else {
                        layoutTech.visibility = View.GONE
                    }

                    if (!result.divergedAtStep.isNullOrBlank()) {
                        tvDiverged.visibility = View.VISIBLE
                        tvDiverged.text = "Diverged At: ${result.divergedAtStep}"
                    } else {
                        tvDiverged.visibility = View.GONE
                    }

                    cardResult.visibility = View.VISIBLE
                } catch (e: Exception) {
                    tvExplanation.text = "Diagnosis failed: ${e.message}"
                    layoutTech.visibility = View.GONE
                    tvDiverged.visibility = View.GONE
                    cardResult.visibility = View.VISIBLE
                } finally {
                    pb.visibility = View.GONE
                    btnDiagnose.isEnabled = true
                    btnDiagnose.text = activity.getString(R.string.argus_btn_diagnose)
                }
            }
        }

        btnShare.setOnClickListener {
            ArgusExportHelper.shareSession(activity, currentGraph, lastResult)
        }

        dialog?.show()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }
}
