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

import android.util.Log
import com.discord.socialsdk.DiscordSocialSdkInit

/**
 * Thin bridge to the Discord Social SDK.
 *
 * The SDK keeps a static Activity reference and resolves and binds the Discord RPC service from
 * it, so presence only works while [DiscordContextActivity] is alive. There is deliberately no
 * queueing here: if the host is missing, the call fails loudly instead of being replayed later.
 * See `docs/gamespace-discord-rpc.md`.
 */
internal object DiscordNative {
    private const val TAG = "GameSpaceDiscord"

    /** Mirrors discordpp::ActivityTypes. */
    const val TYPE_PLAYING = 0
    const val TYPE_LISTENING = 2

    val isLoaded: Boolean = try {
        System.loadLibrary("gamespace_discord")
        true
    } catch (e: UnsatisfiedLinkError) {
        false
    }

    data class Presence(
        val type: Int,
        /** Rendered by the "Listening to <name>" header; only the listening type uses it. */
        val activityName: String? = null,
        val details: String,
        val state: String,
        val largeImage: String?,
        val largeText: String?,
        val smallImage: String?,
        val startEpoch: Long,
    )

    external fun nativeAvailable(): Boolean

    /** Receives the SDK's asynchronous verdict for a presence update. */
    interface ResultListener {
        fun onResult(success: Boolean, error: String?)

        /**
         * Receives the SDK's RPC status transitions. [status] is the SDK's
         * `discordpp::Client::Status` cast to an int, and [detail] is the gateway close code of an
         * abnormal close.
         */
        fun onStatus(status: Int, detail: Int)
    }

    private external fun nativeInit(appId: String, listener: ResultListener?): Boolean

    /** Creates the native client and registers the result listener. */
    @Synchronized
    fun init(appId: String, listener: ResultListener): Boolean {
        if (!isLoaded) return false
        return nativeInit(appId, listener)
    }

    private external fun nativeSetPresence(
        type: Int,
        activityName: String?,
        details: String,
        state: String,
        largeImage: String?,
        largeText: String?,
        smallImage: String?,
        startEpoch: Long,
    ): Boolean

    private external fun nativeClearPresence(): Boolean

    private external fun nativeReset()

    /**
     * A finished Activity reports a null application context, which makes the SDK throw on its own
     * handler thread. The reference alone is not proof of a live host: a finished Activity keeps
     * its application context, and the SDK then binds its RPC service from that Activity forever.
     */
    fun hasContext(): Boolean = try {
        val activity = DiscordSocialSdkInit.getEngineActivity() ?: return false
        !activity.isDestroyed && !activity.isFinishing && activity.applicationContext != null
    } catch (e: Throwable) {
        Log.w(TAG, "Discord SDK context unavailable", e)
        false
    }

    @Synchronized
    fun setPresence(presence: Presence): Boolean {
        if (!hasContext()) {
            Log.w(TAG, "setPresence without a live Discord context")
            return false
        }
        val ok = nativeSetPresence(
            presence.type,
            presence.activityName,
            presence.details,
            presence.state,
            presence.largeImage,
            presence.largeText,
            presence.smallImage,
            presence.startEpoch,
        )
        if (!ok) Log.w(TAG, "nativeSetPresence returned false")
        return ok
    }

    @Synchronized
    fun clearPresence(): Boolean {
        if (!hasContext()) {
            Log.w(TAG, "clearPresence without a live Discord context")
            return false
        }
        if (!isLoaded) return false
        val ok = nativeClearPresence()
        if (!ok) Log.w(TAG, "nativeClearPresence returned false")
        return ok
    }

    /** Mirrors discordpp::Client::Status; only [STATUS_READY] means updates actually reach Discord. */
    const val STATUS_DISCONNECTED = 0
    const val STATUS_CONNECTED = 2
    const val STATUS_READY = 3

    /** The gateway close code the SDK reports when Discord drops an established RPC link. */
    const val CLOSE_ABNORMAL = 1006

    external fun nativeStatus(): Int

    /** Current RPC bridge status, or -1 when no client exists. */
    @Synchronized
    fun status(): Int = if (isLoaded) nativeStatus() else -1

    fun isReady(): Boolean = status() == STATUS_READY

    fun isConnected(): Boolean = status() >= STATUS_CONNECTED

    @Synchronized
    fun reset() {
        if (!isLoaded) return
        nativeReset()
    }
}
