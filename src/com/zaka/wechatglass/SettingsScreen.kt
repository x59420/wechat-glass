package com.zaka.wechatglass

import android.content.Context
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import com.zaka.wechatglass.ksu.liquid.vibrancy
import com.zaka.wechatglass.iso.SettingsModel

/**
 * wxglass 设置界面。
 *
 * 跑在模块自己的进程里（不是微信进程），写的还是模块包名下的那份配置；
 * 微信进程那边一秒内同步，改完回到微信一秒内就作用到 Dock 上。
 *
 * 视觉跟底栏同一套：整屏背景先记进一个 backdrop 图层，卡片用 drawBackdrop 去采样
 * （模糊 + vibrancy + 一层很淡的表面色）。没有 RuntimeShader 的机器（API < 33）
 * 自动退回半透明卡片，不会出现「卡片里什么都没有」。
 */
internal class SettingsState(context: Context) : SettingsModel {

    private val ctx = context.applicationContext
    /** 真正承载设置页的 Activity（切换主题时要用它的 DecorView 排一帧）。 */
    private val activity: android.app.Activity? = context as? android.app.Activity

    override val enabled = mutableStateOf(DockPrefs.enabled(ctx))
    override val showPlus = mutableStateOf(DockPrefs.showPlusButton(ctx))
    override val showLabels = mutableStateOf(DockPrefs.showLabels(ctx))
    override val logEnabled = mutableStateOf(DockPrefs.logEnabled(ctx))
    override val themeMode = mutableStateOf(DockPrefs.themeMode(ctx))
    override val glassAlpha = mutableStateOf(DockPrefs.glassAlpha(ctx))
    override val hideBackground = mutableStateOf(DockPrefs.hideGlassBackground(ctx))
    override val bottomTransparent = mutableStateOf(DockPrefs.bottomTransparent(ctx))
    override val menuTransparent = mutableStateOf(DockPrefs.menuTransparent(ctx))
    override val videoTint = mutableStateOf(DockPrefs.videoTintFallback(ctx))
    override val antiRevoke = mutableStateOf(DockPrefs.antiRevoke(ctx))
    override val chatTime = mutableStateOf(DockPrefs.chatTime(ctx))

    override val height = mutableStateOf(DockPrefs.heightDp(ctx))
    override val tabWidth = mutableStateOf(DockPrefs.tabWidthDp(ctx))
    override val sideMargin = mutableStateOf(DockPrefs.sideMarginDp(ctx))
    override val bottomGap = mutableStateOf(DockPrefs.bottomGapDp(ctx))

    override fun setEnabled(value: Boolean) {
        enabled.value = value
        DockPrefs.setEnabled(ctx, value)
        push()
    }

    override fun setShowPlus(value: Boolean) {
        showPlus.value = value
        DockPrefs.setShowPlusButton(ctx, value)
        push()
    }

    override fun setShowLabels(value: Boolean) {
        showLabels.value = value
        DockPrefs.setShowLabels(ctx, value)
        push()
    }

    override fun setLogEnabled(value: Boolean) {
        logEnabled.value = value
        DockPrefs.setLogEnabled(ctx, value)
        push()
    }

    override fun setAntiRevoke(value: Boolean) {
        antiRevoke.value = value
        DockPrefs.setAntiRevoke(ctx, value)
        push()
    }

    override fun setChatTime(value: Boolean) {
        chatTime.value = value
        DockPrefs.setChatTime(ctx, value)
        push()
    }

    override fun setThemeMode(value: Int) {
        val mode = value.coerceIn(0, 2)
        if (themeMode.value == mode) return
        DockPrefs.setThemeMode(ctx, mode)
        push()
        // 主题一换，整棵子树（palette、玻璃卡片、图层背板）要在同一帧里重画。
        // 直接在点击回调里改快照，部分机型上旧的图层背板正好在绘制中被回收，
        // 会崩在渲染线程 —— 这就是「设置里切深浅色闪退」。推到下一帧，等这一帧画完再换。
        val act = activity
        val decor = act?.window?.decorView
        if (decor == null) {
            themeMode.value = mode
        } else {
            decor.post {
                try {
                    themeMode.value = mode
                } catch (_: Throwable) {
                }
            }
        }
    }

    override fun setGlassAlpha(value: Int) {
        val percent = value.coerceIn(0, 100)
        glassAlpha.value = percent
        DockPrefs.setGlassAlpha(ctx, percent)
        push()
    }

    override fun setHideBackground(value: Boolean) {
        hideBackground.value = value
        DockPrefs.setHideGlassBackground(ctx, value)
        push()
    }

    override fun setBottomTransparent(value: Boolean) {
        bottomTransparent.value = value
        DockPrefs.setBottomTransparent(ctx, value)
        push()
    }

    override fun setMenuTransparent(value: Boolean) {
        menuTransparent.value = value
        DockPrefs.setMenuTransparent(ctx, value)
        push()
    }

    override fun setVideoTint(value: Boolean) {
        videoTint.value = value
        DockPrefs.setVideoTintFallback(ctx, value)
        push()
    }

    override fun setHeight(value: Int) {
        height.value = value
        DockPrefs.setHeightDp(ctx, value)
        push()
    }

    override fun setTabWidth(value: Int) {
        tabWidth.value = value
        DockPrefs.setTabWidthDp(ctx, value)
        push()
    }

    override fun setSideMargin(value: Int) {
        sideMargin.value = value
        DockPrefs.setSideMarginDp(ctx, value)
        push()
    }

    override fun setBottomGap(value: Int) {
        bottomGap.value = value
        DockPrefs.setBottomGapDp(ctx, value)
        push()
    }

    /**
     * 把当前这一份配置推给微信进程。
     *
     * 走广播（最快、不依赖文件权限）：宿主收到之后写进自己的 prefs 并立刻重排 Dock。
     * 同时 DockPrefs 那边还会把 prefs 文件设成可读，作为下次启动的兜底。
     */
    private fun push() {
        SettingsSync.push(
            ctx,
            enabled.value,
            showPlus.value,
            showLabels.value,
            logEnabled.value,
            height.value,
            tabWidth.value,
            sideMargin.value,
            bottomGap.value,
            bottomTransparent.value,
            menuTransparent.value,
            videoTint.value,
            themeMode.value,
            glassAlpha.value,
            hideBackground.value,
            antiRevoke.value,
            chatTime.value,
        )
    }

    override fun reset() {
        setEnabled(true)
        setShowPlus(true)
        setShowLabels(true)
        setHeight(DockPrefs.DEF_HEIGHT)
        setTabWidth(DockPrefs.DEF_TAB_WIDTH)
        setSideMargin(DockPrefs.DEF_SIDE_MARGIN)
        setBottomGap(DockPrefs.DEF_BOTTOM_GAP)
    }
}

private val GlassCardShape = RoundedCornerShape(26.dp)
private data class SettingsPalette(
    val background: Color, val panel: Color, val card: Color, val primary: Color,
    val secondary: Color, val accent: Color, val selectedText: Color,
    val trackOn: Color, val trackOff: Color, val border: Color, val divider: Color,
)
private val DarkSettingsPalette = SettingsPalette(
    Color(0xFF070A12), Color(0xF0161B25), Color(0xCC202735), Color(0xFFF7F8FA),
    Color(0xFFA6ABB4), Color(0xFFD8DDE6), Color(0xFF171A20), Color(0xFFD8DDE6), Color(0x33FFFFFF),
    Color(0x24FFFFFF), Color(0x18FFFFFF),
)
private val LightSettingsPalette = SettingsPalette(
    Color(0xFFF2F4F8), Color(0xF7FFFFFF), Color(0xDDE8ECF2), Color(0xFF171A20),
    Color(0xFF666D78), Color(0xFF424B59), Color.White, Color(0xFF424B59), Color(0x22000000),
    Color(0x1F000000), Color(0x16000000),
)
private val LocalSettingsPalette = staticCompositionLocalOf { DarkSettingsPalette }
private val LocalSettingsOverlay = staticCompositionLocalOf { false }

@Composable
private fun Label(
    text: String,
    color: Color,
    size: TextUnit,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontWeight = weight,
            textAlign = align ?: TextAlign.Start,
            letterSpacing = letterSpacing,
            lineHeight = lineHeight,
        ),
    )
}

/** 设置页读日志的窄接口：模块进程里用 LogStore 实现，微信里的浮层传 null。 */
internal interface LogBridge {
    fun read(): String
    fun export(): String?
    fun clear()
}

@Composable
internal fun SettingsScreen(
    state: SettingsModel,
    version: String,
    onBack: () -> Unit,
    overlay: Boolean = false,
    log: LogBridge? = null,
) {
    // 跟随系统：直接读 Configuration，不调 isSystemInDarkTheme() ——
    // Compose 不允许把 composable 调用包在 try/catch 里（编译直接报错）。
    val nightMode = LocalConfiguration.current.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK
    val dark = when (state.themeMode.value) {
        1 -> false
        2 -> true
        else -> nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
    val palette = if (dark) DarkSettingsPalette else LightSettingsPalette
    CompositionLocalProvider(LocalSettingsPalette provides palette, LocalSettingsOverlay provides overlay) {
        val scene = rememberLayerBackdrop()
        var tab by remember { mutableIntStateOf(0) }
        Box((if (overlay) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
            .background(if (overlay) Color.Transparent else palette.background)) {
            if (!overlay) {
                // 背景跟着深浅模式走：以前这里写死了深色渐变，浅色模式下整页还是黑的。
                val bg = if (dark) {
                    Brush.verticalGradient(listOf(Color(0xFF14161C), Color(0xFF080A10)))
                } else {
                    Brush.verticalGradient(listOf(Color(0xFFF8FAFD), Color(0xFFECEFF5)))
                }
                Box(Modifier.fillMaxSize().background(bg).layerBackdrop(scene))
            }
            val panel = if (overlay) {
                Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 16.dp)
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.86f).dp)
                    .clip(RoundedCornerShape(28.dp)).background(palette.panel)
                    .border(1.dp, palette.border, RoundedCornerShape(28.dp))
            } else Modifier.fillMaxSize()
            Column(
                panel
                    .verticalScroll(rememberScrollState())
                    .then(
                        if (overlay) Modifier
                        else Modifier.windowInsetsPadding(WindowInsets.systemBars)
                    )
                    .padding(horizontal = 18.dp)
                    .padding(top = 8.dp, bottom = if (overlay) 24.dp else 36.dp)
            ) {
                TopBar(version = version, onBack = onBack)
                Spacer(Modifier.height(14.dp))

                SettingsTabBar(
                    tabs = listOf("开关", "尺寸", "外观", "日志"),
                    selected = tab,
                    onSelect = { tab = it },
                )
                Spacer(Modifier.height(16.dp))

                when (tab) {
                    0 -> {
                        SectionTitle("功能开关")
                        GlassCard(scene) {
                            SwitchRow(
                                title = "启用玻璃 Dock",
                                subtitle = "关掉就完全用微信原生底栏",
                                checked = state.enabled.value,
                                onChange = state::setEnabled,
                            )
                            Divider()
                            SwitchRow(
                                title = "显示文字标签",
                                subtitle = "关掉只留图标，图标会自动居中",
                                checked = state.showLabels.value,
                                onChange = state::setShowLabels,
                            )
                            Divider()
                            SwitchRow(
                                title = "底栏区域透明",
                                subtitle = "清掉原生底栏那一条的底色，画面一直延伸到底",
                                checked = state.bottomTransparent.value,
                                onChange = state::setBottomTransparent,
                            )
                            Divider()
                            SwitchRow(
                                title = "弹层背景透明",
                                subtitle = "「＋」面板、长按菜单那层底也透明",
                                checked = state.menuTransparent.value,
                                onChange = state::setMenuTransparent,
                            )
                            Divider()
                            SwitchRow(
                                title = "抓不到画面用主色垫底",
                                subtitle = "纯色页面不至于是一块黑玻璃",
                                checked = state.videoTint.value,
                                onChange = state::setVideoTint,
                            )

                            Divider()
                            SwitchRow(
                                title = "记录日志",
                                subtitle = "关键步骤写进日志，可以在「日志」页导出",
                                checked = state.logEnabled.value,
                                onChange = state::setLogEnabled,
                            )
                        }
                    }

                    1 -> {
                        SectionTitle("尺寸")
                        GlassCard(scene) {
                            SliderRow("Dock 高度", state.height.value,
                                52, 88, 2, state::setHeight, unit = "dp")
                            Divider()
                            SliderRow("单个图标宽度", state.tabWidth.value,
                                60, 100, 2, state::setTabWidth, unit = "dp")
                            Divider()
                            SliderRow("左右留白", state.sideMargin.value,
                                12, 40, 2, state::setSideMargin, unit = "dp")
                            Divider()
                            SliderRow("离底部距离", state.bottomGap.value,
                                0, 28, 1, state::setBottomGap, unit = "dp")
                        }
                    }

                    2 -> {
                        SectionTitle("外观")
                        GlassCard(scene) {
                            ThemeModeRow(
                                backdrop = scene,
                                themeMode = state.themeMode.value,
                                onChange = state::setThemeMode,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        GlassCard(scene) {
                            TextRow(
                                title = "恢复默认尺寸",
                                subtitle = "只重置尺寸和开关，不动别的",
                                onClick = state::reset,
                            )
                        }
                    }

                    else -> {
                        val bridge = log
                        var logText by remember { mutableStateOf(bridge?.read().orEmpty()) }
                        var notice by remember { mutableStateOf<String?>(null) }
                        SectionTitle("运行日志")
                        GlassCard(scene) {
                            if (bridge == null) {
                                TextRow(
                                    title = "日志只在桌面 wxglass 里看",
                                    subtitle = "微信里的这个浮层读不到模块自己的日志文件",
                                    onClick = {},
                                )
                            } else {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement =
                                        androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                                ) {
                                    LogAction("刷新", Modifier.weight(1f)) {
                                        logText = bridge.read()
                                        notice = null
                                    }
                                    LogAction("导出", Modifier.weight(1f)) {
                                        val where = bridge.export()
                                        notice = if (where != null) "已导出到 $where" else "没有日志可导出"
                                    }
                                    LogAction("清空", Modifier.weight(1f)) {
                                        bridge.clear()
                                        logText = ""
                                        notice = "已清空"
                                    }
                                }
                            }
                        }
                        notice?.let {
                            Spacer(Modifier.height(8.dp))
                            Label(it, LocalSettingsPalette.current.secondary, 11.5.sp,
                                modifier = Modifier.padding(start = 8.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                        GlassCard(scene) {
                            val tail = remember(logText) {
                                val lines = logText.split('\n').filter { it.isNotBlank() }
                                lines.takeLast(60).joinToString("\n")
                            }
                            Label(
                                text = if (tail.isBlank()) "还没有日志。开启「记录日志」后，回微信切几个页面再来看。"
                                else tail,
                                color = LocalSettingsPalette.current.secondary,
                                size = 10.5.sp,
                                lineHeight = 15.sp,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(22.dp))
                Label(
                    text = "wxglass $version",
                    color = LocalSettingsPalette.current.secondary,
                    size = 12.sp,
                    modifier = Modifier.fillMaxWidth(),
                    align = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Label(
                    text = "液态玻璃渲染移植自 KernelSU（Compose + Miuix），折射与色散参数与上游一致；"
                        + "模糊采样由 compose-miuix-ui 提供。本模块与微信官方无关，仅限个人非商业用途。",
                    color = LocalSettingsPalette.current.secondary.copy(alpha = 0.75f),
                    size = 11.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                    align = TextAlign.Center,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

/**
 * 顶部分类。
 *
 * 手感按需求来，没有 Dock 那套「按下去放大透镜」：
 *   - 点一下：滑块用弹簧滑到对应那一格（弹性，不是死板的一步到位）；
 *   - 长按之后可以直接横向拖着换分类，松手吸附到最近的一格。
 */
@Composable
private fun SettingsTabBar(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val palette = LocalSettingsPalette.current
    val shape = RoundedCornerShape(18.dp)
    val density = LocalDensity.current
    // 长按拖动时用连续值，松手再交给动画吸附。
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableFloatStateOf(selected.toFloat()) }
    val target = if (dragging) dragPos else selected.toFloat()
    val pos by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "tabPos",
    )

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(shape)
            .background(palette.card.copy(alpha = 0.62f))
            .border(1.dp, palette.border, shape)
            .pointerInput(tabs.size) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        dragging = true
                        dragPos = indexAt(offset.x, size.width.toFloat(), tabs.size)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        dragPos = indexAt(change.position.x, size.width.toFloat(), tabs.size)
                    },
                    onDragEnd = {
                        val picked = dragPos.roundToInt().coerceIn(0, tabs.size - 1)
                        dragging = false
                        onSelect(picked)
                    },
                    onDragCancel = { dragging = false },
                )
            }
    ) {
        val segWidth = maxWidth / tabs.size
        Box(
            Modifier
                .offset {
                    val unit = with(density) { segWidth.roundToPx() }
                    IntOffset((unit * pos).roundToInt(), 0)
                }
                .width(segWidth)
                .fillMaxHeight()
                .padding(4.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(palette.accent.copy(alpha = 0.92f))
        )
        Row(Modifier.fillMaxSize()) {
            tabs.forEachIndexed { index, title ->
                val active = pos.roundToInt() == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Label(
                        text = title,
                        color = if (active) palette.selectedText else palette.primary,
                        size = 13.5.sp,
                        weight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

private fun indexAt(x: Float, width: Float, count: Int): Float {
    if (width <= 0f || count <= 1) return 0f
    val seg = width / count
    return (x / seg).coerceIn(0f, (count - 1).toFloat())
}

@Composable
private fun TopBar(version: String, onBack: () -> Unit) {
    val palette = LocalSettingsPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(56.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onBack() },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(17.dp)) {
                val w = size.minDimension * 0.13f
                drawLine(
                    color = palette.primary,
                    start = Offset(size.width * 0.62f, size.height * 0.12f),
                    end = Offset(size.width * 0.28f, size.height * 0.5f),
                    strokeWidth = w,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = palette.primary,
                    start = Offset(size.width * 0.28f, size.height * 0.5f),
                    end = Offset(size.width * 0.62f, size.height * 0.88f),
                    strokeWidth = w,
                    cap = StrokeCap.Round,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Label("wxglass 设置", palette.primary, 19.sp, weight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        Label(version, palette.secondary, 12.sp)
        Spacer(Modifier.weight(1f))
    }
}

/**
 * 深浅模式：左边文字，右边一个方框显示当前选择；点方框弹出悬浮玻璃胶囊。
 * （以前是顶栏右上角一个小圆，位置不直观，也跟「外观」这一组对不上。）
 */
@Composable
private fun ThemeModeRow(backdrop: Backdrop, themeMode: Int, onChange: (Int) -> Unit) {
    val palette = LocalSettingsPalette.current
    val modes = listOf("跟随系统", "浅色", "深色")
    val current = themeMode.coerceIn(0, 2)
    var open by remember { mutableStateOf(false) }
    val offset = with(LocalDensity.current) { IntOffset(0, 8.dp.roundToPx()) }
    val popupGapPx = with(LocalDensity.current) { 8.dp.roundToPx() }

    Box(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { open = !open }
                .padding(vertical = 13.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Label("深浅模式", palette.primary, 15.sp)
                Spacer(Modifier.height(3.dp))
                Label("玻璃和这个设置页都跟着它", palette.secondary, 11.5.sp)
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(13.dp))
                    .background(palette.card.copy(alpha = 0.72f))
                    .border(1.dp, palette.border, RoundedCornerShape(13.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                Label(modes[current], palette.primary, 13.sp, weight = FontWeight.Medium)
            }
        }
        if (open) {
            Popup(
                popupPositionProvider = object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset {
                        return IntOffset(
                            x = anchorBounds.right - popupContentSize.width,
                            y = anchorBounds.bottom + popupGapPx,
                        )
                    }
                },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                ThemeModeCapsule(backdrop, modes, current) { index ->
                    onChange(index)
                    open = false
                }
            }
        }
    }
}

/** 弹出来的那颗悬浮玻璃胶囊。 */
@Composable
private fun ThemeModeCapsule(
    backdrop: Backdrop,
    modes: List<String>,
    selected: Int,
    onPick: (Int) -> Unit,
) {
    val palette = LocalSettingsPalette.current
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier
            .width(170.dp)
            .clip(shape)
            .glassSurface(backdrop, shape)
            .border(1.dp, palette.border, shape)
            .padding(6.dp)
    ) {
        modes.forEachIndexed { index, label ->
            val isSelected = selected == index
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (isSelected) palette.accent.copy(alpha = 0.85f) else Color.Transparent
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onPick(index) }
                    .padding(horizontal = 14.dp, vertical = 11.dp)
            ) {
                Label(
                    label,
                    if (isSelected) palette.selectedText else palette.primary,
                    13.sp,
                    weight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun LogAction(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalSettingsPalette.current
    Box(
        modifier = modifier
            .padding(vertical = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.accent.copy(alpha = 0.16f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(text, palette.primary, 13.sp, weight = FontWeight.Medium)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Label(
        text = text,
        color = LocalSettingsPalette.current.secondary,
        size = 12.sp,
        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
        letterSpacing = 1.2.sp,
    )
}

@Composable
private fun GlassCard(backdrop: Backdrop, content: @Composable () -> Unit) {
    val shape: Shape = GlassCardShape
    val palette = LocalSettingsPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            // 卡片不再走背景模糊：整页都是玻璃的时候，深色/浅色都发灰、字也糊。
            // 现在用主题色卡面 + 细边框，深浅模式各自清楚。
            .background(palette.card, shape)
            .border(1.dp, palette.border, shape)
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

/** 有 RuntimeShader 就用真玻璃（模糊 + vibrancy），没有就退回半透明底。 */
@Composable
private fun Modifier.glassSurface(backdrop: Backdrop, shape: Shape): Modifier {
    val supported = remember { android.os.Build.VERSION.SDK_INT >= 33 }
    val palette = LocalSettingsPalette.current
    return if (LocalSettingsOverlay.current) {
        this.background(palette.card, shape)
    } else if (supported) {
        val surfaceTint = palette.border.copy(alpha = 0.35f)
        this.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                blur(22.dp.toPx(), 22.dp.toPx())
                vibrancy()
            },
            onDrawSurface = { drawRect(surfaceTint) },
        )
    } else {
        this.background(LocalSettingsPalette.current.card, shape)
    }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LocalSettingsPalette.current.divider)
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onChange(!checked) }
            .padding(vertical = 13.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Label(title, LocalSettingsPalette.current.primary, 15.sp)
            Spacer(Modifier.height(3.dp))
            Label(subtitle, LocalSettingsPalette.current.secondary, 11.5.sp)
        }
        Spacer(Modifier.width(12.dp))
        GlassSwitch(checked)
    }
}

@Composable
private fun GlassSwitch(checked: Boolean) {
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "switch",
    )
    Box(
        modifier = Modifier
            .size(50.dp, 30.dp)
            .clip(CircleShape)
            .background(lerp(LocalSettingsPalette.current.trackOff, LocalSettingsPalette.current.trackOn, progress)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = (3f + 20f * progress).dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    onChange: (Int) -> Unit,
    unit: String = "dp",
    hint: String? = null,
) {
    val palette = LocalSettingsPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(title, palette.primary, 15.sp)
                if (hint != null) {
                    Spacer(Modifier.height(3.dp))
                    Label(hint, palette.secondary, 11.5.sp)
                }
            }
            Label("$value$unit", LocalSettingsPalette.current.secondary, 12.5.sp)
        }
        Spacer(Modifier.height(10.dp))
        GlassSlider(value, min, max, step, onChange)
    }
}

@Composable
private fun GlassSlider(
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    onChange: (Int) -> Unit,
) {
    val palette = LocalSettingsPalette.current
    var widthPx by remember { mutableFloatStateOf(1f) }
    val fraction = ((value - min).toFloat() / (max - min).toFloat()).coerceIn(0f, 1f)

    fun report(fractionX: Float) {
        val ratio = (fractionX / widthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val raw = min + ratio * (max - min)
        val snapped = Math.round(raw / step.toFloat()) * step
        onChange(snapped.coerceIn(min, max))
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(min, max, step) {
                widthPx = size.width.toFloat().coerceAtLeast(1f)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    report(down.position.x)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.pressed } ?: break
                        report(change.position.x)
                        change.consume()
                    }
                }
            }
    ) {
        widthPx = size.width.coerceAtLeast(1f)
        val trackY = size.height / 2f
        val trackH = 6.dp.toPx()
        val radius = CornerRadius(trackH / 2f, trackH / 2f)
        drawRoundRect(
            color = palette.trackOff,
            topLeft = Offset(0f, trackY - trackH / 2f),
            size = Size(size.width, trackH),
            cornerRadius = radius,
        )
        drawRoundRect(
            color = palette.trackOn,
            topLeft = Offset(0f, trackY - trackH / 2f),
            size = Size(size.width * fraction, trackH),
            cornerRadius = radius,
        )
        drawCircle(
            color = Color.White,
            radius = 9.dp.toPx(),
            center = Offset(size.width * fraction, trackY),
        )
    }
}

@Composable
private fun TextRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(vertical = 15.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Label(title, LocalSettingsPalette.current.primary, 15.sp)
            Spacer(Modifier.height(3.dp))
            Label(subtitle, LocalSettingsPalette.current.secondary, 11.5.sp)
        }
        Label("›", LocalSettingsPalette.current.secondary, 20.sp)
    }
}
