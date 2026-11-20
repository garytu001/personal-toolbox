package com.local.personaltoolbox.modules

import com.local.personaltoolbox.R
import com.local.personaltoolbox.accessibility.InstagramReelsModule
import com.local.personaltoolbox.accessibility.YouTubeShortsModule

/** Single registration point for UI rows, settings defaults and service dispatch. */
object ModuleRegistry {
    val modules = listOf(
        ModuleDefinition("yt_shorts", R.string.youtube_title, createService = ::YouTubeShortsModule),
        ModuleDefinition("ig_reels", R.string.instagram_title, createService = ::InstagramReelsModule),
    ).also { definitions -> require(definitions.map { it.id }.distinct().size == definitions.size) }
    val ids = modules.map { it.id }
    val byId = modules.associateBy { it.id }
}
