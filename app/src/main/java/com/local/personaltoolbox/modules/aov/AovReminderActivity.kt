package com.local.personaltoolbox.modules.aov

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.local.personaltoolbox.R
import com.local.personaltoolbox.ToolboxApplication
import com.local.personaltoolbox.databinding.ActivityAovReminderBinding
import com.local.personaltoolbox.modules.recents.RecentsSetupActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class AovReminderActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAovReminderBinding
    private val app get() = application as ToolboxApplication
    private val store get() = AovReminderStore(app.preferences)
    private var busy = false
    private var openedToday = false
    private var completedToday = false
    private var notificationConfirmationPending = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationConfirmationPending = savedInstanceState?.getBoolean("notification_confirmation_pending")
            ?: intent.getBooleanExtra(AovReminder.CONFIRM_EXTRA, false)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityAovReminderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
        binding.notificationPermission.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        binding.exactPermission.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 31) openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
        binding.confirmOpen.setOnClickListener { confirmOpen() }
        binding.backgroundRemoval.setOnClickListener { startActivity(Intent(this, RecentsSetupActivity::class.java)) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                app.preferences.settings.filterNotNull().collect { refresh(); runNotificationConfirmation() }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                AovRunRequests.status.collect { status -> if (status.isNotEmpty()) binding.resultStatus.text = status }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            openedToday = store.hasOpenedToday()
            completedToday = store.hasConfirmedSuccessToday()
            refresh()
            runNotificationConfirmation()
            app.preferences.settings.value?.let { AovReminder.schedule(this@AovReminderActivity, it.isEnabled(AovReminder.ID)) }
        }
    }

    private fun runNotificationConfirmation() {
        if (notificationConfirmationPending && app.preferences.settings.value != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            notificationConfirmationPending = false
            if (binding.confirmOpen.isEnabled) confirmOpen()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("notification_confirmation_pending", notificationConfirmationPending)
        super.onSaveInstanceState(outState)
    }

    private fun refresh() {
        val enabled = app.preferences.settings.value?.isEnabled(AovReminder.ID) == true
        binding.scheduleStatus.setText(when {
            !enabled -> R.string.aov_disabled
            !AovReminder.canNotify(this) -> R.string.aov_notifications_blocked
            AovReminder.hasExactPermission(this) -> R.string.aov_schedule_exact
            else -> R.string.aov_schedule_approximate
        })
        binding.exactPermission.visibility = if (Build.VERSION.SDK_INT >= 31 && !AovReminder.hasExactPermission(this)) View.VISIBLE else View.GONE
        val stale = intent.hasExtra(AovReminder.DATE_EXTRA) && !AovReminderTime.isCurrentDay(intent.getStringExtra(AovReminder.DATE_EXTRA))
        binding.confirmOpen.isEnabled = enabled && !busy && (!openedToday || completedToday) && !stale
        binding.confirmOpen.setText(if (completedToday) R.string.aov_cleanup else R.string.aov_confirm_open)
        if (stale) binding.resultStatus.setText(R.string.aov_stale_reminder)
        else if (AovRunRequests.status.value.isNotEmpty()) binding.resultStatus.text = AovRunRequests.status.value
        else if (openedToday) binding.resultStatus.setText(R.string.aov_already_opened)
    }

    private fun confirmOpen() {
        if (busy) return
        val date = intent.getStringExtra(AovReminder.DATE_EXTRA) ?: AovReminderTime.today().toString()
        if (!AovReminderTime.isCurrentDay(date)) { refresh(); return }
        if (!app.recentsControl.isReady()) { binding.resultStatus.setText(R.string.recents_required); return }
        if (completedToday) {
            busy = true
            refresh()
            app.applicationScope.launch {
                try { removeConfirmedCard(date) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { AovRunRequests.status.value = getString(R.string.recents_remove_failed) }
                finally { busy = false; if (!isDestroyed) refresh() }
            }
            return
        }
        if (!AovRunRequests.ready) { binding.resultStatus.setText(R.string.aov_accessibility_required); return }
        val launch = packageManager.getLaunchIntentForPackage(AovReminder.GAME_PACKAGE)
        if (launch == null) { binding.resultStatus.setText(R.string.aov_game_missing); return }
        busy = true
        refresh()
        // Persist the confirmation in Application scope, even if the Activity is destroyed.
        app.applicationScope.launch {
            try {
                val claimed = store.claimOpen(date)
                if (!claimed) {
                    openedToday = store.hasOpenedToday()
                    if (store.hasConfirmedSuccessToday()) removeConfirmedCard(date)
                    if (!isDestroyed) refresh()
                    return@launch
                }
                if (isDestroyed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    store.releaseOpen(date)
                    return@launch
                }
                if (!AovRunRequests.begin(date, cleanupOnly = false)) {
                    store.releaseOpen(date)
                    if (!isDestroyed) binding.resultStatus.setText(R.string.aov_accessibility_required)
                    return@launch
                }
                try { startActivity(launch) }
                catch (_: ActivityNotFoundException) { cancelLaunch(date); return@launch }
                catch (_: SecurityException) { cancelLaunch(date); return@launch }
                openedToday = true
                AovReminder.dismiss(app)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (!isDestroyed) binding.resultStatus.setText(R.string.preferences_error) }
            finally { busy = false; if (!isDestroyed) refresh() }
        }
    }

    private suspend fun removeConfirmedCard(date: String) {
        check(store.hasConfirmedSuccessToday() && AovReminderTime.isCurrentDay(date))
        val result = app.recentsControl.remove(AovReminder.GAME_PACKAGE, foregroundOnly = false) {
            app.preferences.settings.value?.isEnabled(AovReminder.ID) == true && AovReminderTime.isCurrentDay(date)
        }
        AovRunRequests.status.value = if (result.confirmed) "今日已購買，遊戲卡片已移除或不存在"
            else getString(R.string.recents_remove_failed)
    }

    private suspend fun cancelLaunch(date: String) {
        AovRunRequests.requests.value?.let { AovRunRequests.finish(it, getString(R.string.aov_game_missing)) }
        store.releaseOpen(date)
        if (!isDestroyed) binding.resultStatus.setText(R.string.aov_game_missing)
    }

    private fun openSettings(intent: Intent) {
        try { startActivity(intent) }
        catch (_: ActivityNotFoundException) { binding.resultStatus.setText(R.string.settings_unavailable) }
    }
}
