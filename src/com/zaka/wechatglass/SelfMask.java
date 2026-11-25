package com.zaka.wechatglass;

import android.view.View;
import java.util.WeakHashMap;

/**
 * 本模块自己施加的 alpha 遮罩登记表。
 *
 * 背景（0.5.6 实测日志）：玻璃显示时会把原生底栏整条 alpha 归零，清理底部渐变压暗层时
 * 也会把若干装饰层 alpha 归零。可是「原生底栏该不该显示」这条判据又去读 alpha ——
 * 读到的正是我们自己写进去的 0，于是判成「宿主把底栏藏了」，把玻璃收起来；玻璃一收，
 * alpha 还原成 1，下一帧又判可见，玻璃再出来……
 *
 * 结果就是日志里那 68 次「底栏隐藏」+ 53 次「底栏恢复显示」的来回抖，用户看到的是
 * 「Dock 出来一下立马消失」「评论区返回以后不回来」。
 *
 * 处理办法：凡是本模块为了给玻璃让位而改的 alpha，都在这里记下原值；判可见性的时候用
 * 原值参与计算，绝不再把自己的改动当成宿主的意图。
 */
final class SelfMask {

    private static final WeakHashMap<View, Float> ORIGINAL = new WeakHashMap<View, Float>();

    private SelfMask() {
    }

    /** 归零并把原值记下来（重复调用不会覆盖第一次记下的原值）。 */
    static void mask(View v) {
        if (v == null) return;
        synchronized (ORIGINAL) {
            if (!ORIGINAL.containsKey(v)) ORIGINAL.put(v, v.getAlpha());
        }
        try {
            v.setAlpha(0f);
        } catch (Throwable ignored) {
        }
    }

    /** 还原成原值；没登记过就还原成 1。 */
    static void restore(View v) {
        if (v == null) return;
        Float a;
        synchronized (ORIGINAL) {
            a = ORIGINAL.remove(v);
        }
        try {
            v.setAlpha(a == null ? 1f : a);
        } catch (Throwable ignored) {
        }
    }

    /** 这个 View 的 alpha 被我们改过时返回「原本的」alpha，否则返回当前值。 */
    static float effectiveAlpha(View v) {
        if (v == null) return 1f;
        synchronized (ORIGINAL) {
            Float a = ORIGINAL.get(v);
            if (a != null) return a;
        }
        try {
            return v.getAlpha();
        } catch (Throwable t) {
            return 1f;
        }
    }

    static boolean masked(View v) {
        if (v == null) return false;
        synchronized (ORIGINAL) {
            return ORIGINAL.containsKey(v);
        }
    }

    /** 页面销毁时把所有登记清掉，避免稀疏表里留下失效引用。 */
    static void clear() {
        synchronized (ORIGINAL) {
            ORIGINAL.clear();
        }
    }
}
