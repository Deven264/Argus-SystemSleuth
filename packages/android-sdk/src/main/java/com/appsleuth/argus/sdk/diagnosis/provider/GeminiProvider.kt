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

class GeminiProvider(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) : AiProvider {

    override val providerId: String = "gemini"

    private val geminiBase = "https://generativelanguage.googleapis.com/v1beta"
    private val fallbackModels = listOf(
        "gemini-2.5-flash",
        "gemini-2.0-flash",
        "gemini-1.5-flash-002",
        "gemini-1.5-flash"
    )

    override suspend fun diagnose(
        userComplaint: String,
        graphContext: String,
        graph: JourneyGraph,
        apiKey: String
    ): DiagnosisResult {
        val prompt = """
You are Argus: SystemSleuth's Principal Android Reliability Engineer & Behavioral Diagnostician.
A user reported an issue with the application (version "${graph.appVersion}").
Your goal is to diagnose the root cause by cross-referencing the user's complaint with the observed session journey DAG.

USER COMPLAINT:
"$userComplaint"

$graphContext

DIAGNOSIS INSTRUCTIONS:
1. Pinpoint which specific user action, screen transition, or API call is the root cause.
2. Formulate a plain-English explanation (1-2 empathetic sentences) suitable for a non-technical end user.
3. Formulate a precise technical root cause (1-2 sentences) for developers, specifying endpoints, status codes, or timeouts.
4. Pinpoint the exact step in the journey where execution diverged from expected behavior.
5. If all network calls returned 200 OK or no network calls exist, diagnose potential silent failures (e.g. empty payloads, client unresponsiveness, or state drops).

Return strictly valid JSON with this EXACT structure:
{
  "userFacingExplanation": "string",
  "technicalRootCause": "string",
  "divergedAtStep": "string or null",
  "confidence": "HIGH|MEDIUM|LOW"
}
""".trimIndent()

        val modelsToTry = discoverActiveModels(apiKey)
        var lastError: Exception? = null

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
                    .url("$geminiBase/models/$cleanModel:generateContent?key=$apiKey")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val err = response.body?.string()?.take(200) ?: ""
                        throw Exception("HTTP ${response.code}: $err")
                    }

                    val body = response.body?.string() ?: throw Exception("Empty body")
                    val root = JSONObject(body)
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
                    return DiagnosisResult(
                        userFacingExplanation = parsed.optString("userFacingExplanation", "Could not deduce explanation."),
                        technicalRootCause = parsed.optString("technicalRootCause", "Unknown technical cause."),
                        divergedAtStep = parsed.optString("divergedAtStep").takeIf { it.isNotBlank() && it != "null" },
                        confidence = parsed.optString("confidence", "HIGH"),
                        engine = "Gemini ($cleanModel)"
                    )
                }
            } catch (e: Exception) {
                lastError = e
                Log.w("ArgusSDK", "Model $cleanModel failed: ${e.message}")
            }
        }

        throw lastError ?: Exception("All Gemini models failed")
    }

    private fun discoverActiveModels(apiKey: String): List<String> {
        try {
            val req = Request.Builder()
                .url("$geminiBase/models?key=$apiKey")
                .get()
                .build()

            httpClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return fallbackModels
                val body = res.body?.string() ?: return fallbackModels
                val json = JSONObject(body)
                val arr = json.optJSONArray("models") ?: return fallbackModels
                val supported = mutableListOf<String>()

                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
                    var canGenerate = false
                    for (j in 0 until methods.length()) {
                        if (methods.optString(j) == "generateContent") {
                            canGenerate = true
                            break
                        }
                    }
                    if (canGenerate) {
                        supported.add(m.optString("name").removePrefix("models/"))
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
                    return prioritized
                }
            }
        } catch (_: Exception) {}
        return fallbackModels
    }
}
