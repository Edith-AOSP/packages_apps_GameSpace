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

internal object DiscordNative {
    private const val TAG = "GameSpaceDiscord"

    val isLoaded: Boolean = try {
        System.loadLibrary("gamespace_discord")
        true
    } catch (e: UnsatisfiedLinkError) {
        false
    }

    private data class PendingPresence(
        val presenceName: String,
        val gameName: String,
        val state: String,
        val iconUrl: String?,
        val startEpoch: Long,
    )

    @Volatile
    private var pending: PendingPresence? = null

    external fun nativeAvailable(): Boolean

    external fun nativeInit(appId: String): Boolean

    private external fun nativeSetPresence(
        presenceName: String,
        gameName: String,
        state: String,
        iconUrl: String?,
        startEpoch: Long,
    ): Boolean

    private external fun nativeClearPresence(): Boolean

    private external fun nativeReset()

    fun hasContext(): Boolean = try {
        DiscordSocialSdkInit.getEngineActivity() != null
    } catch (e: Throwable) {
        Log.w(TAG, "Discord SDK context unavailable", e)
        false
    }

    fun setPresence(
        presenceName: String,
        gameName: String,
        state: String,
        iconUrl: String?,
        startEpoch: Long,
    ) {
        val presence = PendingPresence(presenceName, gameName, state, iconUrl, startEpoch)
        pending = presence
        if (hasContext()) {
            apply(presence)
        }
    }

    fun flushPendingPresence() {
        pending?.let(::apply)
    }

    fun clearPresence() {
        pending = null
        if (isLoaded) nativeClearPresence()
    }

    fun reset() {
        pending = null
        if (isLoaded) nativeReset()
    }

    private fun apply(presence: PendingPresence) {
        nativeSetPresence(
            presence.presenceName,
            presence.gameName,
            presence.state,
            presence.iconUrl,
            presence.startEpoch,
        )
    }
}
