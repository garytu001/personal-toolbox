package com.local.personaltoolbox.modules

import com.local.personaltoolbox.R
import com.local.personaltoolbox.accessibility.InstagramReelsModule
import com.local.personaltoolbox.accessibility.YouTubeShortsModule
import com.local.personaltoolbox.accessibility.YouTubeAdSkipModule
import com.local.personaltoolbox.accessibility.FacebookReelsModule
import android.provider.Settings
import com.local.personaltoolbox.accessibility.ads.MediaControlAccess
import com.local.personaltoolbox.modules.aov.AovReminder
import com.local.personaltoolbox.modules.aov.AovReminderActivity
import com.local.personaltoolbox.modules.aov.AovAutomationModule
import android.content.Intent
import com.local.personaltoolbox.modules.usagelimit.AppUsageLimitModule
import com.local.personaltoolbox.modules.recents.RecentApps

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
        ModuleCard(AovReminder.ID, R.string.aov_title, listOf(
            ModuleDefinition(AovReminder.ID, R.string.aov_daily, defaultEnabled = false,
                createService = ::AovAutomationModule,
                countLabelRes = R.string.aov_open_count,
                action = ModuleAction(R.string.aov_settings,
                    intentFactory = { Intent(it, AovReminderActivity::class.java) }),
                initialize = AovReminder::initialize),
        )),
        ModuleCard("app_usage_limit", R.string.app_usage_limit_title, listOf(
            ModuleDefinition("yt_usage_limit", R.string.youtube_usage_limit,
                createService = { AppUsageLimitModule("yt_usage_limit", RecentApps.YOUTUBE, 20) },
                countLabelRes = R.string.usage_limit_exit_count),
            ModuleDefinition("ig_usage_limit", R.string.instagram_usage_limit,
                createService = { AppUsageLimitModule("ig_usage_limit", RecentApps.INSTAGRAM, 5) },
                countLabelRes = R.string.usage_limit_exit_count),
            ModuleDefinition("threads_usage_limit", R.string.threads_usage_limit,
                createService = { AppUsageLimitModule("threads_usage_limit", RecentApps.THREADS, 5) },
                countLabelRes = R.string.usage_limit_exit_count),
            ModuleDefinition("fb_usage_limit", R.string.facebook_usage_limit,
                createService = { AppUsageLimitModule("fb_usage_limit", RecentApps.FACEBOOK, 5) },
                countLabelRes = R.string.usage_limit_exit_count),
            ModuleDefinition("dcard_usage_limit", R.string.dcard_usage_limit,
                createService = { AppUsageLimitModule("dcard_usage_limit", RecentApps.DCARD, 5) },
                countLabelRes = R.string.usage_limit_exit_count),
        )),
    )
    val modules = cards.flatMap { it.modules }
        .also { definitions -> require(definitions.map { it.id }.distinct().size == definitions.size) }
    val ids = modules.map { it.id }
    val byId = modules.associateBy { it.id }
}
