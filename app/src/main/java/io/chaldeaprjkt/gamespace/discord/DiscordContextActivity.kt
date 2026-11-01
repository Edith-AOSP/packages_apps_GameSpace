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

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.discord.socialsdk.DiscordSocialSdkInit

/**
 * The Social SDK's Android RPC bridge needs a live Activity to obtain a
 * Context. GameSpace normally starts presence from a service, so this
 * no-display activity supplies one and immediately flushes the pending
 * presence update.
 */
class DiscordContextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            DiscordSocialSdkInit.setEngineActivity(this)
            DiscordPresenceManager.onContextReady()
            DiscordNative.flushPendingPresence()
        } catch (e: Throwable) {
            Log.w("GameSpaceDiscord", "Unable to provide Discord SDK context", e)
        }
        finish()
    }
}
