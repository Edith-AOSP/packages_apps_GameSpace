/*
 * Copyright (C) 2026 The Edith AOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package io.chaldeaprjkt.gamespace.preferences

import android.content.Context
import android.util.AttributeSet
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.discord.MediaApp
import io.chaldeaprjkt.gamespace.discord.MediaAppRepository
import io.chaldeaprjkt.gamespace.utils.di.MediaEntryPoint
import io.chaldeaprjkt.gamespace.utils.entryPointOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The apps whose media is mirrored to Discord.
 *
 * Adding or removing an app patches the rows in place, so the screen updates without a reload.
 * See the media allowlist in `docs/gamespace-discord-rpc.md`.
 */
class MediaAppListPreferences @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : PreferenceCategory(context, attrs), Preference.OnPreferenceClickListener {

    private val repository by lazy {
        context.entryPointOf<MediaEntryPoint>().mediaApps()
    }
    private val rows = mutableListOf<Preference>()

    /** Loads the installed apps and shows the selection. */
    fun updateList(owner: LifecycleOwner) {
        owner.lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val candidates = repository.candidates().associateBy { it.packageName }
                repository.selected().map { candidates[it] ?: MediaApp(it, it) }
            }
            show(apps)
        }
    }

    /** Adds a package and shows it immediately. */
    fun add(packageName: String) {
        repository.setSelected(repository.selected() + packageName)
        selectionChanged?.invoke()
        val app = repository.candidates().firstOrNull { it.packageName == packageName }
            ?: MediaApp(packageName, packageName)
        insert(app)
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        val packageName = preference.key ?: return true
        repository.setSelected(repository.selected() - packageName)
        selectionChanged?.invoke()
        removeRow(packageName)
        return true
    }

    private fun show(apps: List<MediaApp>) {
        val wanted = apps.sortedBy { it.label.lowercase() }
        rows.filter { row -> wanted.none { it.packageName == row.key } }
            .toList()
            .forEach { removeRow(it.key) }
        wanted.forEach { app -> if (rows.none { it.key == app.packageName }) insert(app) }
    }

    private fun insert(app: MediaApp) {
        val row = Preference(context).apply {
            key = app.packageName
            title = app.label
            summary = removeSummary(app)
            isPersistent = false
            onPreferenceClickListener = this@MediaAppListPreferences
        }
        rows.add(row)
        sortRows()
    }

    /**
     * Re-adds the rows alphabetically.
     *
     * `PreferenceGroup` only appends, so the order is rebuilt by removing and re-adding the same
     * instances; the rows are not recreated, so their listeners and state survive.
     */
    private fun sortRows() {
        rows.sortBy { (it.title?.toString() ?: "").lowercase() }
        rows.forEach(::removePreference)
        rows.forEach(::addPreference)
    }

    private fun removeRow(packageName: String?) {
        val row = rows.firstOrNull { it.key == packageName } ?: return
        rows.remove(row)
        removePreference(row)
    }

    private fun removeSummary(app: MediaApp): String = if (app.summary.isEmpty()) {
        context.getString(R.string.discord_media_remove)
    } else {
        context.getString(R.string.discord_media_remove_package, app.summary)
    }

    fun onSelectionChanged(action: () -> Unit) {
        selectionChanged = action
    }

    private var selectionChanged: (() -> Unit)? = null

    companion object {
        const val KEY_MEDIA_APP_LIST = "gamespace_discord_media_list"
        const val EXTRA_MEDIA_APP = "selected_media_app"
    }
}
