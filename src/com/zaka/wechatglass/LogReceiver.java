package com.zaka.wechatglass;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 模块进程里的日志接收端。
 *
 * 微信进程（LogBus）把日志合并成一串发过来，这里落到模块自己的 files 目录。
 * 设置页直接读这个文件，所以「导出 / 清空」都不需要任何存储权限。
 */
public final class LogReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (context == null || intent == null) return;
            if (!LogBus.ACTION.equals(intent.getAction())) return;
            String lines = intent.getStringExtra(LogBus.EXTRA_LINES);
            if (lines == null || lines.length() == 0) return;
            LogStore.append(context.getApplicationContext(), lines);
        } catch (Throwable ignored) {
        }
    }
}
