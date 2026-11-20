package com.local.personaltoolbox.modules

import androidx.annotation.StringRes

data class ModuleCard(val id: String, @StringRes val nameRes: Int, val modules: List<ModuleDefinition>)

/** Group existing stable module IDs without renaming preference keys or losing saved order. */
object ModuleCardOrder {
    fun resolve(order: List<String>, cards: List<ModuleCard>): List<ModuleCard> {
        val owners = cards.flatMap { card -> card.modules.map { it.id to card } }.toMap()
        return (order.mapNotNull(owners::get) + cards).distinctBy { it.id }
    }
    fun flatten(cards: List<ModuleCard>): List<String> = cards.flatMap { it.modules }.map { it.id }
}
