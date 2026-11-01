package com.appsleuth.argus.sdk.graph

import com.appsleuth.argus.sdk.model.Outcome
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

/**
 * Cumulative Session Journey DAG representing the real causal execution flow:
 * SCREEN_VIEW -> USER_ACTION -> API_CALL -> API_RESULT -> (CRASH)
 */
data class JourneyGraph(
    val appVersion: String,
    val totalEvents: Int,
    val screenViews: List<String>,
    val userActions: List<String>,
    val apiCalls: List<ApiCallSummary>,
    val faultPoints: List<FaultPoint>,
    val happyPathSummary: String,
    val hasFailures: Boolean,
    val rawTraceJson: String = ""
) {
    fun toJson(): JSONObject {
        val root = JSONObject()
        root.put("appVersion", appVersion)
        root.put("totalEvents", totalEvents)
        root.put("hasFailures", hasFailures)
        root.put("happyPathSummary", happyPathSummary)

        val screens = JSONArray()
        screenViews.forEach { screens.put(it) }
        root.put("screenViews", screens)

        val actions = JSONArray()
        userActions.forEach { actions.put(it) }
        root.put("userActions", actions)

        val apisArray = JSONArray()
        apiCalls.forEach { api ->
            apisArray.put(JSONObject().apply {
                put("url", api.url)
                put("method", api.method)
                put("status", api.status ?: JSONObject.NULL)
                put("durationMs", api.durationMs ?: JSONObject.NULL)
                put("outcome", api.outcome.name)
                put("triggeredBy", api.triggeredByAction ?: JSONObject.NULL)
            })
        }
        root.put("apiCalls", apisArray)

        val faultsArray = JSONArray()
        faultPoints.forEach { fault ->
            faultsArray.put(JSONObject().apply {
                put("stepIndex", fault.stepIndex)
                put("label", fault.label)
                put("faultType", fault.faultType)
                put("plainEnglishCause", fault.plainEnglishCause)
                put("triggeredBy", fault.triggeredBy ?: JSONObject.NULL)
            })
        }
        root.put("faultPoints", faultsArray)

        return root
    }
}

data class ApiCallSummary(
    val url: String,
    val method: String,
    val status: Int?,
    val durationMs: Long?,
    val outcome: Outcome,
    val triggeredByAction: String?
)

data class FaultPoint(
    val stepIndex: Int,
    val label: String,
    val faultType: String,
    val plainEnglishCause: String,
    val triggeredBy: String?
)
