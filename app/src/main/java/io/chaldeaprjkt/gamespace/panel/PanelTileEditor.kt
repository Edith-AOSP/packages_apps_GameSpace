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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.chaldeaprjkt.gamespace.R
import kotlin.math.roundToInt

@Composable
internal fun PanelTileEditor(
    config: List<PanelTileConfig>,
    onConfigChanged: (List<PanelTileConfig>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tiles = remember { config.toMutableStateList() }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    var draggingId by remember { mutableStateOf<PanelTileId?>(null) }
    var dragPointer by remember { mutableStateOf(Offset.Zero) }
    var editorOrigin by remember { mutableStateOf(Offset.Zero) }
    var editorSize by remember { mutableStateOf(IntSize.Zero) }
    var activeBounds by remember { mutableStateOf(Rect.Zero) }
    var availableBounds by remember { mutableStateOf(Rect.Zero) }
    var dropTargetId by remember { mutableStateOf<PanelTileId?>(null) }
    var dropTargetVisible by remember { mutableStateOf<Boolean?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }
    val tileBounds = remember { mutableStateMapOf<PanelTileId, Rect>() }

    fun persist() {
        onConfigChanged(tiles.toList())
    }

    fun moveTile(id: PanelTileId, targetId: PanelTileId?, targetVisible: Boolean) {
        val item = tiles.firstOrNull { it.id == id } ?: return
        val remaining = tiles.filterNot { it.id == id }
        val target = remaining.filter { it.visible == targetVisible }.toMutableList()
        val index = targetId
            ?.let { target.indexOfFirst { tile -> tile.id == it } }
            ?.takeIf { it >= 0 }
            ?: target.size
        target.add(index, item.copy(visible = targetVisible))

        val active = if (targetVisible) target else remaining.filter { it.visible }
        val available = if (targetVisible) remaining.filterNot { it.visible } else target
        tiles.clear()
        tiles.addAll(active + available)
        persist()
    }

    fun toggle(id: PanelTileId) {
        val item = tiles.firstOrNull { it.id == id } ?: return
        moveTile(id, targetId = null, targetVisible = !item.visible)
    }

    fun updateDropTarget(pointer: Offset) {
        val draggedBounds = draggingId?.let { tileBounds[it] }
        if (draggedBounds?.contains(pointer) == true) {
            dropTargetId = null
            dropTargetVisible = null
            return
        }

        val target = tileBounds.entries
            .firstOrNull { (id, bounds) -> id != draggingId && bounds.contains(pointer) }
            ?.key
        dropTargetId = target
        dropTargetVisible = when {
            target != null -> tiles.firstOrNull { it.id == target }?.visible
            activeBounds.contains(pointer) -> true
            availableBounds.contains(pointer) -> false
            else -> null
        }
    }

    fun finishDrag() {
        val id = draggingId
        if (id != null) updateDropTarget(dragPointer)
        val visible = dropTargetVisible
        if (id != null && visible != null) {
            moveTile(id, dropTargetId, visible)
        }
        draggingId = null
        dropTargetId = null
        dropTargetVisible = null
    }

    fun cancelDrag() {
        draggingId = null
        dropTargetId = null
        dropTargetVisible = null
    }

    LaunchedEffect(draggingId) {
        while (draggingId != null) {
            val edge = with(density) { 56.dp.toPx() }
            val step = with(density) { 10.dp.toPx() }
            val pointerY = dragPointer.y - editorOrigin.y
            when {
                pointerY < edge -> scrollState.scrollBy(-step)
                pointerY > editorSize.height - edge -> scrollState.scrollBy(step)
            }
            withFrameNanos {}
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val tileWidth = (maxWidth - 32.dp - 8.dp) / 2
        val tileHeight = 66.dp

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned {
                    editorOrigin = it.boundsInRoot().topLeft
                    editorSize = it.size
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { showResetDialog = true }) {
                        Text(stringResource(R.string.panel_tiles_reset))
                    }
                }

                EditorSection(
                    title = stringResource(R.string.panel_tiles_active),
                    tiles = tiles.filter { it.visible },
                    active = true,
                    draggingId = draggingId,
                    dropTargetId = dropTargetId,
                    emptyText = stringResource(R.string.panel_tiles_empty_active),
                    tileBounds = tileBounds,
                    onBoundsChanged = { activeBounds = it },
                    onToggle = ::toggle,
                    onDragStart = { id, offset ->
                        tileBounds[id]?.let { bounds ->
                            draggingId = id
                            dragPointer = bounds.topLeft + offset
                            dropTargetId = null
                            dropTargetVisible = null
                        }
                    },
                    onDrag = { _, amount ->
                        dragPointer += amount
                        updateDropTarget(dragPointer)
                    },
                    onDragEnd = { finishDrag() },
                    onDragCancel = { cancelDrag() },
                )

                EditorSection(
                    title = stringResource(R.string.panel_tiles_available),
                    tiles = tiles.filterNot { it.visible },
                    active = false,
                    draggingId = draggingId,
                    dropTargetId = dropTargetId,
                    emptyText = stringResource(R.string.panel_tiles_empty_available),
                    tileBounds = tileBounds,
                    onBoundsChanged = { availableBounds = it },
                    onToggle = ::toggle,
                    onDragStart = { id, offset ->
                        tileBounds[id]?.let { bounds ->
                            draggingId = id
                            dragPointer = bounds.topLeft + offset
                            dropTargetId = null
                            dropTargetVisible = null
                        }
                    },
                    onDrag = { _, amount ->
                        dragPointer += amount
                        updateDropTarget(dragPointer)
                    },
                    onDragEnd = { finishDrag() },
                    onDragCancel = { cancelDrag() },
                )

                Text(
                    text = stringResource(R.string.panel_tiles_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            val ghostId = draggingId
            if (ghostId != null) {
                val spec = PanelTiles.spec(ghostId)
                val ghostWidth = with(density) { tileWidth.toPx() }
                val ghostHeight = with(density) { tileHeight.toPx() }
                val x = dragPointer.x - editorOrigin.x - ghostWidth / 2f
                val y = dragPointer.y - editorOrigin.y - ghostHeight / 2f
                Box(
                    modifier = Modifier
                        .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                        .size(tileWidth, tileHeight)
                        .zIndex(2f)
                        .shadow(8.dp, RoundedCornerShape(18.dp)),
                ) {
                    PanelTile(
                        title = stringResource(spec.titleRes),
                        enabled = true,
                        icon = spec.iconRes,
                        onClick = {},
                        modifier = Modifier.fillMaxSize(),
                        interactive = false,
                        statusText = null,
                    )
                }
            }

            if (showResetDialog) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { showResetDialog = false },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier
                            .padding(20.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                            ),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.panel_tiles_reset_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.panel_tiles_reset_message),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = { showResetDialog = false }) {
                                    Text(stringResource(R.string.panel_tiles_cancel))
                                }
                                TextButton(
                                    onClick = {
                                        tiles.clear()
                                        tiles.addAll(PanelTiles.defaults())
                                        persist()
                                        showResetDialog = false
                                    },
                                ) {
                                    Text(stringResource(R.string.panel_tiles_reset_confirm))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

}

@Composable
private fun EditorSection(
    title: String,
    tiles: List<PanelTileConfig>,
    active: Boolean,
    draggingId: PanelTileId?,
    dropTargetId: PanelTileId?,
    emptyText: String,
    tileBounds: MutableMap<PanelTileId, Rect>,
    onBoundsChanged: (Rect) -> Unit,
    onToggle: (PanelTileId) -> Unit,
    onDragStart: (PanelTileId, Offset) -> Unit,
    onDrag: (PanelTileId, Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { onBoundsChanged(it.boundsInRoot()) },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (tiles.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        } else {
            tiles.chunked(2).forEach { rowTiles ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowTiles.forEach { tile ->
                        val spec = PanelTiles.spec(tile.id)
                        PanelTile(
                            title = stringResource(spec.titleRes),
                            enabled = active,
                            icon = spec.iconRes,
                            onClick = { onToggle(tile.id) },
                            statusText = null,
                            modifier = Modifier
                                .weight(1f)
                                .onGloballyPositioned { tileBounds[tile.id] = it.boundsInRoot() }
                                .pointerInput(tile.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { offset -> onDragStart(tile.id, offset) },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            onDrag(tile.id, amount)
                                        },
                                        onDragEnd = onDragEnd,
                                        onDragCancel = onDragCancel,
                                    )
                                }
                                .alpha(if (draggingId == tile.id) 0.25f else 1f)
                                .then(
                                    if (dropTargetId == tile.id) {
                                        Modifier.border(
                                            2.dp,
                                            MaterialTheme.colorScheme.primary,
                                            RoundedCornerShape(18.dp),
                                        )
                                    } else {
                                        Modifier
                                    }
                                ),
                        )
                    }
                    if (rowTiles.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
