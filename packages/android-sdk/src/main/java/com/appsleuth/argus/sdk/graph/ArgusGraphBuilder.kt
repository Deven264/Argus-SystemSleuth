package com.appsleuth.argus.sdk.graph

import com.appsleuth.argus.sdk.model.ArgusEvent
import com.appsleuth.argus.sdk.model.EventType
import com.appsleuth.argus.sdk.model.Outcome
import org.json.JSONArray

object ArgusGraphBuilder {

    fun build(events: List<ArgusEvent>, appVersion: String = "1.0.0"): JourneyGraph {
        if (events.isEmpty()) {
            return JourneyGraph(
                appVersion = appVersion,
                totalEvents = 0,
                screenViews = emptyList(),
                userActions = emptyList(),
                apiCalls = emptyList(),
                faultPoints = emptyList(),
                happyPathSummary = "No session interactions recorded.",
                hasFailures = false
            )
        }

        val screenViews = events.filter { it.type == EventType.SCREEN_VIEW }.map { it.label }
        val userActions = events.filter { it.type == EventType.USER_ACTION }.map { it.label }
        val faultPoints = mutableListOf<FaultPoint>()
        val apiCalls = mutableListOf<ApiCallSummary>()

        var lastAction: String? = null

        events.forEachIndexed { index, event ->
            when (event.type) {
                EventType.USER_ACTION -> {
                    lastAction = event.label
                }

                EventType.API_RESULT -> {
                    val status = (event.metadata["status"] as? Number)?.toInt()
                        ?: (event.metadata["httpCode"] as? Number)?.toInt()
                    val url = event.metadata["url"] as? String ?: event.label
                    val method = event.metadata["method"] as? String ?: "GET"
                    val durationMs = event.durationMs

                    val outcome = event.outcome
                    apiCalls.add(
                        ApiCallSummary(
                            url = url,
                            method = method,
                            status = status,
                            durationMs = durationMs,
                            outcome = outcome,
                            triggeredByAction = lastAction
                        )
                    )

                    if (outcome == Outcome.TIMEOUT) {
                        faultPoints.add(
                            FaultPoint(
                                stepIndex = index + 1,
                                label = "Timeout on $method $url",
                                faultType = "TIMEOUT",
                                plainEnglishCause = "Network call to $url timed out after ${durationMs ?: 0}ms.",
                                triggeredBy = lastAction
                            )
                        )
                    } else if (outcome == Outcome.FAILURE || (status != null && status >= 400)) {
                        val faultType = when (status) {
                            401, 403 -> "AUTH_REJECTED"
                            404 -> "NOT_FOUND"
                            429 -> "RATE_LIMITED"
                            in 500..599 -> "SERVER_ERROR"
                            else -> "NETWORK_ERROR"
                        }
                        faultPoints.add(
                            FaultPoint(
                                stepIndex = index + 1,
                                label = "HTTP ${status ?: "Err"} on $method $url",
                                faultType = faultType,
                                plainEnglishCause = "Server returned HTTP ${status ?: "Error"} for $url.",
                                triggeredBy = lastAction
                            )
                        )
                    }
                }

                EventType.CRASH -> {
                    val exClass = event.metadata["exceptionClass"] as? String ?: "Exception"
                    val exMsg = event.metadata["message"] as? String ?: event.label
                    faultPoints.add(
                        FaultPoint(
                            stepIndex = index + 1,
                            label = "Crash: $exClass",
                            faultType = "UNCAUGHT_CRASH",
                            plainEnglishCause = "Application crashed: $exClass ($exMsg).",
                            triggeredBy = lastAction
                        )
                    )
                }

                else -> Unit
            }
        }

        val traceArray = JSONArray()
        events.forEach { traceArray.put(it.toJson()) }

        val summary = buildSummary(screenViews, userActions, apiCalls, faultPoints)

        return JourneyGraph(
            appVersion = appVersion,
            totalEvents = events.size,
            screenViews = screenViews,
            userActions = userActions,
            apiCalls = apiCalls,
            faultPoints = faultPoints,
            happyPathSummary = summary,
            hasFailures = faultPoints.isNotEmpty(),
            rawTraceJson = traceArray.toString(2)
        )
    }

    private fun buildSummary(
        screens: List<String>,
        actions: List<String>,
        calls: List<ApiCallSummary>,
        faults: List<FaultPoint>
    ): String = buildString {
        if (screens.isNotEmpty()) append("Screens: ${screens.take(4).joinToString(" → ")}. ")
        if (actions.isNotEmpty()) append("Actions: ${actions.take(4).joinToString(", ")}. ")
        if (calls.isNotEmpty()) {
            val okCount = calls.count { it.outcome == Outcome.SUCCESS }
            append("API Calls: $okCount/${calls.size} succeeded. ")
        }
        if (faults.isNotEmpty()) {
            append("Faults detected: ${faults.size} (primary: ${faults.first().faultType}).")
        }
    }.ifEmpty { "Session recorded without interactions." }

    fun toPromptContext(graph: JourneyGraph): String = buildString {
        appendLine("=== OBSERVED RUNTIME JOURNEY DAG ===")
        appendLine("App Version: ${graph.appVersion}")
        appendLine("Total Intercepted Events: ${graph.totalEvents}")
        appendLine()
        appendLine("SCREENS VISITED:")
        if (graph.screenViews.isEmpty()) {
            appendLine("  (None recorded)")
        } else {
            graph.screenViews.forEachIndexed { i, s -> appendLine("  ${i + 1}. $s") }
        }
        appendLine()
        appendLine("USER ACTIONS:")
        if (graph.userActions.isEmpty()) {
            appendLine("  (None recorded)")
        } else {
            graph.userActions.forEachIndexed { i, a -> appendLine("  ${i + 1}. $a") }
        }
        appendLine()
        appendLine("NETWORK CALLS & RESPONSES:")
        if (graph.apiCalls.isEmpty()) {
            appendLine("  (None recorded)")
        } else {
            graph.apiCalls.forEachIndexed { i, api ->
                val statusStr = api.status?.let { " → HTTP $it" } ?: " → [No Response/Drop]"
                val durStr = api.durationMs?.let { " (${it}ms)" } ?: ""
                val mark = when (api.outcome) {
                    Outcome.SUCCESS -> "✅"
                    Outcome.FAILURE -> "❌"
                    Outcome.TIMEOUT -> "⏱️"
                    else -> "❓"
                }
                appendLine("  ${i + 1}. $mark ${api.method} ${api.url}$statusStr$durStr")
                api.triggeredByAction?.let {
                    appendLine("     Triggered by: $it")
                }
            }
        }

        if (graph.faultPoints.isNotEmpty()) {
            appendLine()
            appendLine("⚠️ DETECTED FAULT DIVERGENCE NODES:")
            graph.faultPoints.forEach { f ->
                appendLine("  • [Step ${f.stepIndex}] ${f.label}")
                appendLine("    Type: ${f.faultType}")
                appendLine("    Cause: ${f.plainEnglishCause}")
                f.triggeredBy?.let { appendLine("    Triggered after action: $it") }
            }
        } else {
            appendLine()
            appendLine("✅ All recorded calls completed successfully (200 OK).")
        }
        appendLine("====================================")
    }
}
