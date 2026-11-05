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
import android.util.Log
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a game's Discord large-image key from the icon manifest.
 *
 * The manifest is fetched once and cached for 12 hours. See the icon manifest section of
 * `docs/gamespace-discord-rpc.md`.
 */
@Singleton
class DiscordIconRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    @Volatile
    private var cached: Map<String, String>? = null

    suspend fun iconFor(packageName: String): String? = withContext(Dispatchers.IO) {
        load().get(packageName)
    }

    private suspend fun load(): Map<String, String> = mutex.withLock {
        cached?.let { return it }

        val now = System.currentTimeMillis()
        val storedAt = prefs.getLong(KEY_TIME, 0L)
        val stored = prefs.getString(KEY_JSON, null)

        if (stored != null && now - storedAt < TTL) {
            parse(stored)?.let {
                cached = it
                return it
            }
        }

        fetch()?.let { fresh ->
            prefs.edit().putString(KEY_JSON, fresh).putLong(KEY_TIME, now).apply()
            parse(fresh)?.let {
                cached = it
                return it
            }
        }

        stored?.let { parse(it) }?.let {
            cached = it
            return it
        }

        emptyMap()
    }

    private fun fetch(): String? {
        for (endpoint in ENDPOINTS) {
            try {
                val text = read(endpoint)
                if (text.contains("\"games\"")) return text
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch $endpoint: ${e.message}")
            }
        }
        return null
    }

    private fun read(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "GameSpace/${android.os.Build.VERSION.RELEASE}")
            if (connection.responseCode !in 200..299) {
                error("HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(json: String): Map<String, String>? = try {
        val root = JsonParser.parseString(json).asJsonObject
        val games = root.getAsJsonObject("games") ?: return null
        val result = HashMap<String, String>()
        games.entrySet().forEach { (packageName, value) ->
            val icon = value.asJsonObject.get("icon")
                ?.takeIf { !it.isJsonNull }
                ?.asString
            if (!icon.isNullOrBlank()) result[packageName] = icon
        }
        result
    } catch (e: Exception) {
        Log.w(TAG, "Bad icon manifest: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "DiscordIcons"
        private const val PREFS = "discord_rpc_icons"
        private const val KEY_JSON = "manifest"
        private const val KEY_TIME = "fetched_at"
        private const val TTL = 12 * 60 * 60 * 1000L

        private val ENDPOINTS = listOf(
            "https://cdn.jsdelivr.net/gh/Edith-AOSP/gamespace-rpc-icons@main/index.json",
            "https://raw.githubusercontent.com/Edith-AOSP/gamespace-rpc-icons/main/index.json",
        )
    }
}
