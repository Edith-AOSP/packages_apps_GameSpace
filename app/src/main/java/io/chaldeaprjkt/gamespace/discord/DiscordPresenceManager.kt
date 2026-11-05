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

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.data.AppSettings
import java.util.concurrent.CopyOnWriteArraySet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Single owner of the Rich Presence payload.
 *
 * The game and the media observer both report here, and exactly one presence is published: an
 * active game first, otherwise the playing media, otherwise nothing. Presence needs
 * [DiscordContextActivity] alive, since the SDK binds the Discord service from it. Bridge
 * recovery is described in `docs/gamespace-discord-rpc.md`.
 */
@Singleton
class DiscordPresenceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettings: AppSettings,
    private val icons: DiscordIconRepository,
    private val artwork: ItunesArtworkRepository,
    private val mediaObserver: MediaSessionObserver,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class GameState(
        val packageName: String,
        val label: String,
        val startEpoch: Long,
    )

    private var game: GameState? = null
    private var media: MediaPresence? = null
    private var mediaStartEpoch = 0L
    private var mediaArtworkUrl: String? = null
    private var published: DiscordNative.Presence? = null
    private var publishJob: Job? = null
    private var finishJob: Job? = null
    private var reconnectJob: Job? = null
    private var watchJob: Job? = null
    private var mediaArtworkJob: Job? = null
    private var contextRequested = false
    private var contextRequestedAt = 0L
    private var contextReadyAt = 0L
    private var contextRestartDelay = HOST_RESTART_DELAY_MS
    private var clientReady = false
    private var lastCallOk = false
    private var pendingSend = 0
    private var failedSends = 0
    private var notifiedStatus: Status? = null
    private val statusListeners = CopyOnWriteArraySet<() -> Unit>()
    private val store = DiscordPresenceStore(context)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val networkAvailable = Channel<Unit>(Channel.CONFLATED)

    /**
     * Whether Discord can be reached at all.
     *
     * Rich presence itself travels over the local RPC bridge, but a device without a validated
     * network has nothing to publish to: retrying and rebuilding there only churns the client.
     */
    @Volatile
    private var online = true

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshNetwork()

        override fun onLost(network: Network) = refreshNetwork()

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            setOnline(capabilities.isUsable())
        }
    }

    private val resultListener = object : DiscordNative.ResultListener {
        override fun onResult(success: Boolean, error: String?) {
            // The SDK reports on its own thread; keep every state change on our scope.
            scope.launch { onPresenceResult(success, error) }
        }

        override fun onStatus(status: Int, detail: Int) {
            scope.launch { onClientStatus(status, detail) }
        }
    }

    init {
        instance = this
        online = hasInternet()
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
            .onFailure { Log.w(TAG, "Unable to watch connectivity", it) }
    }

    /**
     * A network is only usable when it actually reaches the internet. A captive portal or a
     * connected-but-unroutable Wi-Fi is not something Discord can publish over.
     */
    private fun NetworkCapabilities.isUsable(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private fun hasInternet(): Boolean = try {
        val manager = connectivity
        if (manager == null) {
            true
        } else {
            manager.getNetworkCapabilities(manager.activeNetwork)?.isUsable() ?: false
        }
    } catch (e: Exception) {
        // Missing ACCESS_NETWORK_STATE must not disable a feature that works without it.
        true
    }

    private fun refreshNetwork() = setOnline(hasInternet())

    private fun setOnline(now: Boolean) {
        if (online == now) return
        online = now
        Log.i(
            TAG,
            if (now) "Network is back; resuming presence" else "Network is gone; pausing retries",
        )
        scope.launch {
            notifyStatus()
            if (!now) return@launch
            networkAvailable.trySend(Unit)
            if (!owesPresence() || !appSettings.discordRpc) return@launch
            // Retry right away instead of making the user wait out the current backoff.
            retryNow()
        }
    }

    private suspend fun waitForNetwork() {
        while (!online) networkAvailable.receive()
    }

    /** Whether the presence pipeline can currently reach Discord. */
    enum class Status { OFF, WAITING, RUNNING }

    val status: Status
        get() = when {
            !appSettings.discordRpc -> Status.OFF
            // Nothing is owed, so there is nothing to confirm: the feature is on and idle.
            // Reporting "not running" here made the restart row look stuck whenever no game
            // or track was being published.
            !owesPresence() -> Status.RUNNING
            clientReady && lastCallOk && DiscordNative.hasContext() -> Status.RUNNING
            else -> Status.WAITING
        }

    fun addStatusListener(listener: () -> Unit) {
        statusListeners.add(listener)
    }

    fun removeStatusListener(listener: () -> Unit) {
        statusListeners.remove(listener)
    }

    /**
     * Clears a presence left behind by a previous process.
     *
     * Discord only clears "this application's activity", so the stored id is the closest thing to
     * an identifier. An id with no live session in this process is stale: the host is started so
     * the clear can reach Discord, then the presence is cleared.
     */
    fun reconcileStalePresence() {
        val storedId = store.activeId ?: return
        if (!appSettings.discordRpc) {
            store.activeId = null
            return
        }
        if (!isSupported(context)) return
        if (game != null || media != null) return
        Log.i(TAG, "Clearing stale presence $storedId")
        ensureContext()
        publish()
    }

    // region game

    fun startGame(packageName: String, startedAt: Long = 0L) {
        if (!appSettings.discordRpc) return
        if (!isSupported(context)) return

        val start = startedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
        val sameGame = game?.packageName == packageName
        // The panel's timer restarts whenever a session is registered, so the Discord timer has
        // to follow the same value instead of keeping an older one for the same package.
        val startEpoch = start / 1000L
        if (!sameGame || startEpoch != game?.startEpoch) {
            game = GameState(packageName, appLabel(packageName), startEpoch)
        }
        ensureContext()
        publish()
    }

    fun stopGame() {
        // Publish even when no game is tracked: the presence may still be showing because a
        // previous stop ran while the client was gone. The stored id tells publish() whether a
        // clear is still owed, so this cannot clear something we do not own.
        game = null
        publish()
    }

    // endregion

    // region media

    fun startMedia() {
        if (!appSettings.discordRpc || !appSettings.discordMedia) {
            Log.i(TAG, "Media presence not started: rpc=${appSettings.discordRpc} media=${appSettings.discordMedia}")
            return
        }
        if (!isSupported(context)) {
            Log.i(TAG, "Media presence not started: Discord unsupported")
            return
        }
        ensureContext()
        mediaObserver.start(::onMediaChanged)
    }

    fun stopMedia() {
        mediaObserver.stop()
        mediaArtworkJob?.cancel()
        mediaArtworkJob = null
        if (media != null) {
            media = null
            mediaArtworkUrl = null
            publish()
        }
    }

    fun restartMediaObservation() {
        stopMedia()
        startMedia()
    }

    /**
     * Re-reads the media list after the user changes it.
     *
     * The observer re-evaluates on the next refresh, so a track that is no longer allowed is
     * dropped and its presence cleared instead of staying on the profile.
     */
    fun onMediaListChanged() {
        mediaObserver.refresh()
    }

    private fun onMediaChanged(presence: MediaPresence?) {
        if (presence == null || !presence.playing) {
            if (media != null) {
                media = null
                mediaArtworkUrl = null
                mediaArtworkJob?.cancel()
                mediaArtworkJob = null
                publishSoon()
            }
            return
        }

        val previous = media
        val trackChanged = previous == null ||
            previous.packageName != presence.packageName ||
            previous.title != presence.title ||
            previous.artist != presence.artist
        // The observer derives the start from the live playback position, so refresh it on a
        // track change and on a seek (> 3s), but not on every metadata tick.
        val epochShifted = kotlin.math.abs(presence.startEpoch - mediaStartEpoch) > 3
        if (trackChanged || epochShifted) {
            mediaStartEpoch = presence.startEpoch
            if (trackChanged) mediaArtworkUrl = null
        }
        media = presence

        // Skipping tracks quickly must not spam Discord with a presence per song, so the update is
        // coalesced. The artwork lookup is restarted per track, so only the track that stays on
        // screen is ever published with art.
        publishSoon()
        resolveArtwork(presence, trackChanged)
    }

    /**
     * Pushes the pending state to Discord after a short quiet period, so a fast previous/next
     * burst produces one update at the last track instead of one per skip.
     */
    private fun publishSoon() {
        publishJob?.cancel()
        publishJob = scope.launch {
            delay(MEDIA_PUBLISH_DEBOUNCE_MS)
            publish()
        }
    }

    private fun resolveArtwork(presence: MediaPresence, trackChanged: Boolean) {
        if (mediaArtworkUrl != null) return
        if (!trackChanged && mediaArtworkJob?.isActive == true) return

        mediaArtworkJob?.cancel()
        val snapshot = presence
        mediaArtworkJob = scope.launch {
            val url = artwork.artworkFor(snapshot.artist, snapshot.title, snapshot.album)
            // Only apply the result if the same track is still the one on screen.
            if (media?.title == snapshot.title && media?.packageName == snapshot.packageName) {
                mediaArtworkUrl = url
                publish()
            }
        }
    }

    // endregion

    fun restart() {
        val packageName = game?.packageName
        val start = game?.startEpoch ?: 0L

        game = null
        published = null
        lastCallOk = false

        if (packageName != null) {
            game = GameState(
                packageName,
                appLabel(packageName),
                start.takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000L),
            )
        }
        // Show the reconnect window right away instead of leaving the row on its old verdict.
        notifyStatus()
        // Rebuilding joins the SDK callback thread, which must not happen on the caller's thread
        // when that caller is the settings UI.
        scope.launch { rebuildAndPublish() }
    }

    /** Publishes the highest-priority presence, skipping identical payloads. */
    private fun publish() {
        try {
            publishNow()
        } finally {
            notifyStatus()
        }
    }

    private fun notifyStatus() {
        val current = status
        if (current == notifiedStatus) return
        notifiedStatus = current
        statusListeners.forEach { it() }
    }

    private fun onPresenceResult(success: Boolean, error: String?) {
        pendingSend = 0
        if (success) {
            lastCallOk = true
            failedSends = 0
            reconnectJob?.cancel()
            reconnectJob = null
            notifyStatus()
            return
        }

        Log.w(TAG, "Discord rejected the presence: ${error.orEmpty().ifBlank { "no detail" }}")
        lastCallOk = false
        notifyStatus()
        awaitReady()
    }

    /**
     * Reacts to the SDK's own status changes.
     *
     * An abnormal close leaves the SDK holding a dead binder, so a rebuild is the only way back.
     * A return to [DiscordNative.STATUS_READY] is the signal that a send can go out again.
     */
    private fun onClientStatus(status: Int, detail: Int) {
        if (!owesPresence()) return
        if (status < DiscordNative.STATUS_CONNECTED &&
            detail == DiscordNative.CLOSE_ABNORMAL
        ) {
            if (!online) {
                // Discord cannot keep its side of the bridge up without a network. Rebuilding the
                // client now would only reset it again, so wait for the connection to return.
                Log.i(TAG, "Discord closed the RPC bridge while offline; waiting for a network")
                lastCallOk = false
                notifyStatus()
                return
            }
            Log.i(TAG, "Discord closed the RPC bridge; rebuilding")
            if (!appSettings.discordRpc) return
            lastCallOk = false
            rebuildAndPublish()
            return
        }
        if (status == DiscordNative.STATUS_READY && !lastCallOk) {
            // The SDK reconnected by itself after a transient drop; the client is usable again, so
            // the pending presence goes out without waiting for the watchdog.
            Log.i(TAG, "Discord bridge is ready again; re-sending")
            reconnectJob?.cancel()
            reconnectJob = null
            retryNow()
        }
    }

    /** Sends the pending presence immediately, bypassing the identical-payload skip. */
    private fun retryNow() {
        published = null
        lastCallOk = false
        publish()
    }

    /**
     * Treats a send that gets no result as lost.
     *
     * The SDK may accept an update while the bridge is still coming up and never call back, which
     * would leave the presence silently missing.
     */
    private fun awaitResult(token: Int) {
        scope.launch {
            delay(RESULT_TIMEOUT_MS)
            if (pendingSend != token) return@launch
            failedSends++
            Log.w(TAG, "Discord did not acknowledge the presence update (strike $failedSends)")
            lastCallOk = false
            notifyStatus()
            if (failedSends >= MAX_FAILED_SENDS && DiscordNative.hasContext()) {
                // A live host that stops acknowledging sends has a client bound to a dead
                // binder; only a fresh client re-binds. This is the same reset the settings
                // restart performs, so presence comes back without the user doing it.
                Log.i(TAG, "Replacing a client that stopped acknowledging updates")
                failedSends = 0
                rebuildAndPublish()
                return@launch
            }
            awaitReady()
        }
    }

    /**
     * Retries the current presence until the SDK confirms delivery.
     *
     * The send is repeated on the same client; a missing host is reopened first, and the client
     * itself is only replaced once a send fails outright.
     */
    private fun awaitReady() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var attempt = 0
            logBridge()
            while (owesPresence()) {
                if (!online) {
                    Log.i(TAG, "Offline; waiting for a network before retrying the presence")
                    waitForNetwork()
                    attempt = 0
                    continue
                }
                delay(RECONNECT_DELAYS[minOf(attempt, RECONNECT_DELAYS.lastIndex)])
                // The presence may have been cleared while this attempt was waiting out its
                // backoff; a cleared presence must not reopen a host that was just finished.
                if (!owesPresence()) return@launch
                attempt++
                logAttempt(attempt)
                if (!DiscordNative.hasContext()) {
                    Log.i(TAG, "Host is gone (attempt $attempt); reopening it")
                    contextRequested = false
                    ensureContext()
                    retryNow()
                    delay(RESULT_WAIT_MS)
                    if (lastCallOk) return@launch
                    continue
                }
                // Rich presence goes out over the Discord app's RPC bridge without the social
                // gateway, so the client stays Disconnected while updates succeed; a failed send
                // is retried on the same client, and the watchdog rebuilds it only when that
                // client's sends themselves stop working.
                Log.i(TAG, "Re-sending the presence (attempt $attempt)")
                // published is what suppresses an identical payload, so drop it to let the retry
                // out even though the presence itself did not change.
                published = null
                lastCallOk = false
                publish()
                delay(RESULT_WAIT_MS)
                if (lastCallOk) return@launch
            }
        }
    }

    /**
     * Watches the bridge while a presence is owed.
     *
     * The SDK does not report every dead link, so the presence is re-asserted periodically. A
     * missing callback is not proof of a broken client, so the watchdog re-sends and rebuilds only
     * once the bridge's own status says it is not connected.
     */
    private fun ensureWatch() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            var strikes = 0
            while (owesPresence()) {
                delay(WATCH_DELAYS[minOf(strikes, WATCH_DELAYS.lastIndex)])
                // Idle teardown clears the presence and finishes the host; the watchdog must not
                // undo that by waking up and asking for a new one.
                if (!owesPresence()) return@launch
                if (!appSettings.discordRpc) continue
                if (!online) {
                    // Nothing can be re-asserted while offline; the presence is re-sent as soon as
                    // a network comes back. Sleeping long also keeps the host alive cheaply.
                    strikes = 0
                    delay(WATCH_DELAYS.last())
                    continue
                }
                // A confirmed update still needs a periodic re-assert: the result callback is the
                // only way to notice a bridge that died quietly, and a confirmed bridge cannot be
                // probed without sending the same payload again.
                if (lastCallOk && DiscordNative.hasContext() && scanBridge()) {
                    strikes = 0
                    continue
                }
                strikes++
                if (!DiscordNative.hasContext()) {
                    Log.i(TAG, "Presence is unconfirmed and the host is gone (strike $strikes)")
                    // The client is bound to the host Activity; a fresh host is enough to make the
                    // next send work, and rebuilding the client would discard a healthy bridge.
                    lastCallOk = false
                    contextRequested = false
                    ensureContext()
                    retryNow()
                    continue
                }
                Log.i(
                    TAG,
                    "Presence is unconfirmed, host alive, status=${DiscordNative.status()} " +
                        "(strike $strikes); re-sending",
                )
                retryNow()
            }
        }
    }

    /**
     * Sends the last published payload as a probe.
     *
     * A `false` means the client is gone or the host Activity is dead, so a rebuild is due. A
     * `true` only means queued; the result callback decides whether the bridge took it.
     */
    private fun scanBridge(): Boolean {
        val current = published
        return current == null || DiscordNative.setPresence(current)
    }

    /**
     * Drops the native client, makes sure a live host Activity exists and publishes again.
     *
     * Only a fresh client retries the RPC bind, so a payload retry alone cannot recover a client
     * whose connection already failed.
     */
    private fun rebuildAndPublish() {
        reconnectJob?.cancel()
        reconnectJob = null
        // A replaced client must not leave its jobs writing state for it.
        publishJob?.cancel()
        publishJob = null
        logBridge()
        DiscordNative.reset()
        clientReady = false
        lastCallOk = false
        published = null
        pendingSend = 0
        failedSends = 0
        // The host may have been destroyed while this request was still marked recent, so a
        // rebuild always asks for a new one instead of trusting that guard.
        contextRequested = false
        ensureContext()
        publish()
    }

    /** Whether Discord may still be showing something of ours, or should show something. */
    private fun owesPresence(): Boolean =
        game != null || media != null || published != null || store.activeId != null

    private fun discordVersion(): String = try {
        context.packageManager.getPackageInfo(DISCORD_PACKAGE, 0).versionName ?: "?"
    } catch (e: Exception) {
        "missing"
    }

    private fun bridgeState(): String =
        "host=" + DiscordNative.hasContext() + " client=" + clientReady +
            " status=" + DiscordNative.status()

    private fun logAttempt(attempt: Int) {
        Log.i(TAG, "Presence retry " + attempt + ": " + bridgeState())
    }

    private fun logBridge() {
        Log.i(TAG, "Bridge: " + bridgeState() + " discord=" + discordVersion())
    }

    private fun publishNow() {
        val gameState = game
        val mediaState = media

        if (!owesPresence()) {
            // Nothing is showing and nothing is wanted, so a host that exists is only keeping the
            // SDK bridge open for no reason.
            finishContextIfIdle()
            return
        }

        val presence = when {
            gameState != null -> DiscordNative.Presence(
                type = DiscordNative.TYPE_PLAYING,
                // Discord renders "Playing <name>"; naming the game here keeps the header on the
                // game instead of on the Discord application that publishes it.
                activityName = gameState.label,
                // Second line of the card: what the game is running on. State carries the
                // GameSpace marker so a glance at the card says where the presence came from.
                details = "on " + deviceModel(),
                state = GAMESPACE_MARKER,
                largeImage = null,
                largeText = null,
                smallImage = null,
                startEpoch = gameState.startEpoch,
            )

            mediaState != null && appSettings.discordMedia -> DiscordNative.Presence(
                type = DiscordNative.TYPE_LISTENING,
                activityName = mediaState.playerName,
                details = mediaState.title,
                state = mediaState.artist.orEmpty(),
                largeImage = mediaArtworkUrl ?: mediaState.playerName,
                largeText = mediaState.album,
                smallImage = if (mediaArtworkUrl == null) mediaState.playerName else null,
                startEpoch = mediaStartEpoch,
            )

            else -> null
        }

        if (!owesPresence()) return
        ensureWatch()
        if (!DiscordNative.hasContext()) {
            ensureContext()
            return
        }
        if (!ensureClient()) {
            lastCallOk = false
            return
        }
        if (presence == null) {
            // A clear is only needed (and only possible) when Discord may still be showing
            // something, either from this process or from a previous one that died mid-session.
            val maybeShowing = published != null || store.activeId != null
            if (maybeShowing) {
                // The SDK can only reach Discord from a live host Activity. If the host is gone,
                // ask for it and let onContextReady call back into publish() to finish the clear
                // instead of dropping it.
                if (!DiscordNative.hasContext()) {
                    ensureContext()
                    return
                }
                if (!ensureClient()) {
                    lastCallOk = false
                    return
                }
                if (!DiscordNative.clearPresence()) {
                    lastCallOk = false
                    return
                }
                // The SDK reports nothing for a clear, so repeat it once while the host is still
                // alive. Clearing an already-clear activity is a no-op, and a second attempt
                // covers a first one that was queued over a bridge that was about to die.
                scope.launch {
                    delay(CLEAR_REPEAT_MS)
                    DiscordNative.clearPresence()
                }
                store.activeId = null
                published = null
                // A clear has no result callback; reaching this point means the request was
                // accepted, so treat it as delivered instead of retrying forever.
                lastCallOk = true
            }
            finishContextIfIdle()
            return
        }
        if (presence == published) return
        if (!ensureClient()) {
            lastCallOk = false
            return
        }

        // Discord keeps the existing activity for an application when only the type changes, so
        // the old one is cleared first to force a fresh activity instead of leaving a stale entry.
        if (published != null && published?.type != presence.type) {
            DiscordNative.clearPresence()
        }

        // Only remember the payload once Discord accepted it. Otherwise a call made before the
        // host Activity was ready would be treated as already published and never retried.
        if (!DiscordNative.setPresence(presence)) {
            lastCallOk = false
            return
        }
        published = presence
        store.activeId = presence.identity()
        awaitResult(++pendingSend)
        Log.i(
            TAG,
            "Presence: type=${presence.type} name=${presence.activityName} " +
                "details=${presence.details} " +
                "state=${presence.state} " +
                "image=${presence.largeImage} start=${presence.startEpoch}",
        )

        val packageName = gameState?.packageName ?: mediaState?.packageName ?: return
        // Nothing to look up when the presence already carries artwork or a listening badge.
        if (presence.largeImage != null || presence.smallImage != null) return
        scope.launch {
            val icon = icons.iconFor(packageName) ?: return@launch
            if (published != presence) return@launch
            val withIcon = presence.copy(largeImage = icon)
            if (DiscordNative.setPresence(withIcon)) {
                published = withIcon
                store.activeId = withIcon.identity()
                awaitResult(++pendingSend)
            }
            notifyStatus()
        }
    }

    /**
     * Initializes the native Discord client once.
     *
     * The SDK binds Discord's RPC service from its static Activity, so the client is only created
     * once that host is alive; creating it earlier leaves the bridge disconnected.
     */
    private fun ensureClient(): Boolean {
        if (clientReady) return true
        if (!DiscordNative.hasContext()) {
            ensureContext()
            return false
        }
        val appId = applicationId(context) ?: return false
        if (!DiscordNative.init(appId, resultListener)) {
            Log.w(TAG, "Discord SDK initialization failed")
            return false
        }
        clientReady = true
        return true
    }

    /**
     * Starts the Activity that hosts the SDK context, if it is not already alive.
     *
     * The host reports back through [onContextReady] in the companion. A request that produced no
     * host is retried after [CONTEXT_RETRY_MS].
     */
    private fun ensureContext() {
        finishJob?.cancel()
        finishJob = null
        if (DiscordNative.hasContext()) return
        if (
            contextRequested &&
            SystemClock.elapsedRealtime() - contextRequestedAt < CONTEXT_RETRY_MS
        ) {
            return
        }
        contextRequested = true
        contextRequestedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "Starting the Discord host")
        try {
            startHost()
        } catch (e: Exception) {
            contextRequested = false
            Log.w(TAG, "Unable to start Discord context activity", e)
        }
    }

    /**
     * Starts the SDK host and lets it hide itself once the launch transition has settled.
     */
    private fun startHost(finish: Boolean = false) {
        context.startActivity(
            Intent(context, DiscordContextActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                if (finish) putExtra(DiscordContextActivity.EXTRA_FINISH, true)
            }
        )
    }

    /**
     * Finishes the host once nothing needs presence.
     *
     * The clear travels over the RPC bridge bound to this Activity, so the finish is deferred past
     * the clear and cancelled if a session starts again.
     */
    private fun finishContextIfIdle() {
        if (owesPresence()) return
        if (!contextRequested) return
        finishJob?.cancel()
        finishJob = scope.launch {
            delay(CONTEXT_FINISH_DELAY_MS)
            // A session can start while the finish waits; keep the host in that case.
            if (owesPresence()) return@launch
            // The host may already be gone (a configuration change or a previous finish), in
            // which case launching a finish-only host would create another one just to tear it
            // down again.
            if (!contextRequested) return@launch
            Log.i(TAG, "Finishing the idle host")
            contextRequested = false
            runCatching { startHost(finish = true) }
        }
    }

    /** Stable identity of a published presence; Discord exposes no activity id of its own. */
    private fun DiscordNative.Presence.identity(): String =
        "$type|$activityName|$details|$state|$startEpoch"

    /**
     * The marketing name lives in the Settings overlay (`edith_about_device_title`), because
     * `Build.MODEL` is only the model code on many devices. Settings is the source of truth for
     * that string, so read the overlaid value from there instead of duplicating the overlay in
     * GameSpace. Devices that do not overlay it keep their model.
     */
    private fun deviceModel(): String = deviceLabel

    private val deviceLabel: String by lazy {
        overlaidDeviceName() ?: Build.MODEL?.takeIf { it.isNotBlank() } ?: Build.DEVICE
    }

    private fun overlaidDeviceName(): String? = runCatching {
        val resources = context.packageManager.getResourcesForApplication(SETTINGS_PACKAGE)
        val id = resources.getIdentifier(
            "edith_about_device_title",
            "string",
            SETTINGS_PACKAGE,
        )
        if (id == 0) null else resources.getString(id).takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun appLabel(packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: Exception) {
        packageName
    }

    companion object {
        private const val TAG = "DiscordPresence"
        private const val DISCORD_PACKAGE = "com.discord"
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val GAMESPACE_MARKER = "• Game Space"
        private const val MEDIA_PUBLISH_DEBOUNCE_MS = 900L
        private const val CONTEXT_FINISH_DELAY_MS = 1_500L
        private const val RESULT_WAIT_MS = 2_500L
        private const val RESULT_TIMEOUT_MS = 5_000L
        private const val CONTEXT_RETRY_MS = 10_000L
        private const val HOST_RESTART_DELAY_MS = 1_000L
        private const val HOST_RESTART_MAX_MS = 15_000L
        private const val HOST_STABLE_MS = 30_000L
        private val WATCH_DELAYS = longArrayOf(8_000L, 15_000L, 30_000L, 60_000L)
        private const val CLEAR_REPEAT_MS = 800L
        private const val MAX_FAILED_SENDS = 2
        private val RECONNECT_DELAYS = longArrayOf(0L, 2_000L, 5_000L, 10_000L, 30_000L, 60_000L)

        @Volatile
        private var instance: DiscordPresenceManager? = null

        fun onContextReady() {
            instance?.let { manager ->
                manager.contextRequested = true
                manager.contextReadyAt = SystemClock.elapsedRealtime()
                manager.publish()
            }
        }

        fun onContextGone() {
            instance?.let { manager ->
                // A replacement host may already be alive: during a finish/start race the new
                // Activity can bind before the old one is destroyed. Keep that client instead of
                // tearing down the bridge the new host just established.
                if (DiscordNative.hasContext()) {
                    manager.notifyStatus()
                    return
                }
                manager.contextRequested = false
                // A host that dies right after it was created is not going to survive a tight
                // retry loop either; back off instead of creating and tearing one down per second.
                val lived = if (manager.contextReadyAt == 0L) {
                    0L
                } else {
                    SystemClock.elapsedRealtime() - manager.contextReadyAt
                }
                manager.contextReadyAt = 0L
                manager.contextRestartDelay = if (lived >= HOST_STABLE_MS) {
                    HOST_RESTART_DELAY_MS
                } else {
                    (manager.contextRestartDelay * 2).coerceAtMost(HOST_RESTART_MAX_MS)
                }
                // The SDK binds Discord's RPC service from the engine Activity. Once that host is
                // gone the client keeps a dead binder and cannot re-bind on its own, so every
                // later send is silently dropped until the client is replaced.
                val hadClient = manager.clientReady
                manager.clientReady = false
                manager.lastCallOk = false
                manager.published = null
                manager.pendingSend = 0
                manager.failedSends = 0
                manager.reconnectJob?.cancel()
                manager.reconnectJob = null
                if (hadClient) DiscordNative.reset()
                manager.notifyStatus()
                manager.scope.launch {
                    // The SDK binds from that Activity, so a presence that is still owed needs a
                    // new host; without one every update fails with no notification to the user.
                    if (!manager.owesPresence() || !manager.appSettings.discordRpc) {
                        return@launch
                    }
                    delay(manager.contextRestartDelay)
                    // A clear can land while the restart is backing off; do not create a host
                    // for a presence that is no longer owed.
                    if (!manager.owesPresence() || !manager.appSettings.discordRpc) {
                        return@launch
                    }
                    manager.ensureContext()
                }
            }
        }

        fun isSupported(context: Context): Boolean {
            if (!DiscordNative.isLoaded || !DiscordNative.nativeAvailable()) return false
            if (applicationId(context) == null) return false
            return try {
                context.packageManager.getPackageInfo(DISCORD_PACKAGE, 0)
                true
            } catch (e: Exception) {
                false
            }
        }

        private fun applicationId(context: Context): String? =
            context.getString(R.string.discord_application_id)
                .trim()
                .takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    }
}
