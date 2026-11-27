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

    /** 消息防撤回。 */
    static final boolean DISABLED = false;

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
        if (DISABLED || hooked) return;
        hooked = true;
        int count = 0;
        for (String name : DB_CLASSES) {
            try {
                Class<?> cls = XposedHelpers.findClassIfExists(name, cl);
                if (cls == null) continue;
                count += hookUpdate(cls, "update");
                count += hookUpdate(cls, "updateWithOnConflict");
                count += hookExecSql(cls);
            } catch (Throwable t) {
                WechatGlass.warnOnce("revoke-" + name, "防撤回:挂 " + name + " 失败: " + t);
            }
        }
        WechatGlass.log("防撤回:已挂上 " + count + " 个落库入口");
    }

    /** 挂一个类（含父类）里所有 (String table, ContentValues values, ...) 形态的 update。 */
    private static int hookUpdate(Class<?> cls, final String methodName) {
        int count = 0;
        java.util.IdentityHashMap<Method, Boolean> seen =
                new java.util.IdentityHashMap<Method, Boolean>();
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            Method[] methods;
            try {
                methods = c.getDeclaredMethods();
            } catch (Throwable t) {
                continue;
            }
            for (Method method : methods) {
                try {
                    if (!method.getName().equals(methodName)) continue;
                    if (seen.put(method, Boolean.TRUE) != null) continue;
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
                                String content = null;
                                // 微信不同版本把正文放在 content / msgContent，甚至别的列；
                                // 只要这次 update 里任何一个字符串像撤回文案，就拦。
                                for (String key : values.keySet()) {
                                    String v = asString(values.get(key));
                                    if (isRevokeText(v)) {
                                        content = v;
                                        break;
                                    }
                                }
                                if (content == null) {
                                    // 新版可能只把 type 改成 10000/10002，正文由服务端下发。
                                    Integer type = values.getAsInteger("type");
                                    String lowerTable = table == null ? ""
                                            : table.toLowerCase(java.util.Locale.ROOT);
                                    if (type != null && (type == 10000 || type == 10002)
                                            && (lowerTable.contains("message")
                                            || lowerTable.contains("msg"))) {
                                        content = "撤回(type=" + type + ")";
                                    }
                                }
                                if (content == null) return;
                                // 自己撤回（"你撤回了一条消息"）放行。
                                if (content.trim().startsWith("你")) return;
                                param.setResult(0);
                                blocked++;
                                WechatGlass.log("防撤回:拦下一条（表=" + table + " 内容="
                                        + brief(content) + "）累计 " + blocked);
                                if (context != null) {
                                    WechatGlass.toast(context, "wxglass：已拦截一条撤回消息");
                                }
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
        }
        return count;
    }

    /** 有些版本用 execSQL 直接写 SQL，ContentValues 那条路就抓不到。 */
    private static int hookExecSql(Class<?> cls) {
        int count = 0;
        java.util.IdentityHashMap<Method, Boolean> seen =
                new java.util.IdentityHashMap<Method, Boolean>();
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                try {
                    if (!method.getName().startsWith("execSQL")) continue;
                    if (seen.put(method, Boolean.TRUE) != null) continue;
                    Class<?>[] types = method.getParameterTypes();
                    if (types.length < 1 || types[0] != String.class) continue;
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args.length < 1 || !(param.args[0] instanceof String)) return;
                                String sql = (String) param.args[0];
                                String lower = sql.toLowerCase(java.util.Locale.ROOT);
                                if (!lower.contains("update") || !lower.contains("message")) return;
                                boolean revoke = lower.contains("撤回")
                                        || lower.contains("revoke")
                                        || lower.contains("recall");
                                if (!revoke && param.args.length > 1 && param.args[1] instanceof Object[]) {
                                    for (Object arg : (Object[]) param.args[1]) {
                                        if (isRevokeText(asString(arg))) {
                                            revoke = true;
                                            break;
                                        }
                                    }
                                }
                                if (!revoke) return;
                                Context context = WechatGlass.context();
                                if (context != null && !DockPrefs.antiRevoke(context)) return;
                                param.setResult(null);
                                blocked++;
                                WechatGlass.log("防撤回:拦下 SQL 撤回，累计 " + blocked);
                                if (context != null) {
                                    WechatGlass.toast(context, "wxglass：已拦截一条撤回消息");
                                }
                            } catch (Throwable t) {
                                WechatGlass.warnOnce("revoke-sql", "防撤回:SQL 判断失败: " + t);
                            }
                        }
                    });
                    count++;
                } catch (Throwable t) {
                    WechatGlass.warnOnce("revoke-sql-method", "防撤回:挂 execSQL 失败: " + t);
                }
            }
        }
        return count;
    }

    /** 撤回文案：中文「撤回了一条消息」，英文包是 "recalled a message"。 */
    private static boolean isRevokeText(String content) {
        if (content == null || content.isEmpty()) return false;
        if (content.contains("撤回")) return true;
        String lower = content.toLowerCase();
        return lower.contains("recall") || lower.contains("unsend")
                || lower.contains("revoke") || lower.contains("revokemsg");
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
