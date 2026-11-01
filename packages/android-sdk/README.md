# Argus Android SDK (Approach D)

> In-App Behavioral Self-Diagnosis Framework for Android Applications.  
> **Zero Permissions** · **Zero Play Protect Risks** · **Zero CLI**

---

## 🚀 Key Advantages of Approach D (SDK) vs Approach C (Companion App)

| Feature | Approach C (Companion App) | Approach D (Argus SDK) |
|---|---|---|
| **Android Permissions** | ⚠️ Accessibility Service | ✅ **Zero permissions required** |
| **Google Play Protect** | ⚠️ Can be flagged for sideloaded APK | ✅ **100% clean**, standard app code |
| **Android 13+ Restricted Settings** | ⚠️ User must manually unrestrict | ✅ **Not applicable** (no prompts) |
| **Network Telemetry** | ❌ None (or invasive VPN) | ✅ **Full HTTP/TLS telemetry** (OkHttp) |
| **Crash Telemetry** | ❌ Passive detection only | ✅ **Real-time uncaught exception dumps** |
| **Developer Trigger** | Switch between apps | ✅ **Shake-to-Diagnose or 1-line call** |

---

## 📦 Integration

### 1. Add Dependency

In your `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.appsleuth.argus:argus-android-sdk:1.0.0")
}
```

---

### 2. Initialize in `Application.onCreate()`

```kotlin
import android.app.Application
import com.appsleuth.argus.sdk.Argus
import com.appsleuth.argus.sdk.ArgusConfig

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        Argus.init(
            application = this,
            configuration = ArgusConfig.Builder()
                .appVersion("1.0.0")
                .apiKey("YOUR_GEMINI_API_KEY") // Optional — enables AI self-diagnosis
                .enableShakeToDiagnose(true)    // Shake phone anytime to diagnose!
                .enableCrashReporting(true)     // Captures unhandled crashes
                .build()
        )
    }
}
```

---

### 3. Add OkHttp Interceptor (Network Telemetry)

```kotlin
val okHttpClient = OkHttpClient.Builder()
    .addInterceptor(Argus.createOkHttpInterceptor())
    .build()
```

Every network call will now automatically be tracked, timed, and correlated with the preceding user tap!

---

### 4. Trigger In-App Self-Diagnosis

#### Option A: Shake the Phone
Shake your test device to automatically open the in-app diagnostic dialog.

#### Option B: Programmatic Button
```kotlin
binding.btnHelp.setOnClickListener {
    Argus.showDiagnosticDialog(this)
}
```

#### Option C: Headless Programmatic Diagnosis
```kotlin
lifecycleScope.launch {
    val result = Argus.diagnose("The checkout spinner timed out")
    println(result.userFacingExplanation)
    println(result.technicalRootCause)
    println(result.divergedAtStep)
}
```
