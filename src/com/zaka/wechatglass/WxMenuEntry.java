package com.zaka.wechatglass;

import android.app.Activity;
import android.content.Context;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 设置入口：微信「我」页面里「设置」上面那一行 wxglass。
 *
 * 为什么不放桌面图标，也不放 Dock 上：
 *   - 桌面图标是独立 App 入口，用户明确不要；
 *   - Dock 是微信日常用的控件，长按/双击当设置入口会打断正常操作。
 * 所以入口做成微信自己的偏好列表里的一行，位置就在「设置」正上方，
 * 和 dydock 在抖音「我的」菜单里的入口是同一套做法。
 *
 * 实现只在微信自己的 Preference 体系里加一条 Preference：
 *   MoreTabUI(=「我」页面) 里那套列表是 MMPreferenceFragment + g0(adapter)，
 *   加条目走 g0.d(pref, position)，点击走 MoreTabUI.w0(...)，
 *   全程不碰微信的布局、不碰 Dock、不碰其它条目。
 * 任何一步对不上（微信换版本把方法名改了）就整段静默放弃，
 * 「我」页面维持原生，绝不因为插一行把微信搞崩。
 *
 * 下面这些混淆名都是照微信 8.0.80(3220) 的原生包核对过的：
 *   MoreTabUI.w0(q, Preference, m0)Z          —— 「我」页面的条目点击回调
 *   AbstractTabChildPreference.onResume()V    —— 「我」页面每次回到前台
 *   MMPreferenceFragment.d : g0               —— 偏好列表 adapter
 *   g0.h(String)            按 key 找条目
 *   g0.m(String)            按 key 取位置
 *   g0.d(Preference, int)   插入条目（-1 = 末尾）
 *   Preference.H(String)    setKey
 *   Preference.R(CharSequence) setTitle
 *   Preference.N(CharSequence) setSummary
 *   Preference.j()          取 key
 */
final class WxMenuEntry {

    /** 我们自己那一行的 key，必须是微信不会用的。 */
    static final String ENTRY_KEY = "wxglass_entry";

    /** 「设置」那一行的 key，照原生包里读出来的。 */
    private static final String SETTINGS_KEY = "more_setting";

    private static final String CLS_MORE_TAB = "com.tencent.mm.ui.MoreTabUI";
    private static final String CLS_TAB_BASE = "com.tencent.mm.ui.AbstractTabChildPreference";
    private static final String CLS_PREFERENCE = "com.tencent.mm.ui.base.preference.Preference";

    private WxMenuEntry() {
    }

    static void hook(final ClassLoader cl) {
        if (cl == null) return;
        hookClick(cl);
        hookInsert(cl);
    }

    // ------------------------------------------------------------ 点击

    private static void hookClick(final ClassLoader cl) {
        try {
            Class<?> moreTab = XposedHelpers.findClass(CLS_MORE_TAB, cl);
            XposedBridge.hookAllMethods(moreTab, "w0", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 2) return;
                        Object preference = param.args[1];
                        if (preference == null) return;
                        Object key = XposedHelpers.callMethod(preference, "j");
                        if (!ENTRY_KEY.equals(key)) return;

                        Activity activity = activityOf(param.thisObject);
                        if (activity == null) {
                            WechatGlass.warn("入口:点到了 wxglass，但拿不到 Activity");
                            return;
                        }
                        WechatGlass.log("入口:打开 wxglass 设置");
                        // 返回 true = 这次点击我们处理完了，不再往下走微信的逻辑。
                        param.setResult(Boolean.TRUE);
                        IsoDock.showSettings(activity);
                    } catch (Throwable t) {
                        WechatGlass.warnOnce("menu-click", "入口:点击处理失败: " + t);
                    }
                }
            });
            WechatGlass.log("入口:已挂上「我」页面的 wxglass 条目");
        } catch (Throwable t) {
            WechatGlass.warn("入口:「我」页面类没找到，跳过设置入口（" + t + "）");
        }
    }

    // ------------------------------------------------------------ 插入

    private static void hookInsert(final ClassLoader cl) {
        try {
            Class<?> base = XposedHelpers.findClass(CLS_TAB_BASE, cl);
            XposedBridge.hookAllMethods(base, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object self = param.thisObject;
                        if (self == null) return;
                        // 基类被几个 tab 页面共用，只认「我」页面。
                        if (!CLS_MORE_TAB.equals(self.getClass().getName())) return;
                        ensureEntry(self, cl);
                    } catch (Throwable t) {
                        WechatGlass.warnOnce("menu-insert", "入口:插入失败: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            WechatGlass.warnOnce("menu-insert-hook", "入口:挂「我」页面失败: " + t);
        }
    }

    /** 幂等：已经有这一行就直接返回。 */
    private static void ensureEntry(Object fragment, ClassLoader cl) {
        Object adapter = XposedHelpers.getObjectField(fragment, "d");
        if (adapter == null) return;

        Object existed = XposedHelpers.callMethod(adapter, "h", ENTRY_KEY);
        if (existed != null) return;

        Activity activity = activityOf(fragment);
        if (activity == null) return;

        Class<?> prefClass = XposedHelpers.findClass(CLS_PREFERENCE, cl);
        Object preference = XposedHelpers.newInstance(prefClass, (Context) activity);
        XposedHelpers.callMethod(preference, "H", ENTRY_KEY);
        XposedHelpers.callMethod(preference, "R", "wxglass");
        XposedHelpers.callMethod(preference, "N", "液态玻璃 Dock · " + WechatGlass.VER);

        // 放在「设置」正上方；读不到位置就追加到末尾。
        int position = -1;
        try {
            Object index = XposedHelpers.callMethod(adapter, "m", SETTINGS_KEY);
            if (index instanceof Integer && ((Integer) index) >= 0) position = (Integer) index;
        } catch (Throwable ignored) {
        }

        XposedHelpers.callMethod(adapter, "d", preference, position);
        WechatGlass.log("入口:已在「我」页面插入 wxglass（位置 " + position + "）");
    }

    private static Activity activityOf(Object fragment) {
        try {
            Object value = XposedHelpers.callMethod(fragment, "getActivity");
            return value instanceof Activity ? (Activity) value : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
