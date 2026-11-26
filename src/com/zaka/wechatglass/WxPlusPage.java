package com.zaka.wechatglass;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 微信里的设置入口 + 「＋」弹层的玻璃底。
 *
 * 以前的做法是「往那一页的 ListView 末尾接一行」，实际上微信「＋」面板不是 ListView，
 * 所以那一行永远插不进去——这就是设置入口一直看不见的原因。
 *
 * 现在改成两条都不依赖微信内部结构的做法：
 *   1. 页面识别按**文字**来：decor 里出现「扫一扫 / 收付款 / 添加朋友」这些条目，
 *      就认定这是「＋」面板，直接在最上层浮一个自己的胶囊按钮；
 *   2. 「＋」面板如果是 PopupWindow 弹出来的，就挂在它的 contentView 上。
 * 两种都是自己画的 View，微信换控件、换类名、换版本都不影响。
 */
final class WxPlusPage {

    /** 用来认「＋」面板的条目文字：任意命中一个就算。 */
    private static final String[] MARKERS = {"扫一扫", "收付款", "添加朋友", "摇一摇"};

    private static final String TITLE = "wxglass 液态玻璃";

    private static final WeakHashMap<Activity, View> PILLS = new WeakHashMap<Activity, View>();
    private static final WeakHashMap<View, Boolean> STYLED = new WeakHashMap<View, Boolean>();

    private WxPlusPage() {
    }

    // ------------------------------------------------------------ 挂勾

    static void hook(ClassLoader cl) {
        // 1) 「＋」面板是独立页面时：页面一显示就看一眼。
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
                            act.getWindow().getDecorView().postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    attachTo(act);
                                }
                            }, 400L);
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }

        // 2) 「＋」面板是 PopupWindow 时：它一显示就拿到 contentView。
        try {
            XposedBridge.hookAllMethods(PopupWindow.class, "showAsDropDown", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    stylePopup((PopupWindow) param.thisObject);
                }
            });
            XposedBridge.hookAllMethods(PopupWindow.class, "showAtLocation", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    stylePopup((PopupWindow) param.thisObject);
                }
            });
            WechatGlass.log("入口:已挂上弹层监听");
        } catch (Throwable t) {
            WechatGlass.warnOnce("popup-hook", "入口:挂弹层监听失败: " + t);
        }
    }

    /** 任何页面显示时都问一句：这是不是「＋」面板。 */
    static void tryAttach(Activity act) {
        if (act == null) return;
        try {
            if (WxDockController.isHost(act)) return;
            View decor = act.getWindow() == null ? null : act.getWindow().getDecorView();
            if (decor == null) return;
            if (findMarker(decor) == null) return;
            attachTo(act);
        } catch (Throwable ignored) {
        }
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
    }

    // ------------------------------------------------------------ 自己的那一颗胶囊

    private static void attachTo(final Activity act) {
        if (act == null || act.isFinishing()) return;
        synchronized (PILLS) {
            View old = PILLS.get(act);
            if (old != null && old.isAttachedToWindow()) return;
        }
        View decor = act.getWindow() == null ? null : act.getWindow().getDecorView();
        if (!(decor instanceof FrameLayout)) {
            // 有些页面 decor 不是 FrameLayout：直接挂到 android.R.id.content 的父层。
            View content = act.findViewById(android.R.id.content);
            if (content != null && content.getParent() instanceof FrameLayout) {
                decor = (ViewGroup) content.getParent();
            }
        }
        if (!(decor instanceof ViewGroup)) return;

        float density = act.getResources().getDisplayMetrics().density;
        LinearLayout pill = new LinearLayout(act);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding((int) (20 * density), (int) (11 * density),
                (int) (20 * density), (int) (11 * density));
        pill.setBackground(new GlassBg(density, 26f, isDark(act)));
        pill.setClickable(true);
        pill.setElevation(6f * density);

        TextView label = new TextView(act);
        label.setText(TITLE);
        label.setTextSize(15f);
        label.setTextColor(isDark(act) ? 0xFFF2F4F8 : 0xFF16181D);
        pill.addView(label);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = (int) (34 * density);

        pill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!SettingsActivity.open(act)) {
                    WechatGlass.toast(act, "打不开设置页，用桌面那个 wxglass 图标");
                }
            }
        });

        try {
            ((ViewGroup) decor).addView(pill, lp);
            synchronized (PILLS) { PILLS.put(act, pill); }
            WechatGlass.log("入口:「＋」面板已挂上 wxglass 胶囊入口");
        } catch (Throwable t) {
            WechatGlass.warnOnce("pill-add", "入口:胶囊挂不上: " + t);
        }
    }

    // ------------------------------------------------------------ 弹层玻璃

    private static void stylePopup(final PopupWindow pw) {
        if (pw == null) return;
        final View content;
        try {
            content = pw.getContentView();
        } catch (Throwable t) {
            return;
        }
        if (content == null) return;
        // 等它 attach 完再改背景，否则会被自己的布局覆盖掉。
        content.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!content.isAttachedToWindow()) return;
                    Context ctx = content.getContext();
                    float density = ctx.getResources().getDisplayMetrics().density;
                    boolean dark = isDark(ctx);

                    synchronized (STYLED) {
                        if (STYLED.get(content) != null) return;
                        STYLED.put(content, Boolean.TRUE);
                    }

                    // 弹层自己的胶囊底：圆角 + 半透明玻璃，图标和点击完全不碰。
                    if (content instanceof ViewGroup) {
                        int pad = (int) (10 * density);
                        ((ViewGroup) content).setPadding(pad, pad, pad, pad);
                    }
                    content.setBackground(new GlassBg(density, 18f, dark));
                    content.setElevation(10f * density);

                    // 是「＋」面板就顺手把入口挂上去。
                    if (findMarker(content) != null) {
                        attachToPopup(content);
                    }
                } catch (Throwable t) {
                    WechatGlass.warnOnce("popup-style", "弹层美化失败（不影响使用）: " + t);
                }
            }
        });
    }

    private static void attachToPopup(View content) {
        if (!(content instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) content;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i) != null && WechatGlass.TAG.equals(group.getChildAt(i).getTag())) {
                return;
            }
        }
        Context ctx = content.getContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        boolean dark = isDark(ctx);

        TextView entry = new TextView(ctx);
        entry.setText(TITLE);
        entry.setTextSize(14f);
        entry.setTextColor(dark ? 0xFFF2F4F8 : 0xFF16181D);
        entry.setGravity(Gravity.CENTER);
        entry.setPadding(0, (int) (12 * density), 0, (int) (12 * density));
        entry.setBackground(new GlassBg(density, 14f, dark));
        entry.setTag(WechatGlass.TAG);
        entry.setClickable(true);
        entry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Activity act = activityOf(v);
                if (act != null && !SettingsActivity.open(act)) {
                    WechatGlass.toast(act, "打不开设置页，用桌面那个 wxglass 图标");
                }
            }
        });
        try {
            group.addView(entry, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            WechatGlass.log("入口:「＋」弹层已挂上 wxglass 一行");
        } catch (Throwable t) {
            WechatGlass.warnOnce("popup-entry", "入口:弹层挂一行失败: " + t);
        }
    }

    // ------------------------------------------------------------ 工具

    private static Activity activityOf(View v) {
        try {
            android.content.Context c = v.getContext();
            while (c instanceof android.content.ContextWrapper) {
                if (c instanceof Activity) return (Activity) c;
                c = ((android.content.ContextWrapper) c).getBaseContext();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 页面里有没有「＋」面板的条目文字。 */
    private static View findMarker(View root) {
        ArrayDeque<View> queue = new ArrayDeque<View>();
        IdentityHashMap<View, Boolean> seen = new IdentityHashMap<View, Boolean>();
        queue.add(root);
        int budget = 3000;
        Set<String> hits = new LinkedHashSet<String>();
        while (!queue.isEmpty() && budget-- > 0) {
            View v = queue.poll();
            if (v == null || seen.put(v, Boolean.TRUE) != null) continue;
            if (v instanceof TextView) {
                CharSequence text = ((TextView) v).getText();
                if (text != null) {
                    String s = text.toString();
                    for (String m : MARKERS) {
                        if (s.contains(m)) hits.add(m);
                    }
                    if (hits.size() >= 2) return v;
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    if (g.getChildAt(i) != null) queue.add(g.getChildAt(i));
                }
            }
        }
        return hits.size() >= 2 ? root : null;
    }

    private static boolean isDark(Context ctx) {
        try {
            Configuration c = ctx.getResources().getConfiguration();
            return (c.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 圆角玻璃底：半透明填充 + 顶部高光 + 细描边。和 Dock 同一套观感。 */
    static final class GlassBg extends Drawable {

        private final float density;
        private final float radiusDp;
        private final boolean dark;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        GlassBg(float density, float radiusDp, boolean dark) {
            this.density = density;
            this.radiusDp = radiusDp;
            this.dark = dark;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(dark ? 0xE01B202A : 0xE6F7F9FC);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(1f, density));
            stroke.setColor(dark ? 0x3DFFFFFF : 0x2E000000);
            sheen.setStyle(Paint.Style.STROKE);
            sheen.setStrokeWidth(Math.max(1f, density * 1.2f));
            sheen.setColor(dark ? 0x33FFFFFF : 0x66FFFFFF);
        }

        @Override
        public void draw(Canvas canvas) {
            RectF r = rect;
            r.set(getBounds());
            float radius = radiusDp * density;
            r.inset(0.5f * density, 0.5f * density);
            canvas.drawRoundRect(r, radius, radius, fill);
            canvas.drawRoundRect(r, radius, radius, stroke);
            RectF top = new RectF(r);
            top.bottom = r.top + Math.min(r.height() * 0.5f, radius);
            canvas.drawRoundRect(top, radius, radius, sheen);
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            fill.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
