/*
 * Copyright (C) 2026 The Edith AOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package io.chaldeaprjkt.gamespace.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.android.settingslib.widget.MainSwitchPreference
import dagger.hilt.android.AndroidEntryPoint
import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.data.AppSettings
import io.chaldeaprjkt.gamespace.discord.DiscordPresenceManager
import io.chaldeaprjkt.gamespace.gamebar.GameBarService
import io.chaldeaprjkt.gamespace.preferences.MediaAppListPreferences
import io.chaldeaprjkt.gamespace.preferences.appselector.AppSelectorActivity
import io.chaldeaprjkt.gamespace.preferences.appselector.AppSelectorFragment
import javax.inject.Inject
import kotlinx.coroutines.launch

/** The media switch and the apps whose media is mirrored to Discord. */
@AndroidEntryPoint(PreferenceFragmentCompat::class)
class DiscordMediaFragment : Hilt_DiscordMediaFragment() {

    @Inject
    lateinit var appSettings: AppSettings

    @Inject
    lateinit var discordPresence: DiscordPresenceManager

    private var mediaList: MediaAppListPreferences? = null

    private val mediaPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        result.data?.getStringExtra(MediaAppListPreferences.EXTRA_MEDIA_APP)?.let { packageName ->
            mediaList?.add(packageName)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.discord_media_preferences, rootKey)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        findPreference<MainSwitchPreference>(AppSettings.KEY_DISCORD_MEDIA)?.let { preference ->
            preference.isChecked = appSettings.discordMedia
            preference.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue == true
                // Must be stored before the service evaluates its guard.
                appSettings.discordMedia = enabled
                if (enabled) {
                    GameBarService.startMedia(requireContext())
                    discordPresence.restartMediaObservation()
                } else {
                    GameBarService.stopMedia(requireContext())
                    discordPresence.stopMedia()
                }
                true
            }
        }

        findPreference<Preference>(KEY_ADD_MEDIA_APP)
            ?.setOnPreferenceClickListener {
                mediaPicker.launch(Intent(requireContext(), AppSelectorActivity::class.java).apply {
                    putExtra(AppSelectorFragment.ARG_MEDIA, true)
                })
                true
            }

        mediaList = findPreference(MediaAppListPreferences.KEY_MEDIA_APP_LIST)
        mediaList?.let { list ->
            list.onSelectionChanged { discordPresence.onMediaListChanged() }
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    list.updateList(viewLifecycleOwner)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        findPreference<MainSwitchPreference>(AppSettings.KEY_DISCORD_MEDIA)?.isChecked =
            appSettings.discordMedia
    }

    private companion object {
        const val KEY_ADD_MEDIA_APP = "discord_media_add_app"
    }
}
