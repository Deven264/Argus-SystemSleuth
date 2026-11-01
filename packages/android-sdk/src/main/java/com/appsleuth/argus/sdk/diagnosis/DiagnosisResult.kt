package com.appsleuth.argus.sdk.diagnosis

data class DiagnosisResult(
    val userFacingExplanation: String,
    val technicalRootCause: String,
    val divergedAtStep: String?,
    val confidence: String,
    val engine: String
)
