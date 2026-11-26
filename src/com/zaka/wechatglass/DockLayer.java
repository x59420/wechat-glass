package com.zaka.wechatglass;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.View;

/**
 * Dock 玻璃层的最小接口。
 *
 * 两个实现：
 *   - {@link IsoDock}      KernelSU 那套 Compose + Miuix 渲染器（完整效果，默认走这条）
 *   - {@link FallbackDock} 纯 Java Canvas 版本（Compse 在宿主进程里起不来时的兜底）
 *
 * 控制器只认这个接口，所以"渲染器挂了"不会变成"整个模块没反应"。
 */
interface DockLayer {

    View view();

    void setGeometry(Rect region, Rect tabs, Rect plus);

    void setBackdrop(Bitmap bitmap);

    void clearBackdrop();

    void setAppearance(int alphaPercent, boolean hideBackground, int themeMode);

    void setTabs(String[] labels, Bitmap[] icons, int selected);

    boolean isBroken();

    boolean hasDrawn();

    void dispose();
}
