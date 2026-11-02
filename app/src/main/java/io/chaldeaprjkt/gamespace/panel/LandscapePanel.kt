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

import android.app.ActivityManager
import android.app.GameManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import io.chaldeaprjkt.gamespace.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun LandscapePanel(
    state: LandscapePanelState,
    actions: LandscapePanelActions,
    anchor: PanelAnchor,
) {
    val scrimInteraction = remember { MutableInteractionSource() }
    var editing by remember { mutableStateOf(false) }
    var normalScale by remember { mutableStateOf(1f) }
    var normalPanelHeight by remember { mutableStateOf(0) }
    var scaleLocked by remember { mutableStateOf(false) }

    LaunchedEffect(editing) {
        if (scaleLocked) {
            delay(280)
            scaleLocked = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.48f))
            .clickable(
                interactionSource = scrimInteraction,
                indication = null,
                onClick = {
                    editing = false
                    scaleLocked = false
                    actions.onDismiss()
                },
            )
    ) {
        Layout(
            content = {
                PanelSheet(
                    state = state,
                    actions = actions,
                    editing = editing,
                    onCustomize = {
                        editing = !editing
                        scaleLocked = true
                    },
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val margin = 16.dp.roundToPx()
            val gap = 12.dp.roundToPx()
            val landscape = constraints.maxWidth > constraints.maxHeight
            val maxPanelWidth = if (landscape) 400.dp else 520.dp
            val maxWidth = min(maxPanelWidth.roundToPx(), constraints.maxWidth - margin * 2)
                .coerceAtLeast(0)
            val availableHeight = (constraints.maxHeight - anchor.topInset - anchor.bottomInset -
                margin * 2).coerceAtLeast(0)
            val contentMaxHeight = if (normalScale > 0f) {
                (availableHeight / normalScale).roundToInt()
            } else {
                availableHeight
            }
            val panel = measurables.first().measure(
                Constraints(
                    maxWidth = maxWidth,
                    maxHeight = if (editing || scaleLocked) {
                        contentMaxHeight
                    } else {
                        Constraints.Infinity
                    },
                )
            )
            val fitScale = if (availableHeight > 0 && panel.height > availableHeight) {
                (availableHeight.toFloat() / panel.height).coerceAtLeast(0.35f)
            } else {
                1f
            }
            val scale = if (editing || scaleLocked) {
                normalScale
            } else {
                normalScale = fitScale
                fitScale
            }
            val panelWidth = (panel.width * scale).roundToInt()
            val panelHeight = (panel.height * scale).roundToInt()
            if (!editing && !scaleLocked) {
                normalPanelHeight = panelHeight
            }
            val anchorHeight = if (editing || scaleLocked) normalPanelHeight else panelHeight
            val stripBleed = (12.dp * scale).roundToPx()

            val barCenterX = anchor.barX + anchor.barWidth / 2
            val barCenterY = anchor.barY + anchor.barHeight / 2
            var x = if (barCenterX < anchor.screenWidth / 2) {
                anchor.barX + anchor.barWidth + gap
            } else {
                anchor.barX - panelWidth - gap
            }
            val minX = margin + stripBleed
            val maxX = anchor.screenWidth - panelWidth - margin
            x = if (maxX >= minX) x.coerceIn(minX, maxX) else minX

            var y = barCenterY - anchorHeight / 2
            val minY = anchor.topInset + margin
            val maxY = anchor.screenHeight - anchor.bottomInset - panelHeight - margin
            y = if (maxY >= minY) y.coerceIn(minY, maxY) else minY

            layout(constraints.maxWidth, constraints.maxHeight) {
                panel.placeWithLayer(x, y) {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                }
            }
        }
    }
}

@Composable
private fun PanelSheet(
    state: LandscapePanelState,
    actions: LandscapePanelActions,
    editing: Boolean,
    onCustomize: () -> Unit,
) {
    val context = LocalContext.current
    val stats by produceState(initialValue = DeviceStats(), context) {
        while (true) {
            value = withContext(Dispatchers.IO) { readDeviceStats(context) }
            delay(5_000)
        }
    }
    val clock by produceState(initialValue = currentClock()) {
        while (true) {
            delay(10_000)
            value = currentClock()
        }
    }
    val interaction = remember { MutableInteractionSource() }
    val stripFraction by animateFloatAsState(
        targetValue = if (editing) 0f else 1f,
        animationSpec = tween(220),
        label = "panelStripVisibility",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(28.dp),
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {},
            ),
    ) {
        Column(modifier = Modifier.padding(top = 8.dp)) {
            StatusHeader(
                clock = clock,
                batteryPercent = stats.batteryPercent,
                editing = editing,
                onCustomize = onCustomize,
            )
            Spacer(Modifier.height(8.dp))
            GameStrip(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .collapseVertically(stripFraction)
                    .startBleed(12.dp)
                    .alpha(stripFraction),
            )
            AnimatedContent(
                targetState = editing,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = {
                    (fadeIn(tween(200)) +
                        expandVertically(tween(220), expandFrom = Alignment.Top, clip = false))
                        .togetherWith(
                            fadeOut(tween(140)) +
                                shrinkVertically(
                                    tween(180),
                                    shrinkTowards = Alignment.Top,
                                    clip = false,
                                )
                        )
                        .using(SizeTransform(clip = false))
                },
                contentAlignment = Alignment.TopStart,
                label = "panelEditAnimation",
            ) { isEditing ->
                if (isEditing) {
                    PanelTileEditor(
                        config = state.tiles,
                        onConfigChanged = actions.onPanelTilesChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 16.dp,
                                end = 16.dp,
                                top = 12.dp,
                                bottom = 14.dp,
                            ),
                    ) {
                        SystemControls(
                            stats = stats,
                            mode = state.gameMode,
                            batteryPercent = stats.batteryPercent,
                            actions = actions,
                        )
                        Spacer(Modifier.height(14.dp))
                        TileGrid(state = state, actions = actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusHeader(
    clock: String,
    batteryPercent: Int,
    editing: Boolean,
    onCustomize: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val batteryFill = if (dark) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.surface
    }
    val batteryTrack = if (dark) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.height(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = clock,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.height(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UnifiedBattery(
                percent = batteryPercent,
                fillColor = batteryFill,
                trackColor = batteryTrack,
                modifier = Modifier.size(width = 21.dp, height = 12.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = "$batteryPercent%",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(10.dp))
        CustomizeButton(editing = editing, onClick = onCustomize)
    }
}

@Composable
private fun CustomizeButton(editing: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (editing) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = stringResource(R.string.panel_tiles_done),
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        } else {
            Icon(
                imageVector = DashboardCustomize,
                contentDescription = stringResource(R.string.panel_tiles_customize),
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GameStrip(
    state: LandscapePanelState,
    modifier: Modifier = Modifier,
) {
    val elapsed by produceState(initialValue = elapsedSeconds(state.startedAt), state.startedAt) {
        while (state.startedAt > 0) {
            value = elapsedSeconds(state.startedAt)
            delay(1_000)
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(
            topStart = 24.dp,
            topEnd = 0.dp,
            bottomEnd = 0.dp,
            bottomStart = 24.dp,
        ),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GameIcon(state.gameIcon)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.gameLabel.ifBlank {
                        stringResource(R.string.panel_game_fallback)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = stringResource(
                        R.string.panel_elapsed,
                        formatElapsed(elapsed),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.72f),
                )
            }
        }
    }
}

@Composable
private fun GameIcon(icon: Drawable?) {
    val bitmap = remember(icon) {
        icon?.toBitmap(width = 96, height = 96)?.asImageBitmap()
    }

    Box(
        modifier = Modifier
            .size(60.dp)
            .clip(RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_gear),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun SystemControls(
    stats: DeviceStats,
    mode: Int,
    batteryPercent: Int,
    actions: LandscapePanelActions,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth < 340.dp) {
            Column(modifier = Modifier.fillMaxWidth()) {
                StatsRow(stats = stats)
                Spacer(Modifier.height(10.dp))
                ModeSelector(
                    mode = mode,
                    batteryPercent = batteryPercent,
                    onSetMode = actions.onSetMode,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatsRow(stats = stats)
                Spacer(Modifier.weight(1f))
                ModeSelector(
                    mode = mode,
                    batteryPercent = batteryPercent,
                    onSetMode = actions.onSetMode,
                )
            }
        }
    }
}

@Composable
private fun StatsRow(stats: DeviceStats) {
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        StatBlock(
            icon = R.drawable.ic_developer_board,
            value = if (stats.cpuPercent >= 0) "${stats.cpuPercent}%" else "—",
            detail = "${stats.temperatureC}°C",
        )
        StatBlock(
            icon = R.drawable.memory,
            value = "${stats.memoryPercent}%",
            detail = stats.memoryUsed,
        )
    }
}

@Composable
private fun StatBlock(icon: Int, value: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class ModeTab(
    val mode: Int,
    val icon: Int?,
    val label: String,
)

@Composable
private fun ModeSelector(
    mode: Int,
    batteryPercent: Int,
    onSetMode: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = listOf(
        ModeTab(
            GameManager.GAME_MODE_BATTERY,
            null,
            stringResource(R.string.panel_mode_battery),
        ),
        ModeTab(
            GameManager.GAME_MODE_STANDARD,
            R.drawable.ic_bar_chart,
            stringResource(R.string.panel_mode_balanced),
        ),
        ModeTab(
            GameManager.GAME_MODE_PERFORMANCE,
            R.drawable.ic_speed,
            stringResource(R.string.panel_mode_performance),
        ),
    )

    val selectedIndex = tabs.indexOfFirst { it.mode == mode }.coerceAtLeast(0)
    val indicatorOffset by animateDpAsState(
        targetValue = 4.dp + (40.dp + 2.dp) * selectedIndex,
        animationSpec = tween(durationMillis = 240),
        label = "mode-indicator",
    )

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Box(modifier = Modifier.height(48.dp)) {
            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset, y = 4.dp)
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
            Row(
                modifier = Modifier.padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                tabs.forEach { tab ->
                    val selected = mode == tab.mode
                    val content by animateColorAsState(
                        targetValue = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(durationMillis = 220),
                        label = "mode-content",
                    )

                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(),
                                onClick = { onSetMode(tab.mode) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (tab.icon != null) {
                            Icon(
                                painter = painterResource(tab.icon),
                                contentDescription = tab.label,
                                modifier = Modifier.size(20.dp),
                                tint = content,
                            )
                        } else {
                            UnifiedBattery(
                                percent = 100,
                                fillColor = content,
                                trackColor = content,
                                modifier = Modifier.size(width = 21.dp, height = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UnifiedBattery(
    percent: Int,
    fillColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            UnifiedBatteryView(ctx, fillColor.toArgb(), trackColor.toArgb(), percent)
        },
        update = { view ->
            view.update(percent, fillColor.toArgb(), trackColor.toArgb())
        },
    )
}

/**
 * Uses the same XML vectors as SystemUI: battery_unified_frame_bg for the
 * track, battery_unified_frame for the cap/frame, and the body vector as the
 * level fill mask.
 */
private class UnifiedBatteryView(
    context: Context,
    private var fillColor: Int,
    private var trackColor: Int,
    percent: Int,
) : View(context) {
    private val track = context.getDrawable(R.drawable.battery_unified_frame_bg)!!.mutate()
    private val fill = context.getDrawable(R.drawable.battery_unified_frame_bg)!!.mutate()
    private val frame = context.getDrawable(R.drawable.battery_unified_frame)!!.mutate()
    private var level = percent.coerceIn(0, 100)

    fun update(percent: Int, fill: Int, track: Int) {
        level = percent.coerceIn(0, 100)
        fillColor = fill
        trackColor = track
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        track.setBounds(0, 0, width, height)
        fill.setBounds(0, 0, width, height)
        frame.setBounds(0, 0, width, height)

        track.setTint(trackColor)
        fill.setTint(fillColor)
        frame.setTint(trackColor)

        drawMirrored(canvas, track)

        val save = canvas.save()
        canvas.scale(-1f, 1f, width / 2f, height / 2f)
        canvas.clipRect(
            width * (1f - level / 100f),
            0f,
            width.toFloat(),
            height.toFloat(),
        )
        fill.draw(canvas)
        canvas.restoreToCount(save)

        drawMirrored(canvas, frame)
    }

    private fun drawMirrored(canvas: Canvas, drawable: Drawable) {
        val save = canvas.save()
        canvas.scale(-1f, 1f, width / 2f, height / 2f)
        drawable.draw(canvas)
        canvas.restoreToCount(save)
    }
}

@Composable
private fun TileGrid(
    state: LandscapePanelState,
    actions: LandscapePanelActions,
    modifier: Modifier = Modifier,
) {
    val tiles = state.tiles.filter { it.visible }
    val pages = remember(tiles) { tiles.chunked(TILES_PER_PAGE) }

    if (tiles.isEmpty()) {
        Text(
            text = stringResource(R.string.panel_tiles_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }

    val pagerState = rememberPagerState(pageCount = { pages.size })
    LaunchedEffect(pages.size) {
        if (pages.isNotEmpty() && pagerState.currentPage > pages.lastIndex) {
            pagerState.scrollToPage(pages.lastIndex)
        }
    }
    val rowGap = 8.dp
    val gridHeight = TILE_HEIGHT * 2 + rowGap

    Column(modifier = modifier) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(gridHeight),
            pageSpacing = 12.dp,
            beyondViewportPageCount = 1,
        ) { page ->
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(rowGap),
            ) {
                pages[page].chunked(2).forEach { rowTiles ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowTiles.forEach { tile ->
                            TileItem(
                                tile = tile,
                                state = state,
                                actions = actions,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowTiles.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        if (pages.size > 1) {
            TilePageIndicator(
                pageCount = pages.size,
                currentPage = pagerState.currentPage,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun TileItem(
    tile: PanelTileConfig,
    state: LandscapePanelState,
    actions: LandscapePanelActions,
    modifier: Modifier = Modifier,
) {
    val spec = PanelTiles.spec(tile.id)
    val enabled = state.isTileEnabled(tile.id)
    val statusText = when (tile.id) {
        PanelTileId.RINGER -> stringResource(
            when (state.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> R.string.panel_tile_ringer_silent
                AudioManager.RINGER_MODE_VIBRATE -> R.string.panel_tile_ringer_vibrate
                else -> R.string.panel_tile_ringer_normal
            }
        )
        else -> stringResource(
            if (enabled) R.string.panel_tile_on else R.string.panel_tile_off
        )
    }
    PanelTile(
        title = stringResource(spec.titleRes),
        enabled = enabled,
        icon = spec.iconRes,
        onClick = {
            when (tile.id) {
                PanelTileId.FPS -> actions.onToggleFps()
                PanelTileId.GESTURES -> actions.onToggleGestures()
                PanelTileId.STAY_AWAKE -> actions.onToggleStayAwake()
                PanelTileId.HEADS_UP -> actions.onToggleHeadsUp()
                PanelTileId.RINGER -> actions.onCycleRinger()
                PanelTileId.DND -> actions.onToggleDnd()
                PanelTileId.AUTO_BRIGHTNESS -> actions.onToggleAutoBrightness()
                PanelTileId.ROTATION_LOCK -> actions.onToggleRotationLock()
            }
        },
        modifier = modifier,
        statusText = statusText,
    )
}

@Composable
private fun TilePageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val selected = index == currentPage
            val width by animateDpAsState(
                targetValue = if (selected) 16.dp else 6.dp,
                animationSpec = tween(220),
                label = "tilePageWidth",
            )
            val color by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                },
                animationSpec = tween(220),
                label = "tilePageColor",
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(width = width, height = 6.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

private const val TILES_PER_PAGE = 4
private val TILE_HEIGHT = 66.dp

@Composable
internal fun PanelTile(
    title: String,
    enabled: Boolean,
    icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    statusText: String? = null,
) {
    val background = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val foreground = if (enabled) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier
            .height(66.dp)
            .clip(RoundedCornerShape(18.dp))
            .then(if (interactive) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(18.dp),
        color = background,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = foreground,
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                statusText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = foreground,
                    )
                }
            }
        }
    }
}

private fun Modifier.collapseVertically(fraction: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(
        constraints.copy(
            minHeight = 0,
            maxHeight = Constraints.Infinity,
        )
    )
    val value = fraction.coerceIn(0f, 1f)
    val height = (placeable.height * value).roundToInt().coerceAtLeast(0)
    layout(constraints.maxWidth, height) {
        placeable.place(0, 0)
    }
}

private fun Modifier.startBleed(amount: Dp): Modifier = layout { measurable, constraints ->
    val bleed = amount.roundToPx()
    val width = constraints.maxWidth + bleed
    val placeable = measurable.measure(
        constraints.copy(minWidth = width, maxWidth = width)
    )
    layout(constraints.maxWidth, placeable.height) {
        placeable.place(-bleed, 0)
    }
}

@Composable
fun GameSpacePanelTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (darkTheme) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        content = content,
    )
}

private data class DeviceStats(
    val cpuPercent: Int = -1,
    val temperatureC: Int = 0,
    val memoryPercent: Int = 0,
    val memoryUsed: String = "—",
    val batteryPercent: Int = 0,
)

private data class CpuSnapshot(
    val total: Long,
    val idle: Long,
)

private var lastCpuSnapshot: CpuSnapshot? = null

private fun readCpuPercent(): Int {
    return try {
        val line = File("/proc/stat").bufferedReader().use { it.readLine() }
            ?: return -1
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 5 || parts.first() != "cpu") return -1
        val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 4) return -1
        val idle = values[3] + (values.getOrNull(4) ?: 0L)
        val total = values.sum()
        val previous = lastCpuSnapshot
        lastCpuSnapshot = CpuSnapshot(total, idle)
        if (previous == null || total <= previous.total) return -1
        val totalDelta = total - previous.total
        val idleDelta = idle - previous.idle
        (((totalDelta - idleDelta).toDouble() / totalDelta) * 100.0)
            .roundToInt()
            .coerceIn(0, 100)
    } catch (_: Exception) {
        -1
    }
}

private fun readDeviceStats(context: Context): DeviceStats {
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else 0
    val batteryTempC = ((battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10)

    val cpuTemp = readTemperature() ?: batteryTempC

    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo()
    am.getMemoryInfo(info)
    val used = (info.totalMem - info.availMem).coerceAtLeast(0)
    val total = info.totalMem.coerceAtLeast(1)
    val memoryPercent = (used * 100 / total).toInt().coerceIn(0, 100)
    val usedGb = String.format(Locale.US, "%.1f GB", used / 1_073_741_824.0)

    return DeviceStats(
        cpuPercent = readCpuPercent(),
        temperatureC = cpuTemp,
        memoryPercent = memoryPercent,
        memoryUsed = usedGb,
        batteryPercent = batteryPercent,
    )
}

private fun readTemperature(): Int? {
    val root = File("/sys/class/thermal")
    val zones = root.listFiles() ?: return null
    var fallback: Int? = null
    for (zone in zones) {
        try {
            val type = File(zone, "type").readText().trim()
            val raw = File(zone, "temp").readText().trim().toIntOrNull() ?: continue
            val celsius = if (raw > 1000) raw / 1000 else raw
            if (celsius !in 10..120) continue
            if (type.contains("cpu", ignoreCase = true) ||
                type.contains("soc", ignoreCase = true)
            ) {
                return celsius
            }
            if (fallback == null && type.contains("battery", ignoreCase = true)) {
                fallback = celsius
            }
        } catch (_: Exception) {
            // Ignore unreadable thermal zones.
        }
    }
    return fallback
}

private fun currentClock(): String =
    SimpleDateFormat("h:mm", Locale.getDefault()).format(Date())

private fun elapsedSeconds(startedAt: Long): Long =
    if (startedAt > 0) ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0) else 0

private fun formatElapsed(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}
