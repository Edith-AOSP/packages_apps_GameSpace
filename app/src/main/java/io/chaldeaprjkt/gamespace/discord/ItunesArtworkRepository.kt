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
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Resolves album artwork through the public iTunes search, the same source the expanded media
 * player uses for motion art. Discord accepts a plain HTTPS image URL for the large image, so no
 * hosting or upload step is needed.
 *
 * Results are cached, including misses, so a track without a match is not re-searched on every
 * metadata tick.
 */
@Singleton
class ItunesArtworkRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    suspend fun artworkFor(artist: String?, title: String, album: String?): String? =
        withContext(Dispatchers.IO) {
            val searchTitle = title.takeIf { it.isNotBlank() } ?: return@withContext null
            val searchArtist = artist?.takeIf { it.isNotBlank() }
            val key = cacheKey(searchArtist, searchTitle, album)

            mutex.withLock {
                cached(key)?.let { return@withLock it.ifEmpty { null } }
                val resolved = lookup(searchArtist, searchTitle, album)
                store(key, resolved ?: MISS)
                resolved
            }
        }

    private suspend fun lookup(artist: String?, title: String, album: String?): String? {
        val term = listOfNotNull(artist, title).joinToString(" ")
        val country = Locale.getDefault().country.lowercase().ifEmpty { "us" }
        val url = "https://itunes.apple.com/search?term=" +
            "${URLEncoder.encode(term, "UTF-8")}&entity=song&limit=10&country=$country"
        val body = fetch(url) ?: return null
        val results = try {
            JSONObject(body).optJSONArray("results") ?: return null
        } catch (e: Exception) {
            Log.w(TAG, "Bad iTunes response", e)
            return null
        }

        val wantedTitle = normalise(title)
        val wantedArtist = artist?.let(::normalise).orEmpty()
        val wantedAlbum = album?.let(::normalise).orEmpty()

        var bestArtwork: String? = null
        var bestScore = Int.MIN_VALUE
        for (index in 0 until results.length()) {
            val song = results.optJSONObject(index) ?: continue
            val songTitle = normalise(song.optString("trackName"))
            if (songTitle.isEmpty() || !titleMatches(songTitle, wantedTitle)) continue

            var score = 0
            if (!wantedArtist.isEmpty()) {
                val songArtist = normalise(song.optString("artistName"))
                if (!artistMatches(songArtist, wantedArtist)) continue
                score += 2
            }
            if (!wantedAlbum.isEmpty()) {
                val collection = normalise(song.optString("collectionName"))
                if (collection.isNotEmpty() && collection == wantedAlbum) score += 1
            }

            val artwork = song.optString("artworkUrl100")
                .takeIf { it.isNotBlank() }
                ?.replace(ARTWORK_SIZE, LARGE_ARTWORK_SIZE)
                ?: continue
            if (score > bestScore) {
                bestScore = score
                bestArtwork = artwork
            }
        }
        return bestArtwork
    }

    private fun titleMatches(candidate: String, wanted: String): Boolean =
        candidate == wanted || candidate.startsWith(wanted) || wanted.startsWith(candidate)

    private fun artistMatches(candidate: String, wanted: String): Boolean =
        candidate.contains(wanted) || wanted.contains(candidate)

    private fun cacheKey(artist: String?, title: String, album: String?): String =
        listOfNotNull(artist, title, album).joinToString("|") { normalise(it) }

    private fun cached(key: String): String? =
        prefs.getString(key, null)?.takeIf {
            System.currentTimeMillis() - prefs.getLong("$key$TIME_SUFFIX", 0L) < TTL
        }

    private fun store(key: String, value: String) {
        prefs.edit()
            .putString(key, value)
            .putLong("$key$TIME_SUFFIX", System.currentTimeMillis())
            .apply()
    }

    private fun fetch(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "GameSpace")
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "iTunes lookup failed: ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    /** Same normalization the media player uses, so both pick the same match. */
    private fun normalise(text: String) = text.lowercase()
        .replace(Regex("\\(.*?\\)|\\[.*?]"), "")
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

    private companion object {
        const val TAG = "DiscordArtwork"
        const val PREFS = "discord_rpc_artwork"
        const val TIME_SUFFIX = ":t"
        const val ARTWORK_SIZE = "100x100bb"
        const val LARGE_ARTWORK_SIZE = "600x600bb"
        const val MISS = ""
        const val TIMEOUT_MS = 6000
        const val TTL = 30L * 24 * 60 * 60 * 1000
    }
}
