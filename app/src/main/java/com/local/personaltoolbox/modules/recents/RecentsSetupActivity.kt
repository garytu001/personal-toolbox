package com.local.personaltoolbox.modules.recents

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.local.personaltoolbox.R
import com.local.personaltoolbox.ToolboxApplication
import com.local.personaltoolbox.databinding.ActivityRecentsSetupBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class RecentsSetupActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRecentsSetupBinding
    private val app get() = application as ToolboxApplication
    private val control get() = app.recentsControl

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityRecentsSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
        binding.enableControl.setOnClickListener {
            app.applicationScope.launch {
                try { app.preferences.setEnabled(RecentsControl.ID, true) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { if (!isDestroyed) binding.connectionStatus.setText(R.string.preferences_error) }
            }
        }
        binding.openDebugging.setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            catch (_: ActivityNotFoundException) { binding.connectionStatus.setText(R.string.settings_unavailable) }
        }
        binding.pairDevice.setOnClickListener {
            val port = binding.pairingPort.text?.toString()?.toIntOrNull()
            val code = binding.pairingCode.text?.toString().orEmpty()
            if (port !in 1..65535 || !Regex("[0-9]{6}").matches(code)) {
                binding.connectionStatus.setText(R.string.recents_invalid_pairing); return@setOnClickListener
            }
            binding.pairingCode.text?.clear()
            lifecycleScope.launch { control.pair(port!!, code) }
        }
        binding.connectDevice.setOnClickListener {
            val port = binding.connectionPort.text?.toString()?.toIntOrNull()
            if (port !in 1..65535) { binding.connectionStatus.setText(R.string.recents_invalid_port); return@setOnClickListener }
            lifecycleScope.launch { control.connect(port!!) }
        }
        binding.disconnectDevice.setOnClickListener { control.disconnect() }
        binding.openLicenses.setOnClickListener {
            val files = assets.list("licenses").orEmpty().sorted().toTypedArray()
            AlertDialog.Builder(this).setTitle(R.string.recents_licenses).setItems(files) { _, index ->
                val text = assets.open("licenses/${files[index]}").bufferedReader().use { it.readText() }
                AlertDialog.Builder(this).setTitle(files[index]).setMessage(text).setPositiveButton(android.R.string.ok, null).show()
            }.show()
        }
        lifecycleScope.launch {
            if (savedInstanceState == null) control.savedPort().takeIf { it in 1..65535 }?.let { binding.connectionPort.setText(it.toString()) }
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { control.state.collect { refresh() } } }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { app.preferences.settings.filterNotNull().collect { refresh() } } }
    }

    private fun refresh() {
        val enabled = app.preferences.settings.value?.isEnabled(RecentsControl.ID) == true
        val supported = Build.VERSION.SDK_INT >= 30
        val state = control.state.value
        binding.connectionStatus.text = when {
            !supported -> getString(R.string.recents_unsupported)
            !enabled -> getString(R.string.recents_enable_first)
            else -> state.message
        }
        binding.enableControl.isEnabled = supported && !enabled && !state.busy
        binding.pairDevice.isEnabled = supported && enabled && !state.busy
        binding.connectDevice.isEnabled = supported && enabled && !state.busy
        binding.disconnectDevice.isEnabled = state.ready || state.busy
    }
}
