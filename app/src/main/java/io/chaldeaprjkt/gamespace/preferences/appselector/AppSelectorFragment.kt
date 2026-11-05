/*
 * Copyright (C) 2026 The Edith AOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package io.chaldeaprjkt.gamespace.preferences.appselector

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import dagger.hilt.android.AndroidEntryPoint
import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.data.SystemSettings
import io.chaldeaprjkt.gamespace.discord.MediaApp
import io.chaldeaprjkt.gamespace.discord.MediaAppRepository
import io.chaldeaprjkt.gamespace.preferences.MediaAppListPreferences
import io.chaldeaprjkt.gamespace.preferences.appselector.adapter.AppsAdapter
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Picks an app for the list the caller asked for.
 *
 * The game list offers every installed user app; the media list offers the apps that can hold a
 * media session and are not already selected.
 */
@AndroidEntryPoint(Fragment::class)
class AppSelectorFragment : Hilt_AppSelectorFragment(), SearchView.OnQueryTextListener,
    MenuItem.OnActionExpandListener {
    @Inject
    lateinit var settings: SystemSettings

    @Inject
    lateinit var mediaApps: MediaAppRepository

    private var appListView: RecyclerView? = null
    private var appsAdapter: AppsAdapter? = null
    private var appBarLayout: AppBarLayout? = null

    private val menuProvider = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            menuInflater.inflate(R.menu.app_selector_menu, menu)
            val searchMenuItem = menu.findItem(R.id.app_search_menu)
            val searchView = searchMenuItem.actionView as? SearchView
            searchView?.setOnQueryTextListener(this@AppSelectorFragment)
            searchView?.queryHint = getString(R.string.app_search_title)
            searchMenuItem.setOnActionExpandListener(this@AppSelectorFragment)
        }

        override fun onMenuItemSelected(menuItem: MenuItem) = false
    }

    private val forMedia: Boolean
        get() = requireArguments().getBoolean(ARG_MEDIA)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        appBarLayout = activity?.findViewById(com.android.settingslib.collapsingtoolbar.R.id.app_bar)
        return inflater.inflate(R.layout.app_selector, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.addMenuProvider(menuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED)
        view.findViewById<RecyclerView>(R.id.app_list)?.apply {
            setupAppListView(this)
        }
    }

    private fun setupAppListView(view: RecyclerView) {
        appListView = view
        view.layoutManager = LinearLayoutManager(view.context)

        if (forMedia) {
            viewLifecycleOwner.lifecycleScope.launch {
                val pm = requireContext().packageManager
                val selected = mediaApps.selected()
                val apps = withContext(Dispatchers.IO) {
                    mediaApps.candidates()
                        .filter { it.packageName !in selected }
                        .map { it.toApplicationInfo(pm) }
                        .filterNotNull()
                }
                appsAdapter = AppsAdapter(pm, apps)
                view.adapter = appsAdapter
                appsAdapter?.onItemClick(::finishWith)
            }
            return
        }

        val flags = PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
        val apps = view.context.packageManager
            .getInstalledApplications(flags)
            .filter {
                it.packageName != context?.packageName &&
                    it.flags and ApplicationInfo.FLAG_SYSTEM == 0 &&
                    !settings.userGames.any { game -> game.packageName == it.packageName }
            }
            .sortedBy { it.loadLabel(view.context.packageManager).toString().lowercase() }

        appsAdapter = AppsAdapter(view.context.packageManager, apps)
        view.adapter = appsAdapter
        appsAdapter?.onItemClick(::finishWith)
    }

    private fun finishWith(info: ApplicationInfo) {
        activity?.setResult(Activity.RESULT_OK, Intent().apply {
            putExtra(
                if (forMedia) MediaAppListPreferences.EXTRA_MEDIA_APP
                else io.chaldeaprjkt.gamespace.preferences.AppListPreferences.EXTRA_APP,
                info.packageName,
            )
        })
        activity?.finish()
    }

    private fun MediaApp.toApplicationInfo(pm: PackageManager): ApplicationInfo? = try {
        pm.getApplicationInfo(
            packageName,
            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
        )
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    override fun onQueryTextSubmit(query: String?) = false

    override fun onQueryTextChange(newText: String?): Boolean {
        appsAdapter?.filterWith(newText)
        return false
    }

    override fun onMenuItemActionExpand(item: MenuItem): Boolean {
        appBarLayout?.setExpanded(false, false)
        appListView?.let { ViewCompat.setNestedScrollingEnabled(it, false) }
        return true
    }

    override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
        appBarLayout?.setExpanded(false, false)
        appListView?.let { ViewCompat.setNestedScrollingEnabled(it, true) }
        return true
    }

    companion object {
        const val ARG_MEDIA = "media_app_selector"
    }
}
