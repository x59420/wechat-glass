package com.zaka.wechatglass.iso

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PeopleAlt
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.zaka.wechatglass.ksu.FloatingBottomBar
import com.zaka.wechatglass.ksu.FloatingBottomBarItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import java.util.function.IntConsumer

/**
 * 玻璃层本体。
 *
 * **这个类只会在独立类加载器里被加载**（见 IsoDock），所以它可以随便用
 * androidx.compose / Miuix，而不会跟抖音自己那份 androidx 撞车。
 * 因此这里绝对不引用宿主那边的模块类（WechatGlass / Backdrop / DockController），
 * 只接收框架类型：Context、Bitmap、Rect 和 java.util.function 回调。
 *
 * 渲染器仍是 KernelSU 那一套：FloatingBottomBar + lens / innerShadow / vibrancy，
 * 参数一个字没改。区别只有两处，都是抖音这边的客观差异：
 *   1. 背景不是 Compose 兄弟图层，而是宿主采来的一整块 Bitmap，先画进图层再让透镜去折射；
 *   2. 四个 tab 的胶囊和「＋」是分开的两块，位置由宿主算好后传进来。
 */
class IsoOverlay private constructor(
    context: Context,
    private val onLog: Consumer<String>,
    private val onTabClick: IntConsumer,
    private val onPlusClick: Runnable,
    private val onLongPress: Runnable,
    /**
     * 宿主页面内容的「画师」：把微信这一页的内容画到给定 Canvas 上（窗口坐标）。
     *
     * 这是 KernelSU 那套背景做法的移植：KernelSU 的 Dock 不是隔几十毫秒截一张屏，
     * 而是把内容画进一个 GraphicsLayer，玻璃用 drawBackdrop 直接采样这层纹理
     * （GPU 上做模糊/折射），所以滚动、视频都是实时的。
     * 微信这边内容不是 Compose，就让宿主把它的 View 树画进同一个图层里。
     */
    private val hostPainter: Consumer<android.graphics.Canvas>?
) : FrameLayout(context) {

    private val overlayOwner = OverlayLifecycleOwner()

    /**
     * 自己的 Recomposer。
     *
     * ComposeView attach 时会从 **rootView（抖音的 DecorView）** 往上找
     * ViewTreeLifecycleOwner / SavedStateRegistryOwner 来取 windowRecomposer；
     * DecorView 是抖音自己的，我们不能覆盖它的 tag，找不到就直接抛
     * IllegalStateException，表现就是「玻璃整层不出现」。
     * 所以这里给 ComposeView 一个自带 recomposer 的 parentContext，
     * 完全不依赖宿主那棵树的 lifecycle。
     */
    private val recomposer = Recomposer(AndroidUiDispatcher.Main)
    private val recomposerScope = CoroutineScope(AndroidUiDispatcher.Main + Job())
    private val composeView = SafeComposeHost(context)
    private val drawn = AtomicBoolean(false)

    private var region by mutableStateOf(Rect())
    private var tabsRect by mutableStateOf(Rect())
    private var plusRect by mutableStateOf(Rect())
    /** 宿主采来的背景帧。用 State 而不是普通变量：玻璃在绘制阶段读它，换帧就自动重录。 */
    private val backdropImage = mutableStateOf<ImageBitmap?>(null)
    private var labels by mutableStateOf<List<String>>(emptyList())
    private var icons by mutableStateOf<List<ImageBitmap?>>(emptyList())
    private var selectedIndex by mutableIntStateOf(0)
    /** 玻璃表面不透明度（0..1）。1f = 默认外观，一点不变。 */
    private val glassAlpha = mutableStateOf(1f)
    /** 隐藏玻璃背景：只留图标。 */
    private val hideSurface = mutableStateOf(false)
    /** 0=跟随系统 1=浅色 2=深色；0 时行为和以前完全一致。 */
    private val themeMode = mutableIntStateOf(0)

    @Volatile
    private var disposed = false

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setWillNotDraw(false)
        composeView.onBroken = { where, t ->
            log("Compose 在 $where 阶段出错：$t")
            try {
                visibility = GONE
            } catch (_: Throwable) {
            }
        }
        composeView.setViewTreeLifecycleOwner(overlayOwner)
        composeView.inner.setViewTreeLifecycleOwner(overlayOwner)
        composeView.setViewTreeSavedStateRegistryOwner(overlayOwner)
        composeView.inner.setViewTreeSavedStateRegistryOwner(overlayOwner)
        overlayOwner.resume()
        composeView.inner.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindow
        )
        // 先把 parentContext 设好再 setContent：AbstractComposeView 只允许在
        // composition 建立之前赋值，之后设置会直接抛。
        //
        // 这个属性在部分 Compose 版本里是 private，所以走反射；设不进去也不影响，
        // 外面已经给 ComposeView 挂了 ViewTreeLifecycleOwner，会退回 windowRecomposer。
        if (attachRecomposer()) {
            recomposerScope.launch { recomposer.runRecomposeAndApplyChanges() }
        } else {
            try {
                recomposer.cancel()
            } catch (_: Throwable) {
            }
            try {
                recomposerScope.cancel()
            } catch (_: Throwable) {
            }
            log("自定义 Recomposer 未启用，回退到宿主 lifecycle")
        }
        addView(composeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        composeView.inner.setContent {
            // 只创建一次：ThemeController 自带状态和协程，放在 composable 体里
            // 会随每一帧重组重新 new（背景 30fps 刷新时就是每秒几十个），几十秒能把宿主内存拖爆。
            val systemDark = isSystemInDarkTheme()
            val dark = when (themeMode.intValue) {
                1 -> false
                2 -> true
                else -> systemDark
            }
            val controller = remember(dark) {
                ThemeController(
                    ColorSchemeMode.System,
                    isDark = dark,
                    paletteStyle = ThemePaletteStyle.TonalSpot,
                    colorSpec = ThemeColorSpec.Spec2025
                )
            }
            MiuixTheme(controller = controller) {
                GlassSurface(
                    image = backdropImage,
                    region = region,
                    tabsRect = tabsRect,
                    plusRect = plusRect,
                    labels = labels,
                    icons = icons,
                    selectedIndex = selectedIndex,
                    surfaceAlpha = glassAlpha.value,
                    hideSurface = hideSurface.value,
                    dark = dark,
                    onTabClick = { index -> onTabClick.accept(index) },
                    onPlusClick = { onPlusClick.run() },
                    onLongPress = { onLongPress.run() },
                    hostPainter = hostPainter,
                    onDrawn = {
                        if (drawn.compareAndSet(false, true)) log("玻璃已绘制")
                    }
                )
            }
        }
    }

    companion object {
        @JvmStatic
        fun create(
            context: Context,
            onLog: Consumer<String>,
            onTabClick: IntConsumer,
            onPlusClick: Runnable,
            onLongPress: Runnable,
            hostPainter: Consumer<android.graphics.Canvas>?
        ): IsoOverlay = IsoOverlay(
            context, onLog, onTabClick, onPlusClick, onLongPress, hostPainter
        )
    }

    /** 宿主算好的位置（都在覆盖层自己的坐标系里）。 */
    fun setGeometry(region: Rect, tabs: Rect, plus: Rect) {
        if (disposed) return
        this.region = Rect(region)
        this.tabsRect = Rect(tabs)
        this.plusRect = Rect(plus)
    }

    /** 宿主采来的底栏那一条真实画面。所有权交给这里，宿主每帧给的都是新副本。 */
    fun setBackdrop(bitmap: Bitmap?) {
        if (disposed || bitmap == null || bitmap.isRecycled) return
        backdropImage.value = try {
            bitmap.asImageBitmap()
        } catch (t: Throwable) {
            log("背景位图转换失败：$t")
            null
        }
    }

    /** 采样画面整片发黑时把它清掉：宁可只留玻璃本身的模糊，也不要一块黑玻璃。 */
    fun clearBackdrop() {
        if (disposed) return
        backdropImage.value = null
    }

    /**
     * 外观参数：玻璃不透明度 / 隐藏玻璃背景 / 深浅模式。
     * 默认（100%、不隐藏、跟随系统）和以前的绘制结果完全一致。
     */
    fun setAppearance(alphaPercent: Int, hide: Boolean, theme: Int) {
        if (disposed) return
        val alpha = (alphaPercent.coerceIn(0, 100)).toFloat() / 100f
        val mode = theme.coerceIn(0, 2)
        if (glassAlpha.value != alpha) glassAlpha.value = alpha
        if (hideSurface.value != hide) hideSurface.value = hide
        if (themeMode.intValue != mode) themeMode.intValue = mode
    }

    fun setTabs(labelArray: Array<String>, iconArray: Array<Bitmap?>, selected: Int) {
        if (disposed) return
        labels = labelArray.toList()
        icons = iconArray.map { bitmap ->
            if (bitmap == null || bitmap.isRecycled) {
                null
            } else {
                try {
                    bitmap.asImageBitmap()
                } catch (_: Throwable) {
                    null
                }
            }
        }
        selectedIndex = selected.coerceIn(0, maxOf(0, labelArray.size - 1))
    }

    fun isBroken(): Boolean = composeView.broken

    fun hasDrawn(): Boolean = drawn.get()

    fun dispose() {
        if (disposed) return
        disposed = true
        try {
            composeView.inner.disposeComposition()
        } catch (_: Throwable) {
        }
        try {
            recomposer.cancel()
        } catch (_: Throwable) {
        }
        try {
            recomposerScope.cancel()
        } catch (_: Throwable) {
        }
        overlayOwner.destroy()
        backdropImage.value = null
    }

    private fun log(message: String) {
        try {
            onLog.accept(message)
        } catch (_: Throwable) {
        }
    }

    /**
     * 把自带的 Recomposer 挂到 ComposeView 上。
     *
     * 返回 false 表示这版 Compose 的 parentContext 拿不到（不同版本的可见性不一样），
     * 此时安静回退到宿主 lifecycle，绝不让整层玻璃因为一个反射失败而不出现。
     */
    private fun attachRecomposer(): Boolean {
        return try {
            val field = androidx.compose.ui.platform.AbstractComposeView::class.java
                .getDeclaredField("parentContext")
            field.isAccessible = true
            field.set(composeView.inner, recomposer)
            true
        } catch (t: Throwable) {
            log("反射设置 parentContext 失败：$t")
            false
        }
    }
}

/**
 * Dock 图标：1.85dp 细线手绘，跟 0.0.5 那套完全一致（房子 / 两个人 / 气泡 / 头肩）。
 *
 * 上一版为了在玻璃上「立得住」换成了实心剪影 + 抖音原生图标位图，结果视觉全变了。
 * 这里把原来那组 24x24 的路径坐标原样搬进 Compose 的 DrawScope：
 * 坐标和线宽都没动，只是画笔从 android.graphics.Paint 换成 Stroke。
 */
@Composable
private fun LineTabIcon(index: Int, color: Color) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val k = size.minDimension / 24f
        scale(k, k, pivot = Offset.Zero) {
            val stroke = Stroke(width = 1.85f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val path = Path()
            when (index) {
                0 -> {
                    path.moveTo(3.2f, 10.7f)
                    path.lineTo(12f, 3.4f)
                    path.lineTo(20.8f, 10.7f)
                    path.moveTo(5.4f, 9.4f)
                    path.lineTo(5.4f, 20.5f)
                    path.quadraticBezierTo(5.4f, 21.2f, 6.2f, 21.2f)
                    path.lineTo(17.8f, 21.2f)
                    path.quadraticBezierTo(18.6f, 21.2f, 18.6f, 20.5f)
                    path.lineTo(18.6f, 9.4f)
                    path.moveTo(9.5f, 21f)
                    path.lineTo(9.5f, 15.3f)
                    path.quadraticBezierTo(9.5f, 14.6f, 10.2f, 14.6f)
                    path.lineTo(13.8f, 14.6f)
                    path.quadraticBezierTo(14.5f, 14.6f, 14.5f, 15.3f)
                    path.lineTo(14.5f, 21f)
                }
                1 -> {
                    drawCircle(color, 3.1f, Offset(9f, 8f), style = stroke)
                    drawCircle(color, 2.45f, Offset(17.1f, 9.2f), style = stroke)
                    path.moveTo(2.8f, 20.8f)
                    path.quadraticBezierTo(3.2f, 14.2f, 9f, 14.2f)
                    path.quadraticBezierTo(14.8f, 14.2f, 15.2f, 20.8f)
                    path.moveTo(14.2f, 14.8f)
                    path.quadraticBezierTo(20.6f, 13.7f, 21.2f, 20.8f)
                }
                2 -> {
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(3f, 3.8f),
                        size = Size(18f, 13.3f),
                        cornerRadius = CornerRadius(5f, 5f),
                        style = stroke
                    )
                    path.moveTo(7.2f, 16.8f)
                    path.lineTo(6.1f, 21f)
                    path.lineTo(12.1f, 17.1f)
                    drawCircle(color, 0.85f, Offset(8f, 10.4f), style = stroke)
                    drawCircle(color, 0.85f, Offset(12f, 10.4f), style = stroke)
                    drawCircle(color, 0.85f, Offset(16f, 10.4f), style = stroke)
                }
                else -> {
                    drawCircle(color, 3.7f, Offset(12f, 8f), style = stroke)
                    path.moveTo(3.2f, 21f)
                    path.quadraticBezierTo(3.8f, 13.7f, 12f, 13.7f)
                    path.quadraticBezierTo(20.2f, 13.7f, 20.8f, 21f)
                }
            }
            drawPath(path, color, style = stroke)
        }
    }
}

@Composable
private fun GlassSurface(
    image: MutableState<ImageBitmap?>,
    region: Rect,
    tabsRect: Rect,
    plusRect: Rect,
    labels: List<String>,
    icons: List<ImageBitmap?>,
    selectedIndex: Int,
    surfaceAlpha: Float,
    hideSurface: Boolean,
    dark: Boolean,
    hostPainter: Consumer<android.graphics.Canvas>?,
    onTabClick: (Int) -> Unit,
    onPlusClick: () -> Unit,
    onLongPress: () -> Unit,
    onDrawn: () -> Unit
) {
    // 背景层只做记录，不负责显示：画面由宿主画进这一层，
    // 玻璃（drawBackdrop）自己去采样。界面上不会再画一份副本。
    //
    // 两条路：
    //   1. hostPainter != null（Android 12+）：KernelSU 同款做法 —— 把微信这一页的
    //      View 树直接画进 GraphicsLayer，玻璃采样这层纹理。全程在 GPU 上，
    //      滚动/视频跟手，不用每隔 33ms 做一次「截屏 + 回读」，也就不卡了。
    //   2. 没有 painter：仍旧用宿主采好的 Bitmap（老系统 / 宿主画不出来时的兜底）。
    val regionLeft = region.left.toFloat()
    val regionTop = region.top.toFloat()
    val backdrop = rememberLayerBackdrop(onDraw = {
        val painter = hostPainter
        if (painter != null && size.width > 0f && size.height > 0f) {
            try {
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    val save = native.save()
                    native.translate(-regionLeft, -regionTop)
                    painter.accept(native)
                    native.restoreToCount(save)
                }
            } catch (_: Throwable) {
                drawRect(color = Color(0xFF1C1D22), size = size)
            }
        } else {
            val frame = image.value
            if (frame != null && size.width > 0f && size.height > 0f) {
                drawImage(
                    image = frame,
                    dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                    dstSize = androidx.compose.ui.unit.IntSize(
                        size.width.toInt().coerceAtLeast(1),
                        size.height.toInt().coerceAtLeast(1),
                    ),
                    filterQuality = FilterQuality.Low,
                )
            } else if (size.width > 0f && size.height > 0f) {
                // 一帧画面都没有（视频受保护抓不到 / 刚进页面）：先垫一层中性深色。
                drawRect(color = Color(0xFF1C1D22), size = size)
            }
        }
    })
    val density = LocalDensity.current
    // 重录节拍：40fps。只在这一帧真的需要重画时才自增，避免空转。
    var frameTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(hostPainter) {
        if (hostPainter != null) {
            while (true) {
                kotlinx.coroutines.delay(24L)
                frameTick++
            }
        }
    }
    // 33 以下没有 RuntimeShader；个别机型/ROM 上 AGSL 编译会直接抛错，
    // 这里先探一次：探不通就走 Miuix 自带的「无模糊」降级，绝不让一帧着色器异常把整层玻璃干掉。
    val isBlurEnabled = remember {
        Build.VERSION.SDK_INT >= 33 && runCatching {
            android.graphics.RuntimeShader(
                "uniform float4 c; half4 main(float2 p) { return half4(c); }"
            )
        }.isSuccess
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { onDrawn() }
    ) {
        if (region.width() > 0 && region.height() > 0) {
            Box(
                modifier = Modifier
                    .offset(with(density) { region.left.toDp() }, with(density) { region.top.toDp() })
                    .size(
                        with(density) { region.width().toDp() },
                        with(density) { region.height().toDp() }
                    )
                    // 每一帧都重新录一次这一条：内容在滚、视频在动，图层必须跟着重录。
                    // 只读 tick 不改状态，所以只会重绘、不会触发重组。
                    .drawWithContent {
                        if (hostPainter != null) frameTick
                        drawContent()
                    }
                    .layerBackdrop(backdrop)
            )
        }

        if (labels.isNotEmpty() && tabsRect.width() > 0 && tabsRect.height() > 0) {
            Box(
                modifier = Modifier
                    .offset(with(density) { tabsRect.left.toDp() }, with(density) { tabsRect.top.toDp() })
                    .width(with(density) { tabsRect.width().toDp() }),
                contentAlignment = Alignment.TopCenter
            ) {
                FloatingBottomBar(
                    modifier = Modifier,
                    selectedIndex = selectedIndex.coerceIn(0, labels.lastIndex),
                    onSelected = onTabClick,
                    backdrop = backdrop,
                    tabsCount = labels.size,
                    isBlurEnabled = isBlurEnabled,
                    barWidth = with(density) { tabsRect.width().toDp() },
                    barHeight = with(density) { tabsRect.height().toDp() },
                    surfaceAlpha = surfaceAlpha,
                    hideSurface = hideSurface,
                    darkOverride = dark
                ) { activate ->
                    labels.forEachIndexed { index, label ->
                        FloatingBottomBarItem(
                            selected = index == selectedIndex,
                            onClick = { activate(index) },
                            // KernelSU 的 BottomBarMiuix 就是给每个 item 定 76dp 最小宽度，
                            // FloatingBottomBar 用的是 width(IntrinsicSize.Min)，没有这个下限
                            // 胶囊会被内容挤到很窄，看起来像「玻璃没出来」。
                            modifier = Modifier
                        ) {
                            // 优先用微信原生导航项的图标位图（按当前配色着色，
                            // 选中 / 未选中跟着玻璃自己的内容色走）；
                            // 取不到才退回 dydock 那套手绘线稿图标。
                            val nativeIcon = icons.getOrNull(index)
                            if (nativeIcon != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = nativeIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                                        LocalContentColor.current
                                    )
                                )
                            } else {
                                LineTabIcon(index = index, color = LocalContentColor.current)
                            }
                            // 关掉文字标签时不再塞一个空 Text —— 空 Text 照样占 14sp 的行高，
                            // 图标会被顶在上面。没有这个兄弟节点，Column 自然把图标居中。
                            if (label.isNotEmpty()) {
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Visible
                                )
                            }
                        }
                    }
                }
            }
        }

        if (plusRect.width() > 0 && plusRect.height() > 0) {
            Box(
                modifier = Modifier
                    .offset(with(density) { plusRect.left.toDp() }, with(density) { plusRect.top.toDp() })
                    .size(
                        with(density) { plusRect.width().toDp() },
                        with(density) { plusRect.height().toDp() }
                    )
                    .clip(CircleShape)
                    .background(Color(0xFFFE2C55))
                    .clickable(onClick = onPlusClick),
                contentAlignment = Alignment.Center
            ) {
                Text("+", color = Color.White, fontSize = 34.sp, lineHeight = 34.sp)
            }
        }
    }
}

/** ComposeView 需要的生命周期 / 状态宿主；抖音的 DecorView 上没有现成的。 */
private class OverlayLifecycleOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = registry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
    }

    fun resume() {
        if (registry.currentState == Lifecycle.State.INITIALIZED) {
            registry.currentState = Lifecycle.State.CREATED
        }
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

/**
 * ComposeView 的护栏外壳。
 *
 * 测量 / 布局 / 绘制任何一环抛错，都不许让它冒到宿主主线程（那就是抖音闪退）。
 * 出错就把绘制锁死并上报，宿主会把原生底栏还回去。
 */
private class SafeComposeHost(context: Context) : FrameLayout(context) {

    val inner = ComposeView(context)

    @Volatile
    var broken = false
        private set

    var onBroken: ((String, Throwable) -> Unit)? = null

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        addView(inner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun guard(where: String, block: () -> Unit) {
        if (broken) return
        try {
            block()
        } catch (t: Throwable) {
            broken = true
            onBroken?.invoke(where, t)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        guard("measure") { super.onMeasure(widthMeasureSpec, heightMeasureSpec) }
        if (broken) setMeasuredDimension(0, 0)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        guard("layout") { super.onLayout(changed, left, top, right, bottom) }
    }

    override fun dispatchDraw(canvas: Canvas) {
        guard("draw") { super.dispatchDraw(canvas) }
    }
}
