package com.zaka.wechatglass;

import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * wxglass 的公共常量与日志。
 *
 * 这个模块只做一件事：把微信底部那一条原生导航栏换成悬浮液态玻璃 Dock。
 * 玻璃的形状、材质、参数全部沿用 dydock（KernelSU + Miuix 那套渲染器），
 * 点击仍然是转发给微信自己的原生导航项，模块不接管任何切换逻辑。
 */
public final class WechatGlass {

    /** 宿主包名。 */
    static final String HOST = "com.tencent.mm";

    /** 模块自己的包名（设置界面在模块进程里跑）。 */
    static final String PKG = "com.zaka.wechatglass";

    /** LSPosed 日志里搜索这个 tag。 */
    static final String TAG = "wxglass";

    /** 版本号，和 build.gradle.kts 里的 versionName 一起改。 */
    static final String VER = "0.1.0";

    private static volatile boolean verbose = true;
    private static final Set<String> SAID = new HashSet<String>();

    private WechatGlass() {
    }

    static void setVerbose(boolean value) {
        verbose = value;
    }

    static boolean verbose() {
        return verbose;
    }

    static void log(String message) {
        if (!verbose) return;
        try {
            Log.i(TAG, message);
        } catch (Throwable ignored) {
        }
    }

    static void warn(String message) {
        try {
            Log.w(TAG, message);
        } catch (Throwable ignored) {
        }
    }

    static void error(String message, Throwable t) {
        try {
            Log.e(TAG, message, t);
        } catch (Throwable ignored) {
        }
    }

    /** 同一个 key 只记一次，避免每秒刷屏把日志刷爆。 */
    static void warnOnce(String key, String message) {
        synchronized (SAID) {
            if (!SAID.add(key)) return;
        }
        warn(message);
    }
}
