package com.appsleuth.argus.companion

import android.app.Application
import com.appsleuth.argus.companion.util.ArgusLogger

class ArgusCompanionApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ArgusLogger.init(this)
    }
}
