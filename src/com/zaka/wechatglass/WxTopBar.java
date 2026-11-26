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

        // 「我」那一页右上角本来就没有搜索 / ＋，也不该被我们碰。
        // 微信首页 = 前三个 tab（微信 / 通讯录 / 发现），第四个是「我」。
        if (isMeTab(decor, act)) {
            release(act);
            return;
        }

        List<View> icons = findIcons(decor, act, w, h, density);
        if (icons.isEmpty()) {
            // 认不到就不动：宁可没效果，也不能把顶栏改成四不像。
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

    /** 「我」tab 是否为当前页。 */
    private static boolean isMeTab(ViewGroup decor, Activity act) {
        try {
            ViewGroup bar = WxTabBar.find(decor, act);
            if (bar == null) return false;
            List<View> items = WxTabBar.items(bar);
            if (items == null || items.size() < 4) return false;
            return WxTabBar.selectedIndex(items) == 3;
        } catch (Throwable t) {
            return false;
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

        /** 顶栏那条模糊的刷新节奏：只抓窄窄一条，300ms 一次足够。 */
        private final Runnable topCapture = new Runnable() {
            @Override
            public void run() {
                if (!active) return;
                try {
                    grabTop();
                } catch (Throwable ignored) {
                }
                postDelayed(this, 300L);
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
            int bandH = Math.min(root.getHeight(),
                    Math.max(dp(110), scrimBounds.height() + dp(14)));
            if (bandH <= 2) return;
            float scale = Math.max(1f / 16f,
                    Math.min(1f / 4f, 130f / Math.max(1, root.getWidth())));
            int w = Math.max(8, (int) (root.getWidth() * scale));
            int h = Math.max(4, (int) (bandH * scale));
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            try {
                Canvas c = new Canvas(bmp);
                c.scale(scale, scale);
                c.clipRect(0, 0, root.getWidth(), bandH);
                root.draw(c);

                Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
                mask.setShader(new LinearGradient(0, 0, 0, bandH,
                        new int[]{0xFFFFFFFF, 0xF2FFFFFF, 0x66FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.42f, 0.78f, 1f}, Shader.TileMode.CLAMP));
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
                contentRoot = content;
                if (topSample != null) {
                    topSample.recycle();
                    topSample = null;
                }
            }

            int[] self = new int[2];
            getLocationInWindow(self);
            // 渐变模糊带：从屏幕顶端一直铺到胶囊下沿再往外散一点。
            int bandBottom = Math.max(capsuleInWindow.bottom - self[1] + dp(16),
                    statusBarHeight(act) + dp(64));
            scrimBounds.set(0, 0, getWidth(), Math.max(0, bandBottom));
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

        void setActive(boolean value) {
            active = value;
            glass.setActive(value);
            removeCallbacks(topCapture);
            if (value) {
                post(topCapture);
            }
        }

        void releaseAll() {
            setActive(false);
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
            // 顶栏渐变模糊：把背后那一条画面以低分辨率铺上来（放大本身就是模糊），
            // 再用 alpha 渐变让它从上往下自然淡出。
            if (scrimBounds.height() > 0) {
                if (topSample != null && !topSample.isRecycled()) {
                    scrimDst.set(0, 0, getWidth(),
                            Math.min(getHeight(), scrimBounds.height() + dp(10)));
                    canvas.drawBitmap(topSample, null, scrimDst, topPaint);
                }
                // 再压一层很淡的雾，纯白 / 纯黑背景上也有层次，但不会糊住标题。
                scrim.setShader(new LinearGradient(0, 0, 0, scrimBounds.height(),
                        dark ? new int[]{0x33000000, 0x14000000, 0x00000000}
                                : new int[]{0x2EFFFFFF, 0x12FFFFFF, 0x00FFFFFF},
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
