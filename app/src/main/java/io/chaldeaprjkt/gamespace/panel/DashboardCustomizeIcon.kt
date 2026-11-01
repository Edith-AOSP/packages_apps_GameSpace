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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val DashboardCustomize: ImageVector by lazy {
    ImageVector.Builder(
        name = "dashboard_customize",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = SolidColor(Color.Black),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Bevel,
            strokeLineMiter = 1f,
            pathFillType = PathFillType.NonZero,
        ) {
            moveTo(3f, 3f)
            horizontalLineToRelative(8f)
            verticalLineToRelative(8f)
            horizontalLineTo(3f)
            verticalLineTo(3f)
            close()
            moveTo(5f, 5f)
            verticalLineTo(9f)
            verticalLineTo(5f)
            close()
            moveTo(13f, 3f)
            horizontalLineToRelative(8f)
            verticalLineToRelative(8f)
            horizontalLineTo(13f)
            verticalLineTo(3f)
            close()
            moveToRelative(2f, 2f)
            verticalLineTo(9f)
            verticalLineTo(5f)
            close()
            moveTo(3f, 13f)
            horizontalLineToRelative(8f)
            verticalLineToRelative(8f)
            horizontalLineTo(3f)
            verticalLineTo(13f)
            close()
            moveToRelative(2f, 2f)
            verticalLineToRelative(4f)
            verticalLineTo(15f)
            close()
            moveTo(16f, 13f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(3f)
            horizontalLineToRelative(3f)
            verticalLineToRelative(2f)
            horizontalLineTo(18f)
            verticalLineToRelative(3f)
            horizontalLineTo(16f)
            verticalLineTo(18f)
            horizontalLineTo(13f)
            verticalLineTo(16f)
            horizontalLineToRelative(3f)
            verticalLineTo(13f)
            close()
            moveTo(15f, 5f)
            verticalLineTo(9f)
            horizontalLineToRelative(4f)
            verticalLineTo(5f)
            horizontalLineTo(15f)
            close()
            moveTo(5f, 5f)
            verticalLineTo(9f)
            horizontalLineTo(9f)
            verticalLineTo(5f)
            horizontalLineTo(5f)
            close()
            moveTo(5f, 15f)
            verticalLineToRelative(4f)
            horizontalLineTo(9f)
            verticalLineTo(15f)
            horizontalLineTo(5f)
            close()
        }
    }.build()
}
