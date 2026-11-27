package com.zaka.wechatglass;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/** 桌面图标是 activity-alias；通过它单独开关，不影响 SettingsActivity 被微信调用。 */
public final class DesktopIcon {
    private static final String ALIAS = "com.zaka.wechatglass.Launcher";

    private DesktopIcon() {
    }

    public static void apply(Context context, boolean hidden) {
        try {
            ComponentName alias = new ComponentName(context.getPackageName(), ALIAS);
            context.getPackageManager().setComponentEnabledSetting(
                    alias,
                    hidden ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Throwable t) {
            WechatGlass.warnOnce("desktop-icon", "桌面图标开关失败: " + t);
        }
    }
}
