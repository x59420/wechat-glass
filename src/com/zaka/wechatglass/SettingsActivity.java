package com.zaka.wechatglass;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 设置页。
 *
 * 为什么是模块自己的 Activity，而不是把 Compose 面板塞进微信：
 * 塞进宿主那一套要借宿主的窗口、宿主的资源、还要在微信进程里单独起一个隔离类加载器，
 * 任何一环被挡就是「点了没反应」。这里放在自己进程里，界面永远打得开，
 * 改完通过广播推给微信进程，微信那边立刻生效，不用重启。
 *
 * 入口有两个：
 *   1. 微信首页右上角「＋」弹出的那一页，列表最后一行（WxRowEntry）；
 *   2. 桌面 / LSPosed 里的 wxglass 图标（本 Activity 的 LAUNCHER 入口）。
 */
public final class SettingsActivity extends Activity {

    private Switch swEnabled;
    private Switch swLabels;
    private Switch swPlus;
    private Switch swBottom;
    private Switch swMenu;
    private Switch swTint;
    private Switch swHideBg;

    private SeekBar sbHeight;
    private SeekBar sbWidth;
    private SeekBar sbMargin;
    private SeekBar sbGap;
    private SeekBar sbAlpha;

    private TextView tvHeight;
    private TextView tvWidth;
    private TextView tvMargin;
    private TextView tvGap;
    private TextView tvAlpha;

    private boolean loading = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(build());
        } catch (Throwable t) {
            TextView tv = new TextView(this);
            tv.setText("界面初始化失败：" + t);
            tv.setPadding(48, 48, 48, 48);
            setContentView(tv);
            return;
        }
        load();
        loading = false;
    }

    // ------------------------------------------------------------ 界面

    private View build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF6F6F6);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(28));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView head = new TextView(this);
        head.setText("wxglass 液态玻璃 Dock");
        head.setTextSize(21f);
        head.setTextColor(0xFF111111);
        root.addView(head);

        TextView sub = new TextView(this);
        sub.setText("改动立刻生效，不用重启微信。" + WechatGlass.VER);
        sub.setTextSize(13f);
        sub.setTextColor(0xFF777777);
        sub.setPadding(0, dp(4), 0, dp(14));
        root.addView(sub);

        root.addView(section("开关"));
        swEnabled = addSwitch(root, "启用 Dock", "关掉就是微信原生底栏，完全不动它");
        swLabels = addSwitch(root, "显示图标文字", "微信 / 通讯录 / 发现 / 我");
        swPlus = addSwitch(root, "显示右侧＋号", "跟原生那套一致时留着更好认");
        swBottom = addSwitch(root, "底栏区域透明", "让画面一直延伸到底部手势线");
        swMenu = addSwitch(root, "弹层背景透明", "聊天输入面板、分享面板那层底");
        swTint = addSwitch(root, "抓不到画面用主色垫底", "纯色页面不至于是一块黑玻璃");
        swHideBg = addSwitch(root, "只留图标不画玻璃", "适合配纯透明壁纸");

        root.addView(section("尺寸"));
        sbHeight = addSeek(root, "高度", 52, 88, 64);
        tvHeight = pendingLabel;
        sbWidth = addSeek(root, "单格宽度", 60, 100, 76);
        tvWidth = pendingLabel;
        sbMargin = addSeek(root, "左右边距", 12, 40, 24);
        tvMargin = pendingLabel;
        sbGap = addSeek(root, "离底距离", 0, 28, 8);
        tvGap = pendingLabel;

        root.addView(section("外观"));
        sbAlpha = addSeek(root, "玻璃浓度", 0, 100, 100);
        tvAlpha = pendingLabel;

        Button apply = new Button(this);
        apply.setText("应用到微信");
        apply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                apply();
                Toast.makeText(SettingsActivity.this, "已推送，回微信看效果", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(18);
        root.addView(apply, lp);

        return scroll;
    }

    private TextView pendingLabel;

    private TextView section(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(0xFF999999);
        tv.setPadding(dp(4), dp(16), 0, dp(6));
        return tv;
    }

    private Switch addSwitch(LinearLayout parent, String title, String summary) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(Color.WHITE);
        row.setPadding(dp(14), dp(10), dp(10), dp(10));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(left, leftLp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15f);
        t.setTextColor(0xFF111111);
        left.addView(t);

        TextView s = new TextView(this);
        s.setText(summary);
        s.setTextSize(12f);
        s.setTextColor(0xFF888888);
        left.addView(s);

        Switch sw = new Switch(this);
        row.addView(sw);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(1);
        parent.addView(row, rowLp);

        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (!loading) apply();
            }
        });
        return sw;
    }

    private SeekBar addSeek(LinearLayout parent, String title, final int min, final int max,
                            int def) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.WHITE);
        box.setPadding(dp(14), dp(10), dp(14), dp(6));

        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15f);
        t.setTextColor(0xFF111111);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        headRow.addView(t);

        TextView value = new TextView(this);
        value.setTextSize(14f);
        value.setTextColor(0xFF3C6CD6);
        headRow.addView(value);
        box.addView(headRow);

        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(Math.max(0, Math.min(max - min, def - min)));
        box.addView(bar);

        value.setText(String.valueOf(def));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(String.valueOf(min + progress));
                if (fromUser && !loading) apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(1);
        parent.addView(box, lp);

        pendingLabel = value;
        return bar;
    }

    // ------------------------------------------------------------ 读写

    private void load() {
        try {
            swEnabled.setChecked(DockPrefs.enabled(this));
            swLabels.setChecked(DockPrefs.showLabels(this));
            swPlus.setChecked(DockPrefs.showPlusButton(this));
            swBottom.setChecked(DockPrefs.bottomTransparent(this));
            swMenu.setChecked(DockPrefs.menuTransparent(this));
            swTint.setChecked(DockPrefs.videoTintFallback(this));
            swHideBg.setChecked(DockPrefs.hideGlassBackground(this));

            setSeek(sbHeight, tvHeight, 52, DockPrefs.heightDp(this));
            setSeek(sbWidth, tvWidth, 60, DockPrefs.tabWidthDp(this));
            setSeek(sbMargin, tvMargin, 12, DockPrefs.sideMarginDp(this));
            setSeek(sbGap, tvGap, 0, DockPrefs.bottomGapDp(this));
            setSeek(sbAlpha, tvAlpha, 0, DockPrefs.glassAlpha(this));
        } catch (Throwable ignored) {
        }
    }

    private void setSeek(SeekBar bar, TextView label, int min, int value) {
        bar.setProgress(Math.max(0, Math.min(bar.getMax(), value - min)));
        label.setText(String.valueOf(value));
    }

    private void apply() {
        try {
            boolean enabled = swEnabled.isChecked();
            boolean labels = swLabels.isChecked();
            boolean plus = swPlus.isChecked();
            boolean bottom = swBottom.isChecked();
            boolean menu = swMenu.isChecked();
            boolean tint = swTint.isChecked();
            boolean hideBg = swHideBg.isChecked();

            int height = 52 + sbHeight.getProgress();
            int width = 60 + sbWidth.getProgress();
            int margin = 12 + sbMargin.getProgress();
            int gap = sbGap.getProgress();
            int alpha = sbAlpha.getProgress();

            DockPrefs.setEnabled(this, enabled);
            DockPrefs.setShowLabels(this, labels);
            DockPrefs.setShowPlusButton(this, plus);
            DockPrefs.setBottomTransparent(this, bottom);
            DockPrefs.setMenuTransparent(this, menu);
            DockPrefs.setVideoTintFallback(this, tint);
            DockPrefs.setHideGlassBackground(this, hideBg);
            DockPrefs.setHeightDp(this, height);
            DockPrefs.setTabWidthDp(this, width);
            DockPrefs.setSideMarginDp(this, margin);
            DockPrefs.setBottomGapDp(this, gap);
            DockPrefs.setGlassAlpha(this, alpha);

            SettingsSync.push(this, enabled, plus, labels, DockPrefs.logEnabled(this),
                    height, width, margin, gap, bottom, menu, tint,
                    DockPrefs.themeMode(this), alpha, hideBg);
        } catch (Throwable t) {
            Toast.makeText(this, "保存失败：" + t, Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    /** 微信里点入口时，用这个方法直接拉起设置页。 */
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
