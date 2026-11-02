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

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.chaldeaprjkt.gamespace.R
import org.json.JSONArray
import org.json.JSONObject

enum class PanelTileId(val key: String) {
    FPS("fps"),
    GESTURES("gestures"),
    STAY_AWAKE("stay_awake"),
    HEADS_UP("heads_up"),
    RINGER("ringer"),
    DND("dnd"),
    AUTO_BRIGHTNESS("auto_brightness"),
    ROTATION_LOCK("rotation_lock");

    companion object {
        fun fromKey(key: String): PanelTileId? = values().firstOrNull { it.key == key }
    }
}

data class PanelTileSpec(
    val id: PanelTileId,
    @StringRes val titleRes: Int,
    @DrawableRes val iconRes: Int,
    val defaultVisible: Boolean = true,
)

data class PanelTileConfig(
    val id: PanelTileId,
    val visible: Boolean,
)

object PanelTiles {
    val specs = listOf(
        PanelTileSpec(PanelTileId.FPS, R.string.panel_tile_fps, R.drawable.ic_fps),
        PanelTileSpec(
            PanelTileId.GESTURES,
            R.string.panel_tile_gestures,
            R.drawable.ic_gesture,
        ),
        PanelTileSpec(PanelTileId.STAY_AWAKE, R.string.panel_tile_stay_awake, R.drawable.ic_awake),
        PanelTileSpec(
            PanelTileId.HEADS_UP,
            R.string.panel_tile_heads_up,
            R.drawable.ic_action_heads_up,
        ),
        PanelTileSpec(
            PanelTileId.RINGER,
            R.string.panel_tile_ringer,
            R.drawable.ic_ringer,
            defaultVisible = false,
        ),
        PanelTileSpec(
            PanelTileId.DND,
            R.string.panel_tile_dnd,
            R.drawable.ic_dnd,
            defaultVisible = false,
        ),
        PanelTileSpec(
            PanelTileId.AUTO_BRIGHTNESS,
            R.string.panel_tile_auto_brightness,
            R.drawable.ic_auto_brightness,
            defaultVisible = false,
        ),
        PanelTileSpec(
            PanelTileId.ROTATION_LOCK,
            R.string.panel_tile_rotation_lock,
            R.drawable.ic_rotation_lock,
            defaultVisible = false,
        ),
    )

    fun spec(id: PanelTileId): PanelTileSpec = specs.first { it.id == id }

    fun defaults(): List<PanelTileConfig> =
        specs.map { PanelTileConfig(it.id, visible = it.defaultVisible) }

    fun visible(config: List<PanelTileConfig>): List<PanelTileConfig> =
        config.filter { it.visible }

    fun decode(raw: String?): List<PanelTileConfig> {
        if (raw.isNullOrBlank()) return defaults()
        return try {
            val array = JSONArray(raw)
            val parsed = mutableListOf<PanelTileConfig>()
            val seen = mutableSetOf<PanelTileId>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = PanelTileId.fromKey(item.optString("id")) ?: continue
                if (!seen.add(id)) continue
                parsed += PanelTileConfig(id, item.optBoolean("visible", true))
            }
            specs.forEach { spec ->
                if (spec.id !in seen) {
                    parsed += PanelTileConfig(spec.id, visible = false)
                }
            }
            parsed
        } catch (_: Exception) {
            defaults()
        }
    }

    fun encode(config: List<PanelTileConfig>): String {
        val array = JSONArray()
        config.forEach { tile ->
            array.put(
                JSONObject()
                    .put("id", tile.id.key)
                    .put("visible", tile.visible)
            )
        }
        return array.toString()
    }
}
