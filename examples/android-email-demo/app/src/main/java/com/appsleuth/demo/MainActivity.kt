package com.appsleuth.demo

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.appsleuth.argus.sdk.Argus
import com.appsleuth.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnComposeEmail.setOnClickListener {
            startActivity(Intent(this, ComposeEmailActivity::class.java))
        }

        binding.btnViewGraph.setOnClickListener {
            Argus.showDiagnosticDialog(this)
        }

        binding.btnShareGraph.setOnClickListener {
            Argus.showDiagnosticDialog(this)
        }
    }
}
