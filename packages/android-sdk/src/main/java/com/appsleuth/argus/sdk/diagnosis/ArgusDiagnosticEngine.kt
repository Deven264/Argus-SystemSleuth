package com.appsleuth.argus.sdk.diagnosis

import com.appsleuth.argus.sdk.diagnosis.provider.AiProvider
import com.appsleuth.argus.sdk.diagnosis.provider.ClaudeProvider
import com.appsleuth.argus.sdk.diagnosis.provider.GeminiProvider
import com.appsleuth.argus.sdk.diagnosis.provider.OpenAiProvider
import com.appsleuth.argus.sdk.graph.ArgusGraphBuilder
import com.appsleuth.argus.sdk.graph.JourneyGraph
import com.appsleuth.argus.sdk.model.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Multi-Provider AI Diagnostic Engine.
 * Supports Gemini, Anthropic Claude, and OpenAI ChatGPT/Codex with seamless offline heuristic fallback.
 */
object ArgusDiagnosticEngine {

    private val providers = mutableMapOf<String, AiProvider>(
        "gemini" to GeminiProvider(),
        "google" to GeminiProvider(),
        "claude" to ClaudeProvider(),
        "anthropic" to ClaudeProvider(),
        "openai" to OpenAiProvider(),
        "chatgpt" to OpenAiProvider(),
        "codex" to OpenAiProvider()
    )

    fun registerProvider(provider: AiProvider) {
        providers[provider.providerId.lowercase()] = provider
    }

    suspend fun diagnose(
        userComplaint: String,
        graph: JourneyGraph,
        providerId: String = "gemini",
        apiKey: String? = null
    ): DiagnosisResult = withContext(Dispatchers.IO) {
        if (!apiKey.isNullOrBlank()) {
            val normalizedKey = providerId.lowercase().trim()
            val provider = providers[normalizedKey] ?: providers["gemini"]

            if (provider != null) {
                try {
                    val context = ArgusGraphBuilder.toPromptContext(graph)
                    return@withContext provider.diagnose(userComplaint, context, graph, apiKey.trim())
                } catch (_: Exception) {
                    // Fall back to offline heuristic
                }
            }
        }

        return@withContext heuristicDiagnosis(userComplaint, graph)
    }

    private fun heuristicDiagnosis(
        complaint: String,
        graph: JourneyGraph
    ): DiagnosisResult {
        if (graph.faultPoints.isEmpty()) {
            return DiagnosisResult(
                userFacingExplanation = "All recorded operations completed without detected errors. If you observed an issue, it may be due to an unhandled client state or visual render glitch.",
                technicalRootCause = "Zero fault points logged across ${graph.totalEvents} flight events. Check client-side view logic, unhandled states, or async response parsers.",
                divergedAtStep = null,
                confidence = "LOW",
                engine = "Offline Heuristic"
            )
        }

        val primaryFault = graph.faultPoints.first()
        val relatedApi = graph.apiCalls.find { it.outcome != Outcome.SUCCESS }

        val (userMsg, techMsg) = when (primaryFault.faultType) {
            "TIMEOUT" -> Pair(
                "The server took too long to respond and timed out. This typically occurs when backend services are overloaded or your connection dropped.",
                "SocketTimeoutException exceeded for ${relatedApi?.url ?: "endpoint"} (duration: ${relatedApi?.durationMs ?: "?"}ms)."
            )
            "AUTH_REJECTED" -> Pair(
                "Your authorization token or session was rejected by the server. Please check your credentials.",
                "HTTP ${relatedApi?.status ?: 401} Unauthorized received on ${relatedApi?.url ?: "endpoint"}."
            )
            "RATE_LIMITED" -> Pair(
                "Too many requests were sent in a short time. Please wait a moment and try again.",
                "HTTP 429 Too Many Requests returned by ${relatedApi?.url ?: "gateway"}."
            )
            "SERVER_ERROR" -> Pair(
                "An internal error occurred on the application's server. This is an issue on the service side.",
                "HTTP ${relatedApi?.status ?: 500} Internal Server Error from ${relatedApi?.url ?: "service"}."
            )
            "UNCAUGHT_CRASH" -> Pair(
                "The application encountered an unexpected runtime crash: ${primaryFault.label}.",
                "Uncaught fatal exception thrown. See crash dump and stack trace in session report."
            )
            else -> Pair(
                primaryFault.plainEnglishCause,
                "Observed fault divergence: ${primaryFault.label} on ${relatedApi?.url ?: "unknown"}."
            )
        }

        return DiagnosisResult(
            userFacingExplanation = userMsg,
            technicalRootCause = techMsg,
            divergedAtStep = "Step ${primaryFault.stepIndex}: ${primaryFault.label}",
            confidence = "MEDIUM",
            engine = "Offline Heuristic"
        )
    }
}
