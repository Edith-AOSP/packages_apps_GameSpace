/*
 * Copyright (C) 2026 The Edith AOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package io.chaldeaprjkt.gamespace.gamebar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.chaldeaprjkt.gamespace.discord.DiscordPresenceManager
import io.chaldeaprjkt.gamespace.utils.di.ServiceViewEntryPoint
import io.chaldeaprjkt.gamespace.utils.entryPointOf

/** Brings media Rich Presence back after a reboot when the user left it enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        val settings = context.entryPointOf<ServiceViewEntryPoint>().appSettings()
        if (!settings.discordRpc || !settings.discordMedia) return
        if (!DiscordPresenceManager.isSupported(context)) return

        runCatching { GameBarService.startMedia(context) }
    }
}
