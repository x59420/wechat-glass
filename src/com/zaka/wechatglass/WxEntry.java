package com.zaka.wechatglass;

import android.app.Activity;
import android.app.Application;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 模块入口。
 *
 * 只接管微信首页（LauncherUI）里那条底部导航栏，其余页面、控件、交互一律不碰。
 * 用框架类 Activity 当锚点：微信改版改不到 android.app.Activity，类名对不上顶多
 * 是"这次不接管"，不会把宿主弄崩。
 */
public final class WxEntry implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    @Override
    public void initZygote(StartupParam startupParam) {
        try {
            IsoDock.setModulePath(startupParam.modulePath);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (lp == null || !WechatGlass.HOST.equals(lp.packageName)) return;

        WechatGlass.log("wxglass " + WechatGlass.VER + " 装载进微信（只替换底部导航栏）");

        // api-82 的桩里没有 modulePath，LSPosed 运行时才有 —— 反射读，读不到就算了
        // （IsoDock 自己还有三条兜底能翻出模块 APK 路径）。
        try {
            java.lang.reflect.Field field = lp.getClass().getField("modulePath");
            Object value = field.get(lp);
            if (value instanceof String && ((String) value).toLowerCase().endsWith(".apk")) {
                IsoDock.setModulePath((String) value);
            }
        } catch (Throwable ignored) {
        }

        hookActivityResume();
        hookActivityDestroy();
        hookApplication();
        WxMenuEntry.hook(lp.classLoader);
    }

    /** 每次有页面回到前台都问一次：是不是微信首页。 */
    private static void hookActivityResume() {
        try {
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object self = param.thisObject;
                        if (!(self instanceof Activity)) return;
                        WxDockController.onHostResume((Activity) self);
                    } catch (Throwable t) {
                        WechatGlass.warnOnce("resume", "onResume 处理失败: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            WechatGlass.warn("挂 Activity.onResume 失败: " + t);
        }
    }

    private static void hookActivityDestroy() {
        try {
            XposedBridge.hookAllMethods(Activity.class, "onDestroy", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object self = param.thisObject;
                        if (!(self instanceof Activity)) return;
                        WxDockController.release((Activity) self);
                    } catch (Throwable t) {
                        WechatGlass.warnOnce("destroy", "onDestroy 处理失败: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            WechatGlass.warn("挂 Activity.onDestroy 失败: " + t);
        }
    }

    /** 设置界面的改动通过广播立刻同步进来。 */
    private static void hookApplication() {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object self = param.thisObject;
                                if (self instanceof Application) {
                                    SettingsSync.register((Application) self);
                                }
                            } catch (Throwable t) {
                                WechatGlass.warnOnce("settings-register",
                                        "注册设置同步失败: " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            WechatGlass.warn("挂 Application.onCreate 失败: " + t);
        }
    }
}
