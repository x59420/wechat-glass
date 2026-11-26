package com.zaka.wechatglass;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ListView;

import androidx.recyclerview.widget.RecyclerView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 聊天页：每条消息的头像上方标一行「时:分:秒」。
 *
 * 做法是往聊天页窗口里贴一层**只画字、不吃触摸**的透明 View，不去改微信的消息列表本身：
 *   - 找窗口里最大的那个列表（RecyclerView / ListView）；
 *   - 遍历它当前可见的行，从 adapter 里取出这一行对应的消息对象；
 *   - 从消息对象里翻出时间（微信用的是混淆名，这里按「值落在合理时间范围」识别）；
 *   - 在头像正上方居中画一行 9sp 的小字，跟随滚动实时重画。
 *
 * 一个字都不写进微信的 View 树，所以不会打乱它自己的布局和点击。
 */
final class WxChatTime {

    /** 已知的聊天页类名，挂上 pause/destroy 用来及时摘掉浮层。 */
    private static final String[] CHAT_ACTIVITIES = {
            "com.tencent.mm.ui.chatting.ChattingUI",
            "com.tencent.mm.ui.chatting.AppBrandServiceChattingUI",
            "com.tencent.mm.ui.chatting.ChattingUIProxyUI",
    };

    private static final Map<Activity, Layer> LAYERS = new WeakHashMap<Activity, Layer>();
    private static volatile boolean hooked;

    private WxChatTime() {
    }

    static void hook(ClassLoader cl) {
        if (hooked) return;
        hooked = true;
        int count = 0;
        for (String name : CHAT_ACTIVITIES) {
            try {
                Class<?> cls = XposedHelpers.findClassIfExists(name, cl);
                if (cls == null) continue;
                XC_MethodHook off = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object self = param.thisObject;
                            if (self instanceof Activity) detach((Activity) self);
                        } catch (Throwable ignored) {
                        }
                    }
                };
                XposedBridge.hookAllMethods(cls, "onPause", off);
                XposedBridge.hookAllMethods(cls, "onDestroy", off);
                count++;
            } catch (Throwable t) {
                WechatGlass.warnOnce("chattime-" + name, "聊天时间:挂 " + name + " 失败: " + t);
            }
        }
        WechatGlass.log("聊天时间:已挂上 " + count + " 个聊天页");
    }

    /** 判断是不是聊天页：类名里带 chatting / chatroom 就认。 */
    static boolean isChatPage(Activity act) {
        if (act == null) return false;
        try {
            String name = act.getClass().getName().toLowerCase(Locale.ROOT);
            return name.contains("chatting") || name.contains("chatroom")
                    || name.contains("chat");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 聊天页回来后装浮层；设置里关掉了就顺手摘掉。 */
    static void maybeAttach(Activity act) {
        if (act == null) return;
        WechatGlass.warnOnce("chattime-page-" + act.getClass().getName(),
                "聊天时间:页面 " + act.getClass().getSimpleName()
                        + " isChat=" + isChatPage(act));
        if (!isChatPage(act)) return;
        attach(act);
    }

    /** 聊天 Fragment 自己知道自己打开了，直接强制挂，不再靠 Activity 类名。 */
    static void attachForChat(Activity act) {
        if (act == null) return;
        attach(act);
    }

    private static void attach(Activity act) {
        Context ctx = WechatGlass.context();
        if (ctx != null && !DockPrefs.chatTime(ctx)) {
            detach(act);
            return;
        }
        synchronized (LAYERS) {
            Layer old = LAYERS.get(act);
            if (old != null && old.getParent() != null) return;
        }
        try {
            View decor = act.getWindow() == null ? null : act.getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) return;
            Layer layer = new Layer(act);
            layer.setElevation(1000f);
            layer.setTranslationZ(1000f);
            ((ViewGroup) decor).addView(layer, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            layer.bringToFront();
            synchronized (LAYERS) {
                LAYERS.put(act, layer);
            }
            WechatGlass.log("聊天时间:已挂上（" + act.getClass().getSimpleName() + "）");
        } catch (Throwable t) {
            WechatGlass.warnOnce("chattime-attach", "聊天时间:挂浮层失败: " + t);
        }
    }

    static void detach(Activity act) {
        if (act == null) return;
        Layer layer;
        synchronized (LAYERS) {
            layer = LAYERS.remove(act);
        }
        if (layer == null) return;
        try {
            layer.stop();
            ViewGroup parent = (ViewGroup) layer.getParent();
            if (parent != null) parent.removeView(layer);
        } catch (Throwable ignored) {
        }
    }

    /** 设置里改了开关：全部摘掉，聊天页 resume 时会按新开关重装。 */
    static void onSettingsChanged() {
        List<Activity> acts;
        synchronized (LAYERS) {
            acts = new ArrayList<Activity>(LAYERS.keySet());
        }
        for (Activity act : acts) {
            detach(act);
            maybeAttach(act);
        }
    }

    // ------------------------------------------------------------ 画字的那一层

    private static final class Layer extends View {

        private final Activity host;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final SimpleDateFormat format =
                new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        private final int gapPx;
        private final int minAvatarPx;
        private final int maxAvatarPx;
        private final int[] selfLoc = new int[2];

        /** position -> {时间毫秒, 记下来的时刻}。位置会随新消息变，所以只缓存一小会儿。 */
        private final Map<Integer, long[]> times = new HashMap<Integer, long[]>();
        private final Map<View, ImageView> avatars = new WeakHashMap<View, ImageView>();

        private boolean alive = true;
        private boolean logged;

        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!alive) return;
                invalidate();
                postDelayed(this, 700L);
            }
        };

        Layer(Activity host) {
            super(host);
            this.host = host;
            setWillNotDraw(false);
            setClickable(false);
            setFocusable(false);
            float density = getResources().getDisplayMetrics().density;
            float scaled = getResources().getDisplayMetrics().scaledDensity;
            // 「字小一点」：9sp，够看清又不压画面。
            paint.setTextSize(9f * scaled);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(isNight() ? 0xFF9AA0A6 : 0xFF8A8F98);
            gapPx = Math.max(1, (int) (3f * density));
            minAvatarPx = (int) (24f * density);
            maxAvatarPx = (int) (64f * density);
        }

        private boolean isNight() {
            return WxTheme.isDark(host);
        }

        void stop() {
            alive = false;
            removeCallbacks(tick);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            alive = true;
            postDelayed(tick, 400L);
        }

        @Override
        protected void onDetachedFromWindow() {
            stop();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!alive) return;
            try {
                View list = findList();
                if (list == null) {
                    if (!logged) {
                        logged = true;
                        WechatGlass.log("聊天时间:按头像回退");
                    }
                    drawAvatars(canvas);
                    return;
                }
                getLocationInWindow(selfLoc);

                int children = childCount(list);
                if (!logged) {
                    logged = true;
                    int av = 0;
                    long tv = 0L;
                    if (children > 0) {
                        View r = childAt(list, 0);
                        if (r != null) {
                            av = avatarOf(r) == null ? 0 : 1;
                            tv = timeOfRow(list, 0, r);
                        }
                    }
                    WechatGlass.log("聊天时间:list=" + list.getClass().getName()
                            + " rows=" + children + " avatar=" + av + " time=" + tv);
                }
                for (int i = 0; i < children; i++) {
                    View row = childAt(list, i);
                    if (row == null) continue;
                    ImageView avatar = avatarOf(row);
                    if (avatar == null) continue;
                    long time = timeOfRow(list, i, row);
                    if (time <= 0L) continue;

                    int[] avatarLoc = new int[2];
                    avatar.getLocationInWindow(avatarLoc);
                    float cx = avatarLoc[0] - selfLoc[0] + avatar.getWidth() / 2f;
                    float top = avatarLoc[1] - selfLoc[1];
                    if (top < gapPx) continue;
                    if (cx < 0 || cx > getWidth()) continue;

                    String text;
                    try {
                        text = format.format(new Date(time));
                    } catch (Throwable t) {
                        continue;
                    }
                    canvas.drawText(text, cx, top - gapPx, paint);
                }
            } catch (Throwable t) {
                WechatGlass.warnOnce("chattime-draw", "聊天时间:绘制失败: " + t);
            }
        }

        // -------------------------------------------------------- 找列表 / 行 / 头像

        /** 不依赖列表容器：直接扫 ChattingAvatarImageView，时间从行 tag 取。 */
        private void drawAvatars(Canvas canvas) {
            try {
                View decor = host.getWindow() == null ? null : host.getWindow().getDecorView();
                if (decor == null) return;
                getLocationInWindow(selfLoc);
                java.util.ArrayDeque<View> q = new java.util.ArrayDeque<View>();
                java.util.IdentityHashMap<View, Boolean> seen =
                        new java.util.IdentityHashMap<View, Boolean>();
                q.add(decor);
                int budget = 20000;
                while (!q.isEmpty() && budget-- > 0) {
                    View v = q.poll();
                    if (v == null || seen.put(v, Boolean.TRUE) != null) continue;
                    if (v instanceof ImageView && v.isShown()
                            && v.getWidth() > 0 && v.getHeight() > 0) {
                        String cn = v.getClass().getName();
                        if (cn.contains("ChattingAvatar") || cn.contains("AvatarImageView")) {
                            View row = (View) v.getParent();
                            long time = row == null ? 0L : TimeReader.timeOfView(row);
                            if (time > 0L) {
                                int[] al = new int[2];
                                v.getLocationInWindow(al);
                                float cx = al[0] - selfLoc[0] + v.getWidth() / 2f;
                                float top = al[1] - selfLoc[1];
                                if (top > gapPx) {
                                    canvas.drawText(format.format(new Date(time)),
                                            cx, top - gapPx, paint);
                                }
                            }
                        }
                    }
                    if (v instanceof ViewGroup) {
                        ViewGroup g = (ViewGroup) v;
                        for (int i = 0; i < g.getChildCount(); i++) {
                            q.add(g.getChildAt(i));
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        private void dumpLists() {
            try {
                View decor = host.getWindow() == null ? null : host.getWindow().getDecorView();
                if (decor == null) return;
                java.util.ArrayDeque<View> q = new java.util.ArrayDeque<View>();
                java.util.IdentityHashMap<View, Boolean> seen =
                        new java.util.IdentityHashMap<View, Boolean>();
                q.add(decor);
                int n = 0;
                while (!q.isEmpty() && n < 40) {
                    View v = q.poll();
                    if (v == null || seen.put(v, Boolean.TRUE) != null) continue;
                    String c = v.getClass().getName();
                    String l = c.toLowerCase(Locale.ROOT);
                    if (l.contains("recycler") || l.contains("listview")
                            || l.contains("chat") || l.contains("scroll")) {
                        WechatGlass.log("聊天时间:view " + c + " "
                                + v.getWidth() + "x" + v.getHeight()
                                + " shown=" + v.isShown());
                        n++;
                    }
                    if (v instanceof ViewGroup) {
                        ViewGroup g = (ViewGroup) v;
                        for (int i = 0; i < g.getChildCount(); i++) {
                            q.add(g.getChildAt(i));
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        private View findList() {
            try {
                View decor = host.getWindow() == null ? null : host.getWindow().getDecorView();
                if (decor == null) return null;
                View best = null;
                int bestArea = 0;
                ArrayList<View> stack = new ArrayList<View>();
                stack.add(decor);
                int guard = 0;
                while (!stack.isEmpty() && guard++ < 4000) {
                    View v = stack.remove(stack.size() - 1);
                    if (v == null) continue;
                    String vc = v.getClass().getName();
                    boolean isList = vc.contains("RecyclerView") || vc.contains("ListView")
                            || vc.contains("WxRecyclerView") || vc.contains("MMListView");
                    if (isList) {
                        if (!v.isShown() || v.getVisibility() != View.VISIBLE) continue;
                        int area = v.getWidth() * v.getHeight();
                        if (area > bestArea) {
                            bestArea = area;
                            best = v;
                        }
                        continue;
                    }
                    if (v instanceof ViewGroup) {
                        ViewGroup group = (ViewGroup) v;
                        for (int i = 0; i < group.getChildCount(); i++) {
                            View child = group.getChildAt(i);
                            if (child != null && child != this) stack.add(child);
                        }
                    }
                }
                return best;
            } catch (Throwable t) {
                return null;
            }
        }

        private int childCount(View list) {
            return list instanceof ViewGroup ? ((ViewGroup) list).getChildCount() : 0;
        }

        private View childAt(View list, int index) {
            return list instanceof ViewGroup ? ((ViewGroup) list).getChildAt(index) : null;
        }

        /** 行 → 消息对象 → 时间。 */
        private long timeOfRow(View list, int childIndex, View row) {
            int position = -1;
            Object adapter = null;
            try {
                Method gap = list.getClass().getMethod("getChildAdapterPosition", View.class);
                Object p = gap.invoke(list, row);
                if (p instanceof Integer) position = (Integer) p;
                Method ga = list.getClass().getMethod("getAdapter");
                adapter = ga.invoke(list);
            } catch (Throwable ignored) {
            }
            if (position < 0) {
                try {
                    Method fp = list.getClass().getMethod("getFirstVisiblePosition");
                    Object p = fp.invoke(list);
                    if (p instanceof Integer) position = (Integer) p + childIndex;
                    Method ga = list.getClass().getMethod("getAdapter");
                    adapter = ga.invoke(list);
                } catch (Throwable ignored) {
                }
            }
            if (position < 0 || adapter == null) return 0L;
            Object item = itemAt(adapter, position);
            if (item == null) return 0L;

            long now = android.os.SystemClock.uptimeMillis();
            synchronized (times) {
                long[] cached = times.get(position);
                if (cached != null && now - cached[1] < 1200L) return cached[0];
                long value = TimeReader.timeOf(item);
                if (value <= 0L) {
                    // 新版微信把消息模型藏在行 View 的 tag / 子 View tag 里。
                    value = TimeReader.timeOfView(row);
                }
                times.put(position, new long[]{value, now});
                return value;
            }
        }

        /** adapter 里取第 position 条：优先 getItem(int)，退到内部的 List 字段。 */
        private Object itemAt(Object adapter, int position) {
            if (adapter == null) return null;
            try {
                Method getter = TimeReader.itemMethod(adapter.getClass());
                if (getter != null) {
                    Object value = getter.invoke(adapter, position);
                    if (value != null) return value;
                }
                List<?> data = TimeReader.itemList(adapter.getClass(), adapter);
                if (data != null && position >= 0 && position < data.size()) {
                    return data.get(position);
                }
            } catch (Throwable t) {
                WechatGlass.warnOnce("chattime-item", "聊天时间:取消息失败: " + t);
            }
            return null;
        }

        /** 头像：行里那个 24~64dp 的正方形图片，取最靠边的那一个（左右都可能）。 */
        private ImageView avatarOf(View row) {
            ImageView cached = avatars.get(row);
            if (cached != null && cached.getParent() != null) return cached;
            ImageView found = null;
            try {
                ArrayList<View> stack = new ArrayList<View>();
                stack.add(row);
                int guard = 0;
                while (!stack.isEmpty() && guard++ < 200) {
                    View v = stack.remove(stack.size() - 1);
                    if (v == null) continue;
                    if (v != row && v instanceof ImageView) {
                        ImageView candidate = (ImageView) v;
                        int w = candidate.getWidth();
                        int h = candidate.getHeight();
                        if (candidate.isShown()
                                && w >= minAvatarPx && w <= maxAvatarPx
                                && Math.abs(w - h) <= 2) {
                            int[] loc = new int[2];
                            candidate.getLocationInWindow(loc);
                            int[] rowLoc = new int[2];
                            row.getLocationInWindow(rowLoc);
                            int x = loc[0] - rowLoc[0];
                            if (x < row.getWidth() * 0.35f || x > row.getWidth() * 0.55f) {
                                found = candidate;
                                break;
                            }
                        }
                        continue;
                    }
                    if (v instanceof ViewGroup) {
                        ViewGroup group = (ViewGroup) v;
                        for (int i = 0; i < group.getChildCount(); i++) {
                            stack.add(group.getChildAt(i));
                        }
                    }
                }
            } catch (Throwable t) {
                return null;
            }
            if (found != null) avatars.put(row, found);
            return found;
        }
    }

    // ------------------------------------------------------------ 反射工具

    /** 从消息对象里翻出时间：微信用混淆名，按值的范围认。 */
    private static final class TimeReader {

        private static final Map<Class<?>, Method[]> METHODS =
                new WeakHashMap<Class<?>, Method[]>();
        private static final Map<Class<?>, Method> ITEM_METHODS =
                new WeakHashMap<Class<?>, Method>();
        private static final Map<Class<?>, Field> ITEM_FIELDS =
                new WeakHashMap<Class<?>, Field>();

        static long timeOf(Object message) {
            Class<?> cls = message.getClass();
            Method[] candidates = METHODS.get(cls);
            if (candidates == null) {
                ArrayList<Method> list = new ArrayList<Method>();
                try {
                    Method[] all = cls.getMethods();
                    // 名字里带 time 的先试（有些版本没混淆），其余 long 取值的兜底。
                    for (Method m : all) {
                        if (m.getParameterTypes().length != 0) continue;
                        Class<?> rt = m.getReturnType();
                        if (rt != long.class && rt != int.class) continue;
                        if ("hashCode".equals(m.getName())) continue;
                        if (m.getName().toLowerCase(Locale.ROOT).contains("time")) {
                            list.add(0, m);
                        } else {
                            list.add(m);
                        }
                    }
                } catch (Throwable ignored) {
                }
                candidates = list.toArray(new Method[0]);
                METHODS.put(cls, candidates);
            }
            for (Method m : candidates) {
                try {
                    Object value = m.invoke(message);
                    long raw = value instanceof Number ? ((Number) value).longValue() : 0L;
                    long ms = normalize(raw);
                    if (ms > 0L) return ms;
                } catch (Throwable ignored) {
                }
            }
            // 方法里翻不到就读字段。
            Class<?> c = cls;
            int depth = 0;
            while (c != null && depth++ < 6) {
                try {
                    for (Field f : c.getDeclaredFields()) {
                        if (f.getType() != long.class && f.getType() != int.class) continue;
                        String name = f.getName().toLowerCase(Locale.ROOT);
                        if (!name.contains("time")) continue;
                        f.setAccessible(true);
                        long ms = normalize(f.getLong(message));
                        if (ms > 0L) return ms;
                    }
                } catch (Throwable ignored) {
                }
                c = c.getSuperclass();
            }
            return 0L;
        }

        /** 从行 View 的 tag（以及子 View 的 tag）里找时间。 */
        static long timeOfView(View view) {
            return timeOfView(view, 0);
        }

        private static long timeOfView(View view, int depth) {
            if (view == null || depth > 12) return 0L;
            try {
                Object tag = view.getTag();
                if (tag != null) {
                    long t = timeOf(tag);
                    if (t > 0L) return t;
                }
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        long t = timeOfView(group.getChildAt(i), depth + 1);
                        if (t > 0L) return t;
                    }
                }
            } catch (Throwable ignored) {
            }
            return 0L;
        }

        /** 秒 / 毫秒 / 微秒三种都可能，统一成毫秒；不在合理范围内一律当 0。 */
        private static long normalize(long value) {
            if (value <= 0L) return 0L;
            if (value > 1000000000000L && value < 2200000000000L) return value;
            if (value > 1000000000L && value < 2200000000L) return value * 1000L;
            if (value > 1000000000000000L && value < 2200000000000000L) return value / 1000L;
            return 0L;
        }

        /** adapter 上的 getItem(int) 之类。 */
        static Method itemMethod(Class<?> adapterClass) {
            Method cached = ITEM_METHODS.get(adapterClass);
            if (cached != null) return cached;
            Method found = null;
            try {
                found = adapterClass.getMethod("getItem", int.class);
            } catch (Throwable ignored) {
            }
            if (found == null) {
                try {
                    found = adapterClass.getMethod("get", int.class);
                } catch (Throwable ignored) {
                }
            }
            if (found == null) {
                // 微信自己的 adapter 多半是 public 的 getItem，但有的版本是继承来的。
                for (Method m : adapterClass.getMethods()) {
                    if (m.getParameterTypes().length != 1) continue;
                    if (m.getParameterTypes()[0] != int.class) continue;
                    String name = m.getName().toLowerCase(Locale.ROOT);
                    if (name.contains("item") || name.equals("get")) {
                        found = m;
                        break;
                    }
                }
            }
            if (found != null) {
                try {
                    found.setAccessible(true);
                } catch (Throwable ignored) {
                }
                ITEM_METHODS.put(adapterClass, found);
            }
            return found;
        }

        /** adapter 内部的 List 字段（getItem 找不到时用）。 */
        static List<?> itemList(Class<?> adapterClass, Object adapter) {
            Field field = ITEM_FIELDS.get(adapterClass);
            if (field == null) {
                Class<?> c = adapterClass;
                int depth = 0;
                while (c != null && field == null && depth++ < 6) {
                    try {
                        for (Field f : c.getDeclaredFields()) {
                            if (!List.class.isAssignableFrom(f.getType())) continue;
                            String name = f.getName().toLowerCase(Locale.ROOT);
                            if (!name.contains("data") && !name.contains("list")
                                    && !name.contains("msg")) continue;
                            field = f;
                            break;
                        }
                    } catch (Throwable ignored) {
                    }
                    c = c.getSuperclass();
                }
                if (field != null) {
                    try {
                        field.setAccessible(true);
                    } catch (Throwable ignored) {
                    }
                    ITEM_FIELDS.put(adapterClass, field);
                }
            }
            if (field == null) return null;
            try {
                Object value = field.get(adapter);
                return value instanceof List ? (List<?>) value : null;
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
