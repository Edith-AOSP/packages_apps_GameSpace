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

/**
 * Remembers the presence this application last asked Discord to show.
 *
 * Discord has no server-side activity id, only "clear this application's activity", so the stored
 * id doubles as the ownership marker: it tells a later process that something was published and
 * may still be showing.
 */
internal class DiscordPresenceStore(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var activeId: String?
        get() = prefs.getString(KEY_ACTIVE_ID, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_ACTIVE_ID) else putString(KEY_ACTIVE_ID, value)
            }.apply()
        }

    private companion object {
        const val FILE = "discord_presence"
        const val KEY_ACTIVE_ID = "active_id"
    }
}
