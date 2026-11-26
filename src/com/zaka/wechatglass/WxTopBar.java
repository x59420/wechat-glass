package com.zaka.wechatglass;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
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

    static void pause(Activity act) {
        TopLayer layer;
        synchronized (LAYERS) { layer = LAYERS.get(act); }
        if (layer != null) layer.setActive(false);
    }

    static void resume(Activity act) {
        TopLayer layer;
        synchronized (LAYERS) { layer = LAYERS.get(act); }
        if (layer != null) layer.setActive(true);
    }

    // ------------------------------------------------------------ 主流程

    private static void apply(ViewGroup decor, Activity act) {
        int w = decor.getWidth();
        int h = decor.getHeight();
        if (w < 200 || h < 400) return;
        float density = act.getResources().getDisplayMetrics().density;
        boolean dark = isDark(act);

        List<View> icons = findIcons(decor, w, h, density);
        if (icons.size() < 2) {
            // 认不到两颗就不动：宁可没效果，也不能把顶栏改成四不像。
            return;
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
        if (union.width() <= 0 || union.height() <= 0) return;

        int padH = (int) (13 * density);
        int padV = (int) (8 * density);
        Rect capsule = new Rect(union.left - padH, union.top - padV,
                union.right + padH, union.bottom + padV);

        TopLayer layer;
        synchronized (LAYERS) { layer = LAYERS.get(act); }
        if (layer == null || layer.getParent() == null) {
            layer = new TopLayer(act.getApplicationContext());
            try {
                decor.addView(layer, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            } catch (Throwable t) {
                WechatGlass.warnOnce("topbar-add", "顶栏玻璃层挂不上: " + t);
                return;
            }
            synchronized (LAYERS) { LAYERS.put(act, layer); }
            WechatGlass.log("顶栏:玻璃胶囊已建立");
        }
        layer.bind(act, dark, capsule, icons, union);
        tintTitleBar(decor, act, capsule, w, density, dark);
    }

    /** 顶栏换成从上到下的透明渐变（只在标题栏那一条，不动内容区）。 */
    private static void tintTitleBar(ViewGroup decor, Activity act, Rect capsule, int screenWidth,
                                     float density, boolean dark) {
        // 之前那一版是给微信的标题栏容器换背景，容易被它自己重新 setBackground 顶掉，
        // 也会影响别的页面。现在渐变画在我们自己的层里，微信一个控件都不用动。
    }

    private static boolean isDark(Activity act) {
        try {
            return (act.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 右上角那两颗图标（搜索 / ＋）。只认最上面一带、右半边、尺寸像图标的 ImageView。 */
    private static List<View> findIcons(ViewGroup decor, int w, int h, float density) {
        List<View> found = new ArrayList<View>();
        ArrayDeque<View> queue = new ArrayDeque<View>();
        IdentityHashMap<View, Boolean> seen = new IdentityHashMap<View, Boolean>();
        queue.add(decor);
        int budget = 4000;
        int[] loc = new int[2];

        while (!queue.isEmpty() && budget-- > 0) {
            View v = queue.poll();
            if (v == null || seen.put(v, Boolean.TRUE) != null) continue;
            if (v == decor) {
                // 跳过
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    if (c != null && !(c instanceof TopLayer)) queue.add(c);
                }
            }
            if (!(v instanceof ImageView) || !v.isShown()) continue;
            if (v.getAlpha() < 0.5f) continue;
            int vw = v.getWidth();
            int vh = v.getHeight();
            if (vw < 14 * density || vw > 64 * density) continue;
            if (vh < 14 * density || vh > 64 * density) continue;
            v.getLocationInWindow(loc);
            float cx = loc[0] + vw / 2f;
            float cy = loc[1] + vh / 2f;
            if (cx < w * 0.55f) continue;   // 只要右半边
            if (cy > h * 0.22f) continue;   // 只要最上面那一带
            if (v.getAlpha() < 0.2f) continue;
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
        // 最右边两颗就是搜索和「＋」。
        return found.size() > 2 ? new ArrayList<View>(found.subList(found.size() - 2, found.size()))
                : found;
    }

    // ------------------------------------------------------------ 覆盖层

    /** 全屏透明的覆盖层：画玻璃 + 顶栏渐变 + 两颗图标，只吃胶囊范围内的触摸。 */
    static final class TopLayer extends FrameLayout {

        private final GlassCapsule glass;
        private final ImageView[] slots = new ImageView[2];
        private final View[] natives = new View[2];
        private final Rect scrimBounds = new Rect();
        private final Paint scrim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Activity host;
        private boolean dark;

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

        void bind(Activity act, boolean isDark, Rect capsuleInWindow, List<View> icons,
                  Rect unionInWindow) {
            host = act;
            dark = isDark;
            setActive(true);
            glass.bind(act, capsuleInWindow);
            glass.setDark(isDark);

            int[] self = new int[2];
            getLocationInWindow(self);
            scrimBounds.set(0, 0, getWidth(),
                    Math.max(0, capsuleInWindow.bottom - self[1] + dp(10)));
            invalidate();

            for (int i = 0; i < 2; i++) {
                View nativeIcon = i < icons.size() ? icons.get(i) : null;
                natives[i] = nativeIcon;
                ImageView slot = slots[i];
                if (nativeIcon == null) {
                    slot.setVisibility(GONE);
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
                // 图标颜色跟着微信走：原生是白色图标就保持白色，深色底别乱改色。
                slot.setColorFilter(null);
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

        void setActive(boolean active) {
            glass.setActive(active);
        }

        void releaseAll() {
            setActive(false);
            releaseNatives();
            glass.release();
            host = null;
        }

        /** 把点击原样转发给微信那颗原生按钮。 */
        private void forward(int index) {
            View target = index >= 0 && index < natives.length ? natives[index] : null;
            if (target == null) return;
            try {
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
            // 顶栏透明渐变：只铺最上面那一条，往下淡出，内容从状态栏下面透上来。
            if (scrimBounds.height() > 0) {
                scrim.setShader(new LinearGradient(0, 0, 0, scrimBounds.height(),
                        dark ? new int[]{0x59000000, 0x26000000, 0x00000000}
                                : new int[]{0x5CFFFFFF, 0x24FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, getWidth(), scrimBounds.height(), scrim);
                scrim.setShader(null);
            }
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

        private Bitmap iconBitmap(View v) {
            try {
                if (!(v instanceof ImageView)) return null;
                Drawable d = ((ImageView) v).getDrawable();
                return WxTabBar.drawableToBitmap(d);
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
