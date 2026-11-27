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
        backdrop = bitmap == null ? null : blur(bitmap, 10);
        invalidateSelf();
    }

    /** 弹层没有 GPU 采样，这里对背后那一块做一次快速 box blur，效果接近 Dock 的磨砂。 */
    private static Bitmap blur(Bitmap src, int radius) {
        if (src == null || src.isRecycled() || radius <= 0) return src;
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            int[] pix = new int[w * h];
            src.getPixels(pix, 0, w, 0, 0, w, h);
            int[] tmp = new int[w * h];
            for (int pass = 0; pass < 3; pass++) {
                boxBlur(pix, tmp, w, h, radius);
                int[] swap = pix; pix = tmp; tmp = swap;
            }
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            out.setPixels(pix, 0, w, 0, 0, w, h);
            src.recycle();
            return out;
        } catch (Throwable t) {
            return src;
        }
    }

    private static void boxBlur(int[] src, int[] dst, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            int a = 0, rr = 0, gg = 0, bb = 0;
            int row = y * w;
            for (int i = -r; i <= r; i++) {
                int x = Math.max(0, Math.min(w - 1, i));
                int c = src[row + x];
                a += (c >>> 24); rr += (c >> 16) & 0xFF; gg += (c >> 8) & 0xFF; bb += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                dst[row + x] = ((a / div) << 24) | ((rr / div) << 16) | ((gg / div) << 8) | (bb / div);
                int out = Math.max(0, Math.min(w - 1, x - r));
                int in = Math.max(0, Math.min(w - 1, x + r + 1));
                int co = src[row + out], ci = src[row + in];
                a += ((ci >>> 24) - (co >>> 24));
                rr += (((ci >> 16) & 0xFF) - ((co >> 16) & 0xFF));
                gg += (((ci >> 8) & 0xFF) - ((co >> 8) & 0xFF));
                bb += ((ci & 0xFF) - (co & 0xFF));
            }
        }
        for (int x = 0; x < w; x++) {
            int a = 0, rr = 0, gg = 0, bb = 0;
            for (int i = -r; i <= r; i++) {
                int y = Math.max(0, Math.min(h - 1, i));
                int c = dst[y * w + x];
                a += (c >>> 24); rr += (c >> 16) & 0xFF; gg += (c >> 8) & 0xFF; bb += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                src[y * w + x] = ((a / div) << 24) | ((rr / div) << 16) | ((gg / div) << 8) | (bb / div);
                int out = Math.max(0, Math.min(h - 1, y - r));
                int in = Math.max(0, Math.min(h - 1, y + r + 1));
                int co = dst[out * w + x], ci = dst[in * w + x];
                a += ((ci >>> 24) - (co >>> 24));
                rr += (((ci >> 16) & 0xFF) - ((co >> 16) & 0xFF));
                gg += (((ci >> 8) & 0xFF) - ((co >> 8) & 0xFF));
                bb += ((ci & 0xFF) - (co & 0xFF));
            }
        }
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
