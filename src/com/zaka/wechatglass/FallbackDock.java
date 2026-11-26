package com.zaka.wechatglass;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * 兜底玻璃层：纯 Java + Canvas，不依赖 Compose / Miuix。
 *
 * 用来兜住一种情况：模块进到微信进程里以后，隔离类加载器那套 Compose 渲染器
 * 起不来（宿主 androidx 撞版本、native 库解不出来等等）。那种时候如果什么都不做，
 * 用户看到的就是「模块装了跟没装一样」——这是最糟糕的结果。
 *
 * 这里画的是同一种形状：贴底的胶囊、圆角、背景映射下面那层画面、白色高光描边、
 * 图标 + 文字 + 选中态。效果比 Miuix 那版朴素（折射/色散没有），但保证看得见、
 * 点得动，交互照样转发给微信原生底栏。
 */
final class FallbackDock implements DockLayer {

    private final GlassView glass;
    private final Consumer<String> log;

    private FallbackDock(GlassView glass, Consumer<String> log) {
        this.glass = glass;
        this.log = log;
    }

    static FallbackDock create(Context context, Consumer<String> log, IntConsumer onTab) {
        try {
            GlassView view = new GlassView(context, log, onTab);
            log.accept("兜底渲染器已启动（Compose 渲染器这次的加载没成功，先用 Canvas 版）");
            return new FallbackDock(view, log);
        } catch (Throwable t) {
            WechatGlass.warn("兜底渲染器创建失败: " + t);
            return null;
        }
    }

    @Override
    public View view() {
        return glass;
    }

    @Override
    public void setGeometry(Rect region, Rect tabs, Rect plus) {
        glass.setGeometry(region, tabs);
    }

    @Override
    public void setBackdrop(Bitmap bitmap) {
        glass.setBackdrop(bitmap);
    }

    @Override
    public void clearBackdrop() {
        glass.setBackdrop(null);
    }

    @Override
    public void setAppearance(int alphaPercent, boolean hideBackground, int themeMode) {
        glass.setAppearance(alphaPercent, hideBackground, themeMode);
    }

    @Override
    public void setTabs(String[] labels, Bitmap[] icons, int selected) {
        glass.setTabs(labels, icons, selected);
    }

    @Override
    public boolean isBroken() {
        return false;
    }

    @Override
    public boolean hasDrawn() {
        return glass.hasDrawn;
    }

    @Override
    public void dispose() {
        glass.disposed = true;
    }

    // ---------------------------------------------------------------- 画布

    private static final class GlassView extends View {

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF box = new RectF();
        private final RectF iconBox = new RectF();

        private final Consumer<String> log;
        private final IntConsumer onTab;

        private Bitmap raw;
        private Bitmap blurred;
        private Rect tabs = new Rect();
        private String[] labels = new String[0];
        private Bitmap[] icons = new Bitmap[0];
        private int selected;
        private int alphaPercent = 82;
        private boolean hideBackground;
        private boolean dark = true;
        private float downX = -1f;
        private float downY = -1f;

        boolean hasDrawn;
        boolean disposed;

        GlassView(Context context, Consumer<String> log, IntConsumer onTab) {
            super(context);
            this.log = log;
            this.onTab = onTab;
            setWillNotDraw(false);
            text.setTextAlign(Paint.Align.CENTER);
            stroke.setStyle(Paint.Style.STROKE);
        }

        void setGeometry(Rect region, Rect tabs) {
            if (tabs != null) this.tabs.set(tabs);
            invalidate();
        }

        void setBackdrop(Bitmap bitmap) {
            if (bitmap == raw) return;
            raw = bitmap;
            blurred = null;
            invalidate();
        }

        void setAppearance(int alphaPercent, boolean hideBackground, int themeMode) {
            this.alphaPercent = alphaPercent;
            this.hideBackground = hideBackground;
            this.dark = themeMode != 1;
            invalidate();
        }

        void setTabs(String[] labels, Bitmap[] icons, int selected) {
            this.labels = labels == null ? new String[0] : labels;
            this.icons = icons == null ? new Bitmap[0] : icons;
            this.selected = selected;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (tabs.width() <= 0 || tabs.height() <= 0) return;

            float radius = Math.min(tabs.height() / 2f, tabs.width() / 8f);
            box.set(tabs);
            clip.reset();
            clip.addRoundRect(box, radius, radius, Path.Direction.CW);

            float alpha = Math.max(0.2f, Math.min(1f, alphaPercent / 100f));

            canvas.save();
            canvas.clipPath(clip);
            Bitmap bg = hideBackground ? null : blurredBitmap();
            if (bg != null) {
                canvas.drawBitmap(bg, null, box, image);
                // 蒙一层，保证图标和文字在亮画面上也看得清
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(dark ? 0x33000000 : 0x22FFFFFF);
                canvas.drawRect(box, fill);
            } else {
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(dark ? 0xE6262626 : 0xE6F2F2F2);
                canvas.drawRoundRect(box, radius, radius, fill);
            }
            // 上亮下暗，做出玻璃的体积感
            fill.setShader(new android.graphics.LinearGradient(
                    0f, box.top, 0f, box.bottom,
                    new int[]{dark ? 0x33FFFFFF : 0x55FFFFFF, 0x00000000},
                    null, android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRoundRect(box, radius, radius, fill);
            fill.setShader(null);

            // 透明度按设置来。必须在 clip 里面做——出了 clip 这层会涂满整块屏幕。
            if (alpha < 0.99f) {
                canvas.drawColor(Color.argb((int) ((1f - alpha) * 255), 0, 0, 0));
            }
            canvas.restore();

            stroke.setStrokeWidth(dp(1f));
            stroke.setColor(0x40FFFFFF);
            canvas.drawRoundRect(box, radius, radius, stroke);

            drawTabs(canvas);

            hasDrawn = true;
        }

        private void drawTabs(Canvas canvas) {
            int count = Math.max(1, labels.length);
            if (count == 0) return;
            float cell = tabs.width() / (float) count;
            float iconSize = Math.min(dp(24f), tabs.height() * 0.42f);
            float labelSize = Math.min(dp(11f), tabs.height() * 0.22f);
            text.setTextSize(labelSize);

            for (int i = 0; i < count; i++) {
                float cx = tabs.left + cell * (i + 0.5f);
                boolean on = i == selected;
                Bitmap icon = i < icons.length ? icons[i] : null;
                float centerY = tabs.centerY() - (labelSize > 0 ? labelSize * 0.2f : 0f);

                if (icon != null && !icon.isRecycled()) {
                    float half = iconSize / 2f;
                    iconBox.set(cx - half, centerY - half, cx + half, centerY + half);
                    image.setAlpha(on ? 255 : 190);
                    canvas.drawBitmap(icon, null, iconBox, image);
                    image.setAlpha(255);
                }

                String label = i < labels.length ? labels[i] : "";
                if (label != null && label.length() > 0) {
                    text.setFakeBoldText(on);
                    text.setColor(dark ? (on ? 0xFFFFFFFF : 0xCCFFFFFF)
                            : (on ? 0xFF000000 : 0xAA000000));
                    canvas.drawText(label, cx, tabs.bottom - labelSize * 0.55f, text);
                }
            }
        }

        /** 1/12 缩放再拉回来，够用的模糊，比 RenderScript 省事也不会在宿主里崩。 */
        private Bitmap blurredBitmap() {
            Bitmap src = raw;
            if (src == null || src.isRecycled() || src.getWidth() <= 0 || src.getHeight() <= 0) {
                return null;
            }
            if (blurred != null && !blurred.isRecycled()) return blurred;
            try {
                int w = Math.max(1, src.getWidth() / 12);
                int h = Math.max(1, src.getHeight() / 12);
                blurred = Bitmap.createScaledBitmap(src, w, h, true);
                return blurred;
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    return inside(downX, downY);
                case MotionEvent.ACTION_UP:
                    if (downX >= 0 && inside(downX, downY) && inside(event.getX(), event.getY())) {
                        int count = Math.max(1, labels.length);
                        float cell = tabs.width() / (float) count;
                        int index = (int) ((event.getX() - tabs.left) / cell);
                        if (index >= 0 && index < count) {
                            log.accept("兜底 Dock 点击第 " + index + " 格");
                            onTab.accept(index);
                        }
                    }
                    downX = -1f;
                    downY = -1f;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    downX = -1f;
                    downY = -1f;
                    return false;
                default:
                    return true;
            }
        }

        private boolean inside(float x, float y) {
            return tabs.contains((int) x, (int) y);
        }

        private float dp(float value) {
            return value * getResources().getDisplayMetrics().density;
        }
    }
}
