package com.zaka.wechatglass

import android.app.Activity
import android.os.Bundle
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView

/**
 * 设置页宿主。
 *
 * 用最简单的 Activity + ComposeView：不引 activity-compose，也不依赖宿主。
 * 整页内容就是 SettingsScreen 那一套玻璃卡片。
 */
class DockSettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = ComposeView(this)
        setContentView(view)
        view.setContent {
            val state = remember { SettingsState(this) }
            SettingsScreen(
                state = state,
                version = WechatGlass.VER,
                onBack = { finish() },
            )
        }
    }
}
