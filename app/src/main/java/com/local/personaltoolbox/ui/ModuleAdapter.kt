package com.local.personaltoolbox.ui

import android.annotation.SuppressLint
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.RecyclerView
import com.local.personaltoolbox.R
import com.local.personaltoolbox.data.ModuleState
import com.local.personaltoolbox.data.ToolboxSettings
import com.local.personaltoolbox.databinding.ItemModuleBinding
import com.local.personaltoolbox.modules.ModuleDefinition
import com.local.personaltoolbox.modules.ModuleRegistry

class ModuleAdapter(
    private val onToggle: (String, Boolean) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onMoveByAction: (Int, Int) -> Unit,
) : RecyclerView.Adapter<ModuleAdapter.Holder>() {
    private val items = ModuleRegistry.modules.toMutableList()
    private var states = emptyMap<String, ModuleState>()
    private val pendingToggles = mutableMapOf<String, Boolean>()
    init { setHasStableIds(true) }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(settings: ToolboxSettings, applyOrder: Boolean) {
        states = settings.modules
        pendingToggles.entries.removeAll { (id, enabled) -> states[id]?.enabled == enabled }
        val order = settings.moduleOrder.mapNotNull(ModuleRegistry.byId::get)
        if (applyOrder && items.map { it.id } != order.map { it.id }) {
            items.clear(); items.addAll(order); notifyDataSetChanged()
        } else notifyItemRangeChanged(0, itemCount, "state")
    }

    fun order(): List<String> = items.map { it.id }
    fun move(from: Int, to: Int): Boolean {
        if (from !in items.indices || to !in items.indices || from == to) return false
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
        return true
    }
    fun setPendingToggle(id: String, enabled: Boolean) { pendingToggles[id] = enabled; updateRow(id) }
    fun cancelPendingToggle(id: String) { pendingToggles.remove(id); updateRow(id) }
    private fun updateRow(id: String) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) notifyItemChanged(index, "state")
    }
    override fun getItemCount() = items.size
    override fun getItemId(position: Int) = ModuleRegistry.ids.indexOf(items[position].id).toLong()
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemModuleBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) { holder.bind(items[position]) }

    inner class Holder(private val binding: ItemModuleBinding) : RecyclerView.ViewHolder(binding.root) {
        @SuppressLint("ClickableViewAccessibility")
        fun bind(definition: ModuleDefinition) {
            val context = binding.root.context
            val name = context.getString(definition.nameRes)
            val state = states[definition.id]
            binding.moduleName.text = name
            val label = context.getString(R.string.module_count, state?.blockedCount ?: 0)
            binding.moduleCount.text = SpannableString(label).apply {
                val start = label.indexOfFirst(Char::isDigit)
                val end = label.indexOfLast(Char::isDigit) + 1
                if (start >= 0) setSpan(ForegroundColorSpan(ContextCompat.getColor(context, R.color.accent)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            binding.moduleSwitch.setOnCheckedChangeListener(null)
            binding.moduleSwitch.contentDescription = name
            binding.moduleSwitch.isChecked = pendingToggles[definition.id] ?: state?.enabled ?: definition.defaultEnabled
            binding.moduleSwitch.isEnabled = state != null && definition.id !in pendingToggles
            binding.moduleSwitch.setOnCheckedChangeListener { _, enabled -> onToggle(definition.id, enabled) }
            // RecyclerView receives the original DOWN before this child; ItemTouchHelper owns the drag.
            binding.dragHandle.setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(this)
                if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                false
            }
            ViewCompat.replaceAccessibilityAction(itemView, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_BACKWARD,
                context.getString(R.string.move_up)) { _, _ -> moveByAccessibility(-1) }
            ViewCompat.replaceAccessibilityAction(itemView, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD,
                context.getString(R.string.move_down)) { _, _ -> moveByAccessibility(1) }
        }
        private fun moveByAccessibility(delta: Int): Boolean {
            val from = bindingAdapterPosition
            val to = from + delta
            if (from !in items.indices || to !in items.indices) return false
            onMoveByAction(from, to)
            return true
        }
    }
}
