package com.skyler.musicpresence.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.skyler.musicpresence.core.prefs.AppPreferences
import com.skyler.musicpresence.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isTokenVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun setupUI() {
        // Load saved preferences
        binding.etDiscordToken.setText(AppPreferences.discordToken)
        binding.switchService.isChecked = AppPreferences.serviceEnabled
        binding.switchArtwork.isChecked = AppPreferences.showArtwork
        binding.switchButton.isChecked = AppPreferences.showButton
        binding.switchTimestamps.isChecked = AppPreferences.showTimestamps
    }

    private fun setupListeners() {
        // Toggle token visibility
        binding.btnToggleTokenVisibility.setOnClickListener {
            isTokenVisible = !isTokenVisible
            if (isTokenVisible) {
                binding.etDiscordToken.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                binding.etDiscordToken.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            binding.etDiscordToken.setSelection(binding.etDiscordToken.text.length)
        }

        // Save token
        binding.btnSaveToken.setOnClickListener {
            val token = binding.etDiscordToken.text.toString().trim()
            AppPreferences.discordToken = token
            Toast.makeText(this, "Discord token saved!", Toast.LENGTH_SHORT).show()
        }

        // Switches
        binding.switchService.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.serviceEnabled = isChecked
            val status = if (isChecked) "enabled" else "disabled"
            Toast.makeText(this, "Service $status", Toast.LENGTH_SHORT).show()
        }

        binding.switchArtwork.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.showArtwork = isChecked
        }

        binding.switchButton.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.showButton = isChecked
        }

        binding.switchTimestamps.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.showTimestamps = isChecked
        }

        // Permissions
        binding.btnGrantNotificationAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.btnIgnoreBatteryOpt.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }
    }

    private fun updatePermissionStatus() {
        val isGranted = NotificationManagerCompat.getEnabledListenerPackages(this)
            .contains(packageName)

        if (isGranted) {
            binding.btnGrantNotificationAccess.text = "✓ Notification Access Granted"
            binding.btnGrantNotificationAccess.isEnabled = false
            binding.btnGrantNotificationAccess.alpha = 0.6f
        } else {
            binding.btnGrantNotificationAccess.text = "Grant Notification Listener Permission"
            binding.btnGrantNotificationAccess.isEnabled = true
            binding.btnGrantNotificationAccess.alpha = 1.0f
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            } else {
                Toast.makeText(this, "Battery optimization already disabled!", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
