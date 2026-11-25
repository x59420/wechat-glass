package com.zaka.wechatglass;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/**
 * 设置界面（模块进程）→ 抖音进程 的即时同步通道。
 *
 * 原来只靠 `XSharedPreferences` 去读模块那份 prefs 文件：写入用的是 apply()（异步落盘），
 * 文件又是「临时文件 + rename」换上去的，权限会跟着丢，宿主那边经常读到旧值甚至读不到 ——
 * 表现就是「设置里改了，开关不生效」。
 *
 * 现在两条腿走路：
 *   1. 写入改成同步 commit()，落盘后立刻把文件设成可读（DockPrefs.publish）；
 *   2. 设置界面每改一次，直接给抖音进程发一个广播，宿主收到就写进自己的 prefs 并马上应用。
 *      这条路不依赖文件权限，最快，也最稳。
 */
public final class SettingsSync {

    /**
     * 广播动作。
     *
     * 收端会按类型/范围逐个校验（所有值都走 DockPrefs 的 setter，本身就会 clamp），
     * 所以外来的伪造最多改个 Dock 尺寸，不会碰到任何别的状态。
     */
    public static final String ACTION = "com.zaka.wechatglass.SETTINGS_CHANGED";

    private static BroadcastReceiver receiver;

    private SettingsSync() {
    }

    // ------------------------------------------------------------ 抖音进程侧

    /** 在宿主进程里挂上接收器（Application.onCreate 之后调用一次）。 */
    static void register(Context context) {
        if (context == null || receiver != null) return;
        try {
            final Context app = context.getApplicationContext();
            receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    try {
                        if (intent == null) return;
                        apply(app, intent);
                    } catch (Throwable t) {
                        WechatGlass.warnOnce("settings-broadcast", "接收设置广播失败: " + t);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
            WechatGlass.log("设置:已挂上即时同步通道");
        } catch (Throwable t) {
            WechatGlass.warnOnce("settings-register", "注册设置广播失败: " + t);
        }
    }

    /** 把广播里的值写进宿主自己的配置，然后立刻作用到正在跑的 Dock。 */
    private static void apply(Context context, Intent intent) {
        boolean changed = false;
        if (intent.hasExtra("enabled")) {
            DockPrefs.setEnabled(context, intent.getBooleanExtra("enabled", true));
            changed = true;
        }
        if (intent.hasExtra("show_plus")) {
            DockPrefs.setShowPlusButton(context, intent.getBooleanExtra("show_plus", true));
            changed = true;
        }
        if (intent.hasExtra("show_labels")) {
            DockPrefs.setShowLabels(context, intent.getBooleanExtra("show_labels", true));
            changed = true;
        }
        if (intent.hasExtra("verbose_log")) {
            boolean verbose = intent.getBooleanExtra("verbose_log", true);
            DockPrefs.setLogEnabled(context, verbose);
            WechatGlass.setVerbose(verbose);
            changed = true;
        }
        if (intent.hasExtra("height")) {
            DockPrefs.setHeightDp(context, intent.getIntExtra("height", DockPrefs.DEF_HEIGHT));
            changed = true;
        }
        if (intent.hasExtra("tab_width")) {
            DockPrefs.setTabWidthDp(context, intent.getIntExtra("tab_width", DockPrefs.DEF_TAB_WIDTH));
            changed = true;
        }
        if (intent.hasExtra("side_margin")) {
            DockPrefs.setSideMarginDp(context, intent.getIntExtra("side_margin",
                    DockPrefs.DEF_SIDE_MARGIN));
            changed = true;
        }
        if (intent.hasExtra("bottom_gap")) {
            DockPrefs.setBottomGapDp(context, intent.getIntExtra("bottom_gap",
                    DockPrefs.DEF_BOTTOM_GAP));
            changed = true;
        }
        if (intent.hasExtra("theme_mode")) {
            DockPrefs.setThemeMode(context, intent.getIntExtra("theme_mode", 0));
            changed = true;
        }
        if (intent.hasExtra("glass_alpha")) {
            DockPrefs.setGlassAlpha(context, intent.getIntExtra("glass_alpha", 100));
            changed = true;
        }
        if (intent.hasExtra("hide_glass_background")) {
            DockPrefs.setHideGlassBackground(context,
                    intent.getBooleanExtra("hide_glass_background", false));
            changed = true;
        }
        if (intent.hasExtra("bottom_transparent")) {
            DockPrefs.setBottomTransparent(context,
                    intent.getBooleanExtra("bottom_transparent", true));
            changed = true;
        }
        if (intent.hasExtra("menu_transparent")) {
            DockPrefs.setMenuTransparent(context,
                    intent.getBooleanExtra("menu_transparent", true));
            changed = true;
        }
        if (intent.hasExtra("video_tint")) {
            DockPrefs.setVideoTintFallback(context,
                    intent.getBooleanExtra("video_tint", true));
            changed = true;
        }
        if (!changed) return;
        WechatGlass.log("设置:收到设置界面的改动，立刻应用");
        WxDockController.applySettingsToAll();
    }

    // ------------------------------------------------------------ 模块进程侧

    /** 设置界面每改一项就调一次：广播出去 + （兜底）把 prefs 文件设成可读。 */
    static void push(Context context, boolean enabled, boolean showPlus, boolean showLabels,
                     boolean verboseLog, int height, int tabWidth, int sideMargin, int bottomGap,
                     boolean bottomTransparent, boolean menuTransparent, boolean videoTint,
                     int themeMode, int glassAlpha, boolean hideBackground) {
        if (context == null) return;
        try {
            Intent intent = new Intent(ACTION);
            intent.setPackage(WechatGlass.HOST);
            intent.putExtra("enabled", enabled);
            intent.putExtra("show_plus", showPlus);
            intent.putExtra("show_labels", showLabels);
            intent.putExtra("verbose_log", verboseLog);
            intent.putExtra("height", height);
            intent.putExtra("tab_width", tabWidth);
            intent.putExtra("side_margin", sideMargin);
            intent.putExtra("bottom_gap", bottomGap);
            intent.putExtra("bottom_transparent", bottomTransparent);
            intent.putExtra("menu_transparent", menuTransparent);
            intent.putExtra("video_tint", videoTint);
            // 外观三项以前漏在广播外面，只能等宿主一秒轮询兜底：
            // 用户在设置里拖透明度会「慢一拍」，看起来就像没生效。
            intent.putExtra("theme_mode", themeMode);
            intent.putExtra("glass_alpha", glassAlpha);
            intent.putExtra("hide_glass_background", hideBackground);
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            WechatGlass.warnOnce("settings-push", "发送设置广播失败: " + t);
        }
        // 兜底：宿主下次启动时还能从文件里读到同一份配置。
        DockPrefs.publish(context);
    }
}
