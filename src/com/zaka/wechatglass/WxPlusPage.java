package com.zaka.wechatglass;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 「＋」弹层的玻璃底 + 面板出现时让 Dock 让位。
 *
 * 注意：这里**不再往微信里插任何设置入口**。设置只有两个地方能进：
 *   1. 微信「我 → 设置」里那一行（WxSettingsEntry）；
 *   2. 桌面上的 wxglass 图标（SettingsActivity）。
 *
 * 面板是靠盯着整棵视图树认的：每个页面挂一个 OnGlobalLayoutListener，
 * 只要「扫一扫 / 收付款 / 添加朋友」这些条目同时出现，就认为面板开了 ——
 * 这时把 Dock 收起来，并把面板底色换成同一套玻璃。
 */
final class WxPlusPage {

    /** 用来认「＋」面板的条目文字：任意命中一个就算。 */
    private static final String[] MARKERS = {"扫一扫", "收付款", "添加朋友", "摇一摇"};

    private static final String TITLE = "wxglass 设置";

    private static final WeakHashMap<Activity, View> PILLS = new WeakHashMap<Activity, View>();
    private static final WeakHashMap<Activity, Boolean> WATCHED = new WeakHashMap<Activity, Boolean>();
    private static final WeakHashMap<View, Boolean> STYLED = new WeakHashMap<View, Boolean>();
    /** 面板容器 -> 已经插进去的那一行，避免重复插入。 */
    private static final WeakHashMap<ViewGroup, View> ROWS = new WeakHashMap<ViewGroup, View>();
    /** 每个页面最近一次扫描的时间：布局回调可能很密，节流到 200ms 一次。 */
    private static final WeakHashMap<Activity, Long> LAST_SCAN = new WeakHashMap<Activity, Long>();
    /** 面板关闭的看门狗：PopupWindow 关掉不一定有布局回调，靠它把 Dock 放回来。 */
    private static final WeakHashMap<Activity, Boolean> CLOSE_WATCH = new WeakHashMap<Activity, Boolean>();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private WxPlusPage() {
    }

    // ------------------------------------------------------------ 挂勾

    static void hook(ClassLoader cl) {
        // 1) 已知的「添加朋友」页：resume 时扫一遍（真正那一页里也放一个入口）。
        String[] pages = {
                "com.tencent.mm.ui.FindMoreFriendsUI",
                "com.tencent.mm.ui.AddMoreFriendsUI",
                "com.tencent.mm.ui.more.MoreTabUI",
        };
        for (String name : pages) {
            try {
                Class<?> cls = Class.forName(name, false, cl);
                XposedBridge.hookAllMethods(cls, "onResume", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.thisObject instanceof Activity) {
                            final Activity act = (Activity) param.thisObject;
                            watch(act);
                            MAIN.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    scan(act);
                                }
                            }, 300L);
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }

        // 2) PopupWindow：显示之后改玻璃底 + 能插就插一行入口。
        try {
            XC_MethodHook popup = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof PopupWindow) {
                        stylePopup((PopupWindow) param.thisObject);
                    }
                }
            };
            XposedBridge.hookAllMethods(PopupWindow.class, "showAsDropDown", popup);
            XposedBridge.hookAllMethods(PopupWindow.class, "showAtLocation", popup);
            WechatGlass.log("入口:已挂上弹层监听");
        } catch (Throwable t) {
            WechatGlass.warnOnce("popup-hook", "入口:挂弹层监听失败: " + t);
        }
    }

    /** 给这个页面的视图树挂一个「布局一变就看一眼」的监听。 */
    static void watch(final Activity act) {
        if (act == null) return;
        synchronized (WATCHED) {
            if (WATCHED.get(act) != null) return;
            WATCHED.put(act, Boolean.TRUE);
        }
        try {
            final View decor = act.getWindow() == null ? null : act.getWindow().getDecorView();
            if (decor == null) return;
            decor.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            try {
                                scan(act);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable t) {
            WechatGlass.warnOnce("watch", "挂布局监听失败: " + t);
        }
    }

    /** 任何页面显示时都问一句：这是不是「＋」面板。 */
    static void tryAttach(Activity act) {
        if (act == null) return;
        watch(act);
        scan(act);
    }

    static void detach(Activity act) {
        if (act == null) return;
        View pill;
        synchronized (PILLS) { pill = PILLS.remove(act); }
        if (pill != null) {
            try {
                ViewGroup parent = (ViewGroup) pill.getParent();
                if (parent != null) parent.removeView(pill);
            } catch (Throwable ignored) {
            }
        }
        WxDockController.setCovered(act, false);
    }

    // ------------------------------------------------------------ 扫描

    private static void scan(Activity act) {
        if (act == null || act.isFinishing()) return;
        long now = android.os.SystemClock.uptimeMillis();
        synchronized (LAST_SCAN) {
            Long last = LAST_SCAN.get(act);
            if (last != null && now - last < 200L) return;
            LAST_SCAN.put(act, now);
        }
        View decor = act.getWindow() == null ? null : act.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) return;

        java.util.Set<String> words = new java.util.HashSet<String>();
        List<View> markers = findMarkers(decor, words);
        // 必须命中**两个不同的**条目文字：聊天列表里可能正好有一句「添加朋友」，
        // 只凭一个词就当成「＋」面板，会把 Dock 误收起。
        ViewGroup panel = (markers.size() >= 2 && words.size() >= 2)
                ? panelOf(markers, (ViewGroup) decor) : null;

        boolean host = WxDockController.isHost(act);
        if (panel == null) {
            // 面板收了 / 这一页没有：撤掉入口，Dock 恢复。
            removeRow();
            detachPillOnly(act);
            if (host) WxDockController.setCovered(act, false);
            return;
        }

        if (host) {
            WxDockController.setCovered(act, true);
            watchPanelClose(act);
        }
        // 微信里不再插任何设置入口（面板里的那一行 / 浮在外面那颗胶囊都去掉了）。
        // 设置只留「我 → 设置」里那一条 + 桌面 wxglass 图标，少一处少一处出错。
        removeRow();
        detachPillOnly(act);
    }

    /** 找所有「＋」面板条目文字。 */
    private static List<View> findMarkers(View root, java.util.Set<String> words) {
        List<View> found = new ArrayList<View>();
        ArrayDeque<View> queue = new ArrayDeque<View>();
        IdentityHashMap<View, Boolean> seen = new IdentityHashMap<View, Boolean>();
        queue.add(root);
        int budget = 3000;
        while (!queue.isEmpty() && budget-- > 0) {
            View v = queue.poll();
            if (v == null || seen.put(v, Boolean.TRUE) != null) continue;
            if (v.getVisibility() != View.VISIBLE) continue;
            if (v instanceof TextView) {
                CharSequence text = ((TextView) v).getText();
                if (text != null && text.length() > 0) {
                    String s = text.toString().trim();
                    for (String marker : MARKERS) {
                        if (s.equals(marker) || s.startsWith(marker)) {
                            if (v.isShown() && v.getWidth() > 0 && v.getHeight() > 0) {
                                found.add(v);
                                words.add(marker);
                            }
                            break;
                        }
                    }
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    queue.add(g.getChildAt(i));
                }
            }
        }
        return found;
    }

    /** 这些条目所在的那个「面板」容器：能同时装下 >=2 个条目的最小的 ViewGroup。 */
    private static ViewGroup panelOf(List<View> markers, ViewGroup decor) {
        IdentityHashMap<View, Boolean> set = new IdentityHashMap<View, Boolean>();
        for (View v : markers) set.put(v, Boolean.TRUE);
        ViewGroup best = null;
        View cursor = markers.get(0);
        while (cursor != null && cursor != decor) {
            if (cursor instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) cursor;
                int hits = countMarkers(g, set, 0);
                if (hits >= 2 && g.getWidth() > 0 && g.getHeight() > 0
                && g.getWidth() < g.getRootView().getWidth() * 0.95f
                && g.getHeight() < g.getRootView().getHeight() * 0.6f) best = g;
                if (hits >= markers.size()) break;
            }
            Object parent = cursor.getParent();
            cursor = parent instanceof View ? (View) parent : null;
        }
        return best;
    }

    private static int countMarkers(View v, IdentityHashMap<View, Boolean> set, int depth) {
        if (v == null || depth > 12) return 0;
        int n = set.get(v) != null ? 1 : 0;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                n += countMarkers(g.getChildAt(i), set, depth + 1);
            }
        }
        return n;
    }

    // ------------------------------------------------------------ 入口

    /** 能加进面板就加进面板（跟着面板一起弹、一起收）。 */
    private static boolean injectRow(Activity act, ViewGroup panel, List<View> markers) {
        synchronized (ROWS) {
            View old = ROWS.get(panel);
            if (old != null && old.getParent() == panel) return true;
        }
        if (!(panel instanceof LinearLayout)) return false;
        LinearLayout list = (LinearLayout) panel;
        if (list.getOrientation() != LinearLayout.VERTICAL) return false;

        try {
            TextView sample = (TextView) markers.get(0);
            float density = act.getResources().getDisplayMetrics().density;
            int height = Math.max((int) (44 * density), sample.getHeight());

            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (18 * density), 0, (int) (18 * density), 0);
            row.setClickable(true);
            row.setBackground(new RowRipple(isDark(act)));

            TextView label = new TextView(act);
            label.setText(TITLE);
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX, sample.getTextSize());
            label.setTextColor(sample.getCurrentTextColor());
            row.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openSettings(v);
                }
            });

            list.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, height));
            synchronized (ROWS) { ROWS.put(panel, row); }
            WechatGlass.log("入口:已插进「＋」面板");
            return true;
        } catch (Throwable t) {
            WechatGlass.warnOnce("row-add", "入口:插进面板失败: " + t);
            return false;
        }
    }

    /** 面板关掉之后把 Dock 放回来（PopupWindow 关闭时 Activity 不一定有布局回调）。 */
    private static void watchPanelClose(final Activity act) {
        synchronized (CLOSE_WATCH) {
            if (CLOSE_WATCH.get(act) != null) return;
            CLOSE_WATCH.put(act, Boolean.TRUE);
        }
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (act.isFinishing() || act.isDestroyed()) {
                        finishWatch(act);
                        return;
                    }
                    View decor = act.getWindow() == null ? null : act.getWindow().getDecorView();
                    if (decor == null) {
                        finishWatch(act);
                        return;
                    }
                    java.util.Set<String> words = new java.util.HashSet<String>();
                    List<View> left = findMarkers(decor, words);
                    if (left.size() < 2 || words.size() < 2) {
                        removeRow();
                        detachPillOnly(act);
                        WxDockController.setCovered(act, false);
                        finishWatch(act);
                        return;
                    }
                } catch (Throwable ignored) {
                }
                MAIN.postDelayed(this, 700L);
            }
        }, 700L);
    }

    private static void finishWatch(Activity act) {
        synchronized (CLOSE_WATCH) { CLOSE_WATCH.remove(act); }
    }

    private static void removeRow() {
        synchronized (ROWS) {
            for (ViewGroup panel : new ArrayList<ViewGroup>(ROWS.keySet())) {
                View row = ROWS.get(panel);
                try {
                    if (row != null && row.getParent() == panel) panel.removeView(row);
                } catch (Throwable ignored) {
                }
            }
            ROWS.clear();
        }
    }

    /** 面板插不进去（不是竖排列表）时：贴着面板下沿浮一颗胶囊，位置跟着面板走。 */
    private static void attachPill(final Activity act, ViewGroup panel) {
        View decor = act.getWindow() == null ? null : act.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) return;
        synchronized (PILLS) {
            View old = PILLS.get(act);
            if (old != null && old.isAttachedToWindow()) {
                position(act, old, panel);
                return;
            }
        }

        float density = act.getResources().getDisplayMetrics().density;
        TextView pill = new TextView(act);
        pill.setText(TITLE);
        pill.setTextSize(14f);
        pill.setGravity(Gravity.CENTER);
        pill.setTextColor(isDark(act) ? 0xFFF2F4F8 : 0xFF16181D);
        pill.setPadding((int) (20 * density), (int) (10 * density),
                (int) (20 * density), (int) (10 * density));
        pill.setBackground(new GlassDrawable(26 * density, isDark(act)));
        pill.setElevation(8f * density);
        pill.setClickable(true);
        pill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSettings(v);
            }
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        try {
            ((ViewGroup) decor).addView(pill, lp);
            synchronized (PILLS) { PILLS.put(act, pill); }
            position(act, pill, panel);
            WechatGlass.log("入口:「＋」面板下沿已挂上胶囊入口");
        } catch (Throwable t) {
            WechatGlass.warnOnce("pill-add", "入口:胶囊挂不上: " + t);
        }
    }

    private static void position(Activity act, View pill, View panel) {
        try {
            View decor = act.getWindow().getDecorView();
            int[] p = new int[2];
            int[] d = new int[2];
            panel.getLocationInWindow(p);
            decor.getLocationInWindow(d);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) pill.getLayoutParams();
            lp.topMargin = p[1] - d[1] + panel.getHeight()
                    + (int) (8 * act.getResources().getDisplayMetrics().density);
            pill.setLayoutParams(lp);
        } catch (Throwable ignored) {
        }
    }

    private static void detachPillOnly(Activity act) {
        View pill;
        synchronized (PILLS) { pill = PILLS.remove(act); }
        if (pill != null) {
            try {
                ViewGroup parent = (ViewGroup) pill.getParent();
                if (parent != null) parent.removeView(pill);
            } catch (Throwable ignored) {
            }
        }
    }

    static void openSettings(View from) {
        Context ctx = from.getContext();
        Activity act = activityOf(ctx);
        if (act != null && SettingsActivity.open(act)) return;
        if (ctx != null) {
            try {
                android.content.Intent i = new android.content.Intent();
                i.setClassName(WechatGlass.PKG, WechatGlass.PKG + ".SettingsActivity");
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return;
            } catch (Throwable ignored) {
            }
        }
        WechatGlass.toast(ctx, "打不开设置页，用桌面那个 wxglass 图标");
    }

    private static Activity activityOf(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return c instanceof Activity ? (Activity) c : null;
    }

    // ------------------------------------------------------------ 弹层玻璃

    private static final WeakHashMap<View, int[]> POPUP_PADDING = new WeakHashMap<View, int[]>();
    private static final WeakHashMap<ViewGroup, View> POPUP_ROW = new WeakHashMap<ViewGroup, View>();

    private static void stylePopup(final PopupWindow pw) {
        if (pw == null) return;
        final View content;
        try {
            content = pw.getContentView();
        } catch (Throwable t) {
            return;
        }
        if (content == null) return;
        content.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!content.isAttachedToWindow()) return;
                    Context ctx = content.getContext();
                    float density = ctx.getResources().getDisplayMetrics().density;
                    boolean dark = isDark(ctx);

                    // 注意：这里**不能**做"处理过一次就跳过"的缓存。
                    // 微信每次弹面板都会把自己的背景重新设回去，跳过第二遍的结果就是
                    // 「第一次是玻璃，后面又变回原生」。每次 show 都重新套一遍。
                    if (content instanceof ViewGroup) {
                        ViewGroup group = (ViewGroup) content;
                        int[] orig = POPUP_PADDING.get(content);
                        if (orig == null) {
                            orig = new int[]{group.getPaddingLeft(), group.getPaddingTop(),
                                    group.getPaddingRight(), group.getPaddingBottom()};
                            POPUP_PADDING.put(content, orig);
                        }
                        int pad = (int) (12 * density);
                        group.setPadding(orig[0] + pad, orig[1] + pad,
                                orig[2] + pad, orig[3] + pad);
                    }
                    // dense = true：底色做实，文字压得住。
                    // 关键：底色深浅跟着**微信自己写的文字颜色**走，而不是只看系统深色开关——
                    // 微信自己的深色模式不一定和系统一致，跟着系统走就会出现
                    // 「白字 + 白底」或者「黑字 + 黑底」，也就是用户说的「看不清」。
                    boolean surfaceDark = dark || hasLightText(content, 0);
                    GlassDrawable glass = new GlassDrawable(20 * density, surfaceDark, true);
                    content.setBackground(glass);
                    try {
                        content.setElevation(12f * density);
                    } catch (Throwable ignored) {
                    }
                    attachPopupBackdrop(content, glass);
                    if (content instanceof ViewGroup) {
                        injectPopupRow((ViewGroup) content, dark, density);
                    }
                } catch (Throwable t) {
                    WechatGlass.warnOnce("popup-style", "弹层玻璃处理失败: " + t);
                }
            }
        });
    }

    /**
     * 面板里的文字是不是浅色（白/浅灰）。
     *
     * 用来决定玻璃底该做实心白还是实心深灰：浅色字配深底，深色字配浅底。
     * 比看系统 uiMode 靠谱得多 —— 微信自己的深色模式是它内部切的。
     */
    private static boolean hasLightText(View root, int depth) {
        if (root == null || depth > 8) return false;
        try {
            if (root instanceof android.widget.TextView) {
                android.widget.TextView tv = (android.widget.TextView) root;
                CharSequence text = tv.getText();
                if (text != null && text.length() > 0 && tv.getCurrentTextColor() != 0) {
                    int color = tv.getCurrentTextColor();
                    int alpha = (color >>> 24) & 0xFF;
                    int r = (color >> 16) & 0xFF;
                    int g = (color >> 8) & 0xFF;
                    int b = color & 0xFF;
                    if (alpha > 0x40) {
                        float lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f;
                        return lum > 0.6f;
                    }
                }
            }
            if (root instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) root;
                for (int i = 0; i < group.getChildCount(); i++) {
                    if (hasLightText(group.getChildAt(i), depth + 1)) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 弹层的玻璃底：把弹层背后那一块截一次，塞给 Drawable，材质和 Dock 一致。 */
    private static void attachPopupBackdrop(final View content, final GlassDrawable glass) {
        if (Build.VERSION.SDK_INT < 26) return;
        final Activity act = activityOf(content.getContext());
        if (act == null || act.getWindow() == null) return;
        try {
            final View decor = act.getWindow().getDecorView();
            final Rect rect = new Rect();
            int[] loc = new int[2];
            content.getLocationInWindow(loc);
            float scale = 1f / 3f;
            rect.set(0, 0, Math.max(1, (int) (content.getWidth() * scale)),
                    Math.max(1, (int) (content.getHeight() * scale)));
            final Bitmap shot = Bitmap.createBitmap(rect.width(), rect.height(),
                    Bitmap.Config.ARGB_8888);
            final Rect src = new Rect(loc[0], loc[1],
                    loc[0] + content.getWidth(), loc[1] + content.getHeight());
            android.view.PixelCopy.request(act.getWindow(), src, shot,
                    new android.view.PixelCopy.OnPixelCopyFinishedListener() {
                        @Override
                        public void onPixelCopyFinished(int result) {
                            if (result == android.view.PixelCopy.SUCCESS) {
                                glass.setBackdrop(shot);
                            } else {
                                shot.recycle();
                            }
                        }
                    }, new Handler(Looper.getMainLooper()));
        } catch (Throwable ignored) {
        }
    }

    /** PopupWindow 形态的「＋」面板：这里只做玻璃底，不再插设置入口。 */
    private static void injectPopupRow(ViewGroup content, boolean dark, float density) {
        if (Boolean.TRUE) return;
        if (content instanceof LinearLayout
                && ((LinearLayout) content).getOrientation() == LinearLayout.VERTICAL) {
            LinearLayout list = (LinearLayout) content;
            View old = POPUP_ROW.get(list);
            if (old != null && old.getParent() == list) return;
            TextView row = new TextView(content.getContext());
            row.setText("  " + TITLE);
            row.setTextSize(15f);
            row.setTextColor(dark ? 0xFFF2F4F8 : 0xFF16181D);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (18 * density), (int) (10 * density),
                    (int) (18 * density), (int) (10 * density));
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openSettings(v);
                }
            });
            list.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (44 * density)));
            POPUP_ROW.put(list, row);
        }
    }

    private static boolean isDark(Context ctx) {
        return WxTheme.isDark(ctx);
    }

    /** 面板里那一行的按压底色（微信自己是灰的，这里给个淡一点的）。 */
    private static final class RowRipple extends Drawable {
        private final int color;

        RowRipple(boolean dark) {
            color = dark ? 0x22FFFFFF : 0x11000000;
        }

        @Override
        public void draw(Canvas canvas) {
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
