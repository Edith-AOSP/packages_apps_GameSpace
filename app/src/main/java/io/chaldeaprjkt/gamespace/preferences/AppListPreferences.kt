/*
 * Copyright (C) 2021 Chaldeaprjkt
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
package io.chaldeaprjkt.gamespace.preferences

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.AttributeSet
import androidx.activity.result.ActivityResult
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.android.settingslib.PrimarySwitchPreference
import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.data.AppSettings
import io.chaldeaprjkt.gamespace.data.GameConfig
import io.chaldeaprjkt.gamespace.data.UserGame
import io.chaldeaprjkt.gamespace.settings.PerAppSettingsFragment
import io.chaldeaprjkt.gamespace.utils.GameModeUtils.Companion.describeGameMode
import io.chaldeaprjkt.gamespace.utils.di.ServiceViewEntryPoint
import io.chaldeaprjkt.gamespace.utils.entryPointOf


class AppListPreferences @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    PreferenceCategory(context, attrs), Preference.OnPreferenceClickListener {

    private val apps = mutableListOf<UserGame>()
    private val systemSettings by lazy {
        context.entryPointOf<ServiceViewEntryPoint>().systemSettings()
    }

    private val gameModeUtils by lazy {
        context.entryPointOf<ServiceViewEntryPoint>().gameModeUtils()
    }

    private val appSettings by lazy {
        context.entryPointOf<ServiceViewEntryPoint>().appSettings()
    }

    private lateinit var registeredAppClickAction: (String) -> Unit
    private var registeredAppPresenceAction: ((String, Boolean) -> Unit)? = null

    init {
        isOrderingAsAdded = false
    }

    private val makeAddPref by lazy {
        Preference(context).apply {
            title = "Add"
            key = KEY_ADD_GAME
            setIcon(R.drawable.ic_add)
            isPersistent = false
            onPreferenceClickListener = this@AppListPreferences
        }
    }

    private fun getAppInfo(packageName: String): ApplicationInfo? = try {
        val flags = PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
        context.packageManager.getApplicationInfo(packageName, flags)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    fun updateAppList() {
        apps.clear()
        if (!systemSettings.userGames.isNullOrEmpty()) {
            apps.addAll(systemSettings.userGames)
        }
        removeAll()
        addPreference(makeAddPref)
        apps.filter { getAppInfo(it.packageName) != null }
            .map {
                val packageName = it.packageName
                val info = getAppInfo(packageName)
                PrimarySwitchPreference(context).apply {
                    key = packageName
                    title = info?.loadLabel(context.packageManager)
                    summary = context.describeGameMode(it.mode)
                    icon = info?.loadIcon(context.packageManager)
                    layoutResource = R.layout.library_item
                    isPersistent = false
                    isChecked = packageName !in appSettings.discordGameDisabled
                    onPreferenceClickListener = this@AppListPreferences
                    setOnPreferenceChangeListener { _, newValue ->
                        val enabled = newValue == true
                        // Keep the exceptions in sync with the switch before the manager looks
                        // at them, since it may clear or publish right away.
                        appSettings.discordGameDisabled =
                            appSettings.discordGameDisabled.let { disabled ->
                                if (enabled) disabled - packageName else disabled + packageName
                            }
                        registeredAppPresenceAction?.invoke(packageName, enabled)
                        true
                    }
                }
            }
            .sortedBy { it.title.toString().lowercase() }
            .forEach(::addPreference)
    }

    private fun registerApp(packageName: String) {
        if (!apps.any { it.packageName == packageName }) {
            apps.add(UserGame(packageName))
        }
        systemSettings.userGames = apps
        gameModeUtils.setIntervention(packageName, GameConfig.ModeBuilder.build())
        updateAppList()
    }

    private fun unregisterApp(packageName: String) {
        apps.removeIf { it.packageName == packageName }
        systemSettings.userGames = apps
        gameModeUtils.setIntervention(packageName, null)
        updateAppList()
    }

    override fun onAttached() {
        super.onAttached()
        updateAppList()
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        if (preference != makeAddPref && ::registeredAppClickAction.isInitialized) {
            registeredAppClickAction(preference.key)
        }
        return true
    }

    fun onRegisteredAppClick(action: (String) -> Unit) {
        registeredAppClickAction = action
    }

    /** Notifies the caller when a game's Rich Presence switch changes. */
    fun onRegisteredAppPresenceChange(action: (String, Boolean) -> Unit) {
        registeredAppPresenceAction = action
    }

    fun usePerAppResult(result: ActivityResult?) {
        result?.takeIf { it.resultCode == Activity.RESULT_OK }
            ?.data?.getStringExtra(PerAppSettingsFragment.PREF_UNREGISTER)
            ?.let { unregisterApp(it) }
    }

    fun useSelectorResult(result: ActivityResult?) {
        result?.takeIf { it.resultCode == Activity.RESULT_OK }
            ?.data?.getStringExtra(EXTRA_APP)
            ?.let { registerApp(it) }
    }

    companion object {
        const val KEY_ADD_GAME = "add_game"
        const val EXTRA_APP = "selected_app"
    }
}
