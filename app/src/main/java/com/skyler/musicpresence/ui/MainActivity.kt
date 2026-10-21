package com.skyler.musicpresence.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.skyler.musicpresence.R
import com.skyler.musicpresence.core.media.AppleMediaListenerService
import com.skyler.musicpresence.core.prefs.AppPreferences
import com.skyler.musicpresence.core.runtime.PresenceRuntime
import com.skyler.musicpresence.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPreferences
    private var isTokenVisible = false

    private val notificationsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val runtimeListener: (PresenceRuntime.Snapshot) -> Unit = { snapshot ->
        runOnUiThread { render(snapshot) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)

        setupUI()
        setupListeners()
        requestNotificationPermissionIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        PresenceRuntime.addListener(runtimeListener)
        notifyService(AppleMediaListenerService.ACTION_CONFIG_CHANGED)
    }

    override fun onStop() {
        super.onStop()
        PresenceRuntime.removeListener(runtimeListener)
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun setupUI() {
        binding.etDiscordToken.setText(prefs.discordToken)
        binding.switchService.isChecked = prefs.isServiceEnabled
        binding.switchArtwork.isChecked = prefs.showArtwork
        binding.switchButton.isChecked = prefs.showButton
        binding.switchTimestamps.isChecked = prefs.showTimestamps
    }

    private fun setupListeners() {
        binding.btnToggleTokenVisibility.setOnClickListener {
            isTokenVisible = !isTokenVisible
            binding.etDiscordToken.inputType = if (isTokenVisible) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            binding.etDiscordToken.setSelection(binding.etDiscordToken.text.length)
        }

        binding.btnSaveToken.setOnClickListener {
            val token = binding.etDiscordToken.text.toString().trim()
            if (token.isBlank()) {
                toast("Token is empty")
                return@setOnClickListener
            }
            prefs.discordToken = token
            PresenceRuntime.forceReconnect(token)
            notifyService(AppleMediaListenerService.ACTION_FORCE_RECONNECT)
            toast("Token saved - connecting to Discord")
        }

        binding.btnReconnect.setOnClickListener {
            val token = binding.etDiscordToken.text.toString().trim().ifBlank { prefs.discordToken }
            if (token.isBlank()) {
                toast("Fill the token first")
                return@setOnClickListener
            }
            prefs.discordToken = token
            PresenceRuntime.forceReconnect(token)
            notifyService(AppleMediaListenerService.ACTION_FORCE_RECONNECT)
        }

        binding.switchService.setOnCheckedChangeListener { _, isChecked ->
            prefs.isServiceEnabled = isChecked
            if (isChecked) {
                PresenceRuntime.ensureConnected(prefs.discordToken, true)
            } else {
                PresenceRuntime.disconnect("Turned off in app")
            }
            notifyService(AppleMediaListenerService.ACTION_CONFIG_CHANGED)
        }

        binding.switchArtwork.setOnCheckedChangeListener { _, isChecked ->
            prefs.showArtwork = isChecked
            notifyService(AppleMediaListenerService.ACTION_CONFIG_CHANGED)
        }

        binding.switchButton.setOnCheckedChangeListener { _, isChecked ->
            prefs.showButton = isChecked
            notifyService(AppleMediaListenerService.ACTION_CONFIG_CHANGED)
        }

        binding.switchTimestamps.setOnCheckedChangeListener { _, isChecked ->
            prefs.showTimestamps = isChecked
            notifyService(AppleMediaListenerService.ACTION_CONFIG_CHANGED)
        }

        binding.btnGrantNotificationAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.btnIgnoreBatteryOpt.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }
    }

    private fun render(snapshot: PresenceRuntime.Snapshot) {
        val (title, colorRes) = when (snapshot.state) {
            PresenceRuntime.State.READY -> "Connected to Discord" to R.color.status_green
            PresenceRuntime.State.CONNECTING -> "Connecting\u2026" to R.color.status_yellow
            PresenceRuntime.State.FAILED -> "Connection failed" to R.color.status_red
            PresenceRuntime.State.OFF -> "Disconnected" to R.color.status_red
        }

        binding.tvStatusTitle.text = title
        binding.statusIndicator.setBackgroundColor(
            ContextCompat.getColor(this, colorRes)
        )

        val sub = snapshot.detail ?: when (snapshot.state) {
            PresenceRuntime.State.READY -> "Presence updates go live when Apple Music plays"
            PresenceRuntime.State.CONNECTING -> "Handshaking with Discord gateway"
            PresenceRuntime.State.FAILED -> "Check the diagnostic log below"
            PresenceRuntime.State.OFF -> "Enter token and grant notification access"
        }
        binding.tvStatusSub.text = sub

        binding.tvNowPlaying.text = snapshot.track ?: "No music currently playing"

        binding.tvLogs.text = if (snapshot.logs.isEmpty()) {
            "No events yet\u2026"
        } else {
            snapshot.logs.joinToString("\n")
        }
    }

    private fun notifyService(action: String) {
        try {
            val intent = Intent(this, AppleMediaListenerService::class.java).setAction(action)
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            // Service may not be permitted to start in the background; ignore
        }
    }

    private fun updatePermissionStatus() {
        val granted = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        if (granted) {
            binding.btnGrantNotificationAccess.text = "\u2713 Notification Access Granted"
            binding.btnGrantNotificationAccess.isEnabled = false
            binding.btnGrantNotificationAccess.alpha = 0.6f
        } else {
            binding.btnGrantNotificationAccess.text = "Grant Notification Listener Permission"
            binding.btnGrantNotificationAccess.isEnabled = true
            binding.btnGrantNotificationAccess.alpha = 1.0f
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            } else {
                toast("Battery optimization already disabled")
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
