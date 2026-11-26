package com.zaka.wechatglass;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.WeakHashMap;

/**
 * 首页顶栏：搜索 / 「＋」合并成一颗悬浮液态玻璃胶囊，顶栏换成透明渐变。
 *
 * 做法和 Dock 是一套：**自己画玻璃 + 自己画图标 + 点击原样转发给微信原生的那颗按钮**。
 * 只是这里图标用原生的 Drawable 画成位图，点击按原生按钮的中心点派发一对 DOWN/UP，
 * 所以微信那边的点击链路（打开搜索、打开「＋」面板）完全没被绕开。
 *
 * 三条安全线：
 *   1. 认不出那两颗图标就**什么都不动**（顶栏维持原样），绝不乱改微信的标题栏；
 *   2. 原生图标先不动，等玻璃真的画出画面了再把它藏起来；玻璃起不来（1.5 秒没帧）
 *      就把原生图标还原，绝不出现"图标没了、玻璃也没有"；
 *   3. 玻璃层只吃胶囊范围内的触摸，别处的点击原样穿透给微信。
 */
final class WxTopBar {

    /** 只有首页需要顶栏。 */
    private static final WeakHashMap<Activity, TopLayer> LAYERS = new WeakHashMap<Activity, TopLayer>();
    /** 已经处理过的顶栏渐变，避免每次刷新重刷背景。 */
    private static final WeakHashMap<View, Boolean> TINTED = new WeakHashMap<View, Boolean>();
    /** 原生图标的原 alpha：藏起来之前记一份，要能还原。 */
    private static final WeakHashMap<View, Float> HIDDEN = new WeakHashMap<View, Float>();
    /** 每个首页一个「跟着 tab 切换走」的看门任务：切 tab 不换 Activity，只能自己盯。 */
    private static final WeakHashMap<Activity, Runnable> WATCHERS = new WeakHashMap<Activity, Runnable>();
    /** 上一次认到的两颗图标：布局回调偶尔会短暂认不到，不能因此把整层撤掉。 */
    private static final WeakHashMap<Activity, List<View>> ICON_CACHE = new WeakHashMap<Activity, List<View>>();
    /** 胶囊第一次算出来的位置：之后只要屏幕尺寸没变就钉死，避免每 600ms 重算导致漂移。 */
    private static final WeakHashMap<Activity, Rect> CAPSULE_CACHE = new WeakHashMap<Activity, Rect>();
    private static final WeakHashMap<Activity, int[]> CAPSULE_SIZE = new WeakHashMap<Activity, int[]>();
    /** 看门间隔。 */
    private static final long WATCH_MS = 600L;

    private WxTopBar() {
    }

    static void refresh(final Activity act) {
        if (act == null) return;
        try {
            if (!WxDockController.isHost(act)) {
                release(act);
                return;
            }
            if (!DockPrefs.enabled(act)) {
                release(act);
                return;
            }
            View decor = act.getWindow() == null ? null : act.getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) return;
            apply((ViewGroup) decor, act);
            startWatch(act);
            // 首页刚进来时标题栏往往还没量好尺寸，认不到图标。
            // 补两次重试，别让「没生效」变成常态。
            final Activity page = act;
            decor.postDelayed(new Runnable() {
                @Override
                public void run() {
                    applyLater(page);
                }
            }, 900L);
            decor.postDelayed(new Runnable() {
                @Override
                public void run() {
                    applyLater(page);
                }
            }, 2100L);
        } catch (Throwable t) {
            WechatGlass.warnOnce("topbar", "顶栏处理失败: " + t);
        }
    }

    /**
     * 盯着 tab 切换。
     *
     * 微信的四个 tab 是同一个 Activity 里的四个页面，切 tab **不会**走 onCreate/onResume，
     * 所以只在 resume 时判断「是不是我那一页」等于只判断了一次：
     * 建好的顶栏层会一直挂在窗口上，切到「我」以后照样盖着 —— 用户看到的就是
     * 「我的界面被加了蒙版」。这里每 600ms 看一眼当前是不是「我」页，是就把层撤掉。
     */
    private static void startWatch(final Activity act) {
        synchronized (WATCHERS) {
            if (WATCHERS.containsKey(act)) return;
        }
        Runnable watch = new Runnable() {
            @Override
            public void run() {
                try {
                    if (act.isFinishing() || act.isDestroyed()) {
                        synchronized (WATCHERS) { WATCHERS.remove(act); }
                        return;
                    }
                    View decor = act.getWindow() == null ? null : act.getWindow().getDecorView();
                    if (!(decor instanceof ViewGroup)) return;
                    if (!DockPrefs.enabled(act) || !WxDockController.isHost(act)) {
                        release(act);
                        stopWatch(act);
                        return;
                    } else if (isMeTab((ViewGroup) decor, act)) {
                        // 「我」页：右上角本来就没有搜索 / ＋，整层撤掉，一个像素都不画。
                        release(act);
                    } else {
                        TopLayer layer;
                        synchronized (LAYERS) { layer = LAYERS.get(act); }
                        if (layer == null || layer.getParent() == null) apply((ViewGroup) decor, act);
                    }
                } catch (Throwable ignored) {
                }
                synchronized (WATCHERS) {
                    if (WATCHERS.get(act) == this) {
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed(this, WATCH_MS);
                    }
                }
            }
        };
        synchronized (WATCHERS) { WATCHERS.put(act, watch); }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(watch, WATCH_MS);
    }

    private static void stopWatch(Activity act) {
        synchronized (WATCHERS) { WATCHERS.remove(act); }
    }

    private static void applyLater(Activity act) {
        try {
            if (act == null || act.isFinishing() || act.isDestroyed()) return;
            if (!WxDockController.isHost(act) || !DockPrefs.enabled(act)) return;
            View decor = act.getWindow() == null ? null : act.getWindow().getDecorView();
            if (decor instanceof ViewGroup) apply((ViewGroup) decor, act);
        } catch (Throwable ignored) {
        }
    }

    /** 页面切走 / 销毁：停采样、还原原生图标。 */
    static void release(Activity act) {
        if (act == null) return;
        synchronized (ICON_CACHE) { ICON_CACHE.remove(act); }
        synchronized (CAPSULE_CACHE) {
            CAPSULE_CACHE.remove(act);
            CAPSULE_SIZE.remove(act);
        }
        TopLayer layer;
        synchronized (LAYERS) { layer = LAYERS.remove(act); }
        if (layer != null) {
            try {
                ViewGroup parent = (ViewGroup) layer.getParent();
                if (parent != null) parent.removeView(layer);
            } catch (Throwable ignored) {
            }
            layer.releaseAll();
        }
    }

    /** 所有首页的顶栏层全部撤掉（聊天页 / 添加朋友页出现时用）。 */
    static void releaseAll() {
        java.util.List<Activity> acts;
        synchronized (LAYERS) {
            acts = new ArrayList<Activity>(LAYERS.keySet());
        }
        for (Activity a : acts) release(a);
    }

    static void pause(Activity act) {
        // 离开首页就把整层撤掉。只 setActive(false) 会把空胶囊留在窗口上，
        // 聊天页右上角那块黑色药丸就是这么来的。
        release(act);
    }

    static void resume(Activity act) {
        release(act);
    }

    // ------------------------------------------------------------ 主流程

    private static void apply(ViewGroup decor, Activity act) {
        release(act);
        int w = decor.getWidth();
        int h = decor.getHeight();
        if (w < 200 || h < 400) return;
        float density = act.getResources().getDisplayMetrics().density;
        boolean dark = isDark(act);
        Activity resumed = WxDockController.resumedActivity();
        if (resumed != null && resumed != act) {
            release(act);
            return;
        }

        // 「我」那一页右上角本来就没有搜索 / ＋，也不该被我们碰。
        // 微信首页 = 前三个 tab（微信 / 通讯录 / 发现），第四个是「我」。
        if (isMeTab(decor, act)) {
            release(act);
            return;
        }
        // 聊天页 / 联系人页也挂在同一个 LauncherUI 上，标题会变成联系人名。
        // 这时必须把顶栏层撤掉，否则会盖住聊天页原生标题。
        if (!isMainTabPage(decor, act, density)) {
            release(act);
            return;
        }
        // 只有顶栏确实压着一层蒙版/渐变/模糊层时才做渐变；没有就完全不碰。
        if (!hasTopMask(decor, act, density)) {
            release(act);
            return;
        }

        List<View> icons = findIcons(decor, act, w, h, density);

        // 微信布局重排时会有一两帧认不到图标；这时沿用上一次认到的两颗，
        // 避免胶囊「有时在、有时不在」地闪。
        List<View> cached;
        synchronized (ICON_CACHE) { cached = ICON_CACHE.get(act); }
        if (icons.size() < 2 && validIcons(cached)) {
            icons = new ArrayList<View>(cached);
        }

        // 硬保证：「我」那一页保持 100% 原生。
        // 判据就是「右上角有没有搜索和＋」——「我」页本来就没有这两颗，
        // 认不到就整层不画（渐变也不画），所以它不可能被我们改到。
        if (icons.isEmpty()) {
            release(act);
            return;
        }
        if (icons.size() == 1) {
            View plus = findPlusIcon(decor, act, density, icons.get(0));
            if (plus != null) icons.add(plus);
        }
        if (icons.size() >= 2) {
            synchronized (ICON_CACHE) { ICON_CACHE.put(act, new ArrayList<View>(icons)); }
        }

        Rect union = new Rect(Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 0);
        int[] loc = new int[2];
        for (View v : icons) {
            v.getLocationInWindow(loc);
            union.left = Math.min(union.left, loc[0]);
            union.top = Math.min(union.top, loc[1]);
            union.right = Math.max(union.right, loc[0] + v.getWidth());
            union.bottom = Math.max(union.bottom, loc[1] + v.getHeight());
        }
        if (union.width() <= 0 || union.height() <= 0) {
            release(act);
            return;
        }

        // 固定钉在屏幕右上角：不再依赖 ActionBarContainer / 图标位置，
        // 微信布局重排、切 tab 都不会让胶囊漂移。
        int padH = (int) (14 * density);
        int minH = (int) (38 * density);
        int maxH = (int) (46 * density);
        int capsuleH = Math.min(maxH, Math.max(minH, union.height() + (int) (10 * density)));
        int statusBar = statusBarHeight(act);
        int top = statusBar + (int) (1 * density);
        int right = w - (int) (5 * density);
        int capsuleW = (int) (84 * density);
        int left = right - capsuleW;
        Rect capsule = new Rect(left, top, right, top + capsuleH);
        synchronized (CAPSULE_CACHE) {
            CAPSULE_CACHE.remove(act);
            CAPSULE_SIZE.remove(act);
        }

        TopLayer layer;
        synchronized (LAYERS) { layer = LAYERS.get(act); }

        if (layer == null || layer.getParent() == null) {
            layer = addLayer(decor, act);
            if (layer == null) return;
        } else {
            layer.bringToFront();
        }
        layer.setBandBottom(bandBottomOf(decor, act, density));
        layer.setTitleView(findTitleView(decor, act, density));
        layer.bind(act, dark, capsule, icons, union);
    }

    /**
     * 渐变那条模糊带的底边（窗口坐标）。
     *
     * 取标题栏底边再往下多 6dp：模糊最浓的地方就是标题栏那一条，
     * 再往上（状态栏方向）逐渐淡到完全透明 —— 也就是用户要的「从顶栏往上渐变」。
     */
    private static int bandBottomOf(View decor, Activity act, float density) {
        int bottom = titleBottom(decor, act, density);
        if (bottom > 0) return bottom;
        return statusBarHeight(act) + (int) (44 * density);
    }

    /** 建那层全屏透明的顶栏覆盖层（已经存在就复用）。 */
    private static TopLayer addLayer(ViewGroup decor, Activity act) {
        TopLayer layer = new TopLayer(act.getApplicationContext());
        try {
            // 只覆盖状态栏 + 标题栏这一小条，绝不铺满全屏：
            // 全屏覆盖层会被微信当成“盖住底栏”的悬浮层，导致 Dock 直接不接管。
            int density = (int) act.getResources().getDisplayMetrics().density;
            int layerH = Math.min(decor.getHeight(),
                    statusBarHeight(act) + 96 * density);
            decor.addView(layer, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, layerH)));
            layer.bringToFront();
        } catch (Throwable t) {
            WechatGlass.warnOnce("topbar-add", "顶栏玻璃层挂不上: " + t);
            return null;
        }
        synchronized (LAYERS) { LAYERS.put(act, layer); }
        WechatGlass.log("顶栏:覆盖层已建立");
        return layer;
    }

    /** 顶栏换成从上到下的透明渐变（只在标题栏那一条，不动内容区）。 */
    private static void tintTitleBar(ViewGroup decor, Activity act, Rect capsule, int screenWidth,
                                     float density, boolean dark) {
        // 之前那一版是给微信的标题栏容器换背景，容易被它自己重新 setBackground 顶掉，
        // 也会影响别的页面。现在渐变画在我们自己的层里，微信一个控件都不用动。
    }

    private static boolean validIcons(List<View> icons) {
        if (icons == null || icons.size() < 2) return false;
        for (View v : icons) {
            if (v == null || !v.isAttachedToWindow() || !v.isShown()) return false;
        }
        return true;
    }

    private static boolean isDark(Activity act) {
        return WxTheme.isDark(act);
    }

    /** 「我」tab 是否为当前页。 */
    private static boolean isMeTab(ViewGroup decor, Activity act) {
        try {
            ViewGroup bar = WxTabBar.find(decor, act);
            if (bar != null) {
                List<View> items = WxTabBar.items(bar);
                if (items != null && items.size() >= 4
                        && WxTabBar.selectedIndex(items) == 3) return true;
            }
            // 兜底：底栏选中项读不到时，标题是「我 / Me / Profile」也一律不碰。
            View title = findTitleView(decor, act,
                    act.getResources().getDisplayMetrics().density);
            if (title instanceof android.widget.TextView) {
                CharSequence text = ((android.widget.TextView) title).getText();
                if (text != null) {
                    String value = text.toString().trim();
                    return value.equals("我") || value.equals("Me") || value.equals("Profile");
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 官方模块的锚点：androidx.appcompat.widget.ActionBarContainer。 */
    private static View findActionBar(View root, Activity act, float density) {
        try {
            int limitY = statusBarHeight(act) + (int) (96 * density);
            ArrayDeque<View> queue = new ArrayDeque<View>();
            IdentityHashMap<View, Boolean> seen = new IdentityHashMap<View, Boolean>();
            queue.add(root);
            View best = null;
            int bestW = 0;
            int budget = 20000;
            int[] loc = new int[2];
            while (!queue.isEmpty() && budget-- > 0) {
                View v = queue.poll();
                if (v == null || seen.put(v, Boolean.TRUE) != null) continue;
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        View c = g.getChildAt(i);
                        if (c != null && !(c instanceof TopLayer)) queue.add(c);
                    }
                }
                if (!v.isShown() || v.getWidth() <= 0 || v.getHeight() <= 0) continue;
                String name = v.getClass().getName();
                if (!name.contains("ActionBarContainer")) continue;
                v.getLocationInWindow(loc);
                if (loc[1] > limitY) continue;
                if (v.getWidth() > bestW) {
                    bestW = v.getWidth();
                    best = v;
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 顶栏是否有一层可识别的蒙版 / 渐变 / 模糊层；没有就不做处理。 */
    private static boolean hasTopMask(ViewGroup decor, Activity act, float density) {
        try {
            int screenW = decor.getWidth() > 0 ? decor.getWidth()
                    : act.getResources().getDisplayMetrics().widthPixels;
            int limitY = statusBarHeight(act) + (int) (74 * density);
            ArrayDeque<View> queue = new ArrayDeque<View>();
            IdentityHashMap<View, Boolean> seen = new IdentityHashMap<View, Boolean>();
            queue.add(decor);
            int[] loc = new int[2];
            int budget = 20000;
            while (!queue.isEmpty() && budget-- > 0) {
                View v = queue.poll();
                if (v == null || seen.put(v, Boolean.TRUE) != null) continue;
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        View c = g.getChildAt(i);
                        if (c != null && !(c instanceof TopLayer)) queue.add(c);
                    }
                }
                if (v instanceof TopLayer || !v.isShown()
                        || v.getWidth() <= 0 || v.getHeight() <= 0) continue;
                v.getLocationInWindow(loc);
                if (loc[1] > limitY) continue;
                String n = v.getClass().getName().toLowerCase();
                if (n.contains("mask") || n.contains("blur")
                        || n.contains("gradient") || n.contains("shadow")) {
                    WechatGlass.log("顶栏:检测到蒙版 " + v.getClass().getName());
                    return true;
                }
                float alpha = v.getAlpha();
                if (alpha > 0.01f && alpha < 0.99f
                        && v.getWidth() > screenW * 0.5f
                        && v.getHeight() < (int) (80 * density)) {
                    WechatGlass.log("顶栏:检测到半透明蒙版 alpha=" + alpha);
                    return true;
                }
                Drawable bg = v.getBackground();
                if (bg instanceof android.graphics.drawable.ColorDrawable
                        && v.getWidth() > screenW * 0.5f) {
                    int color = ((android.graphics.drawable.ColorDrawable) bg).getColor();
                    int a = (color >>> 24) & 0xFF;
                    if (a > 0 && a < 255) {
                        WechatGlass.log("顶栏:检测到半透明底色 a=" + a);
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        WechatGlass.log("顶栏:没有蒙版，跳过");
        return false;
    }

    /** 只有微信 / 通讯录 / 发现三个主 tab 才画顶栏。 */
    private static boolean isMainTabPage(ViewGroup decor, Activity act, float density) {
        View title = findTitleView(decor, act, density);
        if (!(title instanceof android.widget.TextView)) return false;
        CharSequence text = ((android.widget.TextView) title).getText();
        if (text == null) return false;
        String value = text.toString().trim()
                .replaceAll("\\s*\\(\\d+\\)\\s*$", "")
                .replaceAll("\\s+\\d+$", "").trim();
        return value.equals("微信") || value.equals("通讯录") || value.equals("发现")
                || value.equals("WeChat") || value.equals("Chats")
                || value.equals("Contacts") || value.equals("Discover");
    }

    /** 标题栏里有没有「微信 / 通讯录 / 发现」这种已知 tab 标题（找到才认为可以画顶栏）。 */
    private static boolean hasKnownTitle(View decor, Activity act, float density) {
        return findTitleView(decor, act, density) != null;
    }

    /** 找出标题栏那个标题 View（微信 / 通讯录 / 发现）。 */
    private static View findTitleView(View decor, Activity act, float density) {
        try {
            int limitY = statusBarHeight(act) + (int) (58 * density);
            ArrayDeque<View> queue = new ArrayDeque<View>();
            IdentityHashMap<View, Boolean> seen = new IdentityHashMap<View, Boolean>();
            queue.add(decor);
            int[] loc = new int[2];
            int budget = 20000;
            while (!queue.isEmpty() && budget-- > 0) {
                View v = queue.poll();
                if (v == null || seen.put(v, Boolean.TRUE) != null) continue;
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        View c = g.getChildAt(i);
                        if (c != null && !(c instanceof TopLayer)) queue.add(c);
                    }
                }
                if (!(v instanceof android.widget.TextView)) continue;
                if (!v.isShown()) continue;
                v.getLocationInWindow(loc);
                if (loc[1] > limitY) continue;
                CharSequence text = ((android.widget.TextView) v).getText();
                if (text == null) continue;
                String value = text.toString().trim();
                if (value.equals("微信") || value.equals("通讯录") || value.equals("发现")
                        || value.equals("我") || value.equals("WeChat") || value.equals("Chats")
                        || value.equals("Contacts") || value.equals("Discover")
                        || value.equals("Me") || value.equals("Profile")) {
                    return v;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 标题栏底边在窗口里的 y（找不到返回 -1）。
     *
     * 渐变要「从顶栏往上淡出去」，所以必须要知道标题栏到底那一行在哪，
     * 不能再拿胶囊位置凑 —— 胶囊位置会随图标大小漂。
     */
    private static int titleBottom(View decor, Activity act, float density) {
        View title = findTitleView(decor, act, density);
        if (title == null) return -1;
        try {
            int[] loc = new int[2];
            title.getLocationInWindow(loc);
            return loc[1] + title.getHeight();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int statusBarHeight(Activity act) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsets insets = act.getWindow()
                        .getDecorView().getRootWindowInsets();
                if (insets != null) {
                    return insets.getInsets(android.view.WindowInsets.Type.statusBars()).top;
                }
            }
            int id = act.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return act.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {
        }
        return (int) (26 * act.getResources().getDisplayMetrics().density);
    }

    /**
     * 右上角那几颗图标（搜索 / ＋）。
     *
     * 之前的条件太窄：只认 ImageView、还要 14~64dp、还要落在固定比例里。
     * 微信不同版本把图标包在 FrameLayout、TextView、ImageButton 里都有，
     * 条件一严就"永远认不到"，顶栏玻璃自然从来不会出现。
     * 现在按「长得像图标 + 在右上角 + 能点」来认，ImageView 只是其中一种。
     */
    private static List<View> findIcons(ViewGroup decor, Activity act, int w, int h,
                                        float density) {
        List<View> found = new ArrayList<View>();
        ArrayDeque<View> queue = new ArrayDeque<View>();
        IdentityHashMap<View, Boolean> seen = new IdentityHashMap<View, Boolean>();
        queue.add(decor);
        int budget = 20000;
        int[] loc = new int[2];
        int limitY = statusBarHeight(act) + (int) (58 * density);

        while (!queue.isEmpty() && budget-- > 0) {
            View v = queue.poll();
            if (v == null || seen.put(v, Boolean.TRUE) != null) continue;
            if (v instanceof TopLayer) continue;
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    if (c != null && !(c instanceof TopLayer)) queue.add(c);
                }
            }
            if (!v.isShown() || v.getAlpha() < 0.3f) continue;
            int vw = v.getWidth();
            int vh = v.getHeight();
            if (vw < 8 * density || vh < 8 * density) continue;
            if (vw > 120 * density || vh > 120 * density) continue;
            if (!looksLikeIcon(v)) continue;
            v.getLocationInWindow(loc);
            float cx = loc[0] + vw / 2f;
            if (cx < w * 0.5f) continue;   // 只要右半边
            if (loc[1] > limitY) continue; // 只要最上面那一带
            found.add(v);
        }
        if (found.isEmpty()) return found;
        found.sort(new Comparator<View>() {
            @Override
            public int compare(View a, View b) {
                int[] la = new int[2];
                int[] lb = new int[2];
                a.getLocationInWindow(la);
                b.getLocationInWindow(lb);
                return Integer.compare(la[0], lb[0]);
            }
        });
        // 最右边两颗就是搜索和「＋」；只有一颗的版本就包那一颗。
        return found.size() > 2
                ? new ArrayList<View>(found.subList(found.size() - 2, found.size()))
                : found;
    }

    /** 搜索认到了、＋没认到时，按 contentDescription / 文本 / 类名再找一次。 */
    private static View findPlusIcon(ViewGroup decor, Activity act, float density, View exclude) {
        try {
            ArrayDeque<View> queue = new ArrayDeque<View>();
            IdentityHashMap<View, Boolean> seen = new IdentityHashMap<View, Boolean>();
            queue.add(decor);
            int budget = 20000;
            int limitY = statusBarHeight(act) + (int) (64 * density);
            int[] loc = new int[2];
            View best = null;
            int bestX = -1;
            while (!queue.isEmpty() && budget-- > 0) {
                View v = queue.poll();
                if (v == null || seen.put(v, Boolean.TRUE) != null || v == exclude) continue;
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        View c = g.getChildAt(i);
                        if (c != null && !(c instanceof TopLayer)) queue.add(c);
                    }
                }
                if (!v.isShown() || v.getAlpha() < 0.3f) continue;
                if (v.getWidth() < 8 * density || v.getHeight() < 8 * density) continue;
                v.getLocationInWindow(loc);
                if (loc[1] > limitY || loc[0] < decor.getWidth() / 2) continue;
                String desc = v.getContentDescription() == null
                        ? "" : v.getContentDescription().toString().toLowerCase(java.util.Locale.ROOT);
                String cls = v.getClass().getName().toLowerCase(java.util.Locale.ROOT);
                String text = v instanceof android.widget.TextView
                        ? ((android.widget.TextView) v).getText().toString() : "";
                boolean plus = desc.contains("更多") || desc.contains("添加")
                        || desc.contains("plus") || desc.contains("add") || desc.contains("more")
                        || text.equals("+") || text.equals("＋")
                        || cls.contains("plus") || cls.contains("add");
                if (!plus) continue;
                if (loc[0] > bestX) {
                    bestX = loc[0];
                    best = v;
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 像不像一颗标题栏图标：本身是 ImageView，或者能点、或者带着自己的背景。 */
    private static boolean looksLikeIcon(View v) {
        try {
            if (v instanceof ImageView) return true;
            if (v.isClickable()) return true;
            if (v.getBackground() != null && v.getWidth() <= (int) (96 * v.getResources()
                    .getDisplayMetrics().density)) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }


    // ------------------------------------------------------------ 覆盖层

    /** 全屏透明的覆盖层：画玻璃 + 顶栏渐变 + 两颗图标，只吃胶囊范围内的触摸。 */
    static final class TopLayer extends FrameLayout {

        private final GlassCapsule glass;
        private final ImageView[] slots = new ImageView[2];
        private final View[] natives = new View[2];
        private final Rect scrimBounds = new Rect();
        private final Paint scrim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF scrimDst = new RectF();
        private final Paint topPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private View contentRoot;
        private Bitmap topSample;
        private boolean active;
        private Activity host;
        private boolean dark;
        /** 渐变带底边（窗口坐标）；0 = 还不知道，先按默认算。 */
        private int bandBottomWindow;
        /** 原生标题（微信 / 通讯录 / 发现）：渐变画完再把它盖回去，字不跟着糊。 */
        private View titleView;

        /** 渐变那条要不要重采样：只有页面滚动 / 布局变化时才置 true。 */
        private boolean bandDirty = true;
        private long lastGrabAt;
        private View watchedRoot;
        private final android.view.ViewTreeObserver.OnScrollChangedListener scrollWatcher =
                new android.view.ViewTreeObserver.OnScrollChangedListener() {
                    @Override
                    public void onScrollChanged() {
                        bandDirty = true;
                    }
                };

        /**
         * 顶栏那条模糊的刷新节奏。
         *
         * 以前是每 300ms 无条件把整棵内容树软件重绘进位图 —— 首页越复杂越卡，
         * 用户直接反馈「渐变模糊很卡顿」。现在只在页面滚动过、或者兜底 1.2 秒到点时
         * 才重画一次；静着不动的时候一次都不画。
         */
        private final Runnable topCapture = new Runnable() {
            @Override
            public void run() {
                if (!active) return;
                try {
                    long now = SystemClock.uptimeMillis();
                    if (bandDirty || now - lastGrabAt >= 1200L) {
                        bandDirty = false;
                        lastGrabAt = now;
                        grabTop();
                    }
                } catch (Throwable ignored) {
                }
                postDelayed(this, 320L);
            }
        };

        TopLayer(android.content.Context context) {
            super(context);
            setWillNotDraw(false);
            setBackgroundColor(0x00000000);
            setClipChildren(false);

            glass = new GlassCapsule(context);
            addView(glass, new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT));

            for (int i = 0; i < 2; i++) {
                ImageView icon = new ImageView(context);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                final int index = i;
                icon.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        forward(index);
                    }
                });
                slots[i] = icon;
                addView(icon, new LayoutParams(1, 1));
            }
        }

        /** 把顶栏那一条按低分辨率画进一张位图，再套一层竖直 alpha 渐变。 */
        private void grabTop() {
            View root = contentRoot;
            if (root == null || root.getWidth() <= 0 || root.getHeight() <= 0) return;
            int bandH = Math.min(root.getHeight(), scrimBounds.height());
            if (bandH <= 2) return;
            float scale = Math.max(1f / 16f,
                    Math.min(1f / 4f, 130f / Math.max(1, root.getWidth())));
            int w = Math.max(8, (int) (root.getWidth() * scale));
            int h = Math.max(4, (int) (bandH * scale));
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            try {
                // 位图代表的是「窗口里 0 ~ bandH 那一条」，而 content 在窗口里是有偏移的
                // （状态栏 / 标题栏在上方）。以前没加这个偏移，模糊整体往下错了一段，
                // 看起来就是「渐变位置不对」。
                int[] contentLoc = new int[2];
                root.getLocationInWindow(contentLoc);
                Canvas c = new Canvas(bmp);
                c.scale(scale, scale);
                c.translate(0, contentLoc[1]);
                c.clipRect(0, -contentLoc[1], root.getWidth(),
                        bandH - contentLoc[1]);
                root.draw(c);

                Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
                // 方向：顶栏那一条最实，往上逐渐淡到透明（不是从上往下糊）。
                mask.setShader(new LinearGradient(0, 0, 0, bandH,
                        new int[]{0x00FFFFFF, 0x55FFFFFF, 0xD8FFFFFF, 0xFFFFFFFF},
                        new float[]{0f, 0.30f, 0.76f, 1f}, Shader.TileMode.CLAMP));
                mask.setXfermode(new android.graphics.PorterDuffXfermode(
                        android.graphics.PorterDuff.Mode.DST_IN));
                Canvas mc = new Canvas(bmp);
                mc.scale(scale, scale);
                mc.drawRect(0, 0, root.getWidth(), bandH, mask);
            } catch (Throwable t) {
                bmp.recycle();
                return;
            }
            Bitmap old = topSample;
            topSample = bmp;
            if (old != null && old != bmp) old.recycle();
            postInvalidateOnAnimation();
        }

        void bind(Activity act, boolean isDark, Rect capsuleInWindow, List<View> icons,
                  Rect unionInWindow) {
            host = act;
            dark = isDark;
            setActive(true);
            glass.bind(act, capsuleInWindow);
            glass.setDark(isDark);

            View content = act.findViewById(android.R.id.content);
            if (content != contentRoot) {
                detachScrollWatcher();
                contentRoot = content;
                attachScrollWatcher(content);
                if (topSample != null) {
                    topSample.recycle();
                    topSample = null;
                }
                bandDirty = true;
            }

            int[] self = new int[2];
            getLocationInWindow(self);
            // 渐变模糊带：底边 = 标题栏底边，往上一直淡到屏幕顶端。
            // 之前是「胶囊下沿再往下散」，所以模糊会糊到内容区，位置也是错的。
            int bandBottom = bandBottomWindow > 0 ? bandBottomWindow - self[1]
                    : statusBarHeight(act) + dp(44);
            scrimBounds.set(0, 0, getWidth(), Math.max(0, bandBottom));
            invalidate();

            for (int i = 0; i < 2; i++) {
                View nativeIcon = i < icons.size() ? icons.get(i) : null;
                natives[i] = nativeIcon;
                ImageView slot = slots[i];
                if (nativeIcon == null) {
                    if (i == 1) {
                        // 微信某些版本把「＋」藏得太深，这里补一颗可见的加号，
                        // 点击仍按右上角坐标转发给原生。
                        slot.setVisibility(VISIBLE);
                        slot.setImageBitmap(plusBitmap());
                        slot.setColorFilter(dark ? 0xFFF3F5F8 : 0xFF15171C,
                                android.graphics.PorterDuff.Mode.SRC_IN);
                        FrameLayout.LayoutParams plp =
                                (FrameLayout.LayoutParams) slot.getLayoutParams();
                        int size = dp(22);
                        plp.width = size;
                        plp.height = size;
                        plp.gravity = Gravity.TOP | Gravity.START;
                        plp.leftMargin = getWidth() - dp(34);
                        plp.topMargin = capsuleInWindow.centerY() - self[1] - size / 2;
                        slot.setLayoutParams(plp);
                    } else {
                        slot.setVisibility(GONE);
                    }
                    continue;
                }
                slot.setVisibility(VISIBLE);
                Bitmap bitmap = iconBitmap(nativeIcon);
                if (bitmap != null) {
                    slot.setImageBitmap(bitmap);
                } else {
                    slot.setImageDrawable(null);
                }
                int[] loc = new int[2];
                nativeIcon.getLocationInWindow(loc);
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) slot.getLayoutParams();
                lp.width = nativeIcon.getWidth();
                lp.height = nativeIcon.getHeight();
                lp.leftMargin = loc[0] - self[0];
                lp.topMargin = loc[1] - self[1];
                lp.gravity = Gravity.TOP | Gravity.START;
                slot.setLayoutParams(lp);
                // 图标必须和胶囊表面有对比：原来直接照搬原生图标的位图，
                // 浅色胶囊上放一颗白图标 = 看不见。
                // 现在统一染成「浅色模式深图标 / 深色模式浅图标」。
                slot.setColorFilter(dark ? 0xFFF3F5F8 : 0xFF15171C,
                        android.graphics.PorterDuff.Mode.SRC_IN);
            }

            // 玻璃画出画面了再藏原生图标（1.5 秒都没画出来就放弃，把原生还回去）。
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (glass.hasFrame()) {
                        hideNatives();
                    } else if (isAttachedToWindow()) {
                        releaseNatives();
                        WechatGlass.warn("顶栏:玻璃没画出来，原生图标保持不动");
                    }
                }
            }, 700L);
        }

        private int dp(float v) {
            return (int) (v * getResources().getDisplayMetrics().density);
        }

        private void hideNatives() {
            for (View v : natives) {
                if (v == null) continue;
                synchronized (HIDDEN) {
                    if (!HIDDEN.containsKey(v)) HIDDEN.put(v, v.getAlpha());
                }
                try {
                    v.setAlpha(0f);
                } catch (Throwable ignored) {
                }
            }
        }

        private void releaseNatives() {
            for (View v : natives) {
                if (v == null) continue;
                Float original;
                synchronized (HIDDEN) { original = HIDDEN.remove(v); }
                try {
                    v.setAlpha(original == null ? 1f : original);
                } catch (Throwable ignored) {
                }
            }
        }

        private void attachScrollWatcher(View content) {
            try {
                if (content == null) return;
                watchedRoot = content;
                content.getViewTreeObserver().addOnScrollChangedListener(scrollWatcher);
            } catch (Throwable ignored) {
            }
        }

        private void detachScrollWatcher() {
            try {
                if (watchedRoot != null && watchedRoot.getViewTreeObserver().isAlive()) {
                    watchedRoot.getViewTreeObserver().removeOnScrollChangedListener(scrollWatcher);
                }
            } catch (Throwable ignored) {
            }
            watchedRoot = null;
        }

        void setTitleView(View view) {
            titleView = view;
        }

        void setBandBottom(int windowY) {
            if (windowY > 0 && windowY != bandBottomWindow) {
                bandBottomWindow = windowY;
                bandDirty = true;
            }
        }

        /** 已经包住原生图标了（不是那种只画渐变的空壳）。 */
        boolean hasNatives() {
            return natives[0] != null || natives[1] != null;
        }

        /** 只更新渐变那一条（图标没认到时用）。 */
        void refreshScrim(boolean isDark) {
            dark = isDark;
            setActive(true);
            int[] self = new int[2];
            getLocationInWindow(self);
            int bandBottom = bandBottomWindow > 0 ? bandBottomWindow - self[1]
                    : statusBarHeight(host) + dp(44);
            scrimBounds.set(0, 0, getWidth(), Math.max(0, bandBottom));
            invalidate();
        }

        void setActive(boolean value) {
            active = value;
            glass.setActive(value);
            // 用户要求取消顶栏渐变模糊：不再抓整条画面，只保留胶囊玻璃。
            removeCallbacks(topCapture);
        }

        void releaseAll() {
            setActive(false);
            detachScrollWatcher();
            releaseNatives();
            glass.release();
            if (topSample != null) {
                topSample.recycle();
                topSample = null;
            }
            contentRoot = null;
            host = null;
        }

        /** 把点击原样转发给微信那颗原生按钮。 */
        private void forward(int index) {
            View target = index >= 0 && index < natives.length ? natives[index] : null;
            try {
                if (target == null && index == 1 && host != null) {
                    // 没认到原生＋时，按右上角坐标补一次真实触摸。
                    View decor = host.getWindow() == null ? null
                            : host.getWindow().getDecorView();
                    if (decor == null) return;
                    long now = SystemClock.uptimeMillis();
                    float x = decor.getWidth() - dp(24);
                    float y = statusBarHeight(host) + dp(30);
                    MotionEvent down = MotionEvent.obtain(now, now,
                            MotionEvent.ACTION_DOWN, x, y, 0);
                    MotionEvent up = MotionEvent.obtain(now, now + 40L,
                            MotionEvent.ACTION_UP, x, y, 0);
                    decor.dispatchTouchEvent(down);
                    decor.dispatchTouchEvent(up);
                    down.recycle();
                    up.recycle();
                    return;
                }
                if (target == null) return;
                int w = target.getWidth();
                int h = target.getHeight();
                if (w <= 0 || h <= 0) return;
                long now = SystemClock.uptimeMillis();
                float x = w / 2f;
                float y = h / 2f;
                MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
                MotionEvent up = MotionEvent.obtain(now, now + 40L, MotionEvent.ACTION_UP, x, y, 0);
                down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                target.dispatchTouchEvent(down);
                target.dispatchTouchEvent(up);
                down.recycle();
                up.recycle();
                WechatGlass.log("顶栏:转发点击 → " + (index == 0 ? "搜索" : "＋"));
            } catch (Throwable t) {
                WechatGlass.warnOnce("topbar-tap", "顶栏点击转发失败: " + t);
            }
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            // 顶栏渐变模糊已取消：这里只让子 View（胶囊 + 两个图标）自己画。
            // 不再采样标题栏、不再铺渐变，避免糊住内容，也避免整层挡住底栏。
        }

        /** 玻璃层只在胶囊 / 图标范围内吃触摸，其余穿透给微信。 */
        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            float x = ev.getX();
            float y = ev.getY();
            for (ImageView slot : slots) {
                if (slot == null || slot.getVisibility() != VISIBLE) continue;
                if (x >= slot.getLeft() && x <= slot.getRight()
                        && y >= slot.getTop() && y <= slot.getBottom()) {
                    return false;
                }
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return false;
        }

        private Bitmap plusBitmap() {
            int s = dp(22);
            Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFFFFFFFF);
            p.setStrokeWidth(dp(2.2f));
            p.setStrokeCap(Paint.Cap.ROUND);
            float pad = dp(5);
            c.drawLine(pad, s / 2f, s - pad, s / 2f, p);
            c.drawLine(s / 2f, pad, s / 2f, s - pad, p);
            return b;
        }

        private Bitmap iconBitmap(View v) {
            try {
                if (v instanceof ImageView) {
                    Drawable d = ((ImageView) v).getDrawable();
                    Bitmap b = WxTabBar.drawableToBitmap(d);
                    if (b != null) return b;
                }
                // 有些微信版本把图标包在 ImageButton / TextView / FrameLayout 里。
                // 直接把这个原生 View 画成位图，保证胶囊里至少看得见原来的图标。
                int w = v.getWidth();
                int h = v.getHeight();
                if (w <= 0 || h <= 0) return null;
                Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(b);
                // 原生图标可能已经被 hideNatives() 设成 alpha=0；
                // 直接 draw 会得到一张全透明位图，胶囊里就“没图标”。
                float oldAlpha = v.getAlpha();
                try {
                    v.setAlpha(1f);
                    v.draw(c);
                } finally {
                    v.setAlpha(oldAlpha);
                }
                return b;
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
