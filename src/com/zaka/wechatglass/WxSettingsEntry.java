package com.zaka.wechatglass;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 「我 → 设置」页面里的一条 wxglass 入口。
 *
 * 为什么不继续往「＋」面板里塞：那个面板不是固定的列表，微信每个版本形态都不一样，
 * 「插一行」能不能成都看运气。设置页是真正稳定的列表页，位置也符合直觉
 * （用户自己说的：设置里的分类项插一条）。
 *
 * 做法：不动微信的 adapter（改 adapter 最容易被版本更新搞崩），
 * 而是给列表加一段顶部 padding，把预留出来的那一条的位置让给我们自己画的一行。
 * 视觉上和原生设置项一致，点击直接打开模块设置页。
 */
final class WxSettingsEntry {

    private static final String TITLE = "wxglass 设置";

    private static final WeakHashMap<Activity, View> ROWS = new WeakHashMap<Activity, View>();
    private static final WeakHashMap<View, int[]> PADDING = new WeakHashMap<View, int[]>();
    private static final WeakHashMap<Activity, Boolean> TRIED = new WeakHashMap<Activity, Boolean>();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private WxSettingsEntry() {
    }

    static void hook(ClassLoader cl) {
        String[] pages = {
                "com.tencent.mm.plugin.setting.ui.setting.SettingsUI",
                "com.tencent.mm.plugin.setting.ui.setting.SettingsUIForMine",
                "com.tencent.mm.plugin.setting.ui.setting.SettingsUIForChatting",
        };
        int hooked = 0;
        for (String name : pages) {
            try {
                Class<?> cls = Class.forName(name, false, cl);
                XposedBridge.hookAllMethods(cls, "onResume", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.thisObject instanceof Activity) {
                            schedule((Activity) param.thisObject, 0);
                        }
                    }
                });
                hooked++;
            } catch (Throwable ignored) {
            }
        }
        WechatGlass.log(hooked > 0
                ? "设置入口:已挂上设置页（" + hooked + " 个类）"
                : "设置入口:没找到设置页类，改走 resume 通用匹配");
    }

    /** 只认设置页：类名里带 setting 且是 SettingsUI 这一类。 */
    static boolean isSettingsPage(Activity act) {
        if (act == null) return false;
        try {
            String name = act.getClass().getName();
            return name.endsWith("SettingsUI")
                    || name.endsWith("SettingsUIForMine")
                    || name.endsWith("SettingsUIForChatting")
                    // 有些版本把设置页收进别的包名/内部类，类名里仍有 Setting。
                    || (name.toLowerCase(java.util.Locale.ROOT).contains("setting")
                    && name.endsWith("UI"));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 如果这一页是设置页，就把入口插上（多次调用安全）。 */
    static void maybeInstall(Activity act) {
        if (!isSettingsPage(act)) return;
        install(act);
    }

    private static void schedule(final Activity act, final int attempt) {
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (act.isFinishing() || act.isDestroyed()) return;
                    if (install(act)) return;
                    if (attempt < 10) schedule(act, attempt + 1);
                } catch (Throwable t) {
                    WechatGlass.warnOnce("settings-entry",
                            "设置入口处理失败: " + t);
                }
            }
        }, attempt == 0 ? 80L : 140L);
    }

    private static boolean install(Activity act) {
        View decor = act.getWindow() == null ? null : act.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) return false;

        View existing = ROWS.get(act);
        if (existing != null && existing.isAttachedToWindow()
                && existing.getParent() == decor) {
            return true;
        }

        View list = findList(decor);
        if (list == null || list.getWidth() <= 0) return false;

        float d = act.getResources().getDisplayMetrics().density;
        boolean dark = isDark(act);
        int rowHeight = (int) (52 * d);

        // 给列表顶出一行的位置（只加一次，重复调用不会越加越多）。
        int[] orig = PADDING.get(list);
        if (orig == null) {
            orig = new int[]{list.getPaddingLeft(), list.getPaddingTop(),
                    list.getPaddingRight(), list.getPaddingBottom()};
            PADDING.put(list, orig);
        }
        list.setPadding(orig[0], orig[1] + rowHeight, orig[2], orig[3]);
        if (list instanceof ViewGroup) {
            ((ViewGroup) list).setClipToPadding(false);
        }

        TextView row = buildRow(act, dark, d, rowHeight);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                WxPlusPage.openSettings(v);
            }
        });

        int[] listLoc = new int[2];
        int[] decorLoc = new int[2];
        list.getLocationInWindow(listLoc);
        decor.getLocationInWindow(decorLoc);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rowHeight);
        lp.gravity = Gravity.TOP;
        lp.topMargin = Math.max(0, listLoc[1] - decorLoc[1]);
        // 列表左右如果有留白，跟着对齐。
        lp.leftMargin = listLoc[0] - decorLoc[0];
        lp.rightMargin = Math.max(0, decor.getWidth() - list.getWidth()
                - (listLoc[0] - decorLoc[0]));

        try {
            ((ViewGroup) decor).addView(row, lp);
            ROWS.put(act, row);
            WechatGlass.log("设置入口:已插进设置页（" + rowHeight + "px 高的一行）");
        } catch (Throwable t) {
            WechatGlass.warnOnce("settings-entry-add", "设置入口挂不上: " + t);
            return false;
        }

        // 微信偶尔会重设 padding / 重建列表：过一会儿再看一眼。
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                View r = ROWS.get(act);
                if (r != null && !r.isAttachedToWindow() && !act.isFinishing()) {
                    ROWS.remove(act);
                }
            }
        }, 1500L);
        return true;
    }

    private static TextView buildRow(Activity act, boolean dark, float d, int height) {
        TextView row = new TextView(act);
        row.setText(TITLE);
        row.setTextSize(16f);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (18 * d), 0, (int) (18 * d), 0);
        row.setTextColor(dark ? 0xFFF2F4F8 : 0xFF16181D);
        row.setClickable(true);
        row.setFocusable(true);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dark ? 0xFF2A2C31 : 0xFFFFFFFF);
        bg.setCornerRadius(0f);
        row.setBackground(bg);

        row.setMinimumHeight(height);
        return row;
    }

    /** 找页面里面积最大的那个列表。 */
    private static View findList(View root) {
        ArrayDeque<View> queue = new ArrayDeque<View>();
        IdentityHashMap<View, Boolean> seen = new IdentityHashMap<View, Boolean>();
        queue.add(root);
        View best = null;
        int bestArea = 0;
        int budget = 8000;
        while (!queue.isEmpty() && budget-- > 0) {
            View v = queue.poll();
            if (v == null || seen.put(v, Boolean.TRUE) != null) continue;
            boolean isList = v instanceof ListView;
            if (!isList) {
                String cls = v.getClass().getName();
                isList = cls.contains("RecyclerView") || cls.endsWith("MMListView");
            }
            if (isList && v.isShown() && v.getVisibility() == View.VISIBLE) {
                int area = v.getWidth() * v.getHeight();
                if (area > bestArea) {
                    bestArea = area;
                    best = v;
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    if (c != null && c != best) queue.add(c);
                }
            }
        }
        return best;
    }

    private static boolean isDark(Activity act) {
        return WxTheme.isDark(act);
    }
}
