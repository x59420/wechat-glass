package com.zaka.wechatglass;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.WeakHashMap;

/**
 * 首页顶栏：把那两个图标（搜索 / ＋）包成一颗悬浮玻璃胶囊，并把顶栏换成透明渐变。
 *
 * 做法刻意保守：
 *   - 只在首页做，只在右上角那一小块里找图标，找不到就什么都不动；
 *   - 图标本身一个都不碰（不改可见性、不改点击），只给它们的**父容器**换背景，
 *     所以在任何机型上最多是"没效果"，不会让微信的按钮点不了；
 *   - 顶栏底色换成从上到下的透明渐变，内容从底栏一直铺到状态栏。
 */
final class WxTopBar {

    /** 已经处理过的容器：只处理一次，避免每次轮询都刷一遍背景。 */
    private static final WeakHashMap<View, String> DONE = new WeakHashMap<View, String>();

    private WxTopBar() {
    }

    static void refresh(final Activity act) {
        if (act == null) return;
        try {
            if (!WxDockController.isHost(act)) return;
            if (!DockPrefs.enabled(act)) return;
            View decor = act.getWindow() == null ? null : act.getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) return;
            apply((ViewGroup) decor, act);
        } catch (Throwable t) {
            WechatGlass.warnOnce("topbar", "顶栏处理失败: " + t);
        }
    }

    private static void apply(ViewGroup decor, Activity act) {
        int w = decor.getWidth();
        int h = decor.getHeight();
        if (w < 200 || h < 400) return;
        float density = act.getResources().getDisplayMetrics().density;

        List<View> found = new ArrayList<View>();
        ArrayDeque<View> queue = new ArrayDeque<View>();
        IdentityHashMap<View, Boolean> seen = new IdentityHashMap<View, Boolean>();
        queue.add(decor);
        int budget = 3000;
        int[] loc = new int[2];

        while (!queue.isEmpty() && budget-- > 0) {
            View v = queue.poll();
            if (v == null || seen.put(v, Boolean.TRUE) != null) continue;
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    if (g.getChildAt(i) != null) queue.add(g.getChildAt(i));
                }
            }
            // 只认首页右上角那一小块里的图标。
            if (!(v instanceof ImageView) || !v.isShown()) continue;
            if (!(v.getParent() instanceof ViewGroup)) continue;
            v.getLocationInWindow(loc);
            int vw = v.getWidth();
            int vh = v.getHeight();
            if (vw < 16 * density || vw > 60 * density) continue;
            if (vh < 16 * density || vh > 60 * density) continue;
            float cx = loc[0] + vw / 2f;
            float cy = loc[1] + vh / 2f;
            if (cx < w * 0.55f) continue;          // 只要右半边
            if (cy > h * 0.22f) continue;          // 只要最上面那一带
            if (v.getAlpha() < 0.2f) continue;
            found.add(v);
        }

        if (found.isEmpty()) return;
        // 右边那两个（搜索 / ＋）在最右边：按 x 排一下取最后两个。
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
        List<View> targets = found.size() > 2
                ? found.subList(found.size() - 2, found.size()) : found;

        // 两个图标必须同一个父容器，包成一颗胶囊才有意义。
        ViewGroup container = (ViewGroup) targets.get(0).getParent();
        for (View v : targets) {
            if (v.getParent() != container) return;
        }
        View containerView = container;
        if (DONE.get(containerView) != null) return;
        if (container.getHeight() <= 0 || container.getHeight() > 72 * density) return;
        if (container.getWidth() > w * 0.75f) return;

        DONE.put(containerView, "ok");
        container.setBackground(new WxPlusPage.GlassBg(density, container.getHeight() / 2f / density,
                isDark(act)));
        container.setClipToOutline(false);
        WechatGlass.log("顶栏:搜索/＋ 已包成玻璃胶囊");

        // 顶栏本体：换成从上到下的透明渐变，内容直接从状态栏下面透上来。
        // 只认"宽度接近整屏、高度还是一条标题栏"的那层祖先；往上再也找不到就不动，
        // 绝不能一路爬到内容根上去把整屏刷成渐变。
        int limit = (int) (160 * density);
        View titleBar = null;
        View cursor = container;
        for (int depth = 0; depth < 12; depth++) {
            if (!(cursor.getParent() instanceof View)) break;
            View parent = (View) cursor.getParent();
            if (parent == decor) break;
            int ph = parent.getHeight();
            if (parent.getWidth() < w * 0.9f) break;
            if (ph <= 0 || ph >= limit) break;
            titleBar = parent;
            cursor = parent;
        }
        if (titleBar != null && titleBar != container && DONE.get(titleBar) == null) {
            DONE.put(titleBar, "bar");
            boolean dark = isDark(act);
            GradientDrawable scrim = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    dark ? new int[]{0x66000000, 0x26000000, 0x00000000}
                            : new int[]{0x66FFFFFF, 0x26FFFFFF, 0x00FFFFFF});
            titleBar.setBackground(scrim);
            WechatGlass.log("顶栏:已换成透明渐变");
        }
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
}
