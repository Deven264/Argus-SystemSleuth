package com.appsleuth.argus.sdk

class ArgusConfig private constructor(
    val appVersion: String,
    val apiKey: String?,
    val aiProvider: String,
    val maxEvents: Int,
    val enableLifecycleTracking: Boolean,
    val enableFragmentTracking: Boolean,
    val enableTouchTracking: Boolean,
    val enableCrashReporting: Boolean,
    val enableShakeToDiagnose: Boolean
) {
    class Builder {
        private var appVersion: String = "1.0.0"
        private var apiKey: String? = null
        private var aiProvider: String = "gemini"
        private var maxEvents: Int = 150
        private var enableLifecycleTracking: Boolean = true
        private var enableFragmentTracking: Boolean = true
        private var enableTouchTracking: Boolean = true
        private var enableCrashReporting: Boolean = true
        private var enableShakeToDiagnose: Boolean = true

        fun appVersion(version: String) = apply { this.appVersion = version }
        fun apiKey(key: String?) = apply { this.apiKey = key }
        fun aiProvider(provider: String) = apply { this.aiProvider = provider }
        fun maxEvents(max: Int) = apply { this.maxEvents = max }
        fun enableLifecycleTracking(enable: Boolean) = apply { this.enableLifecycleTracking = enable }
        fun enableFragmentTracking(enable: Boolean) = apply { this.enableFragmentTracking = enable }
        fun enableTouchTracking(enable: Boolean) = apply { this.enableTouchTracking = enable }
        fun enableCrashReporting(enable: Boolean) = apply { this.enableCrashReporting = enable }
        fun enableShakeToDiagnose(enable: Boolean) = apply { this.enableShakeToDiagnose = enable }

        fun build() = ArgusConfig(
            appVersion = appVersion,
            apiKey = apiKey,
            aiProvider = aiProvider,
            maxEvents = maxEvents,
            enableLifecycleTracking = enableLifecycleTracking,
            enableFragmentTracking = enableFragmentTracking,
            enableTouchTracking = enableTouchTracking,
            enableCrashReporting = enableCrashReporting,
            enableShakeToDiagnose = enableShakeToDiagnose
        )
    }
}
