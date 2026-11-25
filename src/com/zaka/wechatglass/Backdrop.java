package com.zaka.wechatglass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * 玻璃底下那块真实画面。
 *
 * 取景方式和 KernelSU 的 layerBackdrop 是同一个思路：**只画玻璃后面那块内容**，
 * 不截屏、不拍窗口。
 *
 *  - 采集根是 Activity 的 android.R.id.content。dock 挂在 DecorView 上，和 content
 *    是兄弟节点，所以这里画出来的东西永远不含玻璃自己 —— 不存在回授，
 *    也就不会出现「玻璃里套着一层玻璃」的重影。
 *  - 也不含别的界面：画的是这棵内容树的当前状态，翻页了树就变了，
 *    不会把上一页的像素留在缓冲里。
 *  - 只在宿主重绘过、且间隔够久的时候重画一次（泵在 DockController 的轮询里），
 *    费用按区域大小算，不按整屏算。
 *
 * 视频层（SurfaceView / TextureView）的像素不在 View 的绘制里，画树只会得到一块透明。
 * 这部分单独走 PixelCopy 抓**落在底栏那一条里的**像素，并把 UI 层里那块挖空，
 * 两层叠起来才是玻璃后面真实看到的画面。
 */
public final class Backdrop {

    /** 采样位图相对屏幕的缩放。33+ 交给 Miuix 在 GPU 上模糊，1:1 采样保证清晰度。 */
    private float downscaleFactor = 4f;
    /** 视频层重新扫描的间隔 */
    private static final long RESCAN_MS = 1500L;
    /** 两次内容树重绘之间的最小间隔（约 10fps）。玻璃后面还压着一层 4dp 模糊，够用且不抢帧。 */
    private static final long UI_GAP_MS = 80L;
    /** 视频层帧多久算过期。过期就不参与合成 —— 换页后残留的旧画面就是「别的界面」。 */
    private static final long LAYER_MAX_AGE_MS = 500L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint clear = new Paint();

    private static final class Layer {
        View view;
        Bitmap frame;
        Bitmap textureFull;
        Rect dst = new Rect();
        boolean pending;
        boolean valid;
        long stamp;
        /** 连续抓到几帧「整片黑」。有些机型/播放器上 PixelCopy 抓 SurfaceView 就是黑的。 */
        int blackHits;
        /** 这一帧的亮度（0-255）。黑帧不能算「可用画面」，否则兜底永远不触发。 */
        int luma = 255;
        /** 这一帧的不透明比例（0-100）。用来区分「抓取失败（全透明）」和「真的是暗场视频」。 */
        int opaque = 100;
        /** 这一层还能不能用来做背景：抓到黑帧到一定次数就放弃它。 */
        boolean usable = true;
    }

    private View content;
    private View excluded;
    private final Rect region = new Rect();
    private Bitmap ui;
    private Bitmap vid;
    private Bitmap out;

    private final List<Layer> layers = new ArrayList<Layer>();
    private long lastScan;
    /** 同一时刻只允许一个 PixelCopy 在飞：30fps 叠多层视频层会堆几十个请求，
     *  渲染线程被压住之后宿主会直接崩，这里统一收口。 */
    private int inflight;
    private long lastRequestAt;
    private long frameStamp;
    private int lastLoggedLayerCount = -1;
    private int meanAlpha;
    private int meanLuma = 255;
    /**
     * 内容树的主色。
     *
     * 抖音的视频是 SurfaceView，画面受保护时 PixelCopy 直接失败（日志里的 code=1），
     * 内容树把视频那一块挖空之后，合成出来的图 alpha 只有 70 多、luma≈0 —— 玻璃后面
     * 就是一块死黑。这张图从内容树本身取平均色，只在「确实没有任何可用视频帧」时垫底，
     * 让玻璃透出界面自身的颜色，而不是黑。开关：设置里的「视频背景兜底」。
     */
    private int uiTint = 0xFF16171B;
    /** 当前这一帧的采样指纹：像素没变就别再往玻璃层推，省一次拷贝也省一次重绘。 */
    private long frameSignature;
    private long lastNotifiedSignature;
    private boolean notified;
    private String note = "未采集";
    private String lastDiagnostic = "";
    private long lastDiagnosticAt;

    /** 宿主这一页重绘了多少次：没重绘就说明画面没变，不用再画一遍树。 */
    private final java.util.concurrent.atomic.AtomicInteger drawCount =
            new java.util.concurrent.atomic.AtomicInteger();
    private int lastPumpedDrawCount = -1;
    private long lastUiAt;
    private boolean force;
    private boolean uiValid;
    private android.view.ViewTreeObserver registeredVto;
    private final android.view.ViewTreeObserver.OnPreDrawListener preDrawListener =
            new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    drawCount.incrementAndGet();
                    return true;
                }
            };

    Backdrop() {
        clear.setXfermode(new android.graphics.PorterDuffXfermode(PorterDuff.Mode.CLEAR));
    }

    /** 模糊全部由 Miuix 的 GPU 着色器完成，这里只需要决定采样分辨率。 */
    void setSampleScale() {
        downscaleFactor = android.os.Build.VERSION.SDK_INT >= 33 ? 1f : 4f;
    }

    float downscaleFactor() {
        return downscaleFactor;
    }

    void setContent(View v) {
        if (v == content) return;
        detachPreDraw();
        content = v;
        releaseLayers();
        releaseBitmaps();
        uiValid = false;
        force = true;
        attachPreDraw();
        note = "换采集根";
    }

    /**
     * 不属于「玻璃后面的画面」的视图：抖音原生底栏。
     *
     * 正常情况下它已经被隐藏了，这里只是兜一层：万一宿主又把它设回可见，
     * 宁可这一帧不画树，也不能把一条原生底栏画进玻璃里。
     */
    void setExcluded(View v) {
        excluded = v;
    }

    View content() {
        return content;
    }

    /** 采集区域（窗口坐标，px） */
    void setRegion(Rect r) {
        if (r == null || r.isEmpty()) return;
        if (r.equals(region)) return;
        boolean sizeChanged = r.width() != region.width() || r.height() != region.height();
        region.set(r);
        if (sizeChanged) {
            releaseBitmaps();
            uiValid = false;
        }
        force = true;
        note = "换区域 " + r.width() + "x" + r.height();
    }

    Rect region() {
        return region;
    }

    Bitmap bitmap() {
        if (out == null || out.isRecycled()) return null;
        if (meanAlpha < 8 && !hasVideo()) return null;
        return out;
    }

    /** 抽 8x8 个点估「有多少像素是不透明的」（0-100）。抓取失败的帧是全透明的。 */
    private static int quickOpaque(Bitmap b) {
        if (b == null || b.isRecycled()) return 0;
        int w = b.getWidth();
        int h = b.getHeight();
        if (w <= 0 || h <= 0) return 0;
        int solid = 0;
        int count = 0;
        int stepX = Math.max(1, w / 8);
        int stepY = Math.max(1, h / 8);
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                if (((b.getPixel(x, y) >>> 24) & 0xFF) >= 64) solid++;
                count++;
            }
        }
        return count == 0 ? 0 : solid * 100 / count;
    }

    /** 快速估一张图黑不黑（抽 8x8 个点，别用 getPixels 整张读）。 */
    private static int quickLuma(Bitmap b) {
        if (b == null || b.isRecycled()) return 0;
        int w = b.getWidth();
        int h = b.getHeight();
        if (w <= 0 || h <= 0) return 0;
        long sum = 0;
        int count = 0;
        int stepX = Math.max(1, w / 8);
        int stepY = Math.max(1, h / 8);
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int p = b.getPixel(x, y);
                sum += ((p >> 16 & 0xFF) * 299 + (p >> 8 & 0xFF) * 587 + (p & 0xFF) * 114) / 1000;
                count++;
            }
        }
        return count == 0 ? 0 : (int) (sum / count);
    }

    /** 有没有正在播放的视频层（SurfaceView / TextureView 都算）。 */
    private boolean hasLiveVideoLayer() {
        for (Layer l : layers) {
            View v = l.view;
            if (v != null && v.isAttachedToWindow() && v.isShown()
                    && v.getWidth() > 16 && v.getHeight() > 16) return true;
        }
        return false;
    }

    private boolean hasVideo() {
        for (Layer l : layers) {
            if (l.usable && l.valid) return true;
        }
        return false;
    }

    /** 有没有一帧可用的画面（没有就别指望玻璃透出东西来） */
    boolean hasFrame() {
        return bitmap() != null;
    }

    int luma() { return meanLuma; }

    /**
     * 这一帧采出来是不是整片发黑。
     *
     * 有视频层帧时不判黑（暗场视频属于画面内容）；只有在视频层也没有、采样又几乎全黑时，
     * 才说明底下根本没画出东西，这时候把背景图丢掉，玻璃只保留自己的模糊。
     */
    boolean looksBlack() {
        if (meanLuma >= 12) return false;
        // 整片黑：只有在「有视频层但采不到」的时候才认定是采样失败，玻璃改用半透明回退。
        // 纯粹的暗色页面照旧用真实画面，不会被误判。
        for (Layer l : layers) {
            if (!l.usable) return true;
        }
        return false;
    }

    private boolean hasFrameLayer() {
        for (Layer l : layers) {
            if (l.usable && l.valid && l.frame != null && !l.frame.isRecycled()) return true;
        }
        return false;
    }

    long frameStamp() {
        return frameStamp;
    }

    String desc() {
        return note + " · " + region.width() + "x" + region.height()
                + " · 视频层 " + layers.size()
                + " · alpha " + meanAlpha + " · luma " + meanLuma;
    }

    void setOnFrame(Runnable r) {
        onFrame = r;
    }

    private Runnable onFrame;

    // ------------------------------------------------------------------ 泵

    /**
     * 外部按帧（DockController 的可见性轮询）调。
     *
     * 只在「宿主重绘过」或者「区域/采集根换过」的时候才真的重画，
     * 静态页面（比如个人页）不会每秒画十遍树。
     */
    void pump() {
        if (content == null || region.isEmpty()) return;
        attachPreDraw();
        int drawn = drawCount.get();
        boolean changed = drawn != lastPumpedDrawCount;
        // 播放中的视频层不会让 View 树重绘，只看「重绘次数」的话画面会冻在第一帧，
        // 玻璃里就是一张不动的旧截图。有活着的视频层时按固定节奏重新抓。
        boolean live = hasLiveVideoLayer();
        if (!changed && !force && !live) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastUiAt < UI_GAP_MS) return;
        lastUiAt = now;
        lastPumpedDrawCount = drawn;
        force = false;
        capture();
    }

    // ------------------------------------------------------------------ 采集

    /** 重采一帧：内容树 + 视频层。开销按区域算，别每帧都调（走 pump）。 */
    boolean capture() {
        View c = content;
        if (c == null || region.isEmpty()) {
            note = "没根视图或没区域";
            return false;
        }
        int w = c.getWidth();
        int h = c.getHeight();
        if (w < 16 || h < 16 || !c.isShown()) {
            note = "根视图 " + w + "x" + h;
            return false;
        }

        int sw = Math.max(8, Math.round(region.width() / downscaleFactor));
        int sh = Math.max(8, Math.round(region.height() / downscaleFactor));
        float kx = sw / (float) region.width();
        float ky = sh / (float) region.height();

        try {
            if (ui == null || ui.isRecycled()
                    || ui.getWidth() != sw || ui.getHeight() != sh) {
                releaseBitmaps();
                ui = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
                vid = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
                out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
                uiValid = false;
            }
        } catch (Throwable t) {
            WechatGlass.error("建背景位图", t);
            releaseBitmaps();
            return false;
        }

        long now = SystemClock.uptimeMillis();
        if (now - lastScan > RESCAN_MS || layers.isEmpty()) {
            rescanLayers(c, now);
        }

        // 1. 先算视频层落在哪
        for (Layer l : layers) {
            updateDst(l, c, kx, ky);
        }

        // 2. 内容树。视频已经把这一条铺满的时候就不用画树了 ——
        //    抖音首页每 1.2 秒重画一遍整棵信息流，卡顿就是这么来的
        boolean covered = videoCoversAll(sw, sh);
        boolean skipTree = excluded != null && excluded.isShown() && !covered;
        if (!covered && !skipTree) {
            try {
                Canvas uc = new Canvas(ui);
                uc.drawColor(0, PorterDuff.Mode.CLEAR);
                uc.scale(kx, ky);
                int[] cl = new int[2];
                c.getLocationInWindow(cl);
                uc.translate(cl[0] - region.left, cl[1] - region.top);
                c.draw(uc);
                uiValid = true;
            } catch (Throwable t) {
                // 宿主树里常有 SurfaceView / 硬件位图，软件画布画到一半失败是常态
                WechatGlass.warnOnce("ui-draw", "内容树绘制中断（通常无害）: " + t);
            }
        } else if (covered) {
            try {
                Canvas uc = new Canvas(ui);
                uc.drawColor(0, PorterDuff.Mode.CLEAR);
                uiValid = true;
            } catch (Throwable ignored) {
            }
        }

        // 3. 请求新帧
        for (Layer l : layers) {
            if (!l.pending) requestFrame(l);
        }

        // 4. UI 层里视频所在的那块挖空，否则视频会被容器自己的深色底盖住 —— 就是黑块
        try {
            Canvas uc = new Canvas(ui);
            for (Layer l : layers) {
                if (l.dst.isEmpty() || !l.usable) continue;
                uc.drawRect(l.dst.left, l.dst.top, l.dst.right, l.dst.bottom, clear);
            }
        } catch (Throwable t) {
            WechatGlass.warnOnce("ui-erase", "挖空视频区失败: " + t);
        }

        compose();
        notifyFrame();
        note = "ok";
        logCaptureState(now);
        return true;
    }

    /** 视频层是不是已经把这一条铺满了（以新鲜帧为准） */
    private boolean videoCoversAll(int sw, int sh) {
        if (layers.isEmpty()) return false;
        long now = SystemClock.uptimeMillis();
        long total = (long) sw * sh;
        long sum = 0;
        for (Layer l : layers) {
            if (!l.usable || !l.valid || l.dst.isEmpty()
                    || l.frame == null || l.frame.isRecycled()) return false;
            if (now - l.stamp > LAYER_MAX_AGE_MS) return false;
            sum += (long) l.dst.width() * l.dst.height();
        }
        return sum >= total * 0.94f;
    }

    private void logCaptureState(long now) {
        String state = (hasFrame() ? "frame" : "no-frame") + " · " + desc();
        if (!state.equals(lastDiagnostic) || now - lastDiagnosticAt >= 5000L) {
            lastDiagnostic = state;
            lastDiagnosticAt = now;
            WechatGlass.log("背景采样: " + state
                    + (layers.isEmpty() ? " · 当前只画内容树，未发现视频 Surface/Texture 层" : ""));
        }
    }

    private void compose() {
        if (out == null || ui == null || vid == null) return;
        long now = SystemClock.uptimeMillis();
        try {
            Canvas vc = new Canvas(vid);
            vc.drawColor(0, PorterDuff.Mode.CLEAR);
            for (Layer l : layers) {
                if (!l.usable || !l.valid || l.frame == null || l.frame.isRecycled()) continue;
                if (l.dst.isEmpty()) continue;
                // 过期的视频帧直接丢：换页之后残留的那一帧就是「别的界面」的来源。
                if (now - l.stamp > LAYER_MAX_AGE_MS) {
                    l.valid = false;
                    continue;
                }
                vc.drawBitmap(l.frame, null, l.dst, paint);
            }

            Canvas oc = new Canvas(out);
            oc.drawColor(0, PorterDuff.Mode.CLEAR);
            oc.drawBitmap(ui, 0f, 0f, paint);
            oc.drawBitmap(vid, 0f, 0f, paint);
        } catch (Throwable t) {
            WechatGlass.error("合成背景", t);
            return;
        }

        measure();
        // 抓不到任何视频帧、合成结果又几乎透明/全黑：用内容树主色垫一层不透明底。
        // 只在「没有可用视频帧」时做，正常能抓到画面的页面完全不受影响（外观不变）。
        if (needsTint() && tintEnabled() && tintUnderlay()) {
            measure();
        }
        frameStamp = System.nanoTime();
    }

    /** 有没有一帧真正可用、且不是黑的视频画面。 */
    private boolean hasUsableVideoFrame() {
        long now = SystemClock.uptimeMillis();
        for (Layer l : layers) {
            if (l.usable && l.valid && l.frame != null && !l.frame.isRecycled()
                    // 暗场视频（夜景/黑背景）也是真实画面：只要不是整帧全透明就算可用，
                    // 阈值和 noteFrameLuma 里判断「抓取失败」的阈值保持一致。
                    && (l.luma >= 14 || l.opaque >= 12)
                    && now - l.stamp <= LAYER_MAX_AGE_MS) {
                return true;
            }
        }
        return false;
    }

    /** 该不该垫底色：没有可用视频帧，而且合成出来又透又黑。 */
    private boolean needsTint() {
        if (hasUsableVideoFrame()) return false;
        return meanAlpha < 160 || meanLuma < 18;
    }

    private boolean tintEnabled() {
        try {
            if (content == null) return true;
            return DockPrefs.videoTintFallback(content.getContext());
        } catch (Throwable t) {
            return true;
        }
    }

    /** 用内容树主色重新合成一版，返回是否真的改了。 */
    private boolean tintUnderlay() {
        if (ui == null || ui.isRecycled() || out == null || out.isRecycled()) return false;
        try {
            updateUiTint();
            Canvas oc = new Canvas(out);
            oc.drawColor(0, PorterDuff.Mode.CLEAR);
            oc.drawColor(uiTint);
            oc.drawBitmap(ui, 0f, 0f, paint);
            if (vid != null && !vid.isRecycled()) oc.drawBitmap(vid, 0f, 0f, paint);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 从内容树那一层估一个主色（抽 10x8 个点，跳过完全透明的像素）。
     * 估不出来就沿用上一次的值，至少不是纯黑。
     */
    private void updateUiTint() {
        if (ui == null || ui.isRecycled()) return;
        try {
            int w = ui.getWidth();
            int h = ui.getHeight();
            if (w <= 0 || h <= 0) return;
            int stepX = Math.max(1, w / 10);
            int stepY = Math.max(1, h / 8);
            long r = 0, g = 0, b = 0;
            int n = 0;
            for (int y = 0; y < h; y += stepY) {
                for (int x = 0; x < w; x += stepX) {
                    int p = ui.getPixel(x, y);
                    if (((p >>> 24) & 0xFF) < 24) continue;
                    r += (p >> 16) & 0xFF;
                    g += (p >> 8) & 0xFF;
                    b += p & 0xFF;
                    n++;
                }
            }
            if (n < 4) return;
            int cr = (int) (r / n), cg = (int) (g / n), cb = (int) (b / n);
            // 太暗的界面（纯黑视频页）抬一点亮度，别让兜底色看起来还是一块黑。
            int luma = (cr * 299 + cg * 587 + cb * 114) / 1000;
            if (luma < 30) {
                int lift = 30 - luma;
                cr = Math.min(255, cr + lift);
                cg = Math.min(255, cg + lift);
                cb = Math.min(255, cb + lift);
            }
            uiTint = 0xFF000000 | (cr << 16) | (cg << 8) | cb;
        } catch (Throwable ignored) {
        }
    }

    private void onVideoFrame() {
        compose();
        notifyFrame();
    }

    /** 有新的一帧：把图推给玻璃层。视频帧和内容树帧走同一条出口。 */
    private void notifyFrame() {
        Runnable cb = onFrame;
        if (cb == null) return;
        // 画面没变（静态页面）就不推：否则「推一帧 → 玻璃重绘 → 又触发采样」会自激成 10fps 空转。
        if (notified && frameSignature == lastNotifiedSignature) return;
        notified = true;
        lastNotifiedSignature = frameSignature;
        try {
            cb.run();
        } catch (Throwable t) {
            WechatGlass.error("玻璃刷新", t);
        }
    }

    private void attachPreDraw() {
        View c = content;
        if (c == null) return;
        try {
            android.view.ViewTreeObserver vto = c.getViewTreeObserver();
            if (vto == registeredVto && vto.isAlive()) return;
            detachPreDraw();
            vto.addOnPreDrawListener(preDrawListener);
            registeredVto = vto;
        } catch (Throwable ignored) {
        }
    }

    private void detachPreDraw() {
        try {
            if (registeredVto != null && registeredVto.isAlive()) {
                registeredVto.removeOnPreDrawListener(preDrawListener);
            }
        } catch (Throwable ignored) {
        }
        registeredVto = null;
    }

    private void measure() {
        if (out == null || out.isRecycled()) return;
        int w = out.getWidth();
        int h = out.getHeight();
        int stepX = Math.max(1, w / 20);
        int stepY = Math.max(1, h / 12);
        long alphaSum = 0L;
        long lumaSum = 0L;
        long signature = 1125899906842597L;
        int count = 0;
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int pixel = out.getPixel(x, y);
                alphaSum += (pixel >>> 24) & 0xFF;
                lumaSum += ((pixel >> 16 & 0xFF) * 299
                        + (pixel >> 8 & 0xFF) * 587
                        + (pixel & 0xFF) * 114) / 1000;
                signature = signature * 31 + pixel;
                count++;
            }
        }
        if (count > 0) {
            meanAlpha = (int) (alphaSum / count);
            meanLuma = (int) (lumaSum / count);
            frameSignature = signature;
        }
    }

    // ------------------------------------------------------------------ 视频层

    private void rescanLayers(View root, long now) {
        lastScan = now;
        List<View> found = new ArrayList<View>();
        collect(root, found, 0);
        boolean same = found.size() == layers.size();
        if (same) {
            for (int i = 0; i < found.size(); i++) {
                if (layers.get(i).view != found.get(i)) {
                    same = false;
                    break;
                }
            }
        }
        if (same) return;

        releaseLayers();
        for (View v : found) {
            Layer l = new Layer();
            l.view = v;
            layers.add(l);
        }
        if (layers.size() != lastLoggedLayerCount) {
            lastLoggedLayerCount = layers.size();
            if (layers.isEmpty()) {
                WechatGlass.warn("背景采样：当前 Dock 区域未发现 SurfaceView/TextureView，玻璃将暂用半透明回退");
            } else {
                WechatGlass.log("背景采样：检测到视频层 " + layers.size() + " 个（"
                        + layers.get(0).view.getClass().getSimpleName() + "）");
            }
        }
    }

    private void collect(View v, List<View> outList, int depth) {
        if (v == null || depth > 40 || outList.size() >= 3) return;
        if (v.getVisibility() != View.VISIBLE || !v.isShown()) return;
        if (v instanceof SurfaceView || v instanceof TextureView) {
            if (overlapsRegion(v)) outList.add(v);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collect(g.getChildAt(i), outList, depth + 1);
            }
        }
    }

    private boolean overlapsRegion(View v) {
        int[] wl = new int[2];
        v.getLocationInWindow(wl);
        Rect r = new Rect(wl[0], wl[1], wl[0] + v.getWidth(), wl[1] + v.getHeight());
        return Rect.intersects(r, region) && v.getWidth() > 16 && v.getHeight() > 16;
    }

    private void updateDst(Layer l, View root, float kx, float ky) {
        try {
            int[] vl = new int[2];
            l.view.getLocationInWindow(vl);
            Rect r = new Rect(vl[0], vl[1], vl[0] + l.view.getWidth(), vl[1] + l.view.getHeight());
            if (!Rect.intersects(r, region)) {
                l.dst.setEmpty();
                return;
            }
            Rect inter = new Rect(Math.max(r.left, region.left), Math.max(r.top, region.top),
                    Math.min(r.right, region.right), Math.min(r.bottom, region.bottom));
            l.dst.set(
                    Math.round((inter.left - region.left) * kx),
                    Math.round((inter.top - region.top) * ky),
                    Math.round((inter.right - region.left) * kx),
                    Math.round((inter.bottom - region.top) * ky));
        } catch (Throwable t) {
            l.dst.setEmpty();
        }
    }

    private void requestFrame(final Layer l) {
        try {
            if (l.dst.isEmpty()) return;
            // 视图已经不在窗口上/被隐藏：立刻丢掉旧帧，绝不让它留在合成里。
            if (!l.view.isAttachedToWindow() || !l.view.isShown()) {
                l.valid = false;
                return;
            }

            if (l.view instanceof TextureView) {
                TextureView tv = (TextureView) l.view;
                if (!tv.isAvailable()) return;
                int fullW = Math.max(4, Math.round(tv.getWidth() / downscaleFactor));
                int fullH = Math.max(4, Math.round(tv.getHeight() / downscaleFactor));
                if (l.textureFull == null || l.textureFull.isRecycled()
                        || l.textureFull.getWidth() != fullW
                        || l.textureFull.getHeight() != fullH) {
                    l.textureFull = recycle(l.textureFull);
                    l.textureFull = Bitmap.createBitmap(fullW, fullH, Bitmap.Config.ARGB_8888);
                }
                Bitmap got = tv.getBitmap(l.textureFull);
                if (got == null) {
                    l.valid = false;
                    return;
                }
                int[] vl = new int[2];
                tv.getLocationInWindow(vl);
                float sourceScaleX = l.textureFull.getWidth() / (float) tv.getWidth();
                float sourceScaleY = l.textureFull.getHeight() / (float) tv.getHeight();
                Rect src = new Rect(
                        Math.round(Math.max(0, region.left - vl[0]) * sourceScaleX),
                        Math.round(Math.max(0, region.top - vl[1]) * sourceScaleY),
                        Math.round(Math.min(tv.getWidth(), region.right - vl[0]) * sourceScaleX),
                        Math.round(Math.min(tv.getHeight(), region.bottom - vl[1]) * sourceScaleY));
                if (src.isEmpty()) {
                    l.valid = false;
                    return;
                }
                int w = Math.max(4, l.dst.width());
                int h = Math.max(4, l.dst.height());
                if (l.frame == null || l.frame.isRecycled()
                        || l.frame.getWidth() != w || l.frame.getHeight() != h) {
                    l.frame = recycle(l.frame);
                    l.frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                }
                Canvas crop = new Canvas(l.frame);
                crop.drawColor(0, PorterDuff.Mode.CLEAR);
                crop.drawBitmap(l.textureFull, src, new Rect(0, 0, w, h), paint);
                l.valid = true;
                l.stamp = SystemClock.uptimeMillis();
                noteFrameLuma(l);
                onVideoFrame();
                return;
            }

            if (!(l.view instanceof SurfaceView)) return;
            SurfaceView sv = (SurfaceView) l.view;
            if (!sv.getHolder().getSurface().isValid()) return;

            int w = Math.max(4, l.dst.width());
            int h = Math.max(4, l.dst.height());
            if (l.frame == null || l.frame.isRecycled()
                    || l.frame.getWidth() != w || l.frame.getHeight() != h) {
                l.frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                l.valid = false;
            }

            // 只抓「底栏那一条」在视频里的那一块
            int[] vl = new int[2];
            sv.getLocationInWindow(vl);
            Rect svRect = new Rect(0, 0, sv.getWidth(), sv.getHeight());
            Rect inter = new Rect(
                    Math.max(region.left, vl[0]) - vl[0],
                    Math.max(region.top, vl[1]) - vl[1],
                    Math.min(region.right, vl[0] + sv.getWidth()) - vl[0],
                    Math.min(region.bottom, vl[1] + sv.getHeight()) - vl[1]);
            if (inter.isEmpty() || !Rect.intersects(inter, svRect)) {
                l.valid = false;
                return;
            }

            long nowMs = SystemClock.uptimeMillis();
            if (inflight > 0 || nowMs - lastRequestAt < 8L) return;
            lastRequestAt = nowMs;
            inflight++;
            l.pending = true;
            PixelCopy.request(sv, inter, l.frame, new PixelCopy.OnPixelCopyFinishedListener() {
                @Override
                public void onPixelCopyFinished(int result) {
                    l.pending = false;
                    synchronized (Backdrop.this) {
                        if (inflight > 0) inflight--;
                    }
                    if (result == PixelCopy.SUCCESS) {
                        l.valid = true;
                        l.stamp = SystemClock.uptimeMillis();
                        noteFrameLuma(l);
                        onVideoFrame();
                    } else {
                        l.valid = false;
                        WechatGlass.warnOnce("pixelcopy", "抓视频画面失败 code=" + result
                                + "（画面受保护时玻璃退回内容树采样）");
                    }
                }
            }, main);
        } catch (Throwable t) {
            if (l.pending) {
                l.pending = false;
                synchronized (Backdrop.this) {
                    if (inflight > 0) inflight--;
                }
            }
            l.valid = false;
            WechatGlass.warnOnce("pixelcopy-err", "抓视频画面异常: " + t);
        }
    }

    /**
     * 记一次「这一帧是不是黑的」。
     *
     * 视频层在切帧/首帧阶段可能短暂返回黑图；连续多帧才降级，避免单帧黑屏变成黑块。
     * 持续近乎全黑时不再挖空 UI 内容树，改用宿主原有内容作为安全回退。
     */
    private static void noteFrameLuma(Layer l) {
        if (l == null || l.frame == null) return;
        l.luma = quickLuma(l.frame);
        l.opaque = quickOpaque(l.frame);
        // 真正抓取失败的帧是「全透明」；暗场视频（夜景、黑屏画面）是「黑但有不透明像素」，
        // 后者属于真实画面，不能当成失败，否则玻璃会拿界面主色把用户的视频盖掉。
        boolean captureFailed = l.opaque < 12;
        if (l.luma < 12 && captureFailed) {
            l.blackHits++;
            if (l.blackHits >= 8 && l.usable) {
                l.usable = false;
                WechatGlass.warnOnce("video-dark-frame", "视频层连续多帧采样接近全黑，已停止挖空该区域并回退内容树背景");
            }
        } else {
            l.blackHits = 0;
            l.usable = true;
        }
    }

    // ------------------------------------------------------------------ 释放

    private void releaseLayers() {
        for (Layer l : layers) {
            if (l.frame != null && !l.frame.isRecycled() && !l.pending) {
                try {
                    l.frame.recycle();
                } catch (Throwable ignored) {
                }
            }
            if (l.textureFull != null && !l.textureFull.isRecycled()) {
                try {
                    l.textureFull.recycle();
                } catch (Throwable ignored) {
                }
            }
            l.frame = null;
            l.textureFull = null;
        }
        layers.clear();
    }

    private void releaseBitmaps() {
        ui = recycle(ui);
        vid = recycle(vid);
        out = recycle(out);
        uiValid = false;
        notified = false;
    }

    private static Bitmap recycle(Bitmap b) {
        if (b != null && !b.isRecycled()) {
            try {
                b.recycle();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    void release() {
        main.removeCallbacksAndMessages(null);
        detachPreDraw();
        releaseLayers();
        releaseBitmaps();
        content = null;
        excluded = null;
    }
}
