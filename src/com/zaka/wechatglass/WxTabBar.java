package com.zaka.wechatglass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Checkable;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 微信底部导航栏的定位与操作。
 *
 * 只认 LauncherUI 里那条 {@code com.tencent.mm.ui.LauncherUIBottomTabView}：
 * 它里面是 3~5 个等宽、横向排列的导航项（微信 / 通讯录 / 发现 / 我）。
 * 类名认不到时按形状兜底，但兜底判据故意写得很严——认错一行会把微信的某个
 * 普通控件当成底栏、把它透明掉，那比"不生效"严重得多。
 *
 * 点击一律用「模拟一次真实触摸」转发给原生底栏：
 * 微信自己的点击链路（选中态、页面切换、角标、重复点击行为）全部照旧，
 * 模块不调用任何内部切换方法，也不改微信的事件处理。
 */
final class WxTabBar {

    /** 微信的底栏类名。8.0.x 一直叫这个；改名了就走形状兜底。 */
    private static final String[] TAB_CLASSES = {
            "com.tencent.mm.ui.LauncherUIBottomTabView",
    };

    private static final String[] DEFAULT_LABELS = {"微信", "通讯录", "发现", "我"};

    private static final int MIN_TABS = 3;
    private static final int MAX_TABS = 5;
    private static final float MIN_TAB_HEIGHT_DP = 32f;

    private WxTabBar() {
    }

    /** 在整棵窗口里找底栏。找不到返回 null，调用方保持微信原样。 */
    static ViewGroup find(View root) {
        ViewGroup named = findByName(root, 0, new int[]{4000});
        if (named != null) return named;
        return findByShape(root);
    }

    // ------------------------------------------------------------ 按类名

    private static ViewGroup findByName(View v, int depth, int[] budget) {
        if (v == null || depth > 40 || budget[0]-- <= 0) return null;
        if (isTabView(v)) {
            return v instanceof ViewGroup ? (ViewGroup) v : null;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ViewGroup found = findByName(g.getChildAt(i), depth + 1, budget);
                if (found != null) return found;
            }
        }
        return null;
    }

    static boolean isTabView(View v) {
        if (v == null) return false;
        String name = v.getClass().getName();
        for (String c : TAB_CLASSES) {
            if (c.equals(name) || name.endsWith("LauncherUIBottomTabView")) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ 按形状兜底

    /**
     * 全屏找一行「贴底、等宽、3~5 个、里面没有输入框」的横向导航项。
     *
     * 只在类名认不到时才走这里，所以宁严勿松：同时要求等宽、同高、从左到右排开、
     * 整行贴在屏幕下半部分，而且每个子项都带图标或文字。
     */
    private static ViewGroup findByShape(View root) {
        ViewGroup found = walkShape(root, 0, new int[]{3000});
        if (found != null) {
            WechatGlass.warn("底栏类名没认到，按形状命中：" + found.getClass().getName()
                    + " tabs=" + found.getChildCount());
        }
        return found;
    }

    private static ViewGroup walkShape(View v, int depth, int[] budget) {
        if (v == null || depth > 40 || budget[0]-- <= 0) return null;
        if (v instanceof ViewGroup && looksLikeTabRow((ViewGroup) v)) {
            return (ViewGroup) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ViewGroup found = walkShape(g.getChildAt(i), depth + 1, budget);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean looksLikeTabRow(ViewGroup g) {
        if (g.getVisibility() != View.VISIBLE || g.getWidth() <= 0 || g.getHeight() <= 0) {
            return false;
        }
        float density = g.getResources().getDisplayMetrics().density;
        if (g.getHeight() < MIN_TAB_HEIGHT_DP * density) return false;

        View first = null;
        int prevRight = Integer.MIN_VALUE;
        int count = 0;
        boolean hasText = false;
        boolean hasIcon = false;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE || c.getWidth() <= 0 || c.getHeight() <= 0) continue;
            if (first == null) {
                first = c;
            } else {
                if (Math.abs(c.getWidth() - first.getWidth()) > 4) return false;
                if (Math.abs(c.getHeight() - first.getHeight()) > 4) return false;
                if (c.getHeight() < first.getHeight() / 2) return false;
            }
            if (c.getLeft() < prevRight - 2) return false;
            prevRight = c.getRight();
            count++;
            if (findText(c, 0, 6).length() > 0) hasText = true;
            if (findIcon(c, 0, 6) != null) hasIcon = true;
            if (hasInput(c, 0)) return false;
        }
        if (count < MIN_TABS || count > MAX_TABS) return false;
        if (!hasText && !hasIcon) return false;

        // 必须在屏幕下半部分，且横向铺开。
        int[] loc = new int[2];
        g.getLocationOnScreen(loc);
        int screenHeight = g.getResources().getDisplayMetrics().heightPixels;
        int screenWidth = g.getResources().getDisplayMetrics().widthPixels;
        if (loc[1] < screenHeight * 0.6f) return false;
        if (g.getWidth() < screenWidth * 0.7f) return false;
        return true;
    }

    private static boolean hasInput(View v, int depth) {
        if (v == null || depth > 6) return false;
        if (v instanceof android.widget.EditText) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hasInput(g.getChildAt(i), depth + 1)) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 导航项

    /**
     * 底栏里的导航项。
     *
     * 微信的底栏通常直接把 4 个导航项摆在自己身上；个别版本会再包一层横向容器，
     * 所以先看自己，不像再看子容器。
     */
    static List<View> items(ViewGroup tabView) {
        List<View> direct = visibleChildren(tabView);
        if (direct.size() >= MIN_TABS) return direct;
        for (int i = 0; i < tabView.getChildCount(); i++) {
            View c = tabView.getChildAt(i);
            if (c instanceof ViewGroup) {
                List<View> nested = visibleChildren((ViewGroup) c);
                if (nested.size() >= MIN_TABS) return nested;
            }
        }
        return direct;
    }

    private static List<View> visibleChildren(ViewGroup g) {
        List<View> out = new ArrayList<View>();
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() == View.VISIBLE && c.getWidth() > 0 && c.getHeight() > 0) {
                out.add(c);
            }
        }
        return out;
    }

    /** 导航项上的文字（微信 / 通讯录 / 发现 / 我）。 */
    static String label(View item) {
        String text = findText(item, 0, 6);
        // 未读角标是纯数字，不能当标签用。
        if (text.length() > 0 && text.length() <= 8 && !text.matches("[0-9]+")) return text;
        return "";
    }

    static String defaultLabel(int index) {
        return index >= 0 && index < DEFAULT_LABELS.length ? DEFAULT_LABELS[index] : "";
    }

    private static String findText(View v, int depth, int maxDepth) {
        if (v == null || depth > maxDepth) return "";
        if (v instanceof TextView) {
            CharSequence text = ((TextView) v).getText();
            if (text != null && text.toString().trim().length() > 0) {
                return text.toString().trim();
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                String found = findText(g.getChildAt(i), depth + 1, maxDepth);
                if (found.length() > 0) return found;
            }
        }
        return "";
    }

    /** 导航项上的图标。有几个 ImageView 时取尺寸最大的那个（角标红点很小）。 */
    static Drawable findIcon(View v, int depth, int maxDepth) {
        if (v == null || depth > maxDepth) return null;
        Drawable best = null;
        int bestSize = -1;
        if (v instanceof ImageView) {
            Drawable d = ((ImageView) v).getDrawable();
            if (d != null) {
                int size = Math.max(d.getIntrinsicWidth(), d.getIntrinsicHeight());
                if (size > bestSize) {
                    bestSize = size;
                    best = d;
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                Drawable d = findIcon(g.getChildAt(i), depth + 1, maxDepth);
                if (d != null) {
                    int size = Math.max(d.getIntrinsicWidth(), d.getIntrinsicHeight());
                    if (size > bestSize) {
                        bestSize = size;
                        best = d;
                    }
                }
            }
        }
        return best;
    }

    /** 自己画一份图标位图：不走 androidx.core 的 toBitmap，避开宿主那份 old androidx。 */
    static Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable == null) return null;
        try {
            int width = drawable.getIntrinsicWidth();
            int height = drawable.getIntrinsicHeight();
            if (width <= 0 || height <= 0) {
                width = 24;
                height = 24;
            }
            width = Math.max(1, Math.min(128, width));
            height = Math.max(1, Math.min(128, height));
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Rect old = new Rect(drawable.getBounds());
            drawable.setBounds(0, 0, width, height);
            drawable.draw(canvas);
            drawable.setBounds(old);
            return bitmap;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ 选中态

    /**
     * 当前选中的是第几格，读不到返回 -1。
     *
     * 微信在每个导航项上直接 setSelected(true/false)，所以从视图状态读最可靠，
     * 不需要 hook 它的任何切换方法。
     */
    static int selectedIndex(List<View> items) {
        if (items == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (isSelectedDeep(items.get(i), 0)) return i;
        }
        return -1;
    }

    private static boolean isSelectedDeep(View v, int depth) {
        if (v == null || depth > 8) return false;
        if (v.isSelected() || v.isActivated()
                || (v instanceof Checkable && ((Checkable) v).isChecked())) {
            return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (isSelectedDeep(g.getChildAt(i), depth + 1)) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 点击转发

    /**
     * 把一次点击转发给微信原生导航项。
     *
     * 做法是在原生底栏自己的坐标系里派发一对真实的 DOWN/UP：
     * 走的是微信原来那条完整的事件链（波纹、选中、页面切换、重复点击回顶），
     * 模块从头到尾只当"手指"，不调用 setTo 之类内部方法。
     */
    static void tap(ViewGroup tabView, View item, int index) {
        if (tabView == null || item == null || !item.isShown()) return;
        try {
            int[] barPos = new int[2];
            int[] itemPos = new int[2];
            tabView.getLocationOnScreen(barPos);
            item.getLocationOnScreen(itemPos);
            float x = itemPos[0] - barPos[0] + item.getWidth() / 2f;
            float y = itemPos[1] - barPos[1] + item.getHeight() / 2f;
            if (x < 0 || y < 0 || x > tabView.getWidth() || y > tabView.getHeight()) {
                WechatGlass.warnOnce("tap-out-of-bar", "导航项坐标落在底栏之外，放弃这次转发");
                return;
            }
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now,
                    MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(now, now + 48L,
                    MotionEvent.ACTION_UP, x, y, 0);
            down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            tabView.dispatchTouchEvent(down);
            tabView.dispatchTouchEvent(up);
            down.recycle();
            up.recycle();
            WechatGlass.log("转发导航点击 → 第 " + index + " 格 (" + (int) x + "," + (int) y + ")");
        } catch (Throwable t) {
            WechatGlass.warnOnce("tap-fail", "转发导航点击失败: " + t);
        }
    }

    /** 诊断用：把树里跟底栏有关的类名打出来。 */
    static String describe(View root) {
        StringBuilder sb = new StringBuilder();
        collect(root, sb, 0);
        return sb.length() == 0 ? "(没找到 com.tencent 的视图)" : sb.toString();
    }

    private static void collect(View v, StringBuilder sb, int depth) {
        if (v == null || depth > 30 || sb.length() > 1200) return;
        String n = v.getClass().getName();
        if (n.contains("Tab") || n.contains("tab")) {
            sb.append(depth).append(':').append(n).append(' ');
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collect(g.getChildAt(i), sb, depth + 1);
            }
        }
    }
}
