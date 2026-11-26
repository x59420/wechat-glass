package com.zaka.wechatglass;

import android.content.Context;
import android.content.res.Configuration;

/**
 * 统一深浅色判断：设置里的“跟随系统 / 浅色 / 深色”优先于系统 uiMode。
 * 微信自己的深色模式不一定跟系统一致，顶栏、弹层、聊天时间必须用同一套判断。
 */
final class WxTheme {

    private WxTheme() {
    }

    static boolean isDark(Context context) {
        try {
            int mode = DockPrefs.themeMode(context);
            if (mode == 2) return true;
            if (mode == 1) return false;
            return (context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }
}
