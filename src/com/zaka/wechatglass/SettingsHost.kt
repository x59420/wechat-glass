package com.zaka.wechatglass

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * 把 Compose 版的设置界面挂到模块自己的 Activity 上。
 *
 * 之前那版设置页是纯 View 拼的（一排 Switch + SeekBar），跟 Dock 那套液态玻璃完全两张皮。
 * 这里换成 SettingsScreen 那套 Compose 界面：整屏玻璃、圆角分组、顶部分类、
 * 状态栏和导航栏透明（内容直接铺满到边）。
 */
object SettingsHost {

    @JvmStatic
    fun attach(activity: Activity, version: String) {
        edgeToEdge(activity)
        val owner = HostLifecycleOwner()
        owner.registry.currentState = Lifecycle.State.RESUMED
        val view = ComposeView(activity)
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        activity.setContentView(view)
        view.setContent {
            SettingsScreen(
                state = SettingsState(activity),
                version = version,
                onBack = { activity.finish() },
                overlay = false,
            )
        }
    }

    /** 状态栏 / 导航栏全透明，内容铺到边，图标深浅跟着界面走。 */
    private fun edgeToEdge(activity: Activity) {
        try {
            val window = activity.window
            window.setStatusBarColor(Color.TRANSPARENT)
            window.setNavigationBarColor(Color.TRANSPARENT)
            if (Build.VERSION.SDK_INT >= 28) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false)
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility =
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                @Suppress("DEPRECATION")
                window.setNavigationBarDividerColor(Color.TRANSPARENT)
            }
            val controller = window.insetsController
            if (controller != null) {
                val light = (activity.resources.configuration.uiMode
                    and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_NO
                val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                controller.setSystemBarsAppearance(if (light) mask else 0, mask)
            }
        } catch (_: Throwable) {
        }
    }

    /** ComposeView 要一个 LifecycleOwner / SavedStateRegistryOwner，宿主 Activity 是原生 Activity。 */
    private class HostLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)

        init {
            saved.performRestore(null)
        }

        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    }
}
