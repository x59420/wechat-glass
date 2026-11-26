package com.zaka.wechatglass;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * wxglass 自己的配置。
 *
 * 存在抖音进程里的私有 SharedPreferences 中：模块的代码本来就跑在宿主进程里，
 * 设置页（注入在抖音设置页里的那个入口）和 Dock 用的是进程里的同一份，
 * 不需要跨进程、也不需要读写模块 APK 所在目录。
 */
final class DockPrefs {

    static final String NAME = "wxglass_settings";

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_HEIGHT = "dock_height_dp";
    private static final String KEY_TAB_WIDTH = "tab_width_dp";
    private static final String KEY_SIDE_MARGIN = "side_margin_dp";
    private static final String KEY_BOTTOM_GAP = "bottom_gap_dp";
    private static final String KEY_SHOW_PLUS = "show_plus";
    private static final String KEY_SHOW_LABELS = "show_labels";
    private static final String KEY_ENTRY_OFFSET = "entry_top_offset_dp";
    /** 日志开关：关掉之后只留警告/错误，LSPosed 里不再刷日常流程。 */
    private static final String KEY_LOG = "verbose_log";
    private static final String KEY_THEME_MODE = "theme_mode";
    /** 玻璃表面的不透明度（0-100，100 = 现在这套默认浓淡）。 */
    private static final String KEY_GLASS_ALPHA = "glass_alpha";
    /** 隐藏玻璃背景：只留图标，不画那块玻璃。 */
    private static final String KEY_HIDE_BG = "hide_glass_background";
    /** 底部背景透明：清掉原生底栏那一条的底色 / 渐变压暗层。 */
    private static final String KEY_BOTTOM_TRANSPARENT = "bottom_transparent";
    /** 原生弹层 / 菜单背景透明：底部菜单、分享面板这类宿主自己铺的那层底。 */
    private static final String KEY_MENU_TRANSPARENT = "menu_transparent";
    /** 抓不到视频画面时，用界面主色垫底（不开就是原来的纯黑玻璃）。 */
    private static final String KEY_VIDEO_TINT = "video_tint_fallback";
    /** 消息防撤回：拦住把消息改写成「撤回了一条消息」的那次落库。 */
    private static final String KEY_ANTI_REVOKE = "anti_revoke";
    /** 聊天页头像上方显示时分秒。 */
    private static final String KEY_CHAT_TIME = "chat_time";
    /** 配置版本号：模块里的设置页写盘时打一个时间戳，宿主这边比它新才覆盖。 */
    private static final String KEY_REV = "settings_rev";

    /** 默认值就是 0.4.x 一直在用的那套参数：64dp 高、单格 76dp、左右各 24dp、离底 8dp。 */
    static final int DEF_HEIGHT = 64;
    static final int DEF_TAB_WIDTH = 76;
    static final int DEF_SIDE_MARGIN = 24;
    static final int DEF_BOTTOM_GAP = 8;

    static final int MIN_HEIGHT = 52;
    static final int MAX_HEIGHT = 88;
    static final int MIN_TAB_WIDTH = 60;
    static final int MAX_TAB_WIDTH = 100;
    static final int MIN_SIDE_MARGIN = 12;
    static final int MAX_SIDE_MARGIN = 40;
    static final int MIN_BOTTOM_GAP = 0;
    static final int MAX_BOTTOM_GAP = 28;

    private DockPrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context context) {
        return readBool(context, KEY_ENABLED, true);
    }

    static void setEnabled(Context context, boolean value) {
        writeBool(context, KEY_ENABLED, value);
    }

    static boolean showPlusButton(Context context) {
        return readBool(context, KEY_SHOW_PLUS, true);
    }

    static void setShowPlusButton(Context context, boolean value) {
        writeBool(context, KEY_SHOW_PLUS, value);
    }

    static boolean showLabels(Context context) {
        return readBool(context, KEY_SHOW_LABELS, true);
    }

    static void setShowLabels(Context context, boolean value) {
        writeBool(context, KEY_SHOW_LABELS, value);
    }

    static int heightDp(Context context) {
        return clamp(readInt(context, KEY_HEIGHT, DEF_HEIGHT), MIN_HEIGHT, MAX_HEIGHT);
    }

    static void setHeightDp(Context context, int value) {
        writeInt(context, KEY_HEIGHT, clamp(value, MIN_HEIGHT, MAX_HEIGHT));
    }

    static int tabWidthDp(Context context) {
        return clamp(readInt(context, KEY_TAB_WIDTH, DEF_TAB_WIDTH), MIN_TAB_WIDTH, MAX_TAB_WIDTH);
    }

    static void setTabWidthDp(Context context, int value) {
        writeInt(context, KEY_TAB_WIDTH, clamp(value, MIN_TAB_WIDTH, MAX_TAB_WIDTH));
    }

    static int sideMarginDp(Context context) {
        return clamp(readInt(context, KEY_SIDE_MARGIN, DEF_SIDE_MARGIN),
                MIN_SIDE_MARGIN, MAX_SIDE_MARGIN);
    }

    static void setSideMarginDp(Context context, int value) {
        writeInt(context, KEY_SIDE_MARGIN, clamp(value, MIN_SIDE_MARGIN, MAX_SIDE_MARGIN));
    }

    static int bottomGapDp(Context context) {
        return clamp(readInt(context, KEY_BOTTOM_GAP, DEF_BOTTOM_GAP),
                MIN_BOTTOM_GAP, MAX_BOTTOM_GAP);
    }

    static void setBottomGapDp(Context context, int value) {
        writeInt(context, KEY_BOTTOM_GAP, clamp(value, MIN_BOTTOM_GAP, MAX_BOTTOM_GAP));
    }

    /** 设置入口在抖音设置页里往下挪多少（避开顶部标题栏）。 */
    static int entryOffsetDp(Context context) {
        return clamp(readInt(context, KEY_ENTRY_OFFSET, 0), 0, 240);
    }

    /** 日志开关（默认开）：关掉只保留警告/错误。 */
    static boolean logEnabled(Context context) {
        return readBool(context, KEY_LOG, true);
    }

    /** 消息防撤回。默认开。 */
    static boolean antiRevoke(Context context) {
        return readBool(context, KEY_ANTI_REVOKE, true);
    }

    static void setAntiRevoke(Context context, boolean value) {
        writeBool(context, KEY_ANTI_REVOKE, value);
    }

    /** 聊天页头像上方显示时分秒。默认开。 */
    static boolean chatTime(Context context) {
        return readBool(context, KEY_CHAT_TIME, true);
    }

    static void setChatTime(Context context, boolean value) {
        writeBool(context, KEY_CHAT_TIME, value);
    }

    /** 0=跟随系统，1=浅色，2=深色。 */
    static int themeMode(Context context) {
        return clamp(readInt(context, KEY_THEME_MODE, 0), 0, 2);
    }

    static void setThemeMode(Context context, int value) {
        writeInt(context, KEY_THEME_MODE, clamp(value, 0, 2));
    }

    /** 玻璃表面不透明度，0-100。默认 100 = 和以前一模一样，不改外观。 */
    static int glassAlpha(Context context) {
        return clamp(readInt(context, KEY_GLASS_ALPHA, 100), 0, 100);
    }

    static void setGlassAlpha(Context context, int value) {
        writeInt(context, KEY_GLASS_ALPHA, clamp(value, 0, 100));
    }

    /** 隐藏玻璃背景：只留图标和「＋」，不画那块玻璃。默认关。 */
    static boolean hideGlassBackground(Context context) {
        return readBool(context, KEY_HIDE_BG, false);
    }

    static void setHideGlassBackground(Context context, boolean value) {
        writeBool(context, KEY_HIDE_BG, value);
    }

    /** 底部背景透明。默认开；关掉就保留抖音原生那条底色。 */
    static boolean bottomTransparent(Context context) {
        return readBool(context, KEY_BOTTOM_TRANSPARENT, true);
    }

    static void setBottomTransparent(Context context, boolean value) {
        writeBool(context, KEY_BOTTOM_TRANSPARENT, value);
    }

    /** 原生弹层 / 菜单背景透明。默认开。 */
    static boolean menuTransparent(Context context) {
        return readBool(context, KEY_MENU_TRANSPARENT, true);
    }

    static void setMenuTransparent(Context context, boolean value) {
        writeBool(context, KEY_MENU_TRANSPARENT, value);
    }

    /** 视频画面抓不到时的主色兜底。默认开；关掉就是原来的黑玻璃。 */
    static boolean videoTintFallback(Context context) {
        return readBool(context, KEY_VIDEO_TINT, true);
    }

    static void setVideoTintFallback(Context context, boolean value) {
        writeBool(context, KEY_VIDEO_TINT, value);
    }

    static void setLogEnabled(Context context, boolean value) {
        writeBool(context, KEY_LOG, value);
    }

    static void setEntryOffsetDp(Context context, int value) {
        writeInt(context, KEY_ENTRY_OFFSET, clamp(value, 0, 240));
    }

    static void resetAll(Context context) {
        try {
            prefs(context).edit().clear()
                    .putLong(KEY_REV, System.currentTimeMillis()).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 让抖音进程读得到这份配置。
     *
     * 设置界面跑在模块进程里，写的是模块包名下的 SharedPreferences；宿主进程用
     * XSharedPreferences 直接读这个文件。这里把文件和目录设成「其他用户可读/可进入」，
     * 不需要 Xposed API（模块进程里没有那个类），纯 java.io 就够。
     */
    static void publish(Context context) {
        try {
            String dataDir = context.getApplicationInfo().dataDir;
            java.io.File file = new java.io.File(dataDir, "shared_prefs/" + NAME + ".xml");
            java.io.File dir = file.getParentFile();
            if (dir != null && dir.isDirectory()) dir.setExecutable(true, false);
            if (file.isFile()) file.setReadable(true, false);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 模块设置页

    private static long lastSyncAt;

    /**
     * 把模块自己那个设置页（桌面图标的 wxglass 设置）里改的东西同步进来。
     *
     * 设置页跑在模块进程里，写的是模块包名的 SharedPreferences；
     * 这边（抖音进程）用 XSharedPreferences 直接读那份文件，谁的版本号新用谁的。
     * 读不到（旧式框架/文件不可读）就什么也不做，Dock 继续用本地配置，不会崩。
     *
     * @return 有变化返回 true
     */
    static boolean syncFromModule(Context context) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastSyncAt < 1000L) return false;
        lastSyncAt = now;
        try {
            de.robv.android.xposed.XSharedPreferences remote =
                    new de.robv.android.xposed.XSharedPreferences(WechatGlass.PKG, NAME);
            remote.reload();
            long remoteRev = remote.getLong(KEY_REV, 0L);
            long localRev = prefs(context).getLong(KEY_REV, 0L);
            if (remoteRev <= localRev) return false;

            SharedPreferences.Editor editor = prefs(context).edit();
            editor.putBoolean(KEY_ENABLED, remote.getBoolean(KEY_ENABLED, true));
            editor.putBoolean(KEY_SHOW_PLUS, remote.getBoolean(KEY_SHOW_PLUS, true));
            editor.putBoolean(KEY_SHOW_LABELS, remote.getBoolean(KEY_SHOW_LABELS, true));
            editor.putInt(KEY_HEIGHT, remote.getInt(KEY_HEIGHT, DEF_HEIGHT));
            editor.putInt(KEY_TAB_WIDTH, remote.getInt(KEY_TAB_WIDTH, DEF_TAB_WIDTH));
            editor.putInt(KEY_SIDE_MARGIN, remote.getInt(KEY_SIDE_MARGIN, DEF_SIDE_MARGIN));
            editor.putInt(KEY_BOTTOM_GAP, remote.getInt(KEY_BOTTOM_GAP, DEF_BOTTOM_GAP));
            editor.putInt(KEY_ENTRY_OFFSET, remote.getInt(KEY_ENTRY_OFFSET, 0));
            editor.putBoolean(KEY_LOG, remote.getBoolean(KEY_LOG, true));
            editor.putInt(KEY_THEME_MODE, remote.getInt(KEY_THEME_MODE, 0));
            editor.putInt(KEY_GLASS_ALPHA, remote.getInt(KEY_GLASS_ALPHA, 100));
            editor.putBoolean(KEY_HIDE_BG, remote.getBoolean(KEY_HIDE_BG, false));
            editor.putBoolean(KEY_BOTTOM_TRANSPARENT,
                    remote.getBoolean(KEY_BOTTOM_TRANSPARENT, true));
            editor.putBoolean(KEY_MENU_TRANSPARENT,
                    remote.getBoolean(KEY_MENU_TRANSPARENT, true));
            editor.putBoolean(KEY_VIDEO_TINT, remote.getBoolean(KEY_VIDEO_TINT, true));
            editor.putBoolean(KEY_ANTI_REVOKE, remote.getBoolean(KEY_ANTI_REVOKE, true));
            editor.putBoolean(KEY_CHAT_TIME, remote.getBoolean(KEY_CHAT_TIME, true));
            editor.putLong(KEY_REV, remoteRev);
            editor.apply();
            WechatGlass.setVerbose(remote.getBoolean(KEY_LOG, true));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean readBool(Context context, String key, boolean def) {
        try {
            return prefs(context).getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private static void writeBool(Context context, String key, boolean value) {
        try {
            // 用 commit()：设置界面（模块进程）写完马上要 publish 让宿主读得到，
            // apply() 是异步落盘，紧接着 publish 会作用在旧文件上，权限也就丢了。
            prefs(context).edit().putBoolean(key, value)
                    .putLong(KEY_REV, System.currentTimeMillis()).commit();
            publishIfModule(context);
        } catch (Throwable ignored) {
        }
    }

    /** 只有模块自己那个进程写盘时才需要把文件开放给宿主读。 */
    private static void publishIfModule(Context context) {
        try {
            if (context != null && WechatGlass.PKG.equals(context.getPackageName())) {
                publish(context);
            }
        } catch (Throwable ignored) {
        }
    }

    private static int readInt(Context context, String key, int def) {
        try {
            return prefs(context).getInt(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private static void writeInt(Context context, String key, int value) {
        try {
            prefs(context).edit().putInt(key, value)
                    .putLong(KEY_REV, System.currentTimeMillis()).commit();
            publishIfModule(context);
        } catch (Throwable ignored) {
        }
    }

    static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }
}
