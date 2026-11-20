package com.local.personaltoolbox.accessibility.ads

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.content.ComponentName
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first

/** Pin every command to YouTube's session token. Never dispatch a global media key to another app. */
internal class YouTubeMediaControl(private val context: Context) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private var bound: MediaController? = null

    private fun active(): List<MediaController> = if (!MediaControlAccess.isGranted(context)) emptyList()
        else manager.getActiveSessions(ComponentName(context, MediaControlAccess::class.java))
            .filter { it.packageName == YouTubeAdDetector.PACKAGE_NAME }

    fun prepare(): VideoBookmark? {
        val controller = active().firstOrNull {
            val state = it.playbackState
            state?.state == PlaybackState.STATE_PLAYING && supports(state, PlaybackState.ACTION_SKIP_TO_NEXT) &&
                supports(state, PlaybackState.ACTION_SKIP_TO_PREVIOUS) && videoId(it.metadata) != null
        } ?: return null
        bound = controller
        return VideoBookmark(controller.sessionToken.hashCode().toString(), videoId(controller.metadata) ?: return null)
    }

    private fun controller(bookmark: VideoBookmark): MediaController? {
        val controller = bound ?: return null
        if (controller.sessionToken.hashCode().toString() != bookmark.sessionId ||
            active().none { it.sessionToken == controller.sessionToken }) return null
        return controller
    }

    fun next(bookmark: VideoBookmark): Boolean {
        val controller = controller(bookmark) ?: return false
        if (videoId(controller.metadata) != bookmark.videoId ||
            !supports(controller.playbackState, PlaybackState.ACTION_SKIP_TO_NEXT)) return false
        controller.transportControls.skipToNext()
        return true // Accepted for dispatch; completion is checked separately using metadata callbacks.
    }

    fun previous(bookmark: VideoBookmark): Boolean {
        val controller = controller(bookmark) ?: return false
        val current = videoId(controller.metadata) ?: return false
        if (current == bookmark.videoId || !supports(controller.playbackState, PlaybackState.ACTION_SKIP_TO_PREVIOUS)) return false
        controller.transportControls.skipToPrevious()
        return true
    }

    suspend fun awaitVideo(bookmark: VideoBookmark, original: Boolean): Boolean {
        val controller = controller(bookmark) ?: return false
        return callbackFlow {
            fun emitState() {
                val identity = videoId(controller.metadata)
                trySend(identity?.let { (it == bookmark.videoId) == original } == true)
            }
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) { emitState() }
                override fun onPlaybackStateChanged(state: PlaybackState?) { emitState() }
                override fun onSessionDestroyed() { close() }
            }
            controller.registerCallback(callback, Handler(Looper.getMainLooper()))
            emitState() // Register first to cover a transition between dispatch and listener attachment.
            awaitClose { controller.unregisterCallback(callback) }
        }.first { it }
    }

    fun isOriginal(bookmark: VideoBookmark): Boolean =
        controller(bookmark)?.let { videoId(it.metadata) == bookmark.videoId } == true

    private fun supports(state: PlaybackState?, action: Long): Boolean = state != null && state.actions and action != 0L

    companion object {
        private fun videoId(metadata: MediaMetadata?): String? {
            metadata ?: return null
            metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.takeIf { it.isNotBlank() }?.let { return "id:$it" }
            metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)?.takeIf { it.isNotBlank() }?.let { return "uri:$it" }
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
            if (title.isBlank()) return null
            // Older sessions omit MEDIA_ID; matching title/artist/duration is only a fallback, not a URL guarantee.
            return "title:$title|artist:${metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()}|duration:${metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)}"
        }
    }
}
