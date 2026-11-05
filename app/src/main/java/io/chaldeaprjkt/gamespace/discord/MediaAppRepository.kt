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
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.session.MediaSessionManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.chaldeaprjkt.gamespace.data.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

/** One app that can hold a media session, as the activity list shows it. */
data class MediaApp(
    val packageName: String,
    val label: String,
) {
    /** What is shown under the label: the package name when the label already carries it. */
    val summary: String
        get() = if (label == packageName || label.contains(packageName)) "" else packageName
}

/**
 * Decides which apps may put their media on Discord.
 *
 * Media sessions exist for far more than music: browsers, screen recorders, games and calls all
 * hold one. Rather than guess from the session's usage, the set is the list the user chose. See
 * `docs/gamespace-discord-rpc.md`.
 */
@Singleton
class MediaAppRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettings: AppSettings,
) {
    /**
     * Every installed app the user can pick, sorted by label.
     *
     * Apps currently holding a media session come first, but the list is not limited to them: a
     * player that is closed, or one that is not on the well-known list, has to be selectable
     * before it ever opens a session.
     */
    fun candidates(): List<MediaApp> {
        val pm = context.packageManager
        val holders = activeSessionPackages() + SUPPORTED_MEDIA_APPS
        val flags = PackageManager.ApplicationInfoFlags.of(0L)
        return pm.getInstalledApplications(flags).asSequence()
            .filter { info ->
                info.packageName != context.packageName &&
                    (info.flags and ApplicationInfo.FLAG_SYSTEM == 0 ||
                        info.packageName in holders)
            }
            .map { info ->
                MediaApp(info.packageName, info.loadLabel(pm).toString().ifBlank { info.packageName })
            }
            .sortedWith(compareByDescending<MediaApp> { it.packageName in holders }
                .thenBy { it.label.lowercase() })
            .toList()
    }

    /** The apps currently allowed to publish their media, in the order they were stored. */
    fun selected(): Set<String> = enforceSelection() ?: candidates().mapTo(mutableSetOf()) {
        it.packageName
    }

    fun setSelected(packages: Set<String>) {
        appSettings.discordMediaPackages = packages
    }

    /**
     * Whether this package may publish. Never chosen means all of them, so a fresh install keeps
     * working without opening the picker.
     */
    fun isAllowed(packageName: String): Boolean =
        enforceSelection()?.contains(packageName) ?: true

    /**
     * The stored selection with uninstalled packages dropped.
     *
     * Discord keeps showing a removed app's media for as long as the package stays in the set, so
     * a stale entry is removed the first time the selection is read.
     */
    private fun enforceSelection(): Set<String>? {
        val stored = appSettings.discordMediaPackages ?: return null
        val installed = stored.filter { appInfo(context.packageManager, it) != null }
        if (installed.size != stored.size) {
            appSettings.discordMediaPackages = installed.toSet()
        }
        return installed.toSet()
    }

    private fun activeSessionPackages(): Set<String> = try {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as
            MediaSessionManager
        manager.getActiveSessions(null)
            .filter { usageOf(it) != AudioAttributes.USAGE_VOICE_COMMUNICATION }
            .mapNotNull { it.packageName }
            .toSet()
    } catch (e: Exception) {
        Log.w(TAG, "Unable to read the active media sessions", e)
        emptySet()
    }


    private fun usageOf(controller: android.media.session.MediaController): Int? = runCatching {
        controller.playbackInfo?.audioAttributes?.usage
    }.getOrNull()

    private fun appInfo(pm: PackageManager, packageName: String): ApplicationInfo? = try {
        pm.getApplicationInfo(
            packageName,
            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
        )
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private companion object {
        const val TAG = "MediaAppRepository"

        /**
         * Players users mirror most often, listed even when they hold no session right now.
         *
         * A player that is installed but idle has no active session to discover, and the list is
         * most useful exactly then.
         */
        val SUPPORTED_MEDIA_APPS = setOf(
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "com.google.android.youtube",
            "com.amazon.mp3",
            "com.deezer.android.app",
            "com.soundcloud.android",
            "com.aspiro.tidal",
            "tv.plex.lantri",
            "com.google.android.apps.podcasts",
            "com.pocketcasts",
            "com.bambuna.podcastaddict",
            "org.antennapod",
            "com.apple.android.music",
            "com.netease.cloudmusic",
            "com.tencent.qqmusic",
            "com.kugou.android",
            "com.xiaomi.music",
        )
    }
}
