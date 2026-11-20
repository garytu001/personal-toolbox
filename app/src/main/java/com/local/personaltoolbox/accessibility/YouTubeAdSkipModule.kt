@file:Suppress("DEPRECATION")

package com.local.personaltoolbox.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.local.personaltoolbox.accessibility.ads.AdPlaybackTarget
import com.local.personaltoolbox.accessibility.ads.AdSkipSampler
import com.local.personaltoolbox.accessibility.ads.MediaAdSkipController
import com.local.personaltoolbox.accessibility.ads.MediaAdSkipHost
import com.local.personaltoolbox.accessibility.ads.MediaControlAccess
import com.local.personaltoolbox.accessibility.ads.VideoBookmark
import com.local.personaltoolbox.accessibility.ads.YouTubeAdDetector
import com.local.personaltoolbox.accessibility.ads.YouTubeMediaControl

class YouTubeAdSkipModule : ToolboxModule("yt_ad_skip", YouTubeAdDetector.PACKAGE_NAME) {
    private var controller: MediaAdSkipController? = null

    internal override fun connect(environment: ModuleEnvironment) {
        controller?.cancel()
        val service = environment.service
        val media = YouTubeMediaControl(service)
        val host = object : MediaAdSkipHost {
            override fun isAllowed(): Boolean {
                if (environment.settings()?.isEnabled(id) != true || !MediaControlAccess.isGranted(service) ||
                    !service.getSystemService(PowerManager::class.java).isInteractive ||
                    service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
                val root = service.rootInActiveWindow ?: return false
                return try { root.packageName?.toString() == packageName && !YouTubeAdDetector.isShorts(root) }
                finally { root.recycle() }
            }
            override fun currentAd(): AdPlaybackTarget? {
                val root = service.rootInActiveWindow ?: return null
                return try { YouTubeAdDetector.find(root) } finally { root.recycle() }
            }
            override fun prepare() = media.prepare()
            override fun next(bookmark: VideoBookmark) = isAllowed() && media.next(bookmark)
            override suspend fun awaitDifferentVideo(bookmark: VideoBookmark) = media.awaitVideo(bookmark, original = false)
            override fun previous(bookmark: VideoBookmark) = isAllowed() && media.previous(bookmark)
            override suspend fun awaitOriginalVideo(bookmark: VideoBookmark) = media.awaitVideo(bookmark, original = true)
            override fun isOriginalVideo(bookmark: VideoBookmark) = media.isOriginal(bookmark)
            override fun onSkipped() { environment.recordSuccess(id) }
        }
        controller = MediaAdSkipController(environment.scope, host, AdSkipSampler()::delayMillis, SystemClock::elapsedRealtime)
    }

    internal override fun observeWindow(root: AccessibilityNodeInfo) { controller?.observe(YouTubeAdDetector.find(root)) }
    internal override fun onUserInteraction(eventType: Int) {
        controller?.onUserInteraction(scrolled = eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED)
    }
    internal override fun cancelPending() { controller?.cancel() }
    override fun matches(root: AccessibilityNodeInfo) = controller?.canSchedule() == true
    override fun act(service: AccessibilityService, root: AccessibilityNodeInfo): Boolean {
        controller?.schedule()
        return false
    }
}
