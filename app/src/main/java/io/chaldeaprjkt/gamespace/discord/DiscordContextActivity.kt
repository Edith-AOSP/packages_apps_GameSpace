/*
 * Copyright (C) 2026 The Edith AOSP Project
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

package io.chaldeaprjkt.gamespace.discord

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import com.discord.socialsdk.DiscordSocialSdkInit

/**
 * Hosts the Activity the Social SDK resolves and binds the Discord RPC service from.
 *
 * The SDK keeps a static Activity reference, so this instance stays alive for as long as presence
 * is in use. It hides its own task shortly after it is launched; see
 * `docs/gamespace-discord-rpc.md`.
 */
class DiscordContextActivity : Activity() {
    private val hideRunnable = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        // Moving the task to the back while the launch transition is still opening it makes
        // WindowManager abort that transition and tear the fresh host down, which left the SDK
        // context alive for barely a frame. Wait for the launch to settle first.
        Log.i(TAG, "Host moving to back")
        moveTaskToBack(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        Log.i(TAG, "Host created (finish=${intent?.getBooleanExtra(EXTRA_FINISH, false)})")
        // A real window rather than windowNoDisplay: that attribute requires finish() before
        // onResume, which would leave the SDK without its context.
        window.setLayout(1, 1)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        if (intent?.getBooleanExtra(EXTRA_FINISH, false) == true) {
            finish()
            return
        }
        bindEngine()
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_FINISH, false) == true) {
            finish()
            return
        }
        // Another Activity may have claimed the SDK context in the meantime (the settings screen
        // does), so a repeat launch has to take it back instead of trusting the first onCreate.
        bindEngine()
    }

    private fun bindEngine() {
        try {
            DiscordSocialSdkInit.setEngineActivity(this)
        } catch (e: Throwable) {
            Log.w(TAG, "Unable to provide Discord SDK context", e)
            finish()
            return
        }
        DiscordPresenceManager.onContextReady()
    }

    override fun onStart() {
        super.onStart()
        Log.i(TAG, "Host started")
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "Host resumed")
        window.decorView.removeCallbacks(hideRunnable)
        window.decorView.postDelayed(hideRunnable, HIDE_DELAY_MS)
    }

    override fun onPause() {
        Log.i(TAG, "Host paused")
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        Log.i(TAG, "Host stopped (finishing=$isFinishing)")
    }

    override fun finish() {
        Log.i(TAG, "Host finish()")
        super.finish()
    }

    override fun onDestroy() {
        Log.i(TAG, "Host destroyed (finishing=$isFinishing changing=${isChangingConfigurations})")
        window?.decorView?.removeCallbacks(hideRunnable)
        DiscordPresenceManager.onContextGone()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GameSpaceDiscord"
        private const val HIDE_DELAY_MS = 500L
        const val EXTRA_FINISH = "finish_host"
    }
}