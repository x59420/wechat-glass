package com.zaka.wechatglass;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

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

    /** 生命周期回调整个进程只挂一次。 */
    private static volatile boolean LIFECYCLE_REGISTERED = false;

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

        hookActivityCreate();
        hookActivityResume();
        hookActivityPause();
        hookActivityDestroy();
        hookWindowFocus();
        hookApplication();
        registerToCurrentApplication();
        hookTabBarCreated(lp.classLoader);
        WxPlusPage.hook(lp.classLoader);
        WxSettingsEntry.hook(lp.classLoader);
        WxAntiRevoke.hook(lp.classLoader);
        WxChatTime.hook(lp.classLoader);
        hookNonHostPages(lp.classLoader);

        // 装没装上，一眼就能看出来：进微信弹一条，省得再来回猜。
        WechatGlass.toast(WechatGlass.VER + " 已加载");
    }

    /**
     * 页面一创建就把系统生命周期回调挂上。
     *
     * 这是整套「页面切换」判断的地基：registerActivityLifecycleCallbacks 由系统直接回调，
     * 跟微信有没有在自己的 onResume 里调 super 完全无关。之前只靠 hook Activity.onResume，
     * 微信每个页面都重写且不一定调 super，结果就是「进了聊天页 Dock 还挂着」。
     */
    private static void hookActivityCreate() {
        try {
            XposedBridge.hookAllMethods(Activity.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!(param.thisObject instanceof Activity)) return;
                        Activity act = (Activity) param.thisObject;
                        registerLifecycle(act.getApplication());
                        WxDockController.onAnyCreate(act);
                    } catch (Throwable t) {
                        WechatGlass.warnOnce("create", "onCreate 处理失败: " + t);
                    }
                }
            });
            WechatGlass.log("已挂上 Activity.onCreate（在这里补挂系统生命周期回调）");
        } catch (Throwable t) {
            WechatGlass.warn("挂 Activity.onCreate 失败: " + t);
        }
    }

    /** 窗口焦点变化：首页被别的页面盖住时焦点会丢，Dock 立刻收起来。 */
    private static void hookWindowFocus() {
        try {
            XposedBridge.hookAllMethods(Activity.class, "onWindowFocusChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!(param.thisObject instanceof Activity)) return;
                        if (param.args == null || param.args.length < 1) return;
                        Object value = param.args[0];
                        if (!(value instanceof Boolean)) return;
                        WxDockController.onFocusChanged((Activity) param.thisObject,
                                (Boolean) value);
                    } catch (Throwable t) {
                        WechatGlass.warnOnce("focus", "onWindowFocusChanged 处理失败: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            WechatGlass.warn("挂 onWindowFocusChanged 失败: " + t);
        }
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
                        final Activity act = (Activity) self;
                        WechatGlass.setVerbose(DockPrefs.logEnabled(act));
                        WxDockController.onAnyResume(act);
                        WxSettingsEntry.maybeInstall(act);
                        WxChatTime.maybeAttach(act);
                        WxTopBar.resume(act);
                        View decor = act.getWindow() == null
                                ? null : act.getWindow().getDecorView();
                        if (decor != null) {
                            decor.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    WxTopBar.refresh(act);
                                }
                            }, 600L);
                        }
                    } catch (Throwable t) {
                        WechatGlass.warnOnce("resume", "onResume 处理失败: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            WechatGlass.warn("挂 Activity.onResume 失败: " + t);
        }
    }

    /**
     * 页面一走开就立刻收 Dock。
     *
     * 这一条不依赖任何回调时序：聊天页 / 添加朋友页是另一个 Activity，它们要显示出来，
     * 首页一定先走 pause（或 stop）。哪怕系统生命周期回调没挂上、哪怕 TOP 没记上，
     * 这一钩子也一定跑得到，Dock 一定收得掉。
     */
    private static void hookActivityPause() {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object self = param.thisObject;
                    if (!(self instanceof Activity)) return;
                    WxDockController.onAnyPause((Activity) self);
                } catch (Throwable t) {
                    WechatGlass.warnOnce("pause", "onPause 处理失败: " + t);
                }
            }
        };
        XC_MethodHook stopHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object self = param.thisObject;
                    if (!(self instanceof Activity)) return;
                    WxDockController.onAnyStop((Activity) self);
                } catch (Throwable t) {
                    WechatGlass.warnOnce("stop", "onStop 处理失败: " + t);
                }
            }
        };
        try {
            XposedBridge.hookAllMethods(Activity.class, "onPause", hook);
            XposedBridge.hookAllMethods(Activity.class, "onStop", stopHook);
        } catch (Throwable t) {
            WechatGlass.warn("挂 Activity.onPause / onStop 失败: " + t);
        }
    }

    private static void hookActivityDestroy() {        try {
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

    /** 设置界面的改动通过广播立刻同步进来；顺便挂上系统生命周期回调。 */
    private static void hookApplication() {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object self = param.thisObject;
                            if (!(self instanceof Application)) return;
                            Application app = (Application) self;
                            try {
                                WechatGlass.setVerbose(DockPrefs.logEnabled(app));
                                SettingsSync.register(app);
                            } catch (Throwable t) {
                                WechatGlass.warnOnce("settings-register",
                                        "注册设置同步失败: " + t);
                            }
                            registerLifecycle(app);
                        }
                    });
        } catch (Throwable t) {
            WechatGlass.warn("挂 Application.onCreate 失败: " + t);
        }
    }

    /**
     * 底栏一被创建就装 Dock。
     *
     * 这是最稳的一条触发路径：不依赖首页 Activity 叫什么、也不依赖 onResume 有没有被调，
     * 微信自己 new 出底栏的那一刻我们就拿到了那个 View，顺着它的 Context 找到 Activity。
     */
    private static void hookTabBarCreated(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookConstructor(
                    "com.tencent.mm.ui.LauncherUIBottomTabView", cl, Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!(param.thisObject instanceof View)) return;
                                // 先让位：从微信画出底栏到我们的玻璃画出来这段时间，
                                // 原生的那条先藏起来，别让用户看到两条栏叠在一起。
                                SelfMask.mask((View) param.thisObject);
                                final Activity act = activityOf((View) param.thisObject);
                                if (act == null) return;
                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        WxDockController.onHostResume(act);
                                    }
                                }, 900L);
                            } catch (Throwable t) {
                                WechatGlass.warnOnce("tabbar-created",
                                        "底栏创建后处理失败: " + t);
                            }
                        }
                    });
            WechatGlass.log("已挂上底栏创建回调");
        } catch (Throwable t) {
            WechatGlass.warnOnce("tabbar-hook", "挂底栏创建回调失败: " + t);
        }
    }

    /**
     * 聊天 / 添加朋友这类页面一 resume，直接撤掉首页的 Dock 和顶栏。
     * 不再依赖 mResumedActivity、焦点、onPause 谁先谁后。
     */
    private static void hookNonHostPages(ClassLoader cl) {
        String[] pages = {
                "com.tencent.mm.ui.chatting.ChattingMainUI",
                "com.tencent.mm.ui.chatting.ChattingUI",
                "com.tencent.mm.ui.chatting.ChattingUIProxyUI",
                "com.tencent.mm.plugin.profile.ui.ContactInfoUI",
                "com.tencent.mm.ui.FindMoreFriendsUI",
                "com.tencent.mm.ui.AddMoreFriendsUI",
        };
        int count = 0;
        for (String name : pages) {
            try {
                Class<?> cls = Class.forName(name, false, cl);
                XposedBridge.hookAllMethods(cls, "onResume", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            WxDockController.suppress(true);
                            WxTopBar.releaseAll();
                        } catch (Throwable ignored) {
                        }
                    }
                });
                count++;
            } catch (Throwable ignored) {
            }
        }
        WechatGlass.log("非首页:已挂上 " + count + " 个页面，进页面立刻收 Dock");
    }

    /** 顺着 View 的 Context 往上找 Activity。 */
    private static Activity activityOf(View view) {
        try {
            Context c = view.getContext();
            while (c instanceof ContextWrapper) {
                if (c instanceof Activity) return (Activity) c;
                c = ((ContextWrapper) c).getBaseContext();
            }
            if (c instanceof Activity) return (Activity) c;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 模块加载时 Application 可能已经建好了，直接把回调补挂上去。
     * 多挂一次不会重复接管：WxDockController 自己有幂等判断。
     */
    private static void registerToCurrentApplication() {
        try {
            Class<?> activityThread = XposedHelpers.findClass("android.app.ActivityThread", null);
            Object app = XposedHelpers.callStaticMethod(activityThread, "currentApplication");
            if (app instanceof Application) registerLifecycle((Application) app);
        } catch (Throwable t) {
            WechatGlass.warnOnce("lifecycle-current", "补挂生命周期回调失败: " + t);
        }
    }

    /**
     * 走系统自己发的 Activity 生命周期回调。
     *
     * 为什么不只 hook Activity.onResume：
     * 微信每个页面都重写了 onResume，只要中间任意一层没调 super，挂在
     * 框架 Activity.onResume 上的钩子就永远不会跑——表现就是「模块装了跟没装一样」。
     * registerActivityLifecycleCallbacks 由系统在 Activity 状态变化时直接回调，
     * 跟微信怎么写自己的 onResume 完全无关。
     */
    private static void registerLifecycle(final Application app) {
        if (app == null || LIFECYCLE_REGISTERED) return;
        LIFECYCLE_REGISTERED = true;
        try {
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                }

                @Override
                public void onActivityStarted(Activity activity) {
                }

                @Override
                public void onActivityResumed(Activity activity) {
                    try {
                        WechatGlass.setVerbose(DockPrefs.logEnabled(activity));
                        WxDockController.onAnyResume(activity);
                        WxSettingsEntry.maybeInstall(activity);
                        WxPlusPage.watch(activity);
                        WxChatTime.maybeAttach(activity);
                        final Activity page = activity;
                        View decor = activity.getWindow() == null
                                ? null : activity.getWindow().getDecorView();
                        if (decor != null) {
                            decor.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    WxPlusPage.tryAttach(page);
                                    WxTopBar.refresh(page);
                                }
                            }, 500L);
                        }
                    } catch (Throwable t) {
                        WechatGlass.warnOnce("lifecycle-resume", "onActivityResumed 处理失败: " + t);
                    }
                }

                @Override
                public void onActivityPaused(Activity activity) {
                    try {
                        WxDockController.onAnyPause(activity);
                        WxPlusPage.detach(activity);
                        WxChatTime.detach(activity);
                        // 页面切走就停止顶栏那一路的连续截图采样，回来再开。
                        WxTopBar.pause(activity);
                    } catch (Throwable t) {
                        WechatGlass.warnOnce("lifecycle-pause", "onActivityPaused 处理失败: " + t);
                    }
                }

                @Override
                public void onActivityStopped(Activity activity) {
                }

                @Override
                public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                }

                @Override
                public void onActivityDestroyed(Activity activity) {
                    try {
                        WxDockController.release(activity);
                        WxChatTime.detach(activity);
                        WxTopBar.release(activity);
                    } catch (Throwable t) {
                        WechatGlass.warnOnce("lifecycle-destroy", "onActivityDestroyed 处理失败: " + t);
                    }
                }
            });
            WechatGlass.log("已挂上系统生命周期回调（页面 resume 一定拿得到）");
        } catch (Throwable t) {
            LIFECYCLE_REGISTERED = false;
            WechatGlass.warn("注册生命周期回调失败: " + t);
        }
    }
}
