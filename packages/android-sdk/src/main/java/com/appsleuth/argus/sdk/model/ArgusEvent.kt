package com.appsleuth.argus.sdk.model

import org.json.JSONObject
import java.util.UUID

/**
 * Fundamental runtime flight event captured by Argus Android SDK.
 */
data class ArgusEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val type: EventType,
    val label: String,
    val source: String = "",
    val outcome: Outcome = Outcome.SUCCESS,
    val durationMs: Long? = null,
    val metadata: Map<String, Any?> = emptyMap()
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("timestamp", timestamp)
        json.put("type", type.name)
        json.put("label", label)
        json.put("source", source)
        json.put("outcome", outcome.name)
        json.put("durationMs", durationMs ?: JSONObject.NULL)

        val metaJson = JSONObject()
        metadata.forEach { (k, v) ->
            metaJson.put(k, v ?: JSONObject.NULL)
        }
        json.put("metadata", metaJson)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): ArgusEvent {
            val metaMap = mutableMapOf<String, Any?>()
            val metaJson = json.optJSONObject("metadata")
            metaJson?.keys()?.forEach { key ->
                metaMap[key] = if (metaJson.isNull(key)) null else metaJson.get(key)
            }

            return ArgusEvent(
                id = json.optString("id", UUID.randomUUID().toString()),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                type = EventType.valueOf(json.optString("type", EventType.CUSTOM.name)),
                label = json.optString("label", ""),
                source = json.optString("source", ""),
                outcome = Outcome.valueOf(json.optString("outcome", Outcome.SUCCESS.name)),
                durationMs = if (json.isNull("durationMs")) null else json.optLong("durationMs"),
                metadata = metaMap
            )
        }
    }
}
