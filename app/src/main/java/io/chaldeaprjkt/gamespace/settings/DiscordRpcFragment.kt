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
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.android.settingslib.PrimarySwitchPreference
import com.android.settingslib.widget.MainSwitchPreference
import dagger.hilt.android.AndroidEntryPoint
import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.data.AppSettings
import io.chaldeaprjkt.gamespace.discord.DiscordPresenceManager
import io.chaldeaprjkt.gamespace.gamebar.GameBarService
import javax.inject.Inject

@AndroidEntryPoint(PreferenceFragmentCompat::class)
class DiscordRpcFragment : Hilt_DiscordRpcFragment() {

    @Inject
    lateinit var appSettings: AppSettings

    @Inject
    lateinit var discordPresence: DiscordPresenceManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private val statusListener: () -> Unit = {
        mainHandler.post { updateRestartSummary() }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.discord_rpc_preferences, rootKey)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        findPreference<MainSwitchPreference>(AppSettings.KEY_DISCORD_RPC)?.let { preference ->
            preference.isChecked = appSettings.discordRpc
            if (DiscordPresenceManager.isSupported(requireContext())) {
                preference.setOnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue == true
                    // Persist first: the service checks this value before it starts observing.
                    appSettings.discordRpc = enabled
                    if (!enabled) {
                        GameBarService.stopMedia(requireContext())
                        discordPresence.stopGame()
                    } else {
                        discordPresence.restart()
                        if (appSettings.discordMedia) {
                            GameBarService.startMedia(requireContext())
                        }
                    }
                    true
                }
            } else {
                preference.isEnabled = false
            }
        }

        findPreference<PrimarySwitchPreference>(AppSettings.KEY_DISCORD_MEDIA)?.let { preference ->
            preference.isChecked = appSettings.discordMedia
            preference.setOnPreferenceChangeListener { _, newValue -> setMediaEnabled(newValue == true) }
            // The whole row opens the media screen; only the switch toggles the feature.
            preference.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), DiscordMediaActivity::class.java))
                true
            }
        }

        findPreference<Preference>("discord_application_id")?.let { preference ->
            val appId = requireContext().getString(R.string.discord_application_id).trim()
            preference.summary = appId.ifEmpty { getString(R.string.discord_app_id_missing) }
        }

        discordPresence.addStatusListener(statusListener)
        updateRestartSummary()

        findPreference<Preference>("discord_restart")?.setOnPreferenceClickListener {
            discordPresence.restart()
            Toast.makeText(
                requireContext(),
                R.string.discord_restart_done,
                Toast.LENGTH_SHORT,
            ).show()
            updateRestartSummary()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        findPreference<MainSwitchPreference>(AppSettings.KEY_DISCORD_RPC)?.isChecked =
            appSettings.discordRpc
        findPreference<PrimarySwitchPreference>(AppSettings.KEY_DISCORD_MEDIA)?.isChecked =
            appSettings.discordMedia
        updateRestartSummary()
    }

    override fun onDestroyView() {
        discordPresence.removeStatusListener(statusListener)
        super.onDestroyView()
    }

    private fun setMediaEnabled(enabled: Boolean): Boolean {
        // Must be stored before the service evaluates its guard.
        appSettings.discordMedia = enabled
        if (enabled) {
            GameBarService.startMedia(requireContext())
            discordPresence.restartMediaObservation()
        } else {
            GameBarService.stopMedia(requireContext())
            discordPresence.stopMedia()
        }
        return true
    }

    private fun updateRestartSummary() {
        val preference = findPreference<Preference>("discord_restart") ?: return
        preference.summary = getString(
            when (discordPresence.status) {
                DiscordPresenceManager.Status.RUNNING -> R.string.discord_restart_running
                DiscordPresenceManager.Status.WAITING -> R.string.discord_restart_waiting
                DiscordPresenceManager.Status.OFF -> R.string.discord_restart_off
            },
        )
    }
}
