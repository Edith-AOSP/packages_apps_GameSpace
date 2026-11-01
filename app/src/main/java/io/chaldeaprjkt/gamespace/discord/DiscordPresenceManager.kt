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
import android.content.Intent
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiscordPresenceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettings: AppSettings,
    private val icons: DiscordIconRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var activePackage: String? = null
    private var sessionStart = 0L
    private var stopJob: Job? = null
    private var pendingPackage: String? = null

    init {
        instance = this
    }

    fun startGame(packageName: String) {
        if (!appSettings.discordRpc) return
        if (!isSupported(context)) return

        stopJob?.cancel()
        stopJob = null

        if (!DiscordNative.hasContext()) {
            pendingPackage = packageName
            startContextActivity()
            return
        }

        startGameInternal(packageName)
    }

    private fun startGameInternal(packageName: String) {
        val appId = applicationId(context) ?: return
        if (!DiscordNative.nativeInit(appId)) {
            Log.w(TAG, "Discord SDK initialization failed")
            return
        }

        val gameName = gameLabel(packageName)
        val presenceName = context.getString(R.string.discord_presence_name)
        val deviceModel = deviceModel()

        if (activePackage != packageName) {
            if (activePackage != null) {
                DiscordNative.clearPresence()
            }
            activePackage = packageName
            sessionStart = System.currentTimeMillis() / 1000
        }

        // Re-applying the same package must not reset the elapsed timer.
        DiscordNative.setPresence(presenceName, gameName, deviceModel, null, sessionStart)

        scope.launch {
            val icon = icons.iconFor(packageName) ?: return@launch
            if (activePackage == packageName) {
                DiscordNative.setPresence(presenceName, gameName, deviceModel, icon, sessionStart)
            }
        }
    }

    fun restart() {
        val packageName = activePackage ?: pendingPackage
        val start = sessionStart
        stopJob?.cancel()
        stopJob = null
        DiscordNative.reset()

        if (packageName == null) return

        activePackage = packageName
        sessionStart = if (start > 0) start else System.currentTimeMillis() / 1000
        if (DiscordNative.hasContext()) {
            startGameInternal(packageName)
        } else {
            pendingPackage = packageName
            startContextActivity()
        }
    }

    fun stopGame(immediate: Boolean = false) {
        pendingPackage = null
        val packageName = activePackage ?: return
        stopJob?.cancel()
        stopJob = null

        if (immediate) {
            activePackage = null
            DiscordNative.clearPresence()
            return
        }

        // GameSpace can stop/start around overlays, task switches, and service
        // restarts. Keep presence alive briefly so the timestamp survives.
        stopJob = scope.launch {
            delay(STOP_GRACE_MS)
            if (activePackage == packageName) {
                activePackage = null
                DiscordNative.clearPresence()
            }
        }
    }

    private fun startPendingGame() {
        val packageName = pendingPackage ?: return
        pendingPackage = null
        if (DiscordNative.hasContext()) {
            startGameInternal(packageName)
        }
    }

    private fun startContextActivity() {
        try {
            context.startActivity(
                Intent(context, DiscordContextActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Unable to start Discord context activity", e)
        }
    }

    private fun deviceModel(): String =
        Build.MODEL?.takeIf { it.isNotBlank() } ?: Build.DEVICE

    private fun gameLabel(packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: Exception) {
        packageName
    }

    companion object {
        private const val TAG = "DiscordPresence"
        private const val DISCORD_PACKAGE = "com.discord"
        private const val STOP_GRACE_MS = 60_000L

        @Volatile
        private var instance: DiscordPresenceManager? = null

        fun onContextReady() {
            instance?.startPendingGame()
        }

        fun isSupported(context: Context): Boolean {
            if (!DiscordNative.isLoaded || !DiscordNative.nativeAvailable()) return false
            if (applicationId(context) == null) return false
            return try {
                context.packageManager.getPackageInfo(DISCORD_PACKAGE, 0)
                true
            } catch (e: Exception) {
                false
            }
        }

        private fun applicationId(context: Context): String? =
            context.getString(R.string.discord_application_id)
                .trim()
                .takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    }
}
