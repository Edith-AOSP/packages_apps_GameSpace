/*
 * Copyright (C) 2026 The Edith AOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package io.chaldeaprjkt.gamespace.discord

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watches the platform media sessions and reports the most relevant one.
 *
 * GameSpace only observes: it never creates a session or controls playback. The sessions are
 * tracked together so the reported one follows whichever is actually playing, and only packages
 * on the user's list in [MediaAppRepository] reach Discord. See `docs/gamespace-discord-rpc.md`.
 */
@Singleton
class MediaSessionObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaApps: MediaAppRepository,
) {
    private val manager by lazy {
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val packageManager: PackageManager
        get() = context.packageManager

    private val controllers = LinkedHashMap<MediaSession.Token, MediaController>()
    private val lastPositions = HashMap<MediaSession.Token, Long>()
    private val callbacks = LinkedHashMap<MediaSession.Token, MediaController.Callback>()

    private var listener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var notificationJob: Job? = null
    private var resyncJob: Job? = null

    @Volatile
    private var onMediaChanged: ((MediaPresence?) -> Unit)? = null

    fun start(onChanged: (MediaPresence?) -> Unit) {
        onMediaChanged = onChanged
        if (listener != null) {
            resync()
            return
        }
        val active = MediaSessionManager.OnActiveSessionsChangedListener { activeControllers ->
            sync(activeControllers.orEmpty())
        }
        listener = active
        try {
            manager.addOnActiveSessionsChangedListener(active, null, Handler(Looper.getMainLooper()))
            resync()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to observe media sessions", e)
            onChanged(null)
        }
        startResync()
    }

    fun stop() {
        notificationJob?.cancel()
        notificationJob = null
        resyncJob?.cancel()
        resyncJob = null
        listener?.let {
            runCatching { manager.removeOnActiveSessionsChangedListener(it) }
        }
        listener = null
        detachAll()
        onMediaChanged = null
    }

    /** Re-evaluates which session should be published. */
    fun refresh() = resync()

    /**
     * The active-session listener misses some player transitions, so the list is re-read
     * periodically with a single binder call.
     */
    private fun startResync() {
        if (resyncJob != null) return
        resyncJob = scope.launch {
            while (true) {
                delay(RESYNC_INTERVAL_MS)
                resync()
            }
        }
    }

    private fun resync() {
        fastForwardStalledTracks()
        pruneDeadPackages()
        val active = runCatching { manager.getActiveSessions(null) }.getOrNull()
        if (active == null) {
            Log.w(TAG, "getActiveSessions failed; missing MEDIA_CONTENT_CONTROL?")
            emit()
            return
        }
        if (active.size != controllers.size || active.any { it.sessionToken !in controllers }) {
            Log.i(TAG, "Active media sessions: ${active.size}")
        }
        sync(active)
    }

    private fun sync(activeControllers: List<MediaController>) {
        val incoming = activeControllers.associateBy { it.sessionToken }

        // Sessions that went away.
        val removed = controllers.keys.filter { it !in incoming }
        removed.forEach(::detach)

        // New or replaced sessions.
        incoming.forEach { (token, controller) ->
            val existing = controllers[token]
            if (existing == null || existing != controller) {
                detach(token)
                attach(token, controller)
            }
        }
        emit()
    }

    /**
     * Drops the sessions of apps whose process is gone.
     *
     * A player that is force-stopped or killed keeps its session visible with the last
     * `STATE_PLAYING` snapshot, and no callback arrives for the process death, so the presence
     * would otherwise keep advertising a track that no longer plays.
     */
    private fun pruneDeadPackages() {
        val dead = controllers.filterValues { !isProcessAlive(it.packageName) }.keys.toList()
        dead.forEach { token ->
            Log.i(TAG, "Media app no longer running: ${controllers[token]?.packageName}")
            detach(token)
        }
    }

    /**
     * Stops advertising playback a killed player never ended.
     *
     * A killed process leaves a `STATE_PLAYING` snapshot behind. Music that is actually playing
     * moves the playback position between refreshes, so a position that stayed the same across
     * two refreshes is not playing any more.
     */
    private fun fastForwardStalledTracks() {
        controllers.forEach { (token, controller) ->
            val state = runCatching { controller.playbackState }.getOrNull() ?: return@forEach
            if (state.state != PlaybackState.STATE_PLAYING) {
                lastPositions.remove(token)
                return@forEach
            }
            val previous = lastPositions.put(token, state.position)
            if (previous != null && previous == state.position) {
                Log.i(TAG, "Playback stalled on ${controller.packageName}; ignoring the session")
                detach(token)
            }
        }
    }

    /**
     * Whether any process of this package is still running.
     *
     * `ActivityManager.getRunningAppProcesses()` only reports our own UID's processes, so the
     * package is matched through its owning UIDs instead: a force-stopped app disappears from the
     * list even though its media session object survives in the system.
     */
    private fun isProcessAlive(packageName: String?): Boolean {
        if (packageName == null) return false
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val uids = try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            intArrayOf(info.uid)
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
        val running = runCatching { activityManager.runningAppProcesses }.getOrNull() ?: return true
        return running.any { it.uid in uids }
    }

    private fun attach(token: MediaSession.Token, controller: MediaController) {
        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) = scheduleEmit()

            override fun onPlaybackStateChanged(state: PlaybackState?) = scheduleEmit()

            override fun onSessionDestroyed() {
                detach(token)
                emit()
            }
        }
        controllers[token] = controller
        callbacks[token] = callback
        runCatching { controller.registerCallback(callback) }
    }

    private fun detach(token: MediaSession.Token) {
        lastPositions.remove(token)
        callbacks.remove(token)?.let { callback ->
            runCatching { controllers[token]?.unregisterCallback(callback) }
        }
        controllers.remove(token)
    }

    private fun detachAll() {
        callbacks.keys.toList().forEach(::detach)
    }

    /** Metadata can arrive in bursts; coalesce them so Discord is not updated per field. */
    private fun scheduleEmit() {
        notificationJob?.cancel()
        notificationJob = scope.launch {
            delay(EMIT_DEBOUNCE_MS)
            emit()
        }
    }

    private fun emit() {
        onMediaChanged?.invoke(bestPresence())
    }

    /**
     * Preference order: a playing session, then a paused one with metadata; within the same state
     * the first active session wins, which is the platform's importance order.
     */
    private fun bestPresence(): MediaPresence? {
        val snapshots = controllers.values.mapNotNull(::snapshotOf)
        return snapshots.firstOrNull { it.playing } ?: snapshots.firstOrNull()
    }

    private fun snapshotOf(active: MediaController): MediaPresence? {
        val packageName = active.packageName ?: return null
        // Only apps the user allowed may publish; a browser, a recorder or a game holding a
        // session must not become the Discord activity.
        if (!mediaApps.isAllowed(packageName)) return null
        // Only music and podcast playback; a game session reports USAGE_GAME and must not win.
        val usage = runCatching {
            active.playbackInfo?.audioAttributes?.usage
        }.getOrNull()
        if (usage != null && usage != AudioAttributes.USAGE_MEDIA &&
            usage != AudioAttributes.USAGE_UNKNOWN
        ) {
            return null
        }

        // A session without a title is a game, a call, a screen recorder or an idle player.
        val metadata = active.metadata ?: return null
        val title = firstNonBlank(
            metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
        )?.takeIf { it.isNotBlank() } ?: return null

        val state = active.playbackState?.state ?: PlaybackState.STATE_NONE
        val playing = state == PlaybackState.STATE_PLAYING

        return MediaPresence(
            packageName = packageName,
            playerName = appLabel(packageName) ?: packageName,
            title = title,
            artist = firstNonBlank(
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
            ),
            album = firstNonBlank(
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION),
            ),
            playing = playing,
            startEpoch = startEpoch(active),
            artworkUrl = null,
        )
    }

    /**
     * Discord renders the timestamp as elapsed time, so the start is the epoch of the moment the
     * track began: wall clock now minus the playback position.
     *
     * `PlaybackState.getLastPositionUpdateTime()` is an `elapsedRealtime()` base, not an epoch, so
     * it cannot be mixed with wall clock.
     */
    private fun startEpoch(active: MediaController): Long {
        val state = active.playbackState ?: return now()
        if (state.state != PlaybackState.STATE_PLAYING) return now()
        val positionMs = state.position.takeIf { it > 0 } ?: return now()
        return now() - positionMs / 1000L
    }

    private fun appLabel(packageName: String): String? = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString().takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    fun icon(packageName: String): Drawable? = try {
        packageManager.getApplicationIcon(packageName)
    } catch (e: Exception) {
        null
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private fun now(): Long = System.currentTimeMillis() / 1000L

    private companion object {
        const val TAG = "MediaSessionObserver"
        const val EMIT_DEBOUNCE_MS = 400L
        const val RESYNC_INTERVAL_MS = 5_000L
    }
}
