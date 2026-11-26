package com.zaka.wechatglass;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

/**
 * 一颗悬浮液态玻璃胶囊（顶栏 / 弹层共用）。
 *
 * 为什么不直接用 Dock 那套 Compose 渲染器：Dock 是自己接管整条底栏、图标也自己画；
 * 顶栏这颗胶囊只有几十 dp 见方，再挂一整套 Compose 进去，内存和帧都白搭。
 * 这里把 Dock 的**材质参数**照搬（表面色、顶部高光、底部内阴影、圆角亮边），
 * 用一块 View 自己画：
 *
 *   采样真实画面 -> 低分辨率抓一次（放大后天然就是柔和模糊）-> 叠表面色 -> 亮边 -> 内阴影
 *
 * 采的是 Activity 的 android.R.id.content（我们的覆盖层是它的兄弟节点），
 * 所以画出来的是玻璃**背后**的东西，不会把玻璃自己再画进去，也就没有重影。
 * 采样 400ms 一次：微信首页这块背景本来就是静的，没必要每帧抓。
 */
final class GlassCapsule extends View {

    private static final long CAPTURE_MS = 400L;

    private final Handler main = new Handler(Looper.getMainLooper());

    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint surfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF capsule = new RectF();
    private final RectF dst = new RectF();
    private final Path clip = new Path();

    private float radius;
    private boolean dark;
    private boolean pillShape = true;

    private View contentRoot;
    private Bitmap sample;
    private boolean active;
    private long lastCapture;
    /** 只有页面滚了 / 布局变了才重新采样：每 400ms 无条件重画整棵页面是卡顿的根源。 */
    private boolean dirty = true;
    private long lastForceAt;
    private View watchedRoot;
    private final android.view.ViewTreeObserver.OnScrollChangedListener scrollWatcher =
            new android.view.ViewTreeObserver.OnScrollChangedListener() {
                @Override
                public void onScrollChanged() {
                    dirty = true;
                }
            };
    private final Rect captureBand = new Rect();

    GlassCapsule(Context context) {
        super(context);
        setWillNotDraw(false);
        setBackgroundColor(Color.TRANSPARENT);
    }

    // ------------------------------------------------------------ 外部设置

    /** 绑到宿主页面：content 用来采样，region 是胶囊在**窗口坐标**里的位置。 */
    void bind(Activity activity, Rect regionInWindow) {
        View content = activity == null ? null : activity.findViewById(android.R.id.content);
        if (content != contentRoot) {
            detachScrollWatcher();
            contentRoot = content;
            attachScrollWatcher(content);
            if (sample != null) {
                sample.recycle();
                sample = null;
            }
            dirty = true;
        }
        setRegion(regionInWindow);
    }

    /** 胶囊位置（窗口坐标）。 */
    void setRegion(Rect regionInWindow) {
        if (regionInWindow == null) return;
        int[] self = new int[2];
        getLocationInWindow(self);
        capsule.set(regionInWindow.left - self[0], regionInWindow.top - self[1],
                regionInWindow.right - self[0], regionInWindow.bottom - self[1]);
        if (pillShape) radius = capsule.height() / 2f;
        dirty = true;
        invalidate();
    }

    void setCornerRadius(float r) {
        pillShape = false;
        radius = r;
        invalidate();
    }

    void setDark(boolean dark) {
        if (this.dark == dark) return;
        this.dark = dark;
        invalidate();
    }

    /** 开始 / 停止按 400ms 采一次背景。页面切走就停。 */
    void setActive(boolean value) {
        if (active == value) return;
        active = value;
        main.removeCallbacks(capture);
        if (value) {
            lastCapture = 0L;
            dirty = true;
            main.post(capture);
        }
    }

    boolean hasFrame() {
        return sample != null && !sample.isRecycled();
    }

    void release() {
        active = false;
        main.removeCallbacks(capture);
        detachScrollWatcher();
        if (sample != null) {
            sample.recycle();
            sample = null;
        }
        contentRoot = null;
    }

    // ------------------------------------------------------------ 采样

    /** 页面滚动 / 布局变化时把「该重画了」的标记打上。 */
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

    private final Runnable capture = new Runnable() {
        @Override
        public void run() {
            if (!active) return;
            long now = android.os.SystemClock.uptimeMillis();
            // 只在「页面滚过 / 布局变了」或者兜底 1.2 秒到点时重画一次。
            // 以前是无条件每 400ms 把整棵内容树软件重绘一遍 —— 首页越复杂越卡。
            boolean force = now - lastForceAt >= 1200L;
            if (dirty || force) {
                dirty = false;
                lastForceAt = now;
                lastCapture = now;
                try {
                    grab();
                } catch (Throwable ignored) {
                }
            }
            main.postDelayed(this, CAPTURE_MS);
        }
    };

    /**
     * 把 content 按很小的比例画进一张位图。
     *
     * 只抓胶囊上下各留 24dp 的那一条：位图只有一条那么高，微信首页再复杂也只是画一条。
     */
    private void grab() {
        View root = contentRoot;
        if (root == null || root.getWidth() <= 0 || root.getHeight() <= 0) return;
        if (capsule.width() < 1 || capsule.height() < 1) return;

        int bandTop = Math.max(0, (int) capsule.top - dp(24));
        int bandBottom = Math.min(root.getHeight(), (int) capsule.bottom + dp(24));
        int bandHeight = bandBottom - bandTop;
        if (bandHeight <= 0) return;
        captureBand.set(0, bandTop, root.getWidth(), bandBottom);

        float scale = Math.max(1f / 16f, Math.min(1f / 4f,
                110f / Math.max(1, root.getWidth())));
        int w = Math.max(8, (int) (root.getWidth() * scale));
        int h = Math.max(4, (int) (bandHeight * scale));
        try {
            if (sample == null || sample.getWidth() != w || sample.getHeight() != h) {
                if (sample != null) sample.recycle();
                sample = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            sample.eraseColor(Color.TRANSPARENT);
            Canvas canvas = new Canvas(sample);
            canvas.scale(scale, scale);
            canvas.translate(0, -bandTop);
            canvas.clipRect(0, bandTop, root.getWidth(), bandBottom);
            root.draw(canvas);
        } catch (Throwable t) {
            if (sample != null) {
                sample.recycle();
                sample = null;
            }
            return;
        }
        postInvalidateOnAnimation();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------ 绘制

    @Override
    protected void onDraw(Canvas canvas) {
        if (capsule.width() < 2 || capsule.height() < 2) return;

        float r = pillShape ? capsule.height() / 2f : Math.min(radius, capsule.height() / 2f);
        clip.reset();
        clip.addRoundRect(capsule, r, r, Path.Direction.CW);

        int save = canvas.save();
        canvas.clipPath(clip);

        // 1) 玻璃背后那块画面（低分辨率放大后本身就是模糊）
        if (sample != null && !sample.isRecycled() && contentRoot != null) {
            int[] self = new int[2];
            getLocationInWindow(self);
            int[] contentLoc = new int[2];
            contentRoot.getLocationInWindow(contentLoc);
            float dx = contentLoc[0] - self[0];
            float dy = contentLoc[1] - self[1] + captureBand.top;
            dst.set(dx, dy, dx + contentRoot.getWidth(),
                    dy + (captureBand.bottom - captureBand.top));
            canvas.drawBitmap(sample, null, dst, imagePaint);
        }

        // 2) 表面色：浅色上薄薄一层白，深色上压暗的夜色——这就是"材质"的底色
        surfacePaint.setShader(null);
        surfacePaint.setColor(dark ? 0x40101216 : 0x40FFFFFF);
        canvas.drawRect(capsule, surfacePaint);

        // 3) 顶部泛光 + 底部压暗：玻璃厚度的错觉
        surfacePaint.setShader(new LinearGradient(0, capsule.top, 0, capsule.bottom,
                new int[]{dark ? 0x33FFFFFF : 0x4DFFFFFF,
                        dark ? 0x0DFFFFFF : 0x14FFFFFF,
                        dark ? 0x33000000 : 0x12000000},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(capsule, surfacePaint);
        surfacePaint.setShader(null);

        // 4) 底部内阴影：让胶囊有一点厚度
        shadePaint.setShader(new LinearGradient(0, capsule.bottom - dp(6), 0, capsule.bottom,
                new int[]{0x00000000, dark ? 0x33000000 : 0x1F000000},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(capsule, shadePaint);
        shadePaint.setShader(null);

        // 5) 折射：贴着圆角的一圈亮边（比 Dock 弱，背景是静的，太重就假）
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(Math.max(1f, dp(1.1f)));
        rimPaint.setShader(new LinearGradient(0, capsule.top, 0, capsule.bottom,
                new int[]{0xA6FFFFFF, dark ? 0x1FFFFFFF : 0x59FFFFFF, 0x0DFFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(capsule, r, r, rimPaint);
        rimPaint.setShader(null);

        canvas.restoreToCount(save);
    }

    // ------------------------------------------------------------ 触摸

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!inside(event.getX(), event.getY())) return false;
        return super.onTouchEvent(event);
    }

    boolean inside(float x, float y) {
        return capsule.contains(x, y);
    }
}
