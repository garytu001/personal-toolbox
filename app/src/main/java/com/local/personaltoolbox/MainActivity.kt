package com.local.personaltoolbox

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.local.personaltoolbox.accessibility.ToolboxAccessibilityService
import com.local.personaltoolbox.data.ToolboxSettings
import com.local.personaltoolbox.databinding.ActivityMainBinding
import com.local.personaltoolbox.ui.ModuleAdapter
import com.local.personaltoolbox.update.ReleaseInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var moduleAdapter: ModuleAdapter
    private lateinit var dragHelper: ItemTouchHelper
    private val app get() = application as ToolboxApplication
    private val accessibilityManager by lazy { getSystemService(AccessibilityManager::class.java) }
    private val accessibilityListener = AccessibilityManager.AccessibilityStateChangeListener { refreshServiceStatus() }
    private var shownRelease: String? = null
    private var updateDialog: AlertDialog? = null
    private var manualCheckRequested = false
    private var dragging = false
    private var dragChanged = false
    private var pendingOrder: List<String>? = null
    private var latestSettings: ToolboxSettings? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
        shownRelease = savedInstanceState?.getString("shown_release")
        binding.versionName.text = getString(R.string.version_value, BuildConfig.VERSION_NAME)
        binding.accessibilityButton.setOnClickListener { openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        binding.batteryButton.setOnClickListener { openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        binding.checkUpdateButton.setOnClickListener { requestUpdate() }
        configureModules()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    app.preferences.settings.filterNotNull().collect { settings ->
                        latestSettings = settings
                        if (settings.moduleOrder == pendingOrder) pendingOrder = null
                        moduleAdapter.submit(settings, applyOrder = !dragging && pendingOrder == null)
                    }
                }
                launch {
                    app.updater.state.collect { state ->
                        binding.checkUpdateButton.isEnabled = !state.busy
                        binding.checkUpdateButton.contentDescription = state.status
                        if (state.release != null) {
                            manualCheckRequested = false
                            if (shownRelease != state.release.version) showUpdateDialog(state.release)
                        } else if (manualCheckRequested && !state.busy) {
                            manualCheckRequested = false
                            Toast.makeText(this@MainActivity, state.status, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        app.updater.checkOnLaunch()
    }

    private fun configureModules() {
        moduleAdapter = ModuleAdapter(
            onToggle = ::persistSwitch,
            onAction = { action -> openSettings(action.intent(this)) },
            onStartDrag = { dragHelper.startDrag(it) },
            onMoveByAction = { from, to ->
                if (moduleAdapter.move(from, to)) persistOrder(moduleAdapter.order())
            },
        )
        binding.moduleList.layoutManager = LinearLayoutManager(this)
        binding.moduleList.adapter = moduleAdapter
        binding.moduleList.itemAnimator = null
        binding.moduleList.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                if (parent.getChildAdapterPosition(view) < state.itemCount - 1) {
                    outRect.bottom = (8 * resources.displayMetrics.density).toInt()
                }
            }
        })
        dragHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun isLongPressDragEnabled() = true
            override fun isItemViewSwipeEnabled() = false
            override fun onMove(recyclerView: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val moved = moduleAdapter.move(source.bindingAdapterPosition, target.bindingAdapterPosition)
                if (moved) dragChanged = true
                return moved
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) dragging = true
            }
            override fun onChildDraw(canvas: android.graphics.Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                                     dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {
                // Default ItemTouchHelper drawing raises elevation; keep OLED cards flat even while dragging.
                viewHolder.itemView.translationX = dX
                viewHolder.itemView.translationY = dY
                viewHolder.itemView.elevation = 0f
            }
            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.elevation = 0f
                dragging = false
                if (dragChanged) {
                    dragChanged = false
                    persistOrder(moduleAdapter.order())
                }
            }
        })
        dragHelper.attachToRecyclerView(binding.moduleList)
    }

    private fun persistSwitch(id: String, enabled: Boolean) {
        moduleAdapter.setPendingToggle(id, enabled)
        app.applicationScope.launch {
            try { app.preferences.setEnabled(id, enabled) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!isDestroyed) moduleAdapter.cancelPendingToggle(id)
                Toast.makeText(applicationContext, R.string.preferences_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun persistOrder(order: List<String>) {
        pendingOrder = order
        app.applicationScope.launch {
            try { app.preferences.setModuleOrder(order) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!isDestroyed && pendingOrder == order) {
                    pendingOrder = null
                    latestSettings?.let { moduleAdapter.submit(it, applyOrder = true) }
                }
                Toast.makeText(applicationContext, R.string.preferences_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestUpdate() {
        val state = app.updater.state.value
        when {
            state.busy -> Unit
            state.installReady -> app.updater.install(this)
            state.release != null -> showUpdateDialog(state.release)
            else -> {
                manualCheckRequested = true
                app.updater.checkForUpdates()
                if (!app.updater.state.value.busy) {
                    manualCheckRequested = false
                    Toast.makeText(this, app.updater.state.value.status, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showUpdateDialog(release: ReleaseInfo) {
        if (updateDialog?.isShowing == true) return
        shownRelease = release.version
        updateDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_title, release.version))
            .setMessage(release.notes)
            .setNegativeButton(R.string.later, null)
            .setPositiveButton(R.string.update_now) { _, _ -> app.updater.download(release) }
            .show()
    }

    override fun onResume() {
        super.onResume()
        accessibilityManager.addAccessibilityStateChangeListener(accessibilityListener)
        refreshServiceStatus()
        app.updater.onActivityResumed(this)
    }
    override fun onPause() {
        app.updater.onActivityPaused(this)
        accessibilityManager.removeAccessibilityStateChangeListener(accessibilityListener)
        super.onPause()
    }
    private fun refreshServiceStatus() {
        val expected = ComponentName(this, ToolboxAccessibilityService::class.java)
        val enabled = accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val service = it.resolveInfo.serviceInfo
            ComponentName(service.packageName, service.name) == expected
        }
        binding.serviceStatus.setText(if (enabled) R.string.service_running else R.string.service_disabled)
        val color = ContextCompat.getColor(this, if (enabled) R.color.accent else R.color.text_secondary)
        binding.serviceStatus.setTextColor(color)
        ViewCompat.setBackgroundTintList(binding.statusDot, android.content.res.ColorStateList.valueOf(color))
    }
    private fun openSettings(intent: Intent) {
        try { startActivity(intent) }
        catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT).show()
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        if (dragChanged) { dragChanged = false; persistOrder(moduleAdapter.order()) }
        outState.putString("shown_release", if (updateDialog?.isShowing == true) null else shownRelease)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { updateDialog?.dismiss(); super.onDestroy() }
}
