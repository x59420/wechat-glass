package com.zaka.wechatglass.iso

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.zaka.wechatglass.SettingsScreen
import com.zaka.wechatglass.iso.SettingsModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 抖音当前 Activity 上的设置弹层。
 * Compose 由模块 APK 的隔离类加载器运行；Dialog 不创建新 Activity，
 * 窗口背景透明且不设置 dim，底下抖音页面保持原亮度。
 */
class IsoSettingsOverlay private constructor(activity: Activity, version: String) : Dialog(activity) {
    private val host = SettingsDialogHost(activity)

    init {
        val state = IsoSettingsState(activity)
        host.compose { SettingsScreen(state, version, { dismiss() }, overlay = true) }
        setContentView(host)
        setCanceledOnTouchOutside(true)
        setOnDismissListener { host.destroyOwner() }
    }

    override fun onStart() {
        super.onStart()
        window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0f)
            w.setGravity(Gravity.CENTER)
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            w.decorView.setPadding(0, 0, 0, 0)
        }
    }

    companion object {
        @JvmStatic
        fun show(activity: Activity, version: String) {
            if (activity.isFinishing || activity.isDestroyed) return
            IsoSettingsOverlay(activity, version).show()
        }
    }
}

private class SettingsDialogHost(context: Context) : FrameLayout(context),
    LifecycleOwner, SavedStateRegistryOwner {
    private val inner = ComposeView(context)
    private val registry = LifecycleRegistry(this)
    private val savedController = SavedStateRegistryController.create(this)
    private val recomposer = Recomposer(AndroidUiDispatcher.Main)
    private val scope = CoroutineScope(AndroidUiDispatcher.Main + Job())

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedController.savedStateRegistry

    init {
        setBackgroundColor(Color.TRANSPARENT)
        savedController.performAttach()
        savedController.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        setViewTreeLifecycleOwner(this)
        setViewTreeSavedStateRegistryOwner(this)
        inner.setViewTreeLifecycleOwner(this)
        inner.setViewTreeSavedStateRegistryOwner(this)
        inner.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        try {
            val field = androidx.compose.ui.platform.AbstractComposeView::class.java
                .getDeclaredField("parentContext")
            field.isAccessible = true
            field.set(inner, recomposer)
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
        } catch (t: Throwable) {
            android.util.Log.w("wxglass", "设置面板 Recomposer 初始化失败", t)
            try { recomposer.cancel() } catch (_: Throwable) {}
            try { scope.cancel() } catch (_: Throwable) {}
        }
        addView(inner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun compose(content: @androidx.compose.runtime.Composable () -> Unit) {
        inner.setContent { content() }
    }

    fun destroyOwner() {
        try { inner.disposeComposition() } catch (_: Throwable) {}
        try { recomposer.cancel() } catch (_: Throwable) {}
        try { scope.cancel() } catch (_: Throwable) {}
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

/**
 * 隔离类加载器中的轻量配置模型。
 * 只依赖 Android SharedPreferences / Intent，不加载宿主的 Xposed 配置类。
 */
private class IsoSettingsState(context: Context) : SettingsModel {
    private val app = context.applicationContext
    private val prefs: SharedPreferences =
        app.getSharedPreferences("wxglass_settings", Context.MODE_PRIVATE)

    override val enabled = mutableStateOf(prefs.getBoolean("enabled", true))
    override val showPlus = mutableStateOf(prefs.getBoolean("show_plus", true))
    override val showLabels = mutableStateOf(prefs.getBoolean("show_labels", true))
    override val logEnabled = mutableStateOf(prefs.getBoolean("verbose_log", true))
    override val themeMode = mutableStateOf(prefs.getInt("theme_mode", 0).coerceIn(0, 2))
    override val glassAlpha = mutableStateOf(prefs.getInt("glass_alpha", 100).coerceIn(0, 100))
    override val hideBackground = mutableStateOf(prefs.getBoolean("hide_glass_background", false))
    override val bottomTransparent = mutableStateOf(prefs.getBoolean("bottom_transparent", true))
    override val menuTransparent = mutableStateOf(prefs.getBoolean("menu_transparent", true))
    override val videoTint = mutableStateOf(prefs.getBoolean("video_tint_fallback", true))
    override val height = mutableStateOf(prefs.getInt("dock_height_dp", 64).coerceIn(52, 88))
    override val tabWidth = mutableStateOf(prefs.getInt("tab_width_dp", 76).coerceIn(60, 100))
    override val sideMargin = mutableStateOf(prefs.getInt("side_margin_dp", 24).coerceIn(12, 40))
    override val bottomGap = mutableStateOf(prefs.getInt("bottom_gap_dp", 8).coerceIn(0, 28))

    override fun setEnabled(value: Boolean) { enabled.value = value; persistAndPush() }
    override fun setShowPlus(value: Boolean) { showPlus.value = value; persistAndPush() }
    override fun setShowLabels(value: Boolean) { showLabels.value = value; persistAndPush() }
    override fun setLogEnabled(value: Boolean) { logEnabled.value = value; persistAndPush() }
    override fun setHeight(value: Int) { height.value = value.coerceIn(52, 88); persistAndPush() }
    override fun setTabWidth(value: Int) { tabWidth.value = value.coerceIn(60, 100); persistAndPush() }
    override fun setSideMargin(value: Int) { sideMargin.value = value.coerceIn(12, 40); persistAndPush() }
    override fun setBottomGap(value: Int) { bottomGap.value = value.coerceIn(0, 28); persistAndPush() }

    override fun setThemeMode(value: Int) {
        val mode = value.coerceIn(0, 2)
        if (themeMode.value == mode) return
        // 和模块进程那一份设置页同样的处理：主题一换，整棵子树的 palette / 玻璃卡片 /
        // 图层背板都要重画。直接在点击回调里改快照，部分机型上会在绘制线程崩掉
        // （旧的背板正好被回收）。这里先落盘 + 广播给宿主，界面状态推到下一帧再改。
        prefs.edit()
            .putInt("theme_mode", mode)
            .putLong("settings_rev", System.currentTimeMillis())
            .commit()
        try {
            app.sendBroadcast(
                Intent("com.zaka.wechatglass.SETTINGS_CHANGED")
                    .setPackage("com.ss.android.ugc.aweme")
                    .putExtra("theme_mode", mode)
                    .putExtra("bottom_transparent", bottomTransparent.value)
                    .putExtra("menu_transparent", menuTransparent.value)
                    .putExtra("video_tint", videoTint.value)
            )
        } catch (_: Throwable) {
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            themeMode.value = mode
        }
    }

    override fun setGlassAlpha(value: Int) {
        glassAlpha.value = value.coerceIn(0, 100)
        persistAndPush()
    }

    override fun setHideBackground(value: Boolean) {
        hideBackground.value = value
        persistAndPush()
    }

    override fun setBottomTransparent(value: Boolean) {
        bottomTransparent.value = value
        persistAndPush()
    }

    override fun setMenuTransparent(value: Boolean) {
        menuTransparent.value = value
        persistAndPush()
    }

    override fun setVideoTint(value: Boolean) {
        videoTint.value = value
        persistAndPush()
    }

    override fun reset() {
        enabled.value = true
        showPlus.value = true
        showLabels.value = true
        height.value = 64
        tabWidth.value = 76
        sideMargin.value = 24
        bottomGap.value = 8
        persistAndPush()
    }

    private fun persistAndPush() {
        prefs.edit()
            .putBoolean("enabled", enabled.value)
            .putBoolean("show_plus", showPlus.value)
            .putBoolean("show_labels", showLabels.value)
            .putBoolean("verbose_log", logEnabled.value)
            .putInt("dock_height_dp", height.value)
            .putInt("tab_width_dp", tabWidth.value)
            .putInt("side_margin_dp", sideMargin.value)
            .putInt("bottom_gap_dp", bottomGap.value)
            .putInt("theme_mode", themeMode.value)
            .putInt("glass_alpha", glassAlpha.value)
            .putBoolean("hide_glass_background", hideBackground.value)
            .putBoolean("bottom_transparent", bottomTransparent.value)
            .putBoolean("menu_transparent", menuTransparent.value)
            .putBoolean("video_tint_fallback", videoTint.value)
            .putLong("settings_rev", System.currentTimeMillis())
            .commit()

        val intent = Intent("com.zaka.wechatglass.SETTINGS_CHANGED")
            .setPackage("com.ss.android.ugc.aweme")
            .putExtra("enabled", enabled.value)
            .putExtra("show_plus", showPlus.value)
            .putExtra("show_labels", showLabels.value)
            .putExtra("verbose_log", logEnabled.value)
            .putExtra("height", height.value)
            .putExtra("tab_width", tabWidth.value)
            .putExtra("side_margin", sideMargin.value)
            .putExtra("bottom_gap", bottomGap.value)
            .putExtra("theme_mode", themeMode.value)
            .putExtra("bottom_transparent", bottomTransparent.value)
            .putExtra("menu_transparent", menuTransparent.value)
            .putExtra("video_tint", videoTint.value)
            .putExtra("glass_alpha", glassAlpha.value)
            .putExtra("hide_glass_background", hideBackground.value)
        app.sendBroadcast(intent)
    }
}


