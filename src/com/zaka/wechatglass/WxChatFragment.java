package com.zaka.wechatglass;

import android.app.Activity;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 微信 8.0.5x 的聊天页不是 Activity，而是 LauncherUI 里的 Fragment。
 * 只 hook Activity 生命周期永远看不到“进入聊天”，Dock 就收不起来。
 * 这里直接 hook Fragment.onResume / onPause，按 Fragment 类名判断聊天页。
 */
final class WxChatFragment {

    private static volatile boolean hooked;

    private WxChatFragment() {
    }

    static void hook(ClassLoader cl) {
        if (hooked) return;
        hooked = true;
        hookOne(cl, "androidx.fragment.app.Fragment");
        hookOne(cl, "android.app.Fragment");
        WechatGlass.log("聊天Fragment:已挂上");
    }

    private static void hookOne(ClassLoader cl, String className) {
        try {
            Class<?> cls = XposedHelpers.findClassIfExists(className, cl);
            if (cls == null) return;
            XposedBridge.hookAllMethods(cls, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!isChat(param.thisObject)) return;
                        Activity act = activityOf(param.thisObject);
                        WechatGlass.log("聊天Fragment:resume "
                                + param.thisObject.getClass().getName());
                        WxDockController.suppress(true);
                        WxTopBar.releaseAll();
                        if (act != null) WxChatTime.attachForChat(act);
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.hookAllMethods(cls, "onPause", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!isChat(param.thisObject)) return;
                        Activity act = activityOf(param.thisObject);
                        WechatGlass.log("聊天Fragment:pause "
                                + param.thisObject.getClass().getName());
                        if (act != null) WxChatTime.detach(act);
                        WxDockController.suppress(false);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            WechatGlass.warn("聊天Fragment:挂 " + className + " 失败: " + t);
        }
    }

    private static boolean isChat(Object fragment) {
        if (fragment == null) return false;
        String name = fragment.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        return name.contains("chatting") || name.contains("chatroom")
                || name.contains("chatfooter");
    }

    private static Activity activityOf(Object fragment) {
        try {
            Method m = fragment.getClass().getMethod("getActivity");
            Object value = m.invoke(fragment);
            return value instanceof Activity ? (Activity) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
