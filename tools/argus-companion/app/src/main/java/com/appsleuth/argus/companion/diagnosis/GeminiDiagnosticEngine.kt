package com.appsleuth.argus.companion.diagnosis

import com.appsleuth.argus.companion.graph.JourneyGraphBuilder
import com.appsleuth.argus.companion.model.FlightEvent
import com.appsleuth.argus.companion.model.Outcome
import com.appsleuth.argus.companion.util.ArgusLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Multi-Provider AI Diagnostic Engine for Android applications.
 * Correlates the user's natural language complaint with the runtime Journey Graph
 * using Google Gemini, Anthropic Claude, or OpenAI ChatGPT/Codex.
 */
object GeminiDiagnosticEngine {

    private const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta"
    private const val ANTHROPIC_ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions"

    private val FALLBACK_GEMINI_MODELS = listOf(
        "gemini-2.5-flash",
        "gemini-2.0-flash",
        "gemini-1.5-flash-002",
        "gemini-1.5-flash"
    )

    private val CLAUDE_MODELS = listOf(
        "claude-3-7-sonnet-20250219",
        "claude-3-5-sonnet-20241022",
        "claude-3-5-haiku-20241022",
        "claude-3-sonnet-20240229"
    )

    private val OPENAI_MODELS = listOf(
        "gpt-4o",
        "gpt-4o-mini",
        "gpt-4-turbo"
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class DiagnosisResult(
        val userFacingExplanation: String,
        val technicalRootCause: String,
        val divergedAtStep: String?,
        val confidence: String,
        val engine: String
    )

    suspend fun diagnose(
        userComplaint: String,
        events: List<FlightEvent>,
        apiKey: String?,
        provider: String = "gemini"
    ): DiagnosisResult = withContext(Dispatchers.IO) {
        val graph = JourneyGraphBuilder.build(events)
        val graphContext = JourneyGraphBuilder.toPromptContext(graph)

        ArgusLogger.i("DiagEngine", "Diagnosis requested via [$provider]. Events: ${events.size}, apiKey present: ${!apiKey.isNullOrBlank()}")
        ArgusLogger.d("DiagEngine", "Graph: screens=${graph.screenViews.size}, actions=${graph.userActions.size}, apis=${graph.apiCalls.size}, faults=${graph.faultPoints.size}, app=${graph.appName}")

        if (!apiKey.isNullOrBlank()) {
            val normalizedProvider = provider.lowercase().trim()
            try {
                when {
                    normalizedProvider.contains("claude") || normalizedProvider.contains("anthropic") -> {
                        return@withContext diagnoseWithClaude(userComplaint, graphContext, graph, apiKey.trim())
                    }
                    normalizedProvider.contains("openai") || normalizedProvider.contains("chatgpt") || normalizedProvider.contains("codex") -> {
                        return@withContext diagnoseWithOpenAi(userComplaint, graphContext, graph, apiKey.trim())
                    }
                    else -> {
                        return@withContext diagnoseWithGemini(userComplaint, graphContext, graph, apiKey.trim())
                    }
                }
            } catch (e: Exception) {
                ArgusLogger.w("DiagEngine", "AI diagnosis with $provider failed: ${e.message}")
            }
        }

        ArgusLogger.w("DiagEngine", "AI provider unavailable or failed. Using heuristic fallback.")
        return@withContext heuristicDiagnosis(userComplaint, graph)
    }

    /**
     * Dynamically queries Google Generative Language API for models supporting generateContent,
     * prioritizing fast Flash models.
     */
    private fun discoverActiveModels(apiKey: String): List<String> {
        try {
            val request = Request.Builder()
                .url("$GEMINI_BASE/models?key=$apiKey")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    ArgusLogger.w("DiagEngine", "Dynamic model discovery returned HTTP ${response.code}, using fallback models")
                    return FALLBACK_GEMINI_MODELS
                }

                val body = response.body?.string() ?: return FALLBACK_GEMINI_MODELS
                val json = JSONObject(body)
                val modelsArray = json.optJSONArray("models") ?: return FALLBACK_GEMINI_MODELS
                val supported = mutableListOf<String>()

                for (i in 0 until modelsArray.length()) {
                    val m = modelsArray.optJSONObject(i) ?: continue
                    val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
                    var canGenerate = false
                    for (j in 0 until methods.length()) {
                        if (methods.optString(j) == "generateContent") {
                            canGenerate = true
                            break
                        }
                    }
                    if (canGenerate) {
                        val name = m.optString("name").removePrefix("models/")
                        supported.add(name)
                    }
                }

                if (supported.isNotEmpty()) {
                    val prioritized = mutableListOf<String>()
                    val preferences = listOf("2.5-flash", "2.0-flash", "1.5-flash", "flash")
                    for (pref in preferences) {
                        supported.filter { it.contains(pref, ignoreCase = true) }
                            .forEach { if (!prioritized.contains(it)) prioritized.add(it) }
                    }
                    supported.forEach { if (!prioritized.contains(it)) prioritized.add(it) }
                    ArgusLogger.i("DiagEngine", "Discovered models for API key: $prioritized")
                    return prioritized
                }
            }
        } catch (e: Exception) {
            ArgusLogger.w("DiagEngine", "Model discovery network error: ${e.message}")
        }
        return FALLBACK_GEMINI_MODELS
    }

    private fun diagnoseWithGemini(
        complaint: String,
        graphContext: String,
        graph: JourneyGraphBuilder.JourneyGraph,
        apiKey: String
    ): DiagnosisResult {
        val prompt = buildDiagnosticPrompt(graph.appName, complaint, graphContext)
        val modelsToTry = discoverActiveModels(apiKey)
        var lastError: Exception? = null

        ArgusLogger.d("DiagEngine", "Attempting Gemini diagnosis, candidate models: $modelsToTry")
        for (model in modelsToTry) {
            val cleanModel = model.removePrefix("models/")
            try {
                val jsonBody = JSONObject().apply {
                    put("contents", JSONArray().put(
                        JSONObject().put("parts", JSONArray().put(
                            JSONObject().put("text", prompt)
                        ))
                    ))
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.2)
                        put("maxOutputTokens", 4096)
                        put("responseMimeType", "application/json")
                    })
                }

                val request = Request.Builder()
                    .url("$GEMINI_BASE/models/$cleanModel:generateContent?key=$apiKey")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                ArgusLogger.d("DiagEngine", "Invoking Gemini model: $cleanModel")
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errBody = response.body?.string()?.take(200) ?: ""
                        throw Exception("HTTP ${response.code}: $errBody")
                    }
                    val bodyString = response.body?.string() ?: throw Exception("Empty body")
                    val root = JSONObject(bodyString)
                    val text = root.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text") ?: throw Exception("No text in candidate")

                    val cleaned = text.trim()
                        .removePrefix("```json")
                        .removePrefix("```")
                        .removeSuffix("```")
                        .trim()

                    val parsed = JSONObject(cleaned)
                    ArgusLogger.i("DiagEngine", "Gemini success with model=$cleanModel, confidence=${parsed.optString("confidence")}")
                    return DiagnosisResult(
                        userFacingExplanation = parsed.optString("userFacingExplanation", "Could not deduce explanation."),
                        technicalRootCause = parsed.optString("technicalRootCause", "Unknown technical cause."),
                        divergedAtStep = parsed.optString("divergedAtStep").takeIf { it.isNotBlank() && it != "null" },
                        confidence = parsed.optString("confidence", "HIGH"),
                        engine = "Gemini AI ($cleanModel)"
                    )
                }
            } catch (e: Exception) {
                lastError = e
                ArgusLogger.w("DiagEngine", "Model $cleanModel failed: ${e.message}")
            }
        }

        throw lastError ?: Exception("All Gemini models failed")
    }

    private fun diagnoseWithClaude(
        complaint: String,
        graphContext: String,
        graph: JourneyGraphBuilder.JourneyGraph,
        apiKey: String
    ): DiagnosisResult {
        val systemPrompt = """
You are Argus: SystemSleuth's Principal Android Reliability Engineer & Behavioral Diagnostician.
A user reported an issue with the application "${graph.appName}".
Your goal is to diagnose the root cause by cross-referencing the user's complaint with the observed session journey DAG.

DIAGNOSIS INSTRUCTIONS:
1. Pinpoint which specific user action, screen transition, or API call is the root cause.
2. Formulate a plain-English explanation (1-2 empathetic sentences) suitable for a non-technical end user.
3. Formulate a precise technical root cause (1-2 sentences) for developers, specifying endpoints, status codes, or timeouts.
4. Pinpoint the exact step in the journey where execution diverged from expected behavior.
5. If the network trace is empty or sparse, diagnose based on the user's complaint and UI flow.

Return strictly valid JSON with this EXACT structure and no markdown code fences:
{
  "userFacingExplanation": "string",
  "technicalRootCause": "string",
  "divergedAtStep": "string or null",
  "confidence": "HIGH|MEDIUM|LOW"
}
""".trimIndent()

        val userMessage = "USER COMPLAINT:\n\"$complaint\"\n\n$graphContext"
        var lastError: Exception? = null

        ArgusLogger.d("DiagEngine", "Attempting Claude diagnosis, models: $CLAUDE_MODELS")
        for (model in CLAUDE_MODELS) {
            try {
                val jsonBody = JSONObject().apply {
                    put("model", model)
                    put("max_tokens", 4096)
                    put("temperature", 0.2)
                    put("system", systemPrompt)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", userMessage)
                        })
                    })
                }

                val request = Request.Builder()
                    .url(ANTHROPIC_ENDPOINT)
                    .addHeader("x-api-key", apiKey.trim())
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("content-type", "application/json")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                ArgusLogger.d("DiagEngine", "Invoking Claude model: $model")
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val err = response.body?.string()?.take(200) ?: ""
                        throw Exception("HTTP ${response.code}: $err")
                    }

                    val body = response.body?.string() ?: throw Exception("Empty body")
                    val root = JSONObject(body)
                    val contentArr = root.optJSONArray("content")
                    val text = contentArr?.optJSONObject(0)?.optString("text")
                        ?: throw Exception("No text in Claude response")

                    val cleaned = text.trim()
                        .removePrefix("```json")
                        .removePrefix("```")
                        .removeSuffix("```")
                        .trim()

                    val parsed = JSONObject(cleaned)
                    ArgusLogger.i("DiagEngine", "Claude success with model=$model, confidence=${parsed.optString("confidence")}")
                    return DiagnosisResult(
                        userFacingExplanation = parsed.optString("userFacingExplanation", "Could not deduce explanation."),
                        technicalRootCause = parsed.optString("technicalRootCause", "Unknown technical cause."),
                        divergedAtStep = parsed.optString("divergedAtStep").takeIf { it.isNotBlank() && it != "null" },
                        confidence = parsed.optString("confidence", "HIGH"),
                        engine = "Claude AI ($model)"
                    )
                }
            } catch (e: Exception) {
                lastError = e
                ArgusLogger.w("DiagEngine", "Claude model $model failed: ${e.message}")
            }
        }

        throw lastError ?: Exception("All Claude models failed")
    }

    private fun diagnoseWithOpenAi(
        complaint: String,
        graphContext: String,
        graph: JourneyGraphBuilder.JourneyGraph,
        apiKey: String
    ): DiagnosisResult {
        val systemPrompt = """
You are Argus: SystemSleuth's Principal Android Reliability Engineer & Behavioral Diagnostician.
A user reported an issue with the application "${graph.appName}".
Your goal is to diagnose the root cause by cross-referencing the user's complaint with the observed session journey DAG.

DIAGNOSIS INSTRUCTIONS:
1. Pinpoint which specific user action, screen transition, or API call is the root cause.
2. Formulate a plain-English explanation (1-2 empathetic sentences) suitable for a non-technical end user.
3. Formulate a precise technical root cause (1-2 sentences) for developers, specifying endpoints, status codes, or timeouts.
4. Pinpoint the exact step in the journey where execution diverged from expected behavior.
5. If the network trace is empty or sparse, diagnose based on the user's complaint and UI flow.

Return strictly valid JSON with this EXACT structure:
{
  "userFacingExplanation": "string",
  "technicalRootCause": "string",
  "divergedAtStep": "string or null",
  "confidence": "HIGH|MEDIUM|LOW"
}
""".trimIndent()

        val userMessage = "USER COMPLAINT:\n\"$complaint\"\n\n$graphContext"
        var lastError: Exception? = null

        ArgusLogger.d("DiagEngine", "Attempting OpenAI diagnosis, models: $OPENAI_MODELS")
        for (model in OPENAI_MODELS) {
            try {
                val jsonBody = JSONObject().apply {
                    put("model", model)
                    put("temperature", 0.2)
                    put("max_tokens", 4096)
                    put("response_format", JSONObject().apply {
                        put("type", "json_object")
                    })
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", systemPrompt)
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", userMessage)
                        })
                    })
                }

                val request = Request.Builder()
                    .url(OPENAI_ENDPOINT)
                    .addHeader("Authorization", "Bearer ${apiKey.trim()}")
                    .addHeader("Content-Type", "application/json")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                ArgusLogger.d("DiagEngine", "Invoking OpenAI model: $model")
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val err = response.body?.string()?.take(200) ?: ""
                        throw Exception("HTTP ${response.code}: $err")
                    }

                    val body = response.body?.string() ?: throw Exception("Empty body")
                    val root = JSONObject(body)
                    val choices = root.optJSONArray("choices")
                    val text = choices?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content") ?: throw Exception("No content in OpenAI choice")

                    val cleaned = text.trim()
                        .removePrefix("```json")
                        .removePrefix("```")
                        .removeSuffix("```")
                        .trim()

                    val parsed = JSONObject(cleaned)
                    ArgusLogger.i("DiagEngine", "OpenAI success with model=$model, confidence=${parsed.optString("confidence")}")
                    return DiagnosisResult(
                        userFacingExplanation = parsed.optString("userFacingExplanation", "Could not deduce explanation."),
                        technicalRootCause = parsed.optString("technicalRootCause", "Unknown technical cause."),
                        divergedAtStep = parsed.optString("divergedAtStep").takeIf { it.isNotBlank() && it != "null" },
                        confidence = parsed.optString("confidence", "HIGH"),
                        engine = "ChatGPT ($model)"
                    )
                }
            } catch (e: Exception) {
                lastError = e
                ArgusLogger.w("DiagEngine", "OpenAI model $model failed: ${e.message}")
            }
        }

        throw lastError ?: Exception("All OpenAI models failed")
    }

    private fun buildDiagnosticPrompt(appName: String, complaint: String, graphContext: String): String {
        return """
You are Argus: SystemSleuth's Principal Android Reliability Engineer & Behavioral Diagnostician.
A user reported an issue with the application "$appName".
Your goal is to diagnose the root cause by cross-referencing the user's complaint with the observed session journey DAG.

USER COMPLAINT:
"$complaint"

$graphContext

DIAGNOSIS INSTRUCTIONS:
1. Pinpoint which specific user action, screen transition, or API call is the root cause.
2. Formulate a plain-English explanation (1-2 empathetic sentences) suitable for a non-technical end user.
3. Formulate a precise technical root cause (1-2 sentences) for developers, specifying endpoints, status codes, or timeouts.
4. Pinpoint the exact step in the journey where execution diverged from expected behavior.
5. If the network trace is empty or sparse, diagnose based on the user's complaint and UI flow.

Return strictly valid JSON with this EXACT structure:
{
  "userFacingExplanation": "string",
  "technicalRootCause": "string",
  "divergedAtStep": "string or null",
  "confidence": "HIGH|MEDIUM|LOW"
}
""".trimIndent()
    }

    private fun heuristicDiagnosis(
        complaint: String,
        graph: JourneyGraphBuilder.JourneyGraph
    ): DiagnosisResult {
        if (graph.faultPoints.isEmpty()) {
            return DiagnosisResult(
                userFacingExplanation = "No critical faults detected in the observed UI trace. If you experienced a problem, it may be due to an unhandled client state, unresponsive UI element, or network delay.",
                technicalRootCause = "Zero fault nodes or divergence points recorded across ${graph.totalEvents} flight events. Inspect client-side click dispatchers, view models, and unhandled async flows.",
                divergedAtStep = null,
                confidence = "LOW",
                engine = "Offline Heuristic Correlator"
            )
        }

        val primaryFault = graph.faultPoints.first()
        val relatedApi = graph.apiCalls.find { it.outcome != Outcome.SUCCESS }

        val (userMsg, techMsg) = when (primaryFault.faultType) {
            "TIMEOUT" -> Pair(
                "The app's server took too long to respond and timed out. This typically occurs when the backend service is experiencing high load or your connection dropped.",
                "SocketTimeoutException or handshake deadline exceeded for ${relatedApi?.host ?: "endpoint"} (duration: ${relatedApi?.durationMs ?: "N/A"}ms)."
            )
            "AUTH_REJECTED" -> Pair(
                "Your session or login token was rejected by the server. Logging out and back in usually resolves this.",
                "HTTP ${relatedApi?.status ?: 401} Unauthorized/Forbidden received on ${relatedApi?.host ?: "endpoint"} following action ${primaryFault.triggeredBy ?: "user tap"}."
            )
            "RATE_LIMITED" -> Pair(
                "Too many requests were sent in a short period and the server temporarily throttled the app. Please wait a minute and retry.",
                "HTTP 429 Too Many Requests returned by ${relatedApi?.host ?: "upstream gateway"}."
            )
            "SERVER_ERROR" -> Pair(
                "The app's server ran into an internal error while processing your request. This is an issue on the service provider's end.",
                "HTTP ${relatedApi?.status ?: 500} Internal Server Error produced by ${relatedApi?.host ?: "service"}."
            )
            else -> Pair(
                primaryFault.plainEnglishCause,
                "Observed fault divergence: ${primaryFault.label} (${relatedApi?.host ?: "unknown"})."
            )
        }

        return DiagnosisResult(
            userFacingExplanation = userMsg,
            technicalRootCause = techMsg,
            divergedAtStep = "Step ${primaryFault.stepIndex}: ${primaryFault.label}",
            confidence = "MEDIUM",
            engine = "Offline Heuristic Correlator"
        )
    }
}
