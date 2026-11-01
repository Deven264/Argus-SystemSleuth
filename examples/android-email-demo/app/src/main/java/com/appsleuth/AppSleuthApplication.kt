package com.appsleuth

import android.app.Application
import com.appsleuth.argus.sdk.Argus
import com.appsleuth.argus.sdk.ArgusConfig

class AppSleuthApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Approach D: Initialize Argus In-App Self-Diagnosis SDK (zero permissions!)
        Argus.init(
            application = this,
            configuration = ArgusConfig.Builder()
                .appVersion("1.0.0")
                .enableShakeToDiagnose(true)
                .enableCrashReporting(true)
                .build()
        )
    }
}
