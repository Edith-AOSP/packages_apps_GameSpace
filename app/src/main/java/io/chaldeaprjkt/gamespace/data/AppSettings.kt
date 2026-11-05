/*
 * Copyright (C) 2021 Chaldeaprjkt
 * Copyright (C) 2023 risingOS Android Project
 *               2022 crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.chaldeaprjkt.gamespace.data

import android.app.Service
import android.content.Context
import android.view.WindowManager
import androidx.preference.PreferenceManager
import io.chaldeaprjkt.gamespace.utils.dp
import io.chaldeaprjkt.gamespace.utils.statusbarHeight
import javax.inject.Inject

class AppSettings @Inject constructor(private val context: Context) {

    private val db by lazy { PreferenceManager.getDefaultSharedPreferences(context) }
    private val wm by lazy { context.getSystemService(Service.WINDOW_SERVICE) as WindowManager }

    var x
        get() = db.getInt("offset_x", wm.maximumWindowMetrics.bounds.width() / 2)
        set(point) = db.edit().putInt("offset_x", point).apply()

    var y
        get() = db.getInt("offset_y", context.statusbarHeight + 8.dp)
        set(point) = db.edit().putInt("offset_y", point).apply()

    var showFps
        get() = db.getBoolean("show_fps", false)
        set(point) = db.edit().putBoolean("show_fps", point).apply()

    var noAutoBrightness
        get() = db.getBoolean(KEY_AUTO_BRIGHTNESS_DISABLE, true)
        set(it) = db.edit().putBoolean(KEY_AUTO_BRIGHTNESS_DISABLE, it).apply()

    var noThreeScreenshot
        get() = db.getBoolean(KEY_3SCREENSHOT_DISABLE, false)
        set(it) = db.edit().putBoolean(KEY_3SCREENSHOT_DISABLE, it).apply()

    var stayAwake
        get() = db.getBoolean(KEY_STAY_AWAKE, false)
        set(value) = db.edit().putBoolean(KEY_STAY_AWAKE, value).apply()

    var danmakuNotification
        get() = db.getBoolean(KEY_DANMAKU_NOTIFICATION_MODE, true)
        set(value) = db.edit().putBoolean(KEY_DANMAKU_NOTIFICATION_MODE, value).apply()

    var callsMode: Int
        get() = db.getString(KEY_CALLS_MODE, "0")?.toIntOrNull() ?: 0
        set(value) = db.edit().putString(KEY_CALLS_MODE, value.toString()).apply()

    var ringerMode: Int
        get() = db.getString(KEY_RINGER_MODE, "3")?.toIntOrNull() ?: 3
        set(value) = db.edit().putString(KEY_RINGER_MODE, value.toString()).apply()

    var menuOpacity: Int
        get() = db.getInt(KEY_MENU_OPACITY, 100)
        set(value) = db.edit().putInt(KEY_MENU_OPACITY, value).apply()

    var lockGesture
        get() = db.getBoolean(KEY_LOCK_GESTURE, false)
        set(value) = db.edit().putBoolean(KEY_LOCK_GESTURE, value).apply()

    var discordRpc
        get() = db.getBoolean(KEY_DISCORD_RPC, false)
        set(value) = db.edit().putBoolean(KEY_DISCORD_RPC, value).apply()

    var discordMedia
        get() = db.getBoolean(KEY_DISCORD_MEDIA, false)
        set(value) = db.edit().putBoolean(KEY_DISCORD_MEDIA, value).apply()

    /**
     * Packages whose media sessions are mirrored to Discord.
     *
     * `null` means the user has not chosen yet, which every installed media app qualifies for;
     * an empty set means the user deselected all of them.
     */
    var discordMediaPackages: Set<String>?
        get() = db.getStringSet(KEY_DISCORD_MEDIA_PACKAGES, null)
        set(value) = db.edit().apply {
            if (value == null) remove(KEY_DISCORD_MEDIA_PACKAGES)
            else putStringSet(KEY_DISCORD_MEDIA_PACKAGES, value)
        }.apply()

    /**
     * Registered games whose presence is hidden from Discord.
     *
     * The list stores the exceptions rather than the allowed packages, so every newly added game
     * starts with presence on and existing installs keep their current behaviour.
     */
    var discordGameDisabled: Set<String>
        get() = db.getStringSet(KEY_DISCORD_GAME_DISABLED, emptySet())?.toSet().orEmpty()
        set(value) = db.edit().putStringSet(KEY_DISCORD_GAME_DISABLED, value).apply()

    var panelTiles
        get() = db.getString(KEY_PANEL_TILES, "").orEmpty()
        set(value) = db.edit().putString(KEY_PANEL_TILES, value).apply()

    companion object {
        const val KEY_AUTO_BRIGHTNESS_DISABLE = "gamespace_auto_brightness_disabled"
        const val KEY_3SCREENSHOT_DISABLE = "gamespace_tfgesture_disabled"
        const val KEY_STAY_AWAKE = "gamespace_stay_awake"
        const val KEY_DANMAKU_NOTIFICATION_MODE = "gamespace_danmaku_notification_mode"
        const val KEY_CALLS_MODE = "gamespace_calls_mode"
        const val KEY_RINGER_MODE = "gamespace_ringer_mode"
        const val KEY_LOCK_GESTURE = "gamespace_lock_gesture"
        const val KEY_MENU_OPACITY = "gamespace_menu_opacity"
        const val KEY_DISCORD_RPC = "gamespace_discord_rpc"
        const val KEY_DISCORD_MEDIA = "gamespace_discord_media"
        const val KEY_DISCORD_MEDIA_PACKAGES = "gamespace_discord_media_packages"
        const val KEY_DISCORD_GAME_DISABLED = "gamespace_discord_game_disabled"
        const val KEY_DISCORD_MEDIA_LIST = "gamespace_discord_media_list"
        const val KEY_PANEL_TILES = "gamespace_panel_tiles"
    }
}
