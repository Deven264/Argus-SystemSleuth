package com.appsleuth.argus.companion.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.appsleuth.argus.companion.R
import com.appsleuth.argus.companion.databinding.FragmentTraceBinding
import com.appsleuth.argus.companion.recorder.FlightRecorder
import com.appsleuth.argus.companion.service.ArgusAccessibilityService

class TraceFragment : Fragment() {

    private var _binding: FragmentTraceBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: FlightEventAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTraceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = FlightEventAdapter()
        val layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvEvents.layoutManager = layoutManager
        binding.rvEvents.adapter = adapter

        FlightRecorder.events.observe(viewLifecycleOwner) { events ->
            adapter.submitList(events) {
                if (events.isNotEmpty()) {
                    binding.rvEvents.scrollToPosition(events.size - 1)
                }
            }

            binding.tvTraceCount.text = "${events.size} events captured"
            binding.tvEmpty.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
            binding.rvEvents.visibility = if (events.isEmpty()) View.GONE else View.VISIBLE
        }

        binding.btnClearTrace.setOnClickListener {
            FlightRecorder.clear()
        }

        // Toggle button opens Accessibility Settings when service is off;
        // when on, it has no direct stop action (user disables via system settings).
        binding.btnToggleMonitoring.setOnClickListener {
            val activity = requireActivity() as? MainActivity ?: return@setOnClickListener
            if (!ArgusAccessibilityService.isRunning) {
                activity.openAccessibilitySettings()
            }
            // Button state reflects accessibility service status only
            updateButtonState()
        }
    }

    override fun onResume() {
        super.onResume()
        updateButtonState()
    }

    private fun updateButtonState() {
        val isMonitoring = ArgusAccessibilityService.isRunning
        binding.btnToggleMonitoring.text = if (isMonitoring) {
            getString(R.string.btn_stop_monitoring)
        } else {
            getString(R.string.btn_start_monitoring)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
