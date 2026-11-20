package com.local.personaltoolbox.modules

import com.local.personaltoolbox.R
import com.local.personaltoolbox.accessibility.InstagramReelsModule
import com.local.personaltoolbox.accessibility.YouTubeShortsModule
import com.local.personaltoolbox.accessibility.YouTubeAdSkipModule
import com.local.personaltoolbox.accessibility.FacebookReelsModule
import android.provider.Settings
import com.local.personaltoolbox.accessibility.ads.MediaControlAccess

/** Single registration point for UI rows, settings defaults and service dispatch. */
object ModuleRegistry {
    val cards = listOf(
        ModuleCard("short_video", R.string.short_video_title, listOf(
            ModuleDefinition("yt_shorts", R.string.youtube_platform, createService = ::YouTubeShortsModule),
            ModuleDefinition("ig_reels", R.string.instagram_platform, createService = ::InstagramReelsModule),
            ModuleDefinition("fb_reels", R.string.facebook_platform, defaultEnabled = false, createService = ::FacebookReelsModule),
        )),
        ModuleCard("yt_ad_skip", R.string.youtube_ads_title, listOf(
            ModuleDefinition("yt_ad_skip", R.string.youtube_platform, defaultEnabled = false,
                createService = ::YouTubeAdSkipModule, countLabelRes = R.string.ads_skip_count,
                action = ModuleAction(R.string.media_control, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
                    R.string.media_control_required, MediaControlAccess::isGranted)),
        )),
    )
    val modules = cards.flatMap { it.modules }
        .also { definitions -> require(definitions.map { it.id }.distinct().size == definitions.size) }
    val ids = modules.map { it.id }
    val byId = modules.associateBy { it.id }
}
