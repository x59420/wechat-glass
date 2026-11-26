package com.zaka.wechatglass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * 弹层 / 菜单用的玻璃背景（Drawable 版）。
 *
 * 和顶栏那颗胶囊（GlassCapsule）同一套材质参数：真背景 + 表面色 + 顶部泛光 +
 * 底部内阴影 + 圆角亮边。区别只在于这里是给 PopupWindow 当 background 用的，
 * 拿不到自己的 View，所以背景图由外面（WxPlusPage 里的 Backdrop）采好了塞进来。
 *
 * 采不到背景（老系统 / 受限窗口）就只画表面色——看上去还是玻璃，只是背后没画面。
 */
final class GlassDrawable extends Drawable {

    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint surfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final RectF dst = new RectF();
    private final Path clip = new Path();

    private final float radiusPx;
    private final boolean dark;
    /** 弹层这类要装文字的，底子必须够实，否则字压在一堆花色上看不清。 */
    private final boolean dense;
    private Bitmap backdrop;

    GlassDrawable(float radiusPx, boolean dark) {
        this(radiusPx, dark, false);
    }

    GlassDrawable(float radiusPx, boolean dark, boolean dense) {
        this.radiusPx = radiusPx;
        this.dark = dark;
        this.dense = dense;
    }

    void setBackdrop(Bitmap bitmap) {
        backdrop = bitmap;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;
        bounds.set(b);
        float r = Math.min(radiusPx, Math.min(b.width(), b.height()) / 2f);

        clip.reset();
        clip.addRoundRect(bounds, r, r, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(clip);

        if (backdrop != null && !backdrop.isRecycled()) {
            dst.set(bounds);
            // 弹层里要放文字：背景图只留一点点，别跟字抢。
            imagePaint.setAlpha(dense ? 120 : 255);
            canvas.drawBitmap(backdrop, null, dst, imagePaint);
            imagePaint.setAlpha(255);
        }

        surfacePaint.setShader(null);
        // dense：实底。浅色模式接近纯白 + 一点冷调，深色模式一块深灰蓝，
        // 不管背后是聊天列表还是图片，字都压得住。
        if (dense) {
            // 和 Dock 一样保留一点透明度，做成玻璃而不是实心色块。
            surfacePaint.setColor(dark ? 0xE61A1C22 : 0xEFFFFFFF);
        } else {
            surfacePaint.setColor(dark ? 0x59101318 : 0x59FFFFFF);
        }
        canvas.drawRect(bounds, surfacePaint);

        surfacePaint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                dense
                        ? new int[]{dark ? 0x1FFFFFFF : 0x26FFFFFF,
                        dark ? 0x0AFFFFFF : 0x0AFFFFFF,
                        dark ? 0x22000000 : 0x0F000000}
                        : new int[]{dark ? 0x33FFFFFF : 0x4DFFFFFF,
                        dark ? 0x0DFFFFFF : 0x14FFFFFF,
                        dark ? 0x33000000 : 0x12000000},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(bounds, surfacePaint);
        surfacePaint.setShader(null);

        shadePaint.setShader(new LinearGradient(0, bounds.bottom - radiusPx, 0, bounds.bottom,
                new int[]{0x00000000, dark ? 0x33000000 : 0x1F000000},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(bounds, shadePaint);
        shadePaint.setShader(null);

        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(1.4f);
        rimPaint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                new int[]{0xA6FFFFFF, dark ? 0x1FFFFFFF : 0x59FFFFFF, 0x0DFFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(bounds, r, r, rimPaint);
        rimPaint.setShader(null);

        canvas.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
