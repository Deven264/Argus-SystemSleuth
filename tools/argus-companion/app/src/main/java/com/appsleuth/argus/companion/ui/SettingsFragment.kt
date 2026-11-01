package com.appsleuth.argus.companion.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.appsleuth.argus.companion.R
import com.appsleuth.argus.companion.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences("argus_prefs", Context.MODE_PRIVATE)
        val savedProvider = prefs.getString("selected_ai_provider", "gemini") ?: "gemini"

        // Set radio button based on saved provider
        when (savedProvider) {
            "claude" -> binding.rbProviderClaude.isChecked = true
            "openai" -> binding.rbProviderOpenAi.isChecked = true
            else -> binding.rbProviderGemini.isChecked = true
        }

        updateUiForSelectedProvider(getCurrentSelectedProvider(), prefs)

        binding.rgAiProvider.setOnCheckedChangeListener { _, _ ->
            updateUiForSelectedProvider(getCurrentSelectedProvider(), prefs)
        }

        binding.btnOpenAccessibilitySettings.setOnClickListener {
            val activity = requireActivity() as? MainActivity
            activity?.openAccessibilitySettings()
        }

        binding.btnSaveApiKey.setOnClickListener {
            val provider = getCurrentSelectedProvider()
            val key = binding.etSettingsApiKey.text?.toString()?.trim() ?: ""

            val keyPrefName = when (provider) {
                "claude" -> "claude_api_key"
                "openai" -> "openai_api_key"
                else -> "gemini_api_key"
            }

            prefs.edit()
                .putString("selected_ai_provider", provider)
                .putString(keyPrefName, key)
                .apply()

            val providerName = when (provider) {
                "claude" -> "Anthropic Claude"
                "openai" -> "OpenAI ChatGPT"
                else -> "Google Gemini"
            }

            Toast.makeText(
                requireContext(),
                if (key.isNotBlank()) "$providerName API key saved!" else "$providerName key cleared. Offline heuristic mode active.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun getCurrentSelectedProvider(): String {
        return when (binding.rgAiProvider.checkedRadioButtonId) {
            R.id.rbProviderClaude -> "claude"
            R.id.rbProviderOpenAi -> "openai"
            else -> "gemini"
        }
    }

    private fun updateUiForSelectedProvider(provider: String, prefs: android.content.SharedPreferences) {
        val (hintText, keyPrefName) = when (provider) {
            "claude" -> Pair("Anthropic Claude API Key (sk-ant-...)", "claude_api_key")
            "openai" -> Pair("OpenAI API Key (sk-...)", "openai_api_key")
            else -> Pair("Google Gemini API Key (AI Studio)", "gemini_api_key")
        }

        binding.tilApiKey.hint = hintText
        val savedKey = prefs.getString(keyPrefName, "") ?: ""
        binding.etSettingsApiKey.setText(savedKey)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
