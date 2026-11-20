package com.local.personaltoolbox.modules

object ModuleOrder {
    fun resolve(stored: String?, available: List<String>): List<String> =
        resolve(stored?.split(',').orEmpty(), available)

    fun resolve(stored: List<String>, available: List<String>): List<String> {
        val known = available.toSet()
        return (stored.map(String::trim).filter { it in known } + available).distinct()
    }

    fun encode(order: List<String>, available: List<String>): String = resolve(order, available).joinToString(",")
}
