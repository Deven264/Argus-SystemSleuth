package com.appsleuth.argus.companion.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.appsleuth.argus.companion.R
import com.appsleuth.argus.companion.databinding.FragmentDiagnoseBinding
import com.appsleuth.argus.companion.diagnosis.GeminiDiagnosticEngine
import com.appsleuth.argus.companion.recorder.FlightRecorder
import com.appsleuth.argus.companion.util.ArgusLogger
import com.appsleuth.argus.companion.util.ExportManager
import kotlinx.coroutines.launch

class DiagnoseFragment : Fragment() {

    private var _binding: FragmentDiagnoseBinding? = null
    private val binding get() = _binding!!

    private var lastDiagnosisResult: GeminiDiagnosticEngine.DiagnosisResult? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDiagnoseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnDiagnose.setOnClickListener {
            val complaint = binding.etComplaint.text?.toString()?.trim() ?: ""
            if (complaint.isBlank()) {
                binding.etComplaint.error = "Please describe the problem you experienced"
                return@setOnClickListener
            }

            performDiagnosis(complaint)
        }

        binding.btnExportSession.setOnClickListener {
            val events = FlightRecorder.getSnapshot()
            if (events.isEmpty()) {
                android.widget.Toast.makeText(requireContext(), "No events captured yet. Use an app first.", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                val result = ExportManager.exportSession(
                    requireContext(), events, lastDiagnosisResult
                )
                val msg = buildString {
                    append("Exported to:\n${result.exportDir}\n\n")
                    result.traceFile?.let { append("• ${it.name}\n") }
                    result.graphFile?.let { append("• ${it.name}\n") }
                    append("\nAccess via USB → Android/data/com.appsleuth.argus.companion/files/argus_exports/")
                }
                android.widget.Toast.makeText(requireContext(), "Export complete! See toast for path.", android.widget.Toast.LENGTH_LONG).show()
                binding.tvUserExplanation.text = msg
                binding.cardResult.visibility = View.VISIBLE
            } catch (e: Exception) {
                android.widget.Toast.makeText(requireContext(), "Export failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun performDiagnosis(complaint: String) {
        val prefs = requireContext().getSharedPreferences("argus_prefs", Context.MODE_PRIVATE)
        val provider = prefs.getString("selected_ai_provider", "gemini") ?: "gemini"
        val apiKey = when {
            provider.contains("claude") -> prefs.getString("claude_api_key", null)
            provider.contains("openai") || provider.contains("chatgpt") -> prefs.getString("openai_api_key", null)
            else -> prefs.getString("gemini_api_key", null)
        }

        binding.btnDiagnose.isEnabled = false
        binding.btnDiagnose.text = getString(R.string.btn_diagnosing)
        binding.progressBar.visibility = View.VISIBLE
        binding.cardResult.visibility = View.VISIBLE
        binding.tvUserExplanation.text = "Analysing captured flight events and synthesizing causal journey..."
        binding.layoutTechnicalCause.visibility = View.GONE
        binding.layoutDivergence.visibility = View.GONE

        val snapshotEvents = FlightRecorder.getSnapshot()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = GeminiDiagnosticEngine.diagnose(
                    userComplaint = complaint,
                    events = snapshotEvents,
                    apiKey = apiKey,
                    provider = provider
                )

                lastDiagnosisResult = result

                binding.tvReportEngineBadge.text = result.engine
                binding.tvUserExplanation.text = result.userFacingExplanation

                if (result.technicalRootCause.isNotBlank()) {
                    binding.layoutTechnicalCause.visibility = View.VISIBLE
                    binding.tvTechnicalCause.text = result.technicalRootCause
                }

                if (!result.divergedAtStep.isNullOrBlank()) {
                    binding.layoutDivergence.visibility = View.VISIBLE
                    binding.tvDivergedStep.text = result.divergedAtStep
                }
            } catch (e: Exception) {
                ArgusLogger.e("DiagnoseFragment", "Diagnosis failed", e)
                binding.tvUserExplanation.text = "Diagnosis encountered an unexpected issue: ${e.message}\n\nPlease verify network connection or Gemini API key."
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.btnDiagnose.isEnabled = true
                binding.btnDiagnose.text = getString(R.string.btn_diagnose)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
