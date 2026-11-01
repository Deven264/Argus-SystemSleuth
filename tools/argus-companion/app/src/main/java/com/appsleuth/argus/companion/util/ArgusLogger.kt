package com.appsleuth.argus.companion.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent file logger for Argus Companion.
 * Writes timestamped log lines to:
 *   /sdcard/Android/data/com.appsleuth.argus.companion/files/argus_logs/argus_debug.log
 *
 * Files can be accessed by the user via USB file transfer (MTP) without any
 * extra permissions on Android 10+. Share the file with the developer for analysis.
 */
object ArgusLogger {

    private const val TAG = "ArgusLogger"
    private const val LOG_DIR = "argus_logs"
    private const val LOG_FILE = "argus_debug.log"
    private const val MAX_LOG_SIZE_BYTES = 2 * 1024 * 1024 // 2 MB — rotate when exceeded

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var logFile: File? = null

    /** Must be called once from Application.onCreate() */
    fun init(context: Context) {
        try {
            val dir = File(context.getExternalFilesDir(null), LOG_DIR)
            if (!dir.exists()) dir.mkdirs()
            logFile = File(dir, LOG_FILE)

            // Write session header
            write("INFO", "===== Argus Companion Session Started =====")
            write("INFO", "Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
            write("INFO", "Log file: ${logFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ArgusLogger: ${e.message}")
        }
    }

    fun i(tag: String, message: String) = write("INFO ", "[$tag] $message")
    fun d(tag: String, message: String) = write("DEBUG", "[$tag] $message")
    fun w(tag: String, message: String) = write("WARN ", "[$tag] $message")
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        write("ERROR", "[$tag] $message")
        throwable?.let { write("ERROR", "[$tag] ${it.stackTraceToString()}") }
    }

    fun getLogFilePath(): String? = logFile?.absolutePath

    private fun write(level: String, message: String) {
        val line = "${dateFormat.format(Date())} $level $message"
        Log.d(TAG, line) // also show in logcat

        try {
            val file = logFile ?: return
            // Rotate if too large
            if (file.exists() && file.length() > MAX_LOG_SIZE_BYTES) {
                val rotated = File(file.parent, "argus_debug_old.log")
                file.renameTo(rotated)
            }
            FileWriter(file, true).use { it.appendLine(line) }
        } catch (e: Exception) {
            Log.e(TAG, "Write failed: ${e.message}")
        }
    }
}
