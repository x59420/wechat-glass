package com.zaka.wechatglass;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * 微信进程里的运行日志 -> 模块设置页。
 *
 * 为什么要有这一层：模块跑在微信进程里，日志只能进 LSPosed 的日志页，
 * 用户在手机上根本看不到。设置页跑在模块自己的进程，和微信的数据目录是两个 UID，
 * 读不到对方的文件，所以这里用一条**显式广播**（setPackage 指到模块自己）
 * 把日志送过去，模块进程收到后落盘，设置页里就能看、能导出、能清空。
 *
 * 约束：
 *   - 只在有日志时才有动作，1.2 秒合并一次，绝不按行发广播；
 *   - 缓冲区有上限，微信长时间在前台也不会把内存吃掉；
 *   - 任何一步失败都静默 —— 日志通道不能用影响宿主。
 */
public final class LogBus {

    /** 模块进程里的接收端。 */
    static final String ACTION = "com.zaka.wechatglass.LOG";

    static final String EXTRA_LINES = "lines";

    /** 一次最多带多少行、一行最长多少字符。 */
    private static final int MAX_PENDING = 300;
    private static final int MAX_LINE = 500;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<String> PENDING = new ArrayList<String>();

    private static volatile boolean scheduled;
    private static volatile Context app;
    private static volatile boolean disabled;
    private static volatile int failures;

    private LogBus() {
    }

    /** 日志统一入口：普通流程、警告、错误都从这里进。 */
    static void write(String line) {
        if (disabled || line == null) return;
        String text = line.length() > MAX_LINE ? line.substring(0, MAX_LINE) : line;
        synchronized (PENDING) {
            if (PENDING.size() >= MAX_PENDING) PENDING.remove(0);
            PENDING.add(text);
        }
        if (scheduled) return;
        scheduled = true;
        try {
            MAIN.postDelayed(FLUSH, 1200L);
        } catch (Throwable t) {
            scheduled = false;
        }
    }

    private static final Runnable FLUSH = new Runnable() {
        @Override
        public void run() {
            scheduled = false;
            flush();
        }
    };

    private static void flush() {
        String[] lines;
        synchronized (PENDING) {
            if (PENDING.isEmpty()) return;
            lines = PENDING.toArray(new String[0]);
            PENDING.clear();
        }
        Context ctx = context();
        if (ctx == null) {
            disabled = true;
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
            Intent intent = new Intent(ACTION);
            // 必须写成显式广播：Android 8 以后，给「别的应用的清单接收器」发隐式广播会被直接丢掉，
            // 表现就是设置页日志永远是空的。
            intent.setClassName(WechatGlass.PKG, WechatGlass.PKG + ".LogReceiver");
            intent.putExtra(EXTRA_LINES, sb.toString());
            ctx.sendBroadcast(intent);
        } catch (Throwable t) {
            // 失败不永久关掉：旧版模块进程没注册接收器时这里会失败，
            // 一旦永久关闭，用户升级模块后也永远收不到日志了。宁可下一轮再试。
            failures++;
            if (failures == 3) {
                WechatGlass.warn("日志广播送不出去（接收端可能没注册）：" + t);
            }
        }
    }

    /** 微信进程里拿 Context：优先缓存，其次问 ActivityThread。 */
    private static Context context() {
        Context cached = app;
        if (cached != null) return cached;
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Object application = thread.getMethod("currentApplication").invoke(null);
            if (application instanceof Context) {
                Context ctx = (Context) application;
                app = ctx;
                return ctx;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
