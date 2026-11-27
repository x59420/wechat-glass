package com.zaka.wechatglass.iso

import androidx.compose.runtime.MutableState

/**
 * SettingsScreen 与配置存储之间的窄接口。
 * 让隔离渲染器不必加载带有 Xposed API 引用的宿主配置类。
 */
internal interface SettingsModel {
    val enabled: MutableState<Boolean>
    val showPlus: MutableState<Boolean>
    val showLabels: MutableState<Boolean>
    val logEnabled: MutableState<Boolean>
    val hideDesktop: MutableState<Boolean>
    val themeMode: MutableState<Int>
    val glassAlpha: MutableState<Int>
    val hideBackground: MutableState<Boolean>
    val bottomTransparent: MutableState<Boolean>
    val videoTint: MutableState<Boolean>
    val height: MutableState<Int>
    val tabWidth: MutableState<Int>
    val sideMargin: MutableState<Int>
    val bottomGap: MutableState<Int>

    fun setEnabled(value: Boolean)
    fun setShowPlus(value: Boolean)
    fun setShowLabels(value: Boolean)
    fun setLogEnabled(value: Boolean)
    fun setHideDesktop(value: Boolean)
    fun setThemeMode(value: Int)
    fun setGlassAlpha(value: Int)
    fun setHideBackground(value: Boolean)
    fun setBottomTransparent(value: Boolean)
    fun setVideoTint(value: Boolean)
    fun setHeight(value: Int)
    fun setTabWidth(value: Int)
    fun setSideMargin(value: Int)
    fun setBottomGap(value: Int)
    fun reset()
}
