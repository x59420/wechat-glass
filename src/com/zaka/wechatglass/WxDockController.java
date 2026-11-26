package com.zaka.wechatglass;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * 把微信底部那条原生导航栏换成悬浮液态玻璃 Dock。
 *
 * 与 dydock 的关系：玻璃渲染（Compose + Miuix、折射 / 色散 / 高光 / 弹簧）
 * 和几何参数完全沿用 dydock，这边只重写"宿主适配层"——找微信底栏、量位置、
 * 让原生底栏让位、把点击转发回原生导航项。
 *
 * 三条硬约束：
 *   1. **不动微信的交互逻辑**：点击一律模拟真实触摸给原生底栏，模块不接管切换；
 *      原生底栏只把 alpha 归零（SelfMask 登记原值），绝不 setVisibility(GONE)，
 *      否则会触发微信重新布局、底栏位置和状态都会跟着变。
 *   2. **先确认玻璃画出来了，再藏原生底栏**：反过来做，中途任何一步失败就是
 *      "原生没了、玻璃也没上来"，用户看到整条底栏消失。另外带一个看门狗兜底。
 *   3. **位置量一次就钉住**：只在窗口尺寸变化（转屏 / 分屏）时重量，
 *      不跟着微信底栏每帧漂。
 */
final class WxDockController {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 轮询间隔：同步图标 / 选中态 / 可见性。 */
    private static final long POLL_MS = 200L;
    /** 找底栏的重试：微信首屏要等布局，最多重试这么多次。 */
    private static final int MAX_TRIES = 60;
    private static final long RETRY_MS = 350L;
    /** 玻璃多久没画出来就认为失败，把原生底栏还回去。 */
    private static final long WATCHDOG_MS = 2600L;
    /** 显示 / 隐藏的过渡，和 dydock 一致：150ms / 110ms。 */
    private static final long IN_MS = 150L;
    private static final long OUT_MS = 110L;

    private static final WeakHashMap<Activity, Boolean> KNOWN =
            new WeakHashMap<Activity, Boolean>();
    private static final WeakHashMap<Activity, DockLayer> OVERLAYS =
            new WeakHashMap<Activity, DockLayer>();
    private static final WeakHashMap<Activity, Backdrop> BACKDROPS =
            new WeakHashMap<Activity, Backdrop>();
    private static final WeakHashMap<Activity, ViewGroup> BARS =
            new WeakHashMap<Activity, ViewGroup>();
    private static final WeakHashMap<Activity, List<View>> ITEMS =
            new WeakHashMap<Activity, List<View>>();
    private static final WeakHashMap<Activity, Rect> BANDS =
            new WeakHashMap<Activity, Rect>();
    private static final WeakHashMap<Activity, Runnable> POLLERS =
            new WeakHashMap<Activity, Runnable>();
    private static final WeakHashMap<Activity, Boolean> LIVE =
            new WeakHashMap<Activity, Boolean>();

    /**
     * 当前真正在前台的那个页面。
     *
     * 只靠「原生底栏还在不在」判断显隐是不够的：微信把底栏留在自己的窗口里，
     * 聊天页、添加朋友页压上来之后，挂在首页 decor 上的玻璃层在部分机型/转场方式下
     * 依然会被画出来（用户看到的就是「进了聊天页 Dock 还在」）。
     * 所以在系统生命周期里记一份「谁在最上面」，不是首页就一律收起来。
     */
    private static volatile Activity TOP;

    /**
     * 系统自己记的「当前前台是哪个页面」。
     *
     * 上一版只靠我们自己记的 TOP 判断，而 TOP 依赖生命周期回调 / onResume 钩子 ——
     * 微信一旦在哪条路上没调 super，或者回调比转场晚一拍，聊天页、添加朋友页压上来时
     * Dock 就还挂在首页上不动。这里直接读 ActivityThread 里的 mResumedActivity，
     * 它由系统在转场时写入，跟微信怎么写自己的生命周期完全无关。
     */
    private static volatile java.lang.reflect.Field RESUMED_FIELD;

    /** 读不到（字段改名 / 隐藏 API 限制）时返回 null，由调用方退回 TOP 判断。 */
    static Activity resumedActivity() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            if (thread == null) return null;
            java.lang.reflect.Field f = RESUMED_FIELD;
            if (f == null) {
                java.lang.reflect.Field found = null;
                Class<?> c = at;
                while (c != null && found == null) {
                    try {
                        found = c.getDeclaredField("mResumedActivity");
                    } catch (NoSuchFieldException ignored) {
                        c = c.getSuperclass();
                    }
                }
                if (found == null) return null;
                found.setAccessible(true);
                RESUMED_FIELD = f = found;
            }
            Object value = f.get(thread);
            return value instanceof Activity ? (Activity) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 最近一次 resume 的时间：用来给「刚进页面、焦点还没给过来」留一点宽限。 */
    private static final WeakHashMap<Activity, Long> RESUMED_AT = new WeakHashMap<Activity, Long>();
    /** 首页上又叠了一层自己的面板（「＋」菜单）时，Dock 先收起来。 */
    private static final WeakHashMap<Activity, Boolean> COVERED = new WeakHashMap<Activity, Boolean>();
    /** 首页窗口当前有没有焦点。失焦基本等于「被别的页面/弹窗盖住了」。 */
    private static final WeakHashMap<Activity, Boolean> FOCUSED = new WeakHashMap<Activity, Boolean>();

    private WxDockController() {
    }

    // ------------------------------------------------------------ 页面生命周期

    /** 微信首页（LauncherUI）才会挂 Dock。 */
    static boolean isHost(Activity act) {
        if (act == null) return false;
        try {
            String name = act.getClass().getName();
            if (name.endsWith("LauncherUI")) return true;
            // 类名对不上也不能直接放弃：这条窗口里真有微信那条底栏，就是首页。
            return WxTabBar.hasTabBar(act);
        } catch (Throwable t) {
            return false;
        }
    }

    static void onHostResume(final Activity act) {
        if (!isHost(act)) return;
        synchronized (KNOWN) {
            KNOWN.put(act, Boolean.TRUE);
        }
        if (OVERLAYS.containsKey(act)) {
            // 回到前台：重新贴一次位置（转屏 / 分屏后会用到），并让 Dock 立刻出现。
            Runnable refresh = POLLERS.get(act);
            if (refresh != null) refresh.run();
            return;
        }
        attempt(act, 0);
    }

    static void release(Activity act) {
        if (act == null) return;
        teardown(act, "页面销毁");
        synchronized (KNOWN) {
            KNOWN.remove(act);
        }
    }

    static void onHostPause(Activity act) {
        // 微信切到别的 Activity（聊天页等）时，Dock 挂在 LauncherUI 的 decor 上，
        // 本来就跟着一起不可见，这里不需要额外动作。
        if (act != null && TOP == act) TOP = null;
        hideNow(act);
    }

    /**
     * 任何页面回到前台都走这里。
     *
     * 是首页 → 正常接管；不是首页（聊天页、添加朋友页、扫一扫…）→ 记下"最上面是谁"，
     * Dock 的轮询会因为 TOP 对不上而立刻收起来。
     */
    static void onAnyResume(Activity act) {
        if (act == null) return;
        TOP = act;
        synchronized (RESUMED_AT) { RESUMED_AT.put(act, SystemClock.uptimeMillis()); }
        if (isHost(act)) onHostResume(act);
    }

    /** 页面创建：先记一笔，方便后面判断谁在最上面。 */
    static void onAnyCreate(Activity act) {
        if (act == null) return;
        synchronized (RESUMED_AT) { RESUMED_AT.put(act, SystemClock.uptimeMillis()); }
    }

    /** 窗口焦点：首页被盖住 / 弹窗抢焦点时，立刻把 Dock 收起来。 */
    static void onFocusChanged(Activity act, boolean focused) {
        if (act == null) return;
        synchronized (FOCUSED) { FOCUSED.put(act, focused); }
        if (!focused && isHost(act)) hideNow(act);
        if (focused && isHost(act)) {
            synchronized (RESUMED_AT) { RESUMED_AT.put(act, SystemClock.uptimeMillis()); }
        }
    }

    /** 「＋」面板这类覆盖层开 / 关：开着的时候 Dock 让位。 */
    static void setCovered(Activity act, boolean covered) {
        if (act == null) return;
        synchronized (COVERED) { COVERED.put(act, covered); }
        if (covered) hideNow(act);
    }

    static void onAnyPause(Activity act) {
        if (act == null) return;
        // 故意不清空 TOP：清空之后「有没有别的页面压在上面」就变成未知，
        // 轮询里会被判成"没人盖住"而把 Dock 又显示出来。保留最后一个前台页面更安全。
        if (isHost(act)) hideNow(act);
    }

    /** 页面要走了（onPause / onStop），立刻收起，不等轮询。 */
    static void onAnyStop(Activity act) {
        if (act == null) return;
        if (isHost(act)) hideNow(act);
    }

    /** 立刻收起 Dock（不拆玻璃层，回来还能接着用）。 */
    private static void hideNow(Activity act) {
        if (act == null) return;
        DockLayer iso;
        synchronized (OVERLAYS) { iso = OVERLAYS.get(act); }
        if (iso == null) return;
        try {
            View v = iso.view();
            v.animate().cancel();
            v.setAlpha(0f);
            v.setVisibility(View.GONE);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 安装

    private static void attempt(final Activity act, final int n) {
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (act.isFinishing() || act.isDestroyed()) return;
                    if (OVERLAYS.containsKey(act)) return;
                    if (install(act)) return;
                    if (n + 1 >= MAX_TRIES) {
                        WechatGlass.warn("底栏:重试 " + MAX_TRIES + " 次仍未认到微信底栏"
                                + "（类名=" + WxTabBar.describe(safeDecor(act)) + "）");
                        WechatGlass.toast(act, "wxglass：没认到微信底栏");
                        return;
                    }
                    attempt(act, n + 1);
                } catch (Throwable t) {
                    WechatGlass.error("底栏:安装过程出错", t);
                }
            }
        }, n == 0 ? 300L : RETRY_MS);
    }

    private static View safeDecor(Activity act) {
        try {
            return act.getWindow() == null ? null : act.getWindow().getDecorView();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean install(final Activity act) {
        WechatGlass.setVerbose(DockPrefs.logEnabled(act));
        if (!DockPrefs.enabled(act)) {
            // 用户在设置里关掉了：微信原生底栏一概不碰。
            return true;
        }
        final View decor = safeDecor(act);
        final View content = act.findViewById(android.R.id.content);
        if (!(decor instanceof ViewGroup) || content == null) return false;
        if (content.getWidth() < 50 || content.getHeight() < 50) return false;

        ViewGroup bar = WxTabBar.find(decor, act);
        if (bar == null) return false;

        List<View> items = WxTabBar.items(bar);
        if (items.size() < 3) {
            // 认到底栏本身就够了：格子可能还没布局完，先按等分接管，
            // 之后每一轮轮询都会重新认一次，认到了就换成真实格子。
            WechatGlass.warn("底栏:暂时只认出 " + items.size() + " 个格子，先按等分接管"
                    + "（" + WxTabBar.describe(bar) + "）");
        }

        final Backdrop backdrop = new Backdrop();
        backdrop.setContent(content);
        backdrop.setSampleScale();

        // 背景走 KernelSU 那一套：把这一页的内容画进 GraphicsLayer，玻璃直接采样这层纹理。
        // 好处是全程在 GPU 上 —— 不用每隔 33ms 把屏幕像素拷回内存（那一步才是卡的根源），
        // 滚动、翻页、视频都是实时的，玻璃里也就是真画面而不是一张旧截图。
        final boolean livePaint = android.os.Build.VERSION.SDK_INT >= 31;
        final Consumer<Canvas> hostPainter;
        if (livePaint) {
            hostPainter = new Consumer<Canvas>() {
                @Override
                public void accept(Canvas canvas) {
                    try {
                        if (!content.isAttachedToWindow() || !content.isShown()) return;
                        int[] loc = new int[2];
                        content.getLocationInWindow(loc);
                        int save = canvas.save();
                        canvas.translate(loc[0], loc[1]);
                        content.draw(canvas);
                        canvas.restoreToCount(save);
                    } catch (Throwable t) {
                        WechatGlass.warnOnce("host-painter", "内容画进玻璃图层失败: " + t);
                    }
                }
            };
        } else {
            hostPainter = null;
            // 老系统（没有 RuntimeShader / GraphicsLayer 录制）：仍用窗口直采。
            backdrop.setWindow(act.getWindow());
        }

        final List<View>[] targets = new List[]{new ArrayList<View>(items)};
        final ViewGroup[] barRef = new ViewGroup[]{bar};

        final Consumer<String> glassLog = new Consumer<String>() {
            @Override
            public void accept(String message) {
                WechatGlass.log("[玻璃] " + message);
            }
        };
        final IntConsumer onTab = new IntConsumer() {
            @Override
            public void accept(int index) {
                try {
                    List<View> list = targets[0];
                    if (list != null && list.size() >= 3
                            && index >= 0 && index < list.size()) {
                        WxTabBar.tap(barRef[0], list.get(index), index);
                        return;
                    }
                    // 格子还没认出来：按等分位置转发，交互照样是微信原生那条链。
                    WxTabBar.tapRatio(barRef[0], index, tabCount(list));
                } catch (Throwable t) {
                    WechatGlass.warnOnce("iso-tab-click", "转发导航点击失败: " + t);
                }
            }
        };
        final Runnable onPlus = new Runnable() {
            @Override
            public void run() {
                // 微信没有「＋」，这个回调用不到。
            }
        };
        final Runnable onLongPress = new Runnable() {
            @Override
            public void run() {
                // 长按不作为设置入口。
            }
        };

        // 先上 KernelSU 那套 Compose 渲染器；它要是起不来，就退到 Canvas 版，
        // 总之不能让用户看到「底栏一点没变」。
        DockLayer created = IsoDock.create(act, glassLog, onTab, onPlus, onLongPress, hostPainter);
        if (created == null) {
            WechatGlass.warn("底栏:Compose 渲染器没起来，改用兜底玻璃层");
            created = FallbackDock.create(act, glassLog, onTab);
        }
        if (created == null) {
            WechatGlass.warn("底栏:两套渲染器都没起来，保留微信原生底栏");
            return true;
        }
        final DockLayer iso = created;

        try {
            ((ViewGroup) decor).addView(iso.view(), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Throwable t) {
            WechatGlass.error("底栏:玻璃层挂不上窗口", t);
            iso.dispose();
            return true;
        }

        WechatGlass.toast(act, "wxglass：Dock 已挂上");

        synchronized (OVERLAYS) { OVERLAYS.put(act, iso); }
        synchronized (BACKDROPS) { BACKDROPS.put(act, backdrop); }
        synchronized (BARS) { BARS.put(act, bar); }
        synchronized (ITEMS) { ITEMS.put(act, targets[0]); }

        final boolean[] live = new boolean[]{true};
        synchronized (LIVE) { LIVE.put(act, Boolean.TRUE); }
        final int[] lastWindowW = new int[]{decor.getWidth()};
        final int[] lastWindowH = new int[]{decor.getHeight()};
        final Rect[] band = new Rect[]{null};
        /** 这一次量到的位置是不是来自原生底栏本身（false = 首屏还没布局好时用的兜底）。 */
        final boolean[] bandFromBar = new boolean[]{false};
        final boolean[] shown = new boolean[]{false};

        backdrop.setOnFrame(new Runnable() {
            @Override
            public void run() {
                if (!live[0]) return;
                pushBackdrop(iso, backdrop);
            }
        });

        final Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (!live[0]) return;
                try {
                    if (iso.isBroken()) {
                        live[0] = false;
                        MAIN.removeCallbacks(this);
                        teardown(act, "玻璃渲染出错");
                        WechatGlass.warn("底栏:玻璃渲染出错，已恢复微信原生底栏");
                        return;
                    }
                    if (act.isFinishing() || act.isDestroyed()) {
                        live[0] = false;
                        MAIN.removeCallbacks(this);
                        return;
                    }

                    // 玻璃层被转场摘掉时挂回去（微信切 tab 一般不重建 decor，兜个底）。
                    if (!iso.view().isAttachedToWindow() && decor.isAttachedToWindow()) {
                        ViewParent parent = iso.view().getParent();
                        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(iso.view());
                        ((ViewGroup) decor).addView(iso.view(), new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                        WechatGlass.log("底栏:玻璃层已重新挂回主窗口");
                    }

                    ViewGroup currentBar = barRef[0];
                    if ((targets[0] == null || targets[0].size() < 3)
                            && currentBar != null && currentBar.isAttachedToWindow()) {
                        List<View> late = WxTabBar.items(currentBar);
                        if (late.size() >= 3) {
                            targets[0] = new ArrayList<View>(late);
                            synchronized (ITEMS) { ITEMS.put(act, targets[0]); }
                            WechatGlass.log("底栏:格子认到了 " + late.size() + " 个，切换为真实格子");
                            band[0] = measureBand(act, currentBar, iso.view(), tabCount(targets[0]));
                            if (band[0] != null) {
                                applyGeometry(act, iso, backdrop, band[0], tabCount(targets[0]));
                            }
                        }
                    }
                    if (currentBar == null || !currentBar.isAttachedToWindow()) {
                        // 微信重建了底栏：重新认一次，认到就换目标。
                        ViewGroup fresh = WxTabBar.find(decor, act);
                        if (fresh != null) {
                            List<View> freshItems = WxTabBar.items(fresh);
                            if (freshItems.size() >= 3) {
                                barRef[0] = fresh;
                                targets[0] = new ArrayList<View>(freshItems);
                                synchronized (BARS) { BARS.put(act, fresh); }
                                synchronized (ITEMS) { ITEMS.put(act, targets[0]); }
                                SelfMask.restore(currentBar);
                                SelfMask.mask(fresh);
                                currentBar = fresh;
                                WechatGlass.log("底栏:微信重建了底栏节点，已重新接管");
                            }
                        }
                    }

                    // 窗口尺寸变了（转屏 / 分屏）才重新量位置。
                    int w = decor.getWidth();
                    int h = decor.getHeight();
                    boolean windowChanged = lastWindowW[0] != w || lastWindowH[0] != h;
                    lastWindowW[0] = w;
                    lastWindowH[0] = h;
                    // 首屏布局还没完成时会先用窗口底部兜一个位置（bandFromBar=false）；
                    // 等原生底栏真正完成布局，必须再量一次，否则 Dock 会一直停在兜底位置。
                    boolean barReady = currentBar != null
                            && currentBar.isAttachedToWindow() && currentBar.getWidth() > 0;
                    if (band[0] == null || windowChanged || (barReady && !bandFromBar[0])) {
                        band[0] = measureBand(act, currentBar, iso.view(), tabCount(targets[0]));
                        bandFromBar[0] = barReady;
                        synchronized (BANDS) { BANDS.put(act, band[0]); }
                        if (band[0] != null) applyGeometry(act, iso, backdrop, band[0], tabCount(targets[0]));
                    }

                    // 广播丢了也能兜住：设置页在模块进程，这边用 XSharedPreferences 比版本号。
                    if (DockPrefs.syncFromModule(act)) {
                        WechatGlass.log("设置:检测到新配置，重新应用");
                        band[0] = measureBand(act, currentBar, iso.view(), tabCount(targets[0]));
                        if (band[0] != null) {
                            applyGeometry(act, iso, backdrop, band[0], tabCount(targets[0]));
                        }
                    }

                    refreshTabs(act, iso, targets[0]);

                    // 原生底栏还在、还有实体，就显示；不然收起来。
                    // 前台必须是首页：聊天页 / 添加朋友页 / 扫一扫压上来时一律收起。
                    //
                    // 两路判断叠一起，谁先拿到算谁：
                    //   1) 系统记的 mResumedActivity（最准，和微信怎么写生命周期无关）；
                    //   2) 我们自己记的 TOP（读不到上面那个字段时兜底）。
                    Activity resumed = resumedActivity();
                    Activity top = resumed != null ? resumed : TOP;
                    boolean otherOnTop = top != null && top != act
                            && !top.isFinishing() && !top.isDestroyed();
                    boolean active = !otherOnTop;
                    boolean covered;
                    synchronized (COVERED) {
                        Boolean flag = COVERED.get(act);
                        covered = flag != null && flag;
                    }
                    Boolean focusFlag;
                    synchronized (FOCUSED) { focusFlag = FOCUSED.get(act); }
                    long resumedAt;
                    synchronized (RESUMED_AT) {
                        Long value = RESUMED_AT.get(act);
                        resumedAt = value == null ? 0L : value;
                    }
                    boolean justResumed = SystemClock.uptimeMillis() - resumedAt < 900L;
                    // 焦点只看真实状态：以前 focusFlag 为空就当"有焦点"，导致页面被盖住
                    // 之后仍然算前台，Dock 死活不收。现在没焦点就是没焦点。
                    boolean focused = act.hasWindowFocus()
                            || Boolean.TRUE.equals(focusFlag);
                    boolean front = !covered && (focused || justResumed);
                    // 窗口本身不可见（被别的页面完全盖住）时也不用画。
                    boolean windowVisible = iso.view().getWindowVisibility() == View.VISIBLE;
                    boolean wantShow = active && front && windowVisible
                            && currentBar != null
                            && currentBar.isAttachedToWindow()
                            && currentBar.getVisibility() == View.VISIBLE
                            && SelfMask.effectiveAlpha(currentBar) > 0.01f
                            && currentBar.getWidth() > 0;

                    // 显隐跟实际画面对齐，而不是跟一个一次性的布尔位：
                    // 从聊天页回来时玻璃层可能已经被上一步设成 GONE，只看布尔位就不会再显示。
                    boolean visible = iso.view().getVisibility() == View.VISIBLE
                            && iso.view().getAlpha() > 0.5f;
                    if (wantShow) {
                        if (!visible) {
                            shown[0] = true;
                            animate(iso.view(), true);
                        }
                        // 每一轮都确认一次原生底栏是藏着的：微信自己会时不时把 alpha 还原。
                        if (!SelfMask.masked(currentBar)) SelfMask.mask(currentBar);
                    } else if (visible || iso.view().getVisibility() == View.VISIBLE) {
                        shown[0] = false;
                        hideNow(act);
                        if (top != null && top != act) {
                            // 让日志说清楚是谁把 Dock 顶掉的，下次看日志就知道判断链对不对。
                            WechatGlass.warnOnce("dock-hide-" + top.getClass().getName(),
                                    "底栏:前台是 " + top.getClass().getSimpleName() + "，已收起");
                        } else if (covered) {
                            WechatGlass.warnOnce("dock-hide-covered",
                                    "底栏:「＋」面板打开中，已收起");
                        }
                    }
                    backdrop.setActive(wantShow);

                    if (band[0] != null) {
                        backdrop.setRegion(inWindow(iso.view(), band[0]));
                        backdrop.pump();
                    }
                    MAIN.postDelayed(this, POLL_MS);
                } catch (Throwable t) {
                    WechatGlass.warnOnce("poll-fail", "Dock 轮询出错: " + t);
                    MAIN.postDelayed(this, POLL_MS);
                }
            }
        };

        synchronized (POLLERS) { POLLERS.put(act, poll); }
        MAIN.post(poll);

        // 看门狗：玻璃两秒多还没画出来，就把微信原生底栏原样还回去，绝不留下"两条栏都没了"。
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!live[0]) return;
                if (iso.hasDrawn()) return;
                live[0] = false;
                MAIN.removeCallbacks(poll);
                teardown(act, "玻璃两秒内没画出来");
                WechatGlass.warn("底栏:玻璃没画出来，已恢复微信原生底栏");
            }
        }, WATCHDOG_MS);

        return true;
    }

    // ------------------------------------------------------------ 几何

    /** 原生底栏在窗口里的矩形；量不到就用窗口底部兜一个。 */
    private static Rect measureBand(Activity act, View bar, View overlay, int count) {
        Rect rect = null;
        if (bar != null && bar.isAttachedToWindow() && bar.getWidth() > 0) {
            rect = inWindow(bar, new Rect(0, 0, bar.getWidth(), bar.getHeight()));
        }
        if (rect != null && rect.width() > 0 && rect.height() > 0) return rect;

        int[] loc = new int[2];
        overlay.getLocationInWindow(loc);
        int w = overlay.getWidth() > 0 ? overlay.getWidth()
                : act.getResources().getDisplayMetrics().widthPixels;
        int h = overlay.getHeight() > 0 ? overlay.getHeight()
                : act.getResources().getDisplayMetrics().heightPixels;
        return new Rect(loc[0], loc[1] + h - dp(act, 56), loc[0] + w, loc[1] + h);
    }

    private static Rect inWindow(View v, Rect local) {
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        Rect out = new Rect(local);
        out.offset(loc[0], loc[1]);
        return out;
    }

    /** Dock 画几格：认出几个算几个，认不出来按微信默认的 4 格。 */
    private static int tabCount(List<View> items) {
        return items == null || items.size() < 3 ? WxTabBar.defaultCount() : items.size();
    }

    private static int dp(Activity act, float value) {
        return (int) (value * act.getResources().getDisplayMetrics().density);
    }

    /**
     * 位置算法和 dydock 完全一致：
     * 胶囊按设置的高度 / 单格宽度算，整组按屏幕宽度居中，底部离手势条留间距。
     * 微信没有「＋」，所以 plusRect 给空矩形，渲染层自然不画那个圆球。
     */
    private static void applyGeometry(Activity act, DockLayer iso, Backdrop backdrop,
                                      Rect bandRect, int count) {
        if (iso == null || bandRect == null) return;
        try {
            // 透明度 / 隐藏玻璃这两项已经从设置里去掉：材质固定成默认那一档。
            iso.setAppearance(100, false, DockPrefs.themeMode(act));

            int[] overlayLoc = new int[2];
            iso.view().getLocationInWindow(overlayLoc);

            float density = act.getResources().getDisplayMetrics().density;
            int tabs = Math.max(1, count);
            int bandTop = bandRect.top - overlayLoc[1];
            int edge = (int) (DockPrefs.sideMarginDp(act) * density);
            int pill = (int) (DockPrefs.heightDp(act) * density);
            int tabWidth = (int) (DockPrefs.tabWidthDp(act) * density);
            int innerPad = (int) (8f * density);

            int tabsWidth = tabWidth * tabs + innerPad;
            int baseWidth = iso.view().getWidth() > 0 ? iso.view().getWidth() : bandRect.width();
            if (baseWidth <= 0) baseWidth = bandRect.width();
            int available = baseWidth - edge * 2;
            if (tabsWidth > available) {
                tabsWidth = Math.max((int) (48f * density) * tabs,
                        Math.min(tabsWidth, available));
            }
            if (tabsWidth <= 0) return;

            int left = Math.max(edge, (baseWidth - tabsWidth) / 2);
            int top = bandTop + bandRect.height()
                    - (int) (DockPrefs.bottomGapDp(act) * density) - pill;

            // 不许压进系统手势条：全屏页面上量到的 band 会贴到屏幕最底。
            int overlayHeight = iso.view().getHeight();
            int navBottom = navigationBarInsetBottom(act);
            if (overlayHeight > 0 && navBottom > 0) {
                int limit = overlayHeight - navBottom - (int) (2f * density) - pill;
                if (top > limit) top = limit;
            }

            Rect tabsRect = new Rect(left, top, left + tabsWidth, top + pill);
            Rect plusRect = new Rect(); // 微信没有「＋」
            int pad = (int) (40f * density);
            Rect region = new Rect(tabsRect.left - pad, top - pad,
                    tabsRect.right + pad, top + pill + pad);
            Rect regionInWindow = new Rect(region);
            regionInWindow.offset(overlayLoc[0], overlayLoc[1]);

            if (backdrop != null) backdrop.setRegion(regionInWindow);
            iso.setGeometry(region, tabsRect, plusRect);
        } catch (Throwable t) {
            WechatGlass.warnOnce("iso-geometry", "玻璃定位失败: " + t);
        }
    }

    private static int navigationBarInsetBottom(Activity act) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsets insets = act.getWindow()
                        .getDecorView().getRootWindowInsets();
                if (insets != null) {
                    android.graphics.Insets bars = insets.getInsets(
                            android.view.WindowInsets.Type.navigationBars());
                    if (bars != null) return Math.max(0, bars.bottom);
                }
            }
            int id = act.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (id > 0) return act.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {
        }
        return 0;
    }

    // ------------------------------------------------------------ 图标 / 选中态

    private static void refreshTabs(Activity act, DockLayer iso, List<View> items) {
        if (iso == null) return;
        try {
            boolean showLabels = DockPrefs.showLabels(act);
            int count = tabCount(items);
            if (items == null || items.size() < 3) {
                // 格子还没认出来：用微信那四个默认标签兜着，别让 Dock 空着。
                String[] labels = new String[count];
                for (int i = 0; i < count; i++) {
                    labels[i] = showLabels ? WxTabBar.defaultLabel(i) : "";
                }
                iso.setTabs(labels, new Bitmap[count], 0);
                return;
            }
            String[] labels = new String[count];
            Bitmap[] icons = new Bitmap[count];
            for (int i = 0; i < count; i++) {
                View item = items.get(i);
                String label = WxTabBar.label(item);
                labels[i] = showLabels ? (label.length() == 0
                        ? WxTabBar.defaultLabel(i) : label) : "";
                icons[i] = WxTabBar.drawableToBitmap(WxTabBar.findIcon(item, 0, 6));
            }
            int selected = WxTabBar.selectedIndex(items);
            iso.setTabs(labels, icons, selected < 0 ? 0 : selected);
        } catch (Throwable t) {
            WechatGlass.warnOnce("iso-tabs", "同步导航状态失败: " + t);
        }
    }

    // ------------------------------------------------------------ 背景与显隐

    private static void pushBackdrop(DockLayer iso, Backdrop backdrop) {
        Bitmap sampled = backdrop.bitmap();
        if (sampled == null || sampled.isRecycled() || backdrop.looksBlack()) {
            iso.clearBackdrop();
            return;
        }
        // 所有权交给玻璃层：它每一帧都会自己复制一份，宿主这边继续用同一块缓冲。
        iso.setBackdrop(sampled);
    }

    private static void animate(final View view, final boolean show) {
        if (view == null) return;
        try {
            view.animate().cancel();
            if (show) {
                view.setVisibility(View.VISIBLE);
                view.setAlpha(0f);
                view.setTranslationY(6f * view.getResources().getDisplayMetrics().density);
                view.animate().alpha(1f).translationY(0f).setDuration(IN_MS).start();
            } else {
                view.animate().alpha(0f)
                        .translationY(6f * view.getResources().getDisplayMetrics().density)
                        .setDuration(OUT_MS)
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                // alpha=0 的 View 照样吃触摸，收起来就彻底让开，
                                // 免得在某些机型上挡住微信自己的误触区域。
                                if (view.getAlpha() < 0.05f) view.setVisibility(View.GONE);
                            }
                        }).start();
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 设置同步 / 卸载

    static void applySettingsToAll() {
        List<Activity> acts;
        synchronized (OVERLAYS) {
            acts = new ArrayList<Activity>(OVERLAYS.keySet());
        }
        for (final Activity act : acts) {
            try {
                final DockLayer iso = OVERLAYS.get(act);
                if (iso == null) continue;
                if (!DockPrefs.enabled(act)) {
                    teardown(act, "设置里关掉了");
                    continue;
                }
                final Backdrop backdrop = BACKDROPS.get(act);
                List<View> items = ITEMS.get(act);
                int count = tabCount(items);
                final Rect band = measureBand(act, BARS.get(act), iso.view(), count);
                synchronized (BANDS) { BANDS.put(act, band); }
                applyGeometry(act, iso, backdrop, band, count);
                refreshTabs(act, iso, items);
            } catch (Throwable t) {
                WechatGlass.warnOnce("settings-apply", "应用设置失败: " + t);
            }
        }
        // 关掉再打开：已经卸载过的页面要重新接管。
        requestReinstall();
    }

    /** 设置里重新打开开关：把见过的页面重新接管一遍。 */
    static void requestReinstall() {
        List<Activity> acts;
        synchronized (KNOWN) {
            acts = new ArrayList<Activity>(KNOWN.keySet());
        }
        for (Activity act : acts) {
            if (!act.isFinishing() && !act.isDestroyed() && !OVERLAYS.containsKey(act)) {
                attempt(act, 0);
            }
        }
    }

    static void teardown(Activity act, String reason) {
        if (act == null) return;
        DockLayer iso;
        Backdrop backdrop;
        ViewGroup bar;
        Runnable poll;
        synchronized (OVERLAYS) { iso = OVERLAYS.remove(act); }
        synchronized (BACKDROPS) { backdrop = BACKDROPS.remove(act); }
        synchronized (BARS) { bar = BARS.remove(act); }
        synchronized (ITEMS) { ITEMS.remove(act); }
        synchronized (POLLERS) { poll = POLLERS.remove(act); }
        synchronized (LIVE) { LIVE.remove(act); }
        synchronized (BANDS) { BANDS.remove(act); }

        if (poll != null) MAIN.removeCallbacks(poll);
        if (iso != null) {
            try {
                ViewParent parent = iso.view().getParent();
                if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(iso.view());
            } catch (Throwable ignored) {
            }
            try {
                iso.dispose();
            } catch (Throwable ignored) {
            }
        }
        if (backdrop != null) {
            try {
                backdrop.release();
            } catch (Throwable ignored) {
            }
        }
        if (bar != null) {
            SelfMask.restore(bar);
        }
        if (iso != null || bar != null) {
            WechatGlass.log("底栏:已卸载（" + reason + "），微信原生底栏恢复");
        }
    }
}
