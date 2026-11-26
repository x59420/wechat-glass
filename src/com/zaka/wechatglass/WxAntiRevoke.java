package com.zaka.wechatglass;

import android.content.ContentValues;
import android.content.Context;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 消息防撤回。
 *
 * 微信撤回一条消息，落到本地就是「把消息表里那一行改成系统提示」：
 * type 变成 10000（系统消息），content 变成「XXX撤回了一条消息」。
 * 在这个 update 真正写进库之前把它拦掉，原消息就原样留着了。
 *
 * 为什么不 hook 具体类：微信的消息存储类名每版都在变（MsgInfoStorage / Storage 之类
 * 全是混淆名），而「往 SQLite 里写一行」这个动作本身是稳定的。所以这里挂在
 * SQLiteDatabase.update / updateWithOnConflict 上，只认「消息表 + 撤回文案」这一种更新，
 * 别的一概放行。
 *
 * 只拦别人的撤回：自己撤回的提示文案以「你」开头（"你撤回了一条消息"），直接放行，
 * 不然用户会发现自己也撤不回去了。
 */
final class WxAntiRevoke {

    /** 微信用的是自家的 WCDB（Android SQLiteDatabase 的分支），两个都挂上。 */
    private static final String[] DB_CLASSES = {
            "com.tencent.wcdb.database.SQLiteDatabase",
            "android.database.sqlite.SQLiteDatabase",
    };

    private static volatile boolean hooked;
    private static volatile int blocked;

    private WxAntiRevoke() {
    }

    static void hook(final ClassLoader cl) {
        if (hooked) return;
        hooked = true;
        int count = 0;
        for (String name : DB_CLASSES) {
            try {
                Class<?> cls = XposedHelpers.findClassIfExists(name, cl);
                if (cls == null) continue;
                count += hookUpdate(cls, "update");
                count += hookUpdate(cls, "updateWithOnConflict");
            } catch (Throwable t) {
                WechatGlass.warnOnce("revoke-" + name, "防撤回:挂 " + name + " 失败: " + t);
            }
        }
        WechatGlass.log("防撤回:已挂上 " + count + " 个落库入口");
    }

    /** 挂一个类里所有 (String table, ContentValues values, ...) 形态的 update。 */
    private static int hookUpdate(Class<?> cls, final String methodName) {
        int count = 0;
        Method[] methods;
        try {
            methods = cls.getDeclaredMethods();
        } catch (Throwable t) {
            return 0;
        }
        for (Method method : methods) {
            try {
                if (!method.getName().equals(methodName)) continue;
                Class<?>[] types = method.getParameterTypes();
                if (types.length < 2) continue;
                if (types[0] != String.class) continue;
                if (!ContentValues.class.isAssignableFrom(types[1])) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args.length < 2) return;
                            if (!(param.args[0] instanceof String)) return;
                            if (!(param.args[1] instanceof ContentValues)) return;
                            ContentValues values = (ContentValues) param.args[1];
                            Context context = WechatGlass.context();
                            if (context != null && !DockPrefs.antiRevoke(context)) return;
                            String table = (String) param.args[0];
                            String content = asString(values.get("content"));
                            if (!isRevokeText(content)) return;
                            // 自己撤回（"你撤回了一条消息"）放行。
                            if (content.trim().startsWith("你")) return;
                            param.setResult(0);
                            blocked++;
                            WechatGlass.log("防撤回:拦下一条（表=" + table + " 内容="
                                    + brief(content) + "）累计 " + blocked);
                        } catch (Throwable t) {
                            WechatGlass.warnOnce("revoke-" + methodName,
                                    "防撤回:判断失败（放行）: " + t);
                        }
                    }
                });
                count++;
            } catch (Throwable t) {
                WechatGlass.warnOnce("revoke-method-" + methodName,
                        "防撤回:挂 " + methodName + " 失败: " + t);
            }
        }
        return count;
    }

    /** 撤回文案：中文「撤回了一条消息」，英文包是 "recalled a message"。 */
    private static boolean isRevokeText(String content) {
        if (content == null || content.isEmpty()) return false;
        if (content.contains("撤回")) return true;
        String lower = content.toLowerCase();
        return lower.contains("recall") || lower.contains("unsend");
    }

    private static String asString(Object value) {
        if (value == null) return null;
        return value instanceof String ? (String) value : String.valueOf(value);
    }

    private static String brief(String text) {
        String flat = text.replace('\n', ' ').trim();
        return flat.length() > 40 ? flat.substring(0, 40) + "…" : flat;
    }
}
