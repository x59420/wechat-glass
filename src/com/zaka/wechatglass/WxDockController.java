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

    /** 彻底停用 Dock：不再安装、不再藏原生底栏，保证聊天页永远不被盖住。 */
    static final boolean DISABLED = false;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 轮询间隔：同步图标 / 选中态 / 可见性。 */
    private static final long POLL_MS = 200L;
    /** 找底栏的重试：微信首屏要等布局，最多重试这么多次。 */
    private static final int MAX_TRIES = 60;
    private static final long RETRY_MS = 350L;
    /** 玻璃多久没画出来就认为失败，把原生底栏还回去。 */
    private static final long WATCHDOG_MS = 9000L;
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
    private static final WeakHashMap<Activity, Boolean> FORCE_FALLBACK =
            new WeakHashMap<Activity, Boolean>();
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
    /** 最后一个 resume 的页面不是首页：在它退出前，轮询不允许把 Dock 再显示出来。 */
    private static volatile boolean NON_HOST;
    /** 已经进入聊天/二级页：标题滚出屏幕后仍要保持收起，直到重新看到主 tab 标题。 */
    private static volatile boolean SUB_PAGE;
    /** 非首页期间持续把微信原生底栏压成透明，防止微信重建节点后重新露出来。 */
    private static Runnable SUPPRESS_GUARD;
    /** 首页 watchdog：微信从资料页/二级页回来时不一定走 Activity.onResume，
     *  这里定期检查首页是否该有 Dock，没有就补装。 */
    private static Runnable HOST_WATCH;

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
            // 聊天页 / 添加朋友页就算窗口里刚好还留着底栏节点，也绝不能当首页。
            if (isBlockedPage(act)) return false;
            // 类名对不上也不能直接放弃：这条窗口里真有微信那条底栏，就是首页。
            return WxTabBar.hasTabBar(act);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 明确不该显示 Dock 的页面：聊天、聊天室、添加朋友 / 更多。 */
    static boolean isBlockedPage(Activity act) {
        if (act == null) return false;
        try {
            String name = act.getClass().getName().toLowerCase(java.util.Locale.ROOT);
            return name.contains("chatting")
                    || name.contains("chatroom")
                    || name.contains("findmorefriends")
                    || name.contains("addmorefriends")
                    || name.contains("moretabui");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 立刻收起所有首页上的 Dock，不等轮询。 */
    static void suppress(boolean value) {
        NON_HOST = value;
        if (value) {
            hideAll();
            startSuppressGuard();
        } else {
            stopSuppressGuard();
        }
    }

    private static void startSuppressGuard() {
        stopSuppressGuard();
        Runnable guard = new Runnable() {
            @Override
            public void run() {
                if (!NON_HOST) return;
                java.util.List<Activity> hosts;
                synchronized (KNOWN) { hosts = new ArrayList<Activity>(KNOWN.keySet()); }
                for (Activity a : hosts) {
                    try {
                        if (a == null || a.isFinishing() || a.isDestroyed()) continue;
                        View decor = safeDecor(a);
                        if (decor == null) continue;
                        ViewGroup bar = WxTabBar.find(decor, a);
                        if (bar != null) SelfMask.mask(bar);
                    } catch (Throwable ignored) {
                    }
                }
                MAIN.postDelayed(this, 200L);
            }
        };
        SUPPRESS_GUARD = guard;
        MAIN.postDelayed(guard, 60L);
    }

    private static void stopSuppressGuard() {
        Runnable old = SUPPRESS_GUARD;
        SUPPRESS_GUARD = null;
        if (old != null) MAIN.removeCallbacks(old);
    }

    private static void startHostWatch() {
        if (HOST_WATCH != null) return;
        Runnable watch = new Runnable() {
            @Override
            public void run() {
                try {
                    java.util.List<Activity> hosts;
                    synchronized (KNOWN) { hosts = new ArrayList<Activity>(KNOWN.keySet()); }
                    for (Activity a : hosts) {
                        if (a == null || a.isFinishing() || a.isDestroyed()) continue;
                        if (!isHost(a)) continue;
                        synchronized (OVERLAYS) {
                            if (OVERLAYS.containsKey(a)) continue;
                        }
                        View decor = safeDecor(a);
                        if (decor == null) continue;
                        if (hasMainTitle(decor) && !hasChatInput(decor)
                                && !hasExplicitNonMainTitle(decor)) {
                            NON_HOST = false;
                            SUB_PAGE = false;
                            attempt(a, 0);
                        }
                    }
                } catch (Throwable ignored) {
                }
                MAIN.postDelayed(this, 600L);
            }
        };
        HOST_WATCH = watch;
        MAIN.postDelayed(watch, 600L);
    }

    static void hideAll() {
        java.util.List<Activity> acts;
        synchronized (OVERLAYS) {
            acts = new java.util.ArrayList<Activity>(OVERLAYS.keySet());
        }
        // 不是隐藏，而是直接卸载玻璃层：聊天/添加朋友页上不可能再被轮询拉回来。
        // teardown 会把微信原生底栏 alpha 还原；如果聊天页只是盖在首页上，
        // 还原后的原生底栏会从底下露出来，看起来就像“Dock 没收起”。
        // 所以卸载后再把原生底栏压回 alpha 0，等真正回首页再交给安装流程。
        for (Activity a : acts) {
            ViewGroup bar;
            synchronized (BARS) { bar = BARS.get(a); }
            teardown(a, "非首页");
            if (bar != null) SelfMask.mask(bar);
        }
    }

    static void onHostResume(final Activity act) {
        if (DISABLED) return;
        if (!isHost(act)) return;
        startHostWatch();
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
        if (!isHost(act)) {
            // 任何非首页 Activity 压上来，先把首页的 Dock 收掉。
            NON_HOST = true;
            hideAll();
            return;
        }
        // 首页 resume 一定要走安装流程；install 内部会根据主标题 / 聊天输入框
        // 决定是清掉旧状态重新挂 Dock，还是继续跳过。
        onHostResume(act);
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
            // 只有首页真的拿到焦点，才解除「非首页」抑制；
            // 聊天页是半透明/叠层时，首页会先 resume 但拿不到焦点。
            NON_HOST = false;
            stopSuppressGuard();
            synchronized (RESUMED_AT) { RESUMED_AT.put(act, SystemClock.uptimeMillis()); }
            onHostResume(act);
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
        hideAll();
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
        if (DISABLED) return true;
        WechatGlass.setVerbose(DockPrefs.logEnabled(act));
        if (!DockPrefs.enabled(act)) {
            // 用户在设置里关掉了：把之前压掉的原生底栏恢复。
            for (Activity a : new ArrayList<Activity>(BARS.keySet())) {
                ViewGroup b = BARS.get(a);
                if (b != null) SelfMask.restore(b);
            }
            return true;
        }
        final View decor = safeDecor(act);
        // 通讯录页标题是主 tab，但会带搜索框 / 软件渲染，先把二级页状态清掉，
        // 否则会误判成聊天页，Dock 和兜底层都装不上。
        if (isContactsPage(decor)) {
            SUB_PAGE = false;
            NON_HOST = false;
        }
        boolean knownSub = isKnownSubPage(decor) || isAddFriendPage(decor);
        if (knownSub) {
            SUB_PAGE = true;
            try {
                ViewGroup b = WxTabBar.find((ViewGroup) decor, act);
                if (b != null) SelfMask.mask(b);
            } catch (Throwable ignored) {
            }
            return true;
        }
        // 明确不是二级页后，四主 tab 才允许清掉旧 SUB_PAGE。
        if (isOnMainTab(act, decor) && !hasChatInput(decor)) {
            SUB_PAGE = false;
            NON_HOST = false;
        }
        final View content = act.findViewById(android.R.id.content);
        if (!(decor instanceof ViewGroup) || content == null) return false;
        if (content.getWidth() < 50 || content.getHeight() < 50) return false;
        // 从聊天点头像进资料页，再一路返回首页时，NON_HOST 可能还留着。
        // 只要当前确实是主 tab（有主标题、没有聊天输入框/二级标题），就清掉。
        if (NON_HOST && hasMainTitle(decor) && !hasChatInput(decor)
                && !hasExplicitNonMainTitle(decor)) {
            NON_HOST = false;
            SUB_PAGE = false;
        }
        if (NON_HOST) return true;

        // 已经在聊天 / 添加朋友 / 二级页：不要安装 Dock。
        // 但回到主 tab 时，标题已经是“微信/通讯录/发现/我”，必须清掉旧状态，
        // 否则 SUB_PAGE 会永久卡住，首页也再也不出 Dock。
        if (SUB_PAGE) {
            if (hasMainTitle(decor) && !hasChatInput(decor)
                    && !hasNonMainPageTitle(decor)) {
                SUB_PAGE = false;
            } else {
                return true;
            }
        }
        if ((!hasMainTitle(decor) && hasChatInput(decor))
                || hasExplicitNonMainTitle(decor)
                || (hasNonMainPageTitle(decor) && !hasMainTitle(decor))) {
            SUB_PAGE = true;
            return true;
        }

        ViewGroup bar = WxTabBar.find(decor, act);
        if (bar == null) return false;

        // 先藏原生底栏，再慢慢建玻璃。否则冷启动时原生底栏会一直露着，
        // 直到用户滑一下触发玻璃绘制才被藏掉。
        SelfMask.mask(bar);

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
        // 兜底只用于通讯录这种软件渲染页；回到微信/发现必须恢复 Compose，
        // 否则整个首页都会变成一块实色玻璃。
        if (!isContactsPage(decor)) {
            synchronized (FORCE_FALLBACK) { FORCE_FALLBACK.remove(act); }
        }
        DockLayer created = null;
        if (!Boolean.TRUE.equals(FORCE_FALLBACK.get(act))) {
            created = IsoDock.create(act, glassLog, onTab, onPlus, onLongPress, hostPainter);
        }
        if (created == null) {
            WechatGlass.warn("底栏:Compose 渲染器没起来，改用兜底玻璃层");
            created = FallbackDock.create(act, glassLog, onTab);
        }
        if (created == null) {
            WechatGlass.warn("底栏:两套渲染器都没起来，保留微信原生底栏");
            SelfMask.restore(bar);
            return true;
        }
        final DockLayer iso = created;

        try {
            ((ViewGroup) decor).addView(iso.view(), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Throwable t) {
            WechatGlass.error("底栏:玻璃层挂不上窗口", t);
            iso.dispose();
            SelfMask.restore(bar);
            return true;
        }

        WechatGlass.log("Dock 已挂上");

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
                    if (Boolean.TRUE.equals(FORCE_FALLBACK.get(act))
                            && !isContactsPage(decor)) {
                        // 离开通讯录：撤掉 Canvas 兜底，恢复 Compose 玻璃。
                        live[0] = false;
                        MAIN.removeCallbacks(this);
                        synchronized (FORCE_FALLBACK) { FORCE_FALLBACK.remove(act); }
                        teardown(act, "离开通讯录，恢复 Compose");
                        MAIN.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                attempt(act, 0);
                            }
                        }, 250L);
                        return;
                    }
                    if (iso.isBroken()) {
                        live[0] = false;
                        MAIN.removeCallbacks(this);
                        // 通讯录等页面会强制软件渲染，Compose 的 RuntimeShader 直接不可用。
                        // 这种情况下不要退回原生底栏，换 Canvas 兜底继续显示玻璃。
                        synchronized (FORCE_FALLBACK) { FORCE_FALLBACK.put(act, Boolean.TRUE); }
                        teardown(act, "玻璃渲染出错，切换兜底");
                        WechatGlass.warn("底栏:Compose 渲染失败，切换 Canvas 兜底");
                        MAIN.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                attempt(act, 0);
                            }
                        }, 300L);
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
                    if (barReady) {
                        // 每次都用原生底栏重新量；只有在矩形真的变了才重设几何。
                        // 这样冷启动先量错、后续布局完成时能自动纠正，不会把 Dock 钉在错位置。
                        Rect fresh = measureBand(act, currentBar, iso.view(), tabCount(targets[0]));
                        if (fresh != null && (band[0] == null || windowChanged || !band[0].equals(fresh))) {
                            band[0] = fresh;
                            bandFromBar[0] = true;
                            synchronized (BANDS) { BANDS.put(act, band[0]); }
                            applyGeometry(act, iso, backdrop, band[0], tabCount(targets[0]));
                        }
                    } else if (band[0] == null || windowChanged) {
                        band[0] = measureBand(act, currentBar, iso.view(), tabCount(targets[0]));
                        bandFromBar[0] = false;
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
                    boolean mainTitleNow = hasMainTitle(decor);
                    boolean chatOpen = !isContactsPage(decor) && !mainTitleNow && hasChatInput(decor);
                    if (chatOpen) {
                        WxChatTime.attachForChat(act);
                    } else {
                        WxChatTime.detach(act);
                    }
                    boolean nonMain = hasNonMainPageTitle(decor);
                    boolean mainTitle = hasMainTitle(decor);
                    boolean explicitSub = hasExplicitNonMainTitle(decor);
                    if (chatOpen || explicitSub) {
                        SUB_PAGE = true;
                    } else if (mainTitle) {
                        // 主 tab 标题优先：列表里“微信运动”这种文字不能被当成二级页标题。
                        SUB_PAGE = false;
                    } else if (nonMain) {
                        SUB_PAGE = true;
                    }
                    boolean knownSubNow = isKnownSubPage(decor) || isAddFriendPage(decor);
                    boolean mainTab = isOnMainTab(act, decor);
                    if (knownSubNow) {
                        chatOpen = false;
                        explicitSub = true;
                        SUB_PAGE = true;
                    } else if (isContactsPage(decor) || (mainTab && !chatOpen)) {
                        chatOpen = false;
                        SUB_PAGE = false;
                        NON_HOST = false;
                    }
                    boolean blocked = chatOpen || SUB_PAGE || (top != null
                            && (isBlockedPage(top) || WxChatTime.isChatPage(top)));
                    boolean active = !otherOnTop && !blocked && !NON_HOST;
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
                            // 主动接管后原生底栏本来就被压成 alpha=0；
                            // 不能用“原生 alpha”来判断要不要显示 Dock，
                            // 否则切到通讯录时新底栏原始 alpha=0，Dock 会直接消失。
                            && (SelfMask.masked(currentBar)
                            || SelfMask.effectiveAlpha(currentBar) > 0.01f)
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
                        if (blocked) {
                            WechatGlass.warnOnce("dock-hide-blocked-" + top.getClass().getName(),
                                    "底栏:聊天/添加朋友页在前台（"
                                            + top.getClass().getSimpleName() + "），已收起");
                        } else if (top != null && top != act) {
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
                // 这个看门狗只负责自己安装的那一层。旧安装留下的看门狗
                // 不能把后来新挂上的 Dock 拆掉，否则会原生/玻璃来回闪。
                DockLayer current;
                synchronized (OVERLAYS) { current = OVERLAYS.get(act); }
                if (current != iso) return;
                if (iso.hasDrawn()) return;
                live[0] = false;
                MAIN.removeCallbacks(poll);
                teardown(act, "玻璃超时没画出来");
                WechatGlass.warn("底栏:玻璃没画出来，已恢复微信原生底栏");
            }
        }, WATCHDOG_MS);

        return true;
    }

    /** 去掉标题末尾的未读角标，例如 微信(2) / 微信(99) / 微信 3。 */
    private static String cleanTitle(String text) {
        if (text == null) return "";
        String s = text.trim();
        s = s.replaceAll("\\s*\\(\\d+\\)\\s*$", "");
        s = s.replaceAll("\\s+\\d+$", "");
        return s.trim();
    }

    /** 明确知道是二级页的标题；这些优先于主标题判断。 */
    private static boolean hasExplicitNonMainTitle(View root) {
        if (!(root instanceof ViewGroup)) return false;
        java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<View>();
        java.util.IdentityHashMap<View, Boolean> seen =
                new java.util.IdentityHashMap<View, Boolean>();
        queue.add(root);
        int budget = 8000;
        int[] loc = new int[2];
        int screenH = root.getResources().getDisplayMetrics().heightPixels;
        while (!queue.isEmpty() && budget-- > 0) {
            View v = queue.poll();
            if (v == null || seen.put(v, Boolean.TRUE) != null) continue;
            if (v instanceof android.widget.TextView && v.isShown()) {
                v.getLocationInWindow(loc);
                if (loc[1] < screenH * 0.32f) {
                    CharSequence cs = ((android.widget.TextView) v).getText();
                    if (cs != null) {
                        String t = cs.toString().trim();
                        if (t.equals("添加朋友") || t.equals("发起群聊")
                                || t.equals("选择联系人") || t.equals("新的朋友")
                                || t.equals("群聊") || t.equals("微信支付")
                                || t.equals("微信团队") || t.equals("微信运动")) {
                            return true;
                        }
                    }
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    if (c != null && !c.getClass().getName().startsWith(WechatGlass.PKG)) {
                        queue.add(c);
                    }
                }
            }
        }
        return false;
    }

    /** 顶部是否还是「微信 / 通讯录 / 发现 / 我」主 tab 标题。 */
    private static boolean hasMainTitle(View root) {
        if (!(root instanceof ViewGroup)) return false;
        java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<View>();
        java.util.IdentityHashMap<View, Boolean> seen =
                new java.util.IdentityHashMap<View, Boolean>();
        queue.add(root);
        int budget = 6000;
        int[] loc = new int[2];
        int screenH = root.getResources().getDisplayMetrics().heightPixels;
        while (!queue.isEmpty() && budget-- > 0) {
            View v = queue.poll();
            if (v == null || seen.put(v, Boolean.TRUE) != null) continue;
            if (v instanceof android.widget.TextView && v.isShown()) {
                v.getLocationInWindow(loc);
                if (loc[1] < screenH * 0.18f) {
                    CharSequence cs = ((android.widget.TextView) v).getText();
                    if (cs != null) {
                        String t = cs.toString().trim();
                        String base = cleanTitle(t);
                        if (base.equals("微信") || base.equals("通讯录")
                                || base.equals("发现") || base.equals("我")
                                || base.equals("WeChat") || base.equals("Chats")
                                || base.equals("Contacts") || base.equals("Discover")
                                || base.equals("Me")) {
                            return true;
                        }
                    }
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    if (c != null && !c.getClass().getName().startsWith(WechatGlass.PKG)) {
                        queue.add(c);
                    }
                }
            }
        }
        return false;
    }

    /** 添加朋友 / 发起群聊这类二级页没有底部输入框，但顶部标题不是主 tab。 */
    private static boolean hasNonMainPageTitle(View root) {
        try {
            if (!(root instanceof ViewGroup)) return false;
            java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<View>();
            java.util.IdentityHashMap<View, Boolean> seen =
                    new java.util.IdentityHashMap<View, Boolean>();
            queue.add(root);
            int budget = 12000;
            int[] loc = new int[2];
            int screenH = root.getResources().getDisplayMetrics().heightPixels;
            while (!queue.isEmpty() && budget-- > 0) {
                View v = queue.poll();
                if (v == null || seen.put(v, Boolean.TRUE) != null) continue;
                if (v instanceof android.widget.TextView && v.isShown()) {
                    v.getLocationInWindow(loc);
                    if (loc[1] < screenH * 0.22f) {
                        CharSequence cs = ((android.widget.TextView) v).getText();
                        if (cs != null) {
                            String t = cs.toString().trim();
                            String base = cleanTitle(t);
                            if (base.equals("微信") || base.equals("通讯录")
                                    || base.equals("发现") || base.equals("我")
                                    || base.equals("WeChat") || base.equals("Chats")
                                    || base.equals("Contacts") || base.equals("Discover")
                                    || base.equals("Me")) {
                                continue;
                            }
                            if (t.equals("添加朋友") || t.equals("发起群聊")
                                    || t.equals("选择联系人") || t.equals("新的朋友")
                                    || t.equals("群聊") || t.equals("微信支付")
                                    || t.equals("微信团队") || t.equals("微信运动")) return true;
                            // 顶部居中的二级页标题：微信支付 / 服务号 / 公众号文章…
                            int screenW = root.getResources().getDisplayMetrics().widthPixels;
                            int status = root.getResources().getIdentifier(
                                    "status_bar_height", "dimen", "android");
                            int statusH = status > 0
                                    ? root.getResources().getDimensionPixelSize(status)
                                    : (int) (26 * root.getResources().getDisplayMetrics().density);
                            if (loc[0] > screenW * 0.22f && loc[0] < screenW * 0.78f
                                    && loc[1] < statusH + 80 * root.getResources()
                                    .getDisplayMetrics().density
                                    && t.length() > 0 && t.length() <= 16) {
                                return true;
                            }
                        }
                    }
                }
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        View c = g.getChildAt(i);
                        if (c != null && !c.getClass().getName().startsWith(WechatGlass.PKG)) {
                            queue.add(c);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 聊天页底部一定有输入框；首页没有。用它判断当前是不是聊天 Fragment。 */
    private static boolean hasChatInput(View root) {
        try {
            if (!(root instanceof ViewGroup)) return false;
            java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<View>();
            java.util.IdentityHashMap<View, Boolean> seen =
                    new java.util.IdentityHashMap<View, Boolean>();
            queue.add(root);
            int budget = 12000;
            int[] loc = new int[2];
            int screenH = root.getResources().getDisplayMetrics().heightPixels;
            while (!queue.isEmpty() && budget-- > 0) {
                View v = queue.poll();
                if (v == null || seen.put(v, Boolean.TRUE) != null) continue;
                if (v instanceof android.widget.EditText && v.isShown()
                        && v.getWidth() > 0 && v.getHeight() > 0) {
                    v.getLocationInWindow(loc);
                    if (loc[1] > screenH * 0.55f) {
                        CharSequence hint = ((android.widget.EditText) v).getHint();
                        String h = hint == null ? "" : hint.toString();
                        // 首页/搜索页也有 EditText，不能只凭“底部有输入框”就认成聊天。
                        if (h.contains("按住") || h.contains("说话")
                                || h.contains("转文字")) {
                            return true;
                        }
                    }
                }
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        View c = g.getChildAt(i);
                        if (c != null && !c.getClass().getName().startsWith(WechatGlass.PKG)) {
                            queue.add(c);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
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
    /** 顶部标题明确是二级页：微信运动/团队/支付/添加朋友等。 */
    private static boolean isKnownSubPage(View decor) {
        if (!(decor instanceof ViewGroup)) return false;
        try {
            java.util.ArrayDeque<View> q = new java.util.ArrayDeque<View>();
            java.util.IdentityHashMap<View, Boolean> seen = new java.util.IdentityHashMap<View, Boolean>();
            q.add(decor);
            int[] loc = new int[2];
            int status = 0;
            if (decor.getContext() instanceof Activity) {
                Activity a = (Activity) decor.getContext();
                if (a.getWindow() != null && a.getWindow().getDecorView().getRootWindowInsets() != null) {
                    status = a.getWindow().getDecorView().getRootWindowInsets()
                            .getInsets(android.view.WindowInsets.Type.statusBars()).top;
                }
            }
            int limit = status + (int) (64 * decor.getResources().getDisplayMetrics().density);
            int budget = 8000;
            while (!q.isEmpty() && budget-- > 0) {
                View v = q.poll();
                if (v == null || seen.put(v, Boolean.TRUE) != null) continue;
                if (v instanceof android.widget.TextView && v.isShown()) {
                    v.getLocationInWindow(loc);
                    if (loc[1] < limit) {
                        String t = ((android.widget.TextView) v).getText() == null ? ""
                                : ((android.widget.TextView) v).getText().toString().trim();
                        if (t.equals("微信运动") || t.equals("微信团队")
                                || t.equals("微信支付") || t.equals("添加朋友")
                                || t.equals("发起群聊") || t.equals("选择联系人")) {
                            return true;
                        }
                    }
                }
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        View c = g.getChildAt(i);
                        if (c != null && !c.getClass().getName().startsWith(WechatGlass.PKG)) q.add(c);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 添加朋友页是 LauncherUI 里的 Fragment，底栏选中项仍是微信，不能只看 tab。 */
    private static boolean isAddFriendPage(View decor) {
        if (!(decor instanceof ViewGroup)) return false;
        try {
            java.util.ArrayDeque<View> q = new java.util.ArrayDeque<View>();
            java.util.IdentityHashMap<View, Boolean> seen = new java.util.IdentityHashMap<View, Boolean>();
            q.add(decor);
            java.util.HashSet<String> hits = new java.util.HashSet<String>();
            int budget = 12000;
            int[] loc = new int[2];
            int screenH = decor.getResources().getDisplayMetrics().heightPixels;
            while (!q.isEmpty() && budget-- > 0) {
                View v = q.poll();
                if (v == null || seen.put(v, Boolean.TRUE) != null) continue;
                if (v instanceof android.widget.TextView && v.isShown()) {
                    v.getLocationInWindow(loc);
                    if (loc[1] < screenH * 0.65f) {
                        CharSequence cs = ((android.widget.TextView) v).getText();
                        if (cs != null) {
                            String t = cs.toString().trim();
                            if (t.equals("添加朋友") || t.equals("扫一扫")
                                    || t.equals("手机联系人") || t.equals("雷达")
                                    || t.equals("面对面建群")) {
                                hits.add(t);
                            }
                        }
                    }
                }
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        View c = g.getChildAt(i);
                        if (c != null && !c.getClass().getName().startsWith(WechatGlass.PKG)) q.add(c);
                    }
                }
            }
            return hits.size() >= 2;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 通讯录页会强制软件渲染，Compose 的 RuntimeShader 不能用。 */
    private static boolean isContactsPage(View decor) {
        if (!(decor instanceof ViewGroup)) return false;
        java.util.ArrayDeque<View> q = new java.util.ArrayDeque<View>();
        java.util.IdentityHashMap<View, Boolean> seen = new java.util.IdentityHashMap<View, Boolean>();
        q.add(decor);
        int[] loc = new int[2];
        int budget = 8000;
        while (!q.isEmpty() && budget-- > 0) {
            View v = q.poll();
            if (v == null || seen.put(v, Boolean.TRUE) != null) continue;
            if (v instanceof android.widget.TextView && v.isShown()) {
                v.getLocationInWindow(loc);
                if (loc[1] < decor.getHeight() * 0.18f) {
                    String t = ((android.widget.TextView) v).getText() == null ? ""
                            : ((android.widget.TextView) v).getText().toString().trim();
                    String base = t.replaceAll("\\s*\\(\\d+\\)\\s*$", "").trim();
                    if (base.equals("通讯录") || base.equals("Contacts")) return true;
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    if (c != null && !c.getClass().getName().startsWith(WechatGlass.PKG)) q.add(c);
                }
            }
        }
        return false;
    }

    /** 当前是不是四个主 tab（微信/通讯录/发现/我）之一；底栏选中项最可靠。 */
    private static boolean isOnMainTab(Activity act, View decor) {
        try {
            if (!(decor instanceof ViewGroup)) return false;
            ViewGroup bar = WxTabBar.find((ViewGroup) decor, act);
            if (bar == null) return false;
            List<View> items = WxTabBar.items(bar);
            if (items == null || items.size() < 3) return false;
            int idx = WxTabBar.selectedIndex(items);
            return idx >= 0 && idx < items.size();
        } catch (Throwable t) {
            return false;
        }
    }

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

            // 不许压进系统手势条：用 WindowVisibleDisplayFrame 做硬边界。
            // 刚冷启动时 RootWindowInsets 可能还是 0，只看 inset 会时对时错，
            // visible frame 则一开始就是稳定的。
            try {
                Rect visible = new Rect();
                View decor = act.getWindow() == null ? null : act.getWindow().getDecorView();
                if (decor != null) {
                    decor.getWindowVisibleDisplayFrame(visible);
                    int visibleBottom = visible.bottom - overlayLoc[1];
                    if (visibleBottom > 0) {
                        int limit = visibleBottom - (int) (2f * density) - pill;
                        if (top > limit) top = limit;
                    }
                }
            } catch (Throwable ignored) {
            }

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
        if (DISABLED) return;
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
