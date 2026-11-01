package com.appsleuth.argus.sdk.diagnosis.provider

import android.util.Log
import com.appsleuth.argus.sdk.diagnosis.DiagnosisResult
import com.appsleuth.argus.sdk.graph.JourneyGraph
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Anthropic Claude Provider for Argus Diagnostic Engine.
 * Supports Claude 3.7 Sonnet, Claude 3.5 Sonnet, Claude 3.5 Haiku, etc.
 */
class ClaudeProvider(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) : AiProvider {

    override val providerId: String = "claude"

    private val anthropicEndpoint = "https://api.anthropic.com/v1/messages"
    private val candidateModels = listOf(
        "claude-3-7-sonnet-20250219",
        "claude-3-5-sonnet-20241022",
        "claude-3-5-haiku-20241022",
        "claude-3-sonnet-20240229"
    )

    override suspend fun diagnose(
        userComplaint: String,
        graphContext: String,
        graph: JourneyGraph,
        apiKey: String
    ): DiagnosisResult {
        val systemPrompt = """
You are Argus: SystemSleuth's Principal Android Reliability Engineer & Behavioral Diagnostician.
A user reported an issue with the application (version "${graph.appVersion}").
Your goal is to diagnose the root cause by cross-referencing the user's complaint with the observed session journey DAG.

DIAGNOSIS INSTRUCTIONS:
1. Pinpoint which specific user action, screen transition, or API call is the root cause.
2. Formulate a plain-English explanation (1-2 empathetic sentences) suitable for a non-technical end user.
3. Formulate a precise technical root cause (1-2 sentences) for developers, specifying endpoints, status codes, or timeouts.
4. Pinpoint the exact step in the journey where execution diverged from expected behavior.
5. If all network calls returned 200 OK or no network calls exist, diagnose potential silent failures (e.g. empty payloads, client unresponsiveness, or state drops).

Return strictly valid JSON with this EXACT structure and no markdown code fences:
{
  "userFacingExplanation": "string",
  "technicalRootCause": "string",
  "divergedAtStep": "string or null",
  "confidence": "HIGH|MEDIUM|LOW"
}
""".trimIndent()

        val userMessage = """
USER COMPLAINT:
"$userComplaint"

$graphContext
""".trimIndent()

        var lastError: Exception? = null

        for (model in candidateModels) {
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
                    .url(anthropicEndpoint)
                    .addHeader("x-api-key", apiKey.trim())
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("content-type", "application/json")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val err = response.body?.string()?.take(200) ?: ""
                        throw Exception("HTTP ${response.code}: $err")
                    }

                    val body = response.body?.string() ?: throw Exception("Empty body")
                    val root = JSONObject(body)
                    val contentArr = root.optJSONArray("content")
                    val text = contentArr?.optJSONObject(0)?.optString("text")
                        ?: throw Exception("No text in Claude response content")

                    val cleaned = text.trim()
                        .removePrefix("```json")
                        .removePrefix("```")
                        .removeSuffix("```")
                        .trim()

                    val parsed = JSONObject(cleaned)
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
                Log.w("ArgusSDK", "Claude model $model failed: ${e.message}")
            }
        }

        throw lastError ?: Exception("All Claude models failed")
    }
}
