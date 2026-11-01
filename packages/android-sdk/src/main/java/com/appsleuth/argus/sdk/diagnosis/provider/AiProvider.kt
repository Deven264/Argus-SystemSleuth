package com.appsleuth.argus.sdk.diagnosis.provider

import com.appsleuth.argus.sdk.diagnosis.DiagnosisResult
import com.appsleuth.argus.sdk.graph.JourneyGraph

interface AiProvider {
    val providerId: String
    suspend fun diagnose(
        userComplaint: String,
        graphContext: String,
        graph: JourneyGraph,
        apiKey: String
    ): DiagnosisResult
}
