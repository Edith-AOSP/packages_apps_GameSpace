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
import android.util.Log
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import com.discord.socialsdk.DiscordSocialSdkInit
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint(CollapsingToolbarBaseActivity::class)
class DiscordRpcActivity : Hilt_DiscordRpcActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            DiscordSocialSdkInit.setEngineActivity(this)
        } catch (e: Throwable) {
            Log.w("GameSpaceDiscord", "Discord SDK context unavailable", e)
        }
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(
                    com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                    DiscordRpcFragment(),
                )
                .commit()
        }
    }
}
