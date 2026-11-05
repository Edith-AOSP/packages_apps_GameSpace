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

import io.chaldeaprjkt.gamespace.R

/**
 * Which part of GameSpace keeps [GameBarService] in the foreground.
 *
 * The service is shared by the game bar and media presence, so the ongoing notification names the
 * running combination instead of a fixed label.
 */
internal enum class ForegroundState(val title: Int) {
    GAME_BAR(R.string.gamespace_running_game_bar),
    MEDIA(R.string.gamespace_running_media),
    GAME_BAR_AND_MEDIA(R.string.gamespace_running_game_bar_and_media),
    ;

    companion object {
        fun of(gameActive: Boolean, mediaActive: Boolean): ForegroundState = when {
            gameActive && mediaActive -> GAME_BAR_AND_MEDIA
            mediaActive -> MEDIA
            else -> GAME_BAR
        }
    }
}
