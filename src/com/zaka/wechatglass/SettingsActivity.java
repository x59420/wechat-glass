package com.zaka.wechatglass;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * 设置页入口。
 *
 * 界面本身在 SettingsHost + SettingsScreen（Compose）：整屏液态玻璃、顶部分类、
 * 圆角卡片、状态栏/导航栏透明。这个类只负责把 Compose 挂上去。
 *
 * 入口有两个：
 *   1. 微信「＋」面板上的那颗 wxglass 胶囊（WxPlusPage）；
 *   2. 桌面 / LSPosed 里的 wxglass 图标（本 Activity 的 LAUNCHER 入口）。
 */
public final class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            SettingsHost.attach(this, WechatGlass.VER);
        } catch (Throwable t) {
            android.widget.TextView tv = new android.widget.TextView(this);
            tv.setText("界面初始化失败：" + t);
            tv.setPadding(48, 48, 48, 48);
            setContentView(tv);
        }
    }

    /** 从微信进程里拉起设置页。 */
    static boolean open(Activity host) {
        try {
            Intent intent = new Intent();
            intent.setClassName(WechatGlass.PKG, WechatGlass.PKG + ".SettingsActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            host.startActivity(intent);
            return true;
        } catch (Throwable t) {
            WechatGlass.warn("拉起设置页失败: " + t);
            return false;
        }
    }
}
