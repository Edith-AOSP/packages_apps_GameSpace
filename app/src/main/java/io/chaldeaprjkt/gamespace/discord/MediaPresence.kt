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

/** A media session that GameSpace is currently mirroring to Discord. */
data class MediaPresence(
    val packageName: String,
    val playerName: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val playing: Boolean,
    val startEpoch: Long,
    val artworkUrl: String?,
)
