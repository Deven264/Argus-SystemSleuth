package com.appsleuth.argus.sdk.network

import com.appsleuth.argus.sdk.model.ArgusEvent
import com.appsleuth.argus.sdk.model.EventType
import com.appsleuth.argus.sdk.model.Outcome
import com.appsleuth.argus.sdk.recorder.ArgusFlightRecorder
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Transparent OkHttp Interceptor that logs network latency, HTTP status codes,
 * and timeouts into ArgusFlightRecorder.
 *
 * Automatically correlates network calls with the preceding UI interaction.
 */
class ArgusOkHttpInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val path = request.url.encodedPath
        val method = request.method
        val triggeredBy = ArgusFlightRecorder.lastUserAction

        // Record API call initiation
        ArgusFlightRecorder.record(
            ArgusEvent(
                type = EventType.API_CALL,
                label = "$method $path",
                source = "OkHttp",
                outcome = Outcome.PENDING,
                metadata = mapOf(
                    "url" to url,
                    "method" to method,
                    "host" to request.url.host,
                    "triggeredBy" to triggeredBy
                )
            )
        )

        val startMs = System.currentTimeMillis()

        return try {
            val response = chain.proceed(request)
            val durationMs = System.currentTimeMillis() - startMs
            val isSuccess = response.isSuccessful
            val code = response.code

            ArgusFlightRecorder.record(
                ArgusEvent(
                    type = EventType.API_RESULT,
                    label = "$method $path [HTTP $code]",
                    source = "OkHttp",
                    outcome = if (isSuccess) Outcome.SUCCESS else Outcome.FAILURE,
                    durationMs = durationMs,
                    metadata = mapOf(
                        "url" to url,
                        "method" to method,
                        "host" to request.url.host,
                        "status" to code,
                        "durationMs" to durationMs,
                        "isSuccess" to isSuccess,
                        "triggeredBy" to triggeredBy
                    )
                )
            )

            response
        } catch (e: SocketTimeoutException) {
            val durationMs = System.currentTimeMillis() - startMs
            ArgusFlightRecorder.record(
                ArgusEvent(
                    type = EventType.API_RESULT,
                    label = "$method $path [TIMEOUT]",
                    source = "OkHttp",
                    outcome = Outcome.TIMEOUT,
                    durationMs = durationMs,
                    metadata = mapOf(
                        "url" to url,
                        "method" to method,
                        "host" to request.url.host,
                        "error" to "SocketTimeoutException",
                        "message" to (e.message ?: "Connection or read timed out"),
                        "durationMs" to durationMs,
                        "triggeredBy" to triggeredBy
                    )
                )
            )
            throw e
        } catch (e: IOException) {
            val durationMs = System.currentTimeMillis() - startMs
            ArgusFlightRecorder.record(
                ArgusEvent(
                    type = EventType.API_RESULT,
                    label = "$method $path [IO ERROR]",
                    source = "OkHttp",
                    outcome = Outcome.FAILURE,
                    durationMs = durationMs,
                    metadata = mapOf(
                        "url" to url,
                        "method" to method,
                        "host" to request.url.host,
                        "error" to e.javaClass.simpleName,
                        "message" to (e.message ?: "Network error"),
                        "durationMs" to durationMs,
                        "triggeredBy" to triggeredBy
                    )
                )
            )
            throw e
        }
    }
}
