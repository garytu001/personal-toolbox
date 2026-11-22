package com.local.personaltoolbox.modules.recents

/** Fixed targets only. The screenshot's ChatGPT card is a menu example, not a target. */
internal object RecentApps {
    const val LAUNCHER = "com.asus.launcher"
    const val YOUTUBE = "com.google.android.youtube"
    const val INSTAGRAM = "com.instagram.android"
    const val THREADS = "com.instagram.barcelona"
    const val FACEBOOK = "com.facebook.katana"
    const val DCARD = "com.sparkslab.dcardreader"
    const val AOV = "com.garena.game.kgtw"
    val labels = mapOf(YOUTUBE to "YouTube", INSTAGRAM to "Instagram", THREADS to "Threads",
        FACEBOOK to "Facebook", DCARD to "Dcard", AOV to "傳說對決")
}
