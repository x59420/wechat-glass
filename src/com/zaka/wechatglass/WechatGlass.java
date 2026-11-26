package com.zaka.wechatglass;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

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
    static final String VER = "0.7.16";

    private static volatile boolean verbose = true;
    private static final Set<String> SAID = new HashSet<String>();

    private WechatGlass() {
    }

    static void setVerbose(boolean value) {
        verbose = value;
        LogBus.setEnabled(value);
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
        LogBus.write("I " + message);
    }

    static void warn(String message) {
        try {
            Log.w(TAG, message);
        } catch (Throwable ignored) {
        }
        LogBus.write("W " + message);
    }

    static void error(String message, Throwable t) {
        try {
            Log.e(TAG, message, t);
        } catch (Throwable ignored) {
        }
        LogBus.write("E " + message + (t == null ? "" : (" <- " + t)));
    }

    /** 弹一条提示。只在主线程上、拿得到 Context 的时候弹，任何异常都吞掉。 */
    static void toast(final Context context, final String message) {
        if (context == null) return;
        try {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Toast.makeText(context.getApplicationContext(), message,
                                Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 宿主进程里拿不到现成 Context 时用：跟系统要当前 Application。 */
    static void toast(final String message) {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object app = activityThread.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        toast((Context) app(), message);
                    }
                }, 1200L);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 宿主进程里拿当前 Application；拿来读配置、弹提示。拿不到返回 null。 */
    static Context context() {
        try {
            Object app = app();
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object app() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            return activityThread.getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
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
