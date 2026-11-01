package com.appsleuth.argus.companion.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.appsleuth.argus.companion.R
import com.appsleuth.argus.companion.databinding.ActivityMainBinding
import com.appsleuth.argus.companion.recorder.FlightRecorder
import com.appsleuth.argus.companion.service.ArgusAccessibilityService
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViewPager()
        setupActiveAppObserver()
    }

    override fun onResume() {
        super.onResume()
        updateStatusBar()
    }

    private fun setupViewPager() {
        val adapter = MainPagerAdapter(this)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_trace)
                1 -> getString(R.string.tab_diagnose)
                2 -> getString(R.string.tab_settings)
                else -> ""
            }
        }.attach()
    }

    private fun setupActiveAppObserver() {
        FlightRecorder.activeApp.observe(this) { pkg ->
            if (!pkg.isNullOrBlank()) {
                val appName = try {
                    val appInfo = packageManager.getApplicationInfo(pkg, 0)
                    packageManager.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    pkg.substringAfterLast(".")
                }
                binding.tvActiveAppBadge.text = getString(R.string.monitoring_app, appName)
            } else {
                binding.tvActiveAppBadge.text = getString(R.string.monitoring_idle)
            }
        }
    }

    /** Refreshes the header status pill whenever accessibility state changes. */
    fun updateStatusBar() {
        val a11yOn = ArgusAccessibilityService.isRunning
        binding.tvAccessibilityStatus.text = getString(
            if (a11yOn) R.string.status_accessibility_on else R.string.status_accessibility_off
        )
    }

    /** Opens system Accessibility Settings so the user can enable ArgusAccessibilityService. */
    fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}
