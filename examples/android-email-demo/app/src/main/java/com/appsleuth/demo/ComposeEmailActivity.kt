package com.appsleuth.demo

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.appsleuth.argus.sdk.Argus
import com.appsleuth.databinding.ActivityComposeEmailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException

class ComposeEmailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityComposeEmailBinding
    private val emailRepository = EmailRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityComposeEmailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Simulate a PDF attachment being pre-selected
        binding.tvAttachmentName.text = "report_Q3_2024.pdf (4.2 MB)"

        binding.btnSend.setOnClickListener {
            handleSendEmail()
        }

        binding.btnViewGraph.setOnClickListener {
            Argus.showDiagnosticDialog(this)
        }

        binding.btnShareGraph.setOnClickListener {
            Argus.showDiagnosticDialog(this)
        }
    }

    private fun handleSendEmail() {
        val recipient = binding.etRecipient.text.toString().trim()
        val subject   = binding.etSubject.text.toString().trim()

        if (recipient.isEmpty() || subject.isEmpty()) {
            Toast.makeText(this, "Please fill in recipient and subject", Toast.LENGTH_SHORT).show()
            return
        }

        // Track user action explicitly in Argus SDK
        Argus.trackAction(
            "SendEmail",
            mapOf("recipient" to recipient, "subject" to subject, "attachment" to "report_Q3_2024.pdf")
        )

        binding.cardDiagnostic.visibility = View.GONE
        binding.btnSend.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val fakeFileContent = ByteArray(1024) { it.toByte() } // 1KB dummy "PDF"

                val success = withContext(Dispatchers.IO) {
                    emailRepository.uploadAttachment("report_Q3_2024.pdf", fakeFileContent)
                }

                if (success) {
                    Toast.makeText(this@ComposeEmailActivity, "Email sent successfully!", Toast.LENGTH_SHORT).show()
                    finish()
                }

            } catch (e: SocketTimeoutException) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnSend.isEnabled = true

                    binding.tvDiagnosticDesc.text = "Attachment upload timed out. Tap below to run AI Self-Diagnosis!"
                    binding.cardDiagnostic.visibility = View.VISIBLE

                    Toast.makeText(
                        this@ComposeEmailActivity,
                        "Upload timed out! Shake device or tap View Graph to diagnose.",
                        Toast.LENGTH_LONG
                    ).show()

                    // Auto-open Argus Self-Diagnosis Dialog!
                    Argus.showDiagnosticDialog(this@ComposeEmailActivity)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnSend.isEnabled = true
                    binding.tvDiagnosticDesc.text = "Error: ${e.message}"
                    binding.cardDiagnostic.visibility = View.VISIBLE
                    Toast.makeText(this@ComposeEmailActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        emailRepository.shutdown()
    }
}
