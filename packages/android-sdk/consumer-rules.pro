# Argus SDK consumer rules
-keepclassmembers class * implements okhttp3.Interceptor { *; }
-keep class com.appsleuth.argus.sdk.model.** { *; }
