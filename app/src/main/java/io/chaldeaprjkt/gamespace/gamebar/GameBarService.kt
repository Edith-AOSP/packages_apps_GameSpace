/*
 * Copyright (C) 2021 Chaldeaprjkt
 *               2022 crDroid Android Project
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
package io.chaldeaprjkt.gamespace.gamebar

import android.app.GameManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.media.AudioManager
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.*
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.android.systemui.screenrecord.IRecordingCallback
import dagger.hilt.android.AndroidEntryPoint
import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.data.AppSettings
import io.chaldeaprjkt.gamespace.data.GameSession
import io.chaldeaprjkt.gamespace.data.SystemSettings
import io.chaldeaprjkt.gamespace.data.UserGame
import io.chaldeaprjkt.gamespace.discord.DiscordPresenceManager
import io.chaldeaprjkt.gamespace.panel.GameSpacePanelTheme
import io.chaldeaprjkt.gamespace.panel.LandscapePanel
import io.chaldeaprjkt.gamespace.panel.LandscapePanelActions
import io.chaldeaprjkt.gamespace.panel.LandscapePanelState
import io.chaldeaprjkt.gamespace.panel.PanelAnchor
import io.chaldeaprjkt.gamespace.panel.PanelTiles
import io.chaldeaprjkt.gamespace.panel.ServiceComposeOwner
import io.chaldeaprjkt.gamespace.settings.SettingsActivity
import io.chaldeaprjkt.gamespace.utils.*
import io.chaldeaprjkt.gamespace.widget.MenuSwitcher
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.reflect.KProperty0

@AndroidEntryPoint(Service::class)
class GameBarService : Hilt_GameBarService() {

    @Inject lateinit var appSettings: AppSettings
    @Inject lateinit var screenUtils: ScreenUtils
    @Inject lateinit var danmakuService: DanmakuService
    @Inject lateinit var gameSession: GameSession
    @Inject lateinit var systemSettings: SystemSettings
    @Inject lateinit var gameModeUtils: GameModeUtils
    @Inject lateinit var discordPresence: DiscordPresenceManager

    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val inflater by lazy { LayoutInflater.from(this) }
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    private var halfWidth = 0
    private var safeHeight = 0
    private var safeArea = 0

    private val barLayoutParam = WindowManager.LayoutParams(
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        preferMinimalPostProcessing = true
        gravity = Gravity.TOP
    }

    private val panelLayoutParam = WindowManager.LayoutParams(
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.MATCH_PARENT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        gravity = Gravity.TOP or Gravity.START
    }

    private lateinit var rootBarView: View
    private lateinit var barView: LinearLayout
    private lateinit var menuSwitcher: MenuSwitcher
    private lateinit var rootPanelView: ViewGroup

    private val binder = GameBarBinder()
    private val firstPaint = Runnable { initActions() }
    private val releaseRunnable = Runnable {
        if (!gameActive && !mediaActive) stopSelf()
    }
    private var shouldClose = false
    private var isGameStarting = false
    private var isRecording = false
    private var panelOwner: ServiceComposeOwner? = null
    private var panelHideRunnable: Runnable? = null
    private var mediaActive = false
    private var gameActive = false
    private var foreground = false
    private val landscapeState = MutableStateFlow(LandscapePanelState())

    private var barExpanded = false
        set(value) {
            field = value
            menuSwitcher.updateIconState(value, barLayoutParam.x)
            barView.children.forEach { if (it.id != R.id.action_menu_switcher) it.isVisible = value }
            updateBackground()
            updateContainerGaps()
        }

    private var showPanel = false
        set(value) {
            field = value
            if (value) {
                panelHideRunnable?.let {
                    handler.removeCallbacks(it)
                    panelHideRunnable = null
                }
                if (::rootPanelView.isInitialized) {
                    rootPanelView.animate().cancel()
                }
                panelLayoutParam.flags = panelLayoutParam.flags and
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                if (!::rootPanelView.isInitialized || !rootPanelView.isAttachedToWindow) {
                    setupPanelView()
                }
                if (!rootPanelView.isAttachedToWindow) {
                    wm.addView(rootPanelView, panelLayoutParam)
                } else {
                    runCatching {
                        wm.updateViewLayout(rootPanelView, panelLayoutParam)
                    }
                }
                rootPanelView.alpha = 1f
                rootPanelView.visibility = View.VISIBLE
            } else {
                if (safeAttached(::rootPanelView.isInitialized, rootPanelView)) {
                    panelLayoutParam.flags =
                        panelLayoutParam.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    runCatching {
                        wm.updateViewLayout(rootPanelView, panelLayoutParam)
                    }
                    rootPanelView.fadeOut {
                        rootPanelView.visibility = View.INVISIBLE
                        if (panelHideRunnable == null) {
                            val hide = Runnable {
                                panelHideRunnable = null
                                runCatching {
                                    wm.removeView(rootPanelView)
                                }.onFailure { it.printStackTrace() }
                            }
                            panelHideRunnable = hide
                            handler.postDelayed(hide, 50)
                        }
                    }
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        // A previous process may have died before it could clear its presence; the stored id is
        // the only ownership marker Discord gives us.
        discordPresence.reconcileStalePresence()
        panelOwner = ServiceComposeOwner().apply {
            create()
            start()
            resume()
        }
        val frame = FrameLayout(this)
        rootBarView = inflater.inflate(R.layout.window_util, frame, false)
        barView = rootBarView.findViewById(R.id.container_bar)
        menuSwitcher = rootBarView.findViewById(R.id.action_menu_switcher)
        applyOpacity()
        updateScreenMetrics()
        danmakuService.init()
    }

    private fun updateScreenMetrics() {
        val bounds = wm.maximumWindowMetrics.bounds
        halfWidth = bounds.width() / 2
        safeArea = statusbarHeight + 4.dp
        safeHeight = bounds.height() - safeArea
    }

    private fun applyOpacity() {
        val alphaValue = appSettings.menuOpacity / 100f
        barView.alpha = alphaValue
        menuSwitcher.alpha = alphaValue
    }

    private fun startForegroundServiceNotification() {
        val channelId = FOREGROUND_CHANNEL_ID
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        // IMPORTANCE_LOW keeps the ongoing notification quiet: no heads-up, no sound, no
        // vibration. An earlier build used IMPORTANCE_HIGH, which made the service visibly pop.
        val channel = NotificationChannel(
            channelId,
            getString(R.string.gamespace_service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
        if (manager.getNotificationChannel(LEGACY_CHANNEL_ID) != null) {
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        }

        startForeground(1001, buildNotification(channelId), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    /**
     * Names what is running, so the ongoing row distinguishes the game bar from media presence.
     *
     * [ForegroundState.of] is derived from the two flags rather than tracked separately, so the
     * text cannot drift from what the service is actually doing.
     */
    private fun buildNotification(channelId: String): Notification {
        val state = ForegroundState.of(gameActive, mediaActive)
        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(state.title))
            .setSmallIcon(R.drawable.ic_gear)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
        // The system shows this under the notification, and it is what used to read as the app
        // name: the manifest label is the settings title, not the service.
        builder.setContentText(getString(R.string.gamespace_running_summary))
        return builder.build()
    }

    /** Repaints the ongoing notification after the running combination changes. */
    private fun refreshForegroundNotification() {
        if (!foreground) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            FOREGROUND_NOTIFICATION_ID,
            buildNotification(FOREGROUND_CHANNEL_ID),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> onGameLeave()
            ACTION_START -> onGameStart()
            ACTION_MEDIA_START -> startMediaPresence()
            ACTION_MEDIA_STOP -> stopMediaPresence()
            // A null action means the system recreated the service after the process died. The
            // foreground notification is gone at that point, so it has to be restored here or
            // the service is treated as a plain background service and killed again.
            null -> restoreAfterRestart()
        }
        return START_STICKY
    }

    private fun restoreAfterRestart() {
        if (appSettings.discordRpc && appSettings.discordMedia &&
            DiscordPresenceManager.isSupported(this)
        ) {
            mediaActive = true
            ensureForeground()
            discordPresence.startMedia()
        } else if (!gameActive) {
            stopSelf()
        }
    }

    override fun onBind(intent: Intent) = binder

    inner class GameBarBinder : Binder() {
        fun getService() = this@GameBarService
    }

    override fun onDestroy() {
        onGameLeave()
        panelOwner?.destroy()
        panelOwner = null
        danmakuService.destroy()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenMetrics()
        if (!rootBarView.isVisible) {
            handler.removeCallbacks(firstPaint)
            handler.postDelayed({
                firstPaint.run()
                dockCollapsedMenu()
            }, 100)
        } else {
            dockCollapsedMenu()
        }
        if (::rootPanelView.isInitialized && showPanel) {
            panelHideRunnable?.let {
                handler.removeCallbacks(it)
                panelHideRunnable = null
            }
            rootPanelView.animate().cancel()
            runCatching {
                if (rootPanelView.isAttachedToWindow) {
                    wm.removeViewImmediate(rootPanelView)
                }
            }
            panelLayoutParam.flags = panelLayoutParam.flags and
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            setupPanelView()
            wm.addView(rootPanelView, panelLayoutParam)
            rootPanelView.alpha = 1f
            rootPanelView.visibility = View.VISIBLE
        }
        danmakuService.updateConfiguration(newConfig)
    }

    fun onGameStart() {
        if (isGameStarting) return
        isGameStarting = true

        handler.post {
            if (!::rootBarView.isInitialized) return@post
            if (safeAttached(::rootBarView.isInitialized, rootBarView)) {
                isGameStarting = false
                return@post
            }

            shouldClose = false
            gameActive = true
            refreshForegroundNotification()
            rootBarView.isVisible = false
            rootBarView.alpha = 0f
            wm.addView(rootBarView, barLayoutParam)
            handler.postDelayed(firstPaint, 500)
            ensureForeground()
            isGameStarting = false
        }
    }

    /**
     * Starts media Rich Presence observation without showing the game bar.
     *
     * Both features share this service so there is a single ongoing notification and a single
     * foreground lifetime to reason about.
     */
    fun startMediaPresence() {
        if (mediaActive) return
        if (!appSettings.discordRpc || !appSettings.discordMedia) return
        if (!DiscordPresenceManager.isSupported(this)) return
        mediaActive = true
        ensureForeground()
        refreshForegroundNotification()
        discordPresence.startMedia()
    }

    fun stopMediaPresence() {
        if (!mediaActive) return
        mediaActive = false
        discordPresence.stopMedia()
        releaseIfIdle()
        refreshForegroundNotification()
    }

    fun onGameLeave() {
        shouldClose = true
        handler.removeCallbacksAndMessages(null)

        runCatching {
            if (safeAttached(::rootPanelView.isInitialized, rootPanelView)) {
                wm.removeViewImmediate(rootPanelView)
            }
        }

        runCatching {
            if (safeAttached(::rootBarView.isInitialized, rootBarView)) {
                wm.removeViewImmediate(rootBarView)
            }
        }

        gameActive = false
        refreshForegroundNotification()
        // Leaving the bar is the strongest signal that the game is over, so do not wait for the
        // delayed grace period here.
        discordPresence.stopGame()
        releaseIfIdle()
    }

    /** Keeps the service foregrounded while either the game bar or media presence needs it. */
    private fun ensureForeground() {
        if (foreground) {
            refreshForegroundNotification()
            return
        }
        foreground = true
        startForegroundServiceNotification()
    }

    private fun releaseIfIdle() {
        if (gameActive || mediaActive) return
        if (foreground) {
            foreground = false
            stopForeground(true)
        }
        // Discord is asked to clear the presence asynchronously. Tearing the process down in the
        // same breath can drop that request, which is what left stale activities on the profile,
        // so keep the service alive briefly and let the clear leave.
        handler.removeCallbacks(releaseRunnable)
        handler.postDelayed(releaseRunnable, RELEASE_FLUSH_MS)
    }

    private fun updateLayout(update: WindowManager.LayoutParams.() -> Unit = {}) {
        barLayoutParam.update()
        if (rootBarView.isAttachedToWindow) {
            wm.updateViewLayout(rootBarView, barLayoutParam)
        }
    }

    private fun initActions() {
        if (shouldClose) return
        rootBarView.fadeIn()
        barExpanded = false
        barLayoutParam.x = appSettings.x
        barLayoutParam.y = appSettings.y
        dockCollapsedMenu()
        menuSwitcherButton()
        panelButton()
        screenshotButton()
        recorderButton()
    }

    private fun updateBackground() {
        val barDragged = !barExpanded && barView.translationX == 0f
        val collapsedAtStart = !barDragged && barLayoutParam.x < 0
        val collapsedAtEnd = !barDragged && barLayoutParam.x > 0
        barView.setBackgroundResource(
            when {
                barExpanded -> R.drawable.bar_expanded
                collapsedAtStart -> R.drawable.bar_collapsed_start
                collapsedAtEnd -> R.drawable.bar_collapsed_end
                else -> R.drawable.bar_dragged
            }
        )
    }

    private fun updateContainerGaps() {
        val currentParams = barView.layoutParams as ViewGroup.MarginLayoutParams
        val targetLeft = if (barExpanded) 48 else 0
        val targetRight = if (barExpanded) 48 else 0

        if (currentParams.leftMargin != targetLeft || currentParams.rightMargin != targetRight) {
            barView.updatePaddingRelative(
                start = if (barExpanded) 8 else 0,
                top = if (barExpanded) 8 else 0,
                end = if (barExpanded) 8 else 0,
                bottom = if (barExpanded) 8 else 0
            )
            currentParams.setMargins(targetLeft, 0, targetRight, 0)
            barView.layoutParams = currentParams
        }
    }

    private fun dockCollapsedMenu() {
        if (barLayoutParam.x < 0) {
            barView.translationX = -22f
            barLayoutParam.x = -halfWidth
        } else {
            barView.translationX = 22f
            barLayoutParam.x = halfWidth
        }

        barLayoutParam.y = barLayoutParam.y.coerceIn(safeArea, safeHeight)
        updateBackground()
        updateContainerGaps()
        menuSwitcher.showFps = if (barExpanded) false else appSettings.showFps
        menuSwitcher.updateIconState(barExpanded, barLayoutParam.x)
        if (safeAttached(::rootBarView.isInitialized, rootBarView)) {
            wm.updateViewLayout(rootBarView, barLayoutParam)
        }
    }

    private fun setupPanelView() {
        refreshLandscapeState()
        val owner = panelOwner ?: return
        val compose = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setContent {
                GameSpacePanelTheme {
                    val state by landscapeState.collectAsState()
                    LandscapePanel(
                        state = state,
                        actions = landscapeActions(),
                        anchor = panelAnchor(),
                    )
                }
            }
        }

        rootPanelView = FrameLayout(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            addView(
                compose,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        rootPanelView.visibility = View.VISIBLE
        rootPanelView.alpha = 1f

        panelLayoutParam.width = WindowManager.LayoutParams.MATCH_PARENT
        panelLayoutParam.height = WindowManager.LayoutParams.MATCH_PARENT
        panelLayoutParam.gravity = Gravity.TOP or Gravity.START
        panelLayoutParam.x = 0
        panelLayoutParam.y = 0
    }

    private fun panelAnchor(): PanelAnchor {
        val metrics = wm.maximumWindowMetrics
        val insets = metrics.windowInsets
        val location = IntArray(2)
        barView.getLocationOnScreen(location)
        return PanelAnchor(
            barX = location[0],
            barY = location[1],
            barWidth = barView.width,
            barHeight = barView.height,
            screenWidth = metrics.bounds.width(),
            screenHeight = metrics.bounds.height(),
            topInset = insets.getInsets(WindowInsets.Type.statusBars()).top,
            bottomInset = insets.getInsets(WindowInsets.Type.navigationBars()).bottom,
        )
    }

    private fun isDndEnabled(): Boolean =
        notificationManager.isNotificationPolicyAccessGranted &&
            notificationManager.currentInterruptionFilter !=
            NotificationManager.INTERRUPTION_FILTER_ALL

    private fun landscapeActions() = LandscapePanelActions(
        onDismiss = { showPanel = false },
        onToggleFps = {
            appSettings.showFps = !appSettings.showFps
            refreshLandscapeState()
        },
        onToggleGestures = {
            val enabled = !appSettings.noThreeScreenshot
            appSettings.noThreeScreenshot = enabled
            systemSettings.threeScreenshot = if (enabled) 0 else 1
            refreshLandscapeState()
        },
        onToggleStayAwake = {
            val enabled = !appSettings.stayAwake
            appSettings.stayAwake = enabled
            screenUtils.stayAwake = enabled
            refreshLandscapeState()
        },
        onToggleHeadsUp = {
            val enabled = !appSettings.danmakuNotification
            appSettings.danmakuNotification = enabled
            systemSettings.headsup = !enabled
            refreshLandscapeState()
        },
        onCycleRinger = {
            val next = when (audioManager.ringerModeInternal) {
                AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            runCatching { audioManager.ringerModeInternal = next }
            refreshLandscapeState()
        },
        onToggleDnd = {
            if (notificationManager.isNotificationPolicyAccessGranted) {
                val enabled = !isDndEnabled()
                runCatching {
                    notificationManager.setInterruptionFilter(
                        if (enabled) {
                            NotificationManager.INTERRUPTION_FILTER_PRIORITY
                        } else {
                            NotificationManager.INTERRUPTION_FILTER_ALL
                        }
                    )
                }
            } else {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            refreshLandscapeState()
        },
        onToggleAutoBrightness = {
            runCatching { systemSettings.autoBrightness = !systemSettings.autoBrightness }
            refreshLandscapeState()
        },
        onToggleRotationLock = {
            runCatching { systemSettings.rotationLocked = !systemSettings.rotationLocked }
            refreshLandscapeState()
        },
        onSetMode = { mode ->
            val packageName = gameSession.activePackage
            if (packageName != null) {
                val current = systemSettings.userGames
                    .firstOrNull { it.packageName == packageName }
                    ?.mode
                    ?: GameManager.GAME_MODE_STANDARD
                gameModeUtils.activeGame = UserGame(packageName, current)
                gameModeUtils.setActiveGameMode(systemSettings, mode)
                refreshLandscapeState()
            }
        },
        onPanelTilesChanged = { tiles ->
            appSettings.panelTiles = PanelTiles.encode(tiles)
            refreshLandscapeState()
        },
    )

    private fun refreshLandscapeState() {
        val packageName = gameSession.activePackage
        val label = packageName?.let(::gameLabel) ?: getString(R.string.panel_game_fallback)
        val icon = packageName?.let(::gameIcon)
        val mode = packageName?.let {
            systemSettings.userGames.firstOrNull { game -> game.packageName == it }?.mode
        } ?: GameManager.GAME_MODE_STANDARD

        landscapeState.value = LandscapePanelState(
            gameLabel = label,
            gameIcon = icon,
            startedAt = gameSession.startedAt,
            gameMode = mode,
            fps = appSettings.showFps,
            gestures = !appSettings.noThreeScreenshot,
            stayAwake = appSettings.stayAwake,
            headsUp = !appSettings.danmakuNotification,
            ringerMode = audioManager.ringerModeInternal,
            dnd = isDndEnabled(),
            autoBrightness = systemSettings.autoBrightness,
            rotationLocked = systemSettings.rotationLocked,
            tiles = PanelTiles.decode(appSettings.panelTiles),
        )
    }

    private fun gameLabel(packageName: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        packageName
    }

    private fun gameIcon(packageName: String): android.graphics.drawable.Drawable? = try {
        packageManager.getApplicationIcon(packageName)
    } catch (_: Exception) {
        null
    }

    private fun takeShot() {
        val afterShot = {
            barExpanded = false
            handler.postDelayed({ updateLayout { alpha = 1f } }, 100)
        }

        updateLayout { alpha = 0f }
        handler.postDelayed({
            runCatching {
                screenUtils.takeScreenshot { afterShot() }
            }.onFailure {
                it.printStackTrace()
                afterShot()
            }
        }, 250)
    }

    private fun menuSwitcherButton() {
        menuSwitcher.setOnClickListener {
            barExpanded = !barExpanded
        }

        menuSwitcher.registerDraggableTouchListener(
            initPoint = { Point(barLayoutParam.x, barLayoutParam.y) },
            listener = { x, y ->
                if (!menuSwitcher.isDragged) {
                    menuSwitcher.isDragged = true
                    barView.translationX = 0f
                }
                updateLayout {
                    this.x = x
                    this.y = y
                }
                updateBackground()
            },
            onComplete = {
                menuSwitcher.isDragged = false
                dockCollapsedMenu()
                updateBackground()
                appSettings.x = barLayoutParam.x
                appSettings.y = barLayoutParam.y
            }
        )
    }

    private fun panelButton() {
        val actionPanel = rootBarView.findViewById<ImageButton>(R.id.action_panel)
        actionPanel.alpha = appSettings.menuOpacity / 100f
        actionPanel.setOnClickListener { showPanel = !showPanel }
        actionPanel.setOnLongClickListener {
            startActivity(Intent(this, SettingsActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }
    }

    private fun screenshotButton() {
        rootBarView.findViewById<ImageButton>(R.id.action_screenshot).apply {
            alpha = appSettings.menuOpacity / 100f
            setOnClickListener { takeShot() }
        }
    }

    private fun recorderButton() {
        val actionRecorder = rootBarView.findViewById<ImageButton>(R.id.action_record)
        actionRecorder.alpha = appSettings.menuOpacity / 100f
        val recorder = screenUtils.recorder ?: run {
            actionRecorder.isVisible = false
            return
        }

        recorder.addRecordingCallback(object : IRecordingCallback.Stub() {
            override fun onRecordingStart() {
                isRecording = true
                handler.post { actionRecorder.isSelected = true }
            }

            override fun onRecordingEnd() {
                isRecording = false
                handler.post { actionRecorder.isSelected = false }
            }
        })

        actionRecorder.setOnClickListener {
            try {
                if (recorder.isStarting) return@setOnClickListener
                if (isRecording || recorder.isRecording) {
                    recorder.stopRecording()
                } else {
                    recorder.startRecording()
                }
            } catch (e: RemoteException) {
                Log.w(TAG, "Screen recording action failed", e)
            }
            barExpanded = false
        }
    }

    fun View.fadeIn(duration: Long = 300L) {
        if (!isVisible || alpha < 1f) {
            alpha = 0f
            isVisible = true
            animate().alpha(1f).setDuration(duration).start()
        }
    }

    fun View.fadeOut(duration: Long = 300L, endAction: () -> Unit = {}) {
        if (isVisible && alpha > 0f) {
            animate().alpha(0f).setDuration(duration).withEndAction(endAction).start()
        } else {
            endAction()
        }
    }
    
    fun <T : View> safeAttached(initialized: Boolean, view: T): Boolean {
        if (!initialized) {
            return false
        }
        return view.isAttachedToWindow
    }

    companion object {
        const val TAG = "GameBar"
        const val ACTION_START = "GameBar.ACTION_START"
        const val ACTION_STOP = "GameBar.ACTION_STOP"
        const val ACTION_MEDIA_START = "GameBar.ACTION_MEDIA_START"
        const val ACTION_MEDIA_STOP = "GameBar.ACTION_MEDIA_STOP"
        private const val LEGACY_CHANNEL_ID = "gamespace_service_channel"
        private const val FOREGROUND_CHANNEL_ID = "gamespace_service_channel_v2"
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val RELEASE_FLUSH_MS = 3_000L

        fun startMedia(context: Context) {
            val intent = Intent(context, GameBarService::class.java)
                .setAction(ACTION_MEDIA_START)
            runCatching { context.startForegroundService(intent) }
        }

        fun stopMedia(context: Context) {
            val intent = Intent(context, GameBarService::class.java)
                .setAction(ACTION_MEDIA_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
