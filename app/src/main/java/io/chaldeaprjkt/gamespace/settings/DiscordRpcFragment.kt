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

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.android.settingslib.widget.MainSwitchPreference
import dagger.hilt.android.AndroidEntryPoint
import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.data.AppSettings
import io.chaldeaprjkt.gamespace.discord.DiscordPresenceManager
import javax.inject.Inject

@AndroidEntryPoint(PreferenceFragmentCompat::class)
class DiscordRpcFragment : Hilt_DiscordRpcFragment() {

    @Inject
    lateinit var appSettings: AppSettings

    @Inject
    lateinit var discordPresence: DiscordPresenceManager

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.discord_rpc_preferences, rootKey)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        findPreference<MainSwitchPreference>(AppSettings.KEY_DISCORD_RPC)?.let { preference ->
            preference.isChecked = appSettings.discordRpc
            if (DiscordPresenceManager.isSupported(requireContext())) {
                preference.setOnPreferenceChangeListener { _, newValue ->
                    if (newValue == false) {
                        discordPresence.stopGame(immediate = true)
                    } else {
                        discordPresence.restart()
                    }
                    true
                }
            } else {
                preference.isEnabled = false
                preference.summary = getString(R.string.discord_rpc_unavailable)
            }
        }

        findPreference<Preference>("discord_application_id")?.let { preference ->
            val appId = requireContext().getString(R.string.discord_application_id).trim()
            preference.summary = appId.ifEmpty { getString(R.string.discord_app_id_missing) }
        }

        findPreference<Preference>("discord_restart")?.setOnPreferenceClickListener {
            discordPresence.restart()
            Toast.makeText(
                requireContext(),
                R.string.discord_restart_done,
                Toast.LENGTH_SHORT,
            ).show()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        findPreference<MainSwitchPreference>(AppSettings.KEY_DISCORD_RPC)?.isChecked =
            appSettings.discordRpc
    }
}
