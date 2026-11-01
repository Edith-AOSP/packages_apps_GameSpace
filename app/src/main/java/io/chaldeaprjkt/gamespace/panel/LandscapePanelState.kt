/*
 * Copyright (C) 2026 The Edith AOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package io.chaldeaprjkt.gamespace.panel

import android.app.GameManager
import android.graphics.drawable.Drawable

data class LandscapePanelState(
    val gameLabel: String = "",
    val gameIcon: Drawable? = null,
    val startedAt: Long = 0L,
    val gameMode: Int = GameManager.GAME_MODE_STANDARD,
    val fps: Boolean = false,
    val gestures: Boolean = true,
    val stayAwake: Boolean = false,
    val headsUp: Boolean = true,
)

data class PanelAnchor(
    val barX: Int,
    val barY: Int,
    val barWidth: Int,
    val barHeight: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val topInset: Int,
    val bottomInset: Int,
)

data class LandscapePanelActions(
    val onDismiss: () -> Unit,
    val onToggleFps: () -> Unit,
    val onToggleGestures: () -> Unit,
    val onToggleStayAwake: () -> Unit,
    val onToggleHeadsUp: () -> Unit,
    val onSetMode: (Int) -> Unit,
)
