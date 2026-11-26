package com.zaka.wechatglass;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.security.CodeSource;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DexClassLoader;
import dalvik.system.PathClassLoader;

/**
 * 玻璃层的外壳：把 Compose + Miuix 那一整套放进**独立类加载器**里跑。
 *
 * 为什么要隔离：
 * 抖音自己的进程里已经带了 androidx（以及可能的 Compose）。LSPosed 把模块的 dex
 * 挂进宿主进程时是父加载器优先，于是模块里的 androidx.lifecycle / savedstate 会被
 * 宿主那份老版本顶掉，ComposeView 一初始化就抛 NoSuchMethodError / ClassCastException。
 * 异常被绘制护栏吞掉之后，用户看到的就是「模块装了，底栏一点没变」。
 *
 * 这里用 DexClassLoader 以**启动类加载器**为父，单独加载模块 APK：
 * 模块自己的 androidx / compose / miuix 全套都是独立的一份，和宿主互不相干，
 * 渲染器仍然是 KernelSU 那套 Compose + Miuix，参数一行没动。
 *
 * 跨加载器只传框架类型（View / Bitmap / Rect / Context）和 java.util.function 回调，
 * 这两边都能看见，不需要共享任何模块类。
 */
final class IsoDock implements DockLayer {

    private static final String ISO_CLASS = "com.zaka.wechatglass.iso.IsoOverlay";
    private static final String OPT_DIR = "wxglass-iso";
    private static final String LIB_DIR = "wxglass-iso-lib";
    /** 隔离加载失败后多久允许再试一次：不再「一次失败整进程报废」。 */
    private static final long RETRY_AFTER_MS = 4000L;

    /** LSPosed 在本进程里告诉我们的模块 APK 路径（拿不到时再走别的兜底）。 */
    private static volatile String modulePathHint;
    /** 类加载器建一次就够了：每个 Activity 都 new 一个 DexClassLoader 又慢又费内存。 */
    private static volatile Class<?> isoClass;
    /** 解出来的 native 库目录，APK 里的 .so 用不了时靠它。 */
    private static volatile String libPath;
    private static volatile long lastFailureAt;

    final View view;

    @Override
    public View view() {
        return view;
    }

    private final Method setGeometryMethod;
    private final Method setBackdropMethod;
    private final Method clearBackdropMethod;
    private final Method setTabsMethod;
    private final Method setAppearanceMethod;
    private final Method isBrokenMethod;
    private final Method hasDrawnMethod;
    private final Method disposeMethod;

    private boolean disposed;

    private IsoDock(View view, Class<?> iso) throws NoSuchMethodException {
        this.view = view;
        this.setGeometryMethod = iso.getMethod("setGeometry", Rect.class, Rect.class, Rect.class);
        this.setBackdropMethod = iso.getMethod("setBackdrop", Bitmap.class);
        this.clearBackdropMethod = iso.getMethod("clearBackdrop");
        this.setTabsMethod = iso.getMethod("setTabs", String[].class, Bitmap[].class, int.class);
        this.setAppearanceMethod = iso.getMethod("setAppearance", int.class, boolean.class, int.class);
        this.isBrokenMethod = iso.getMethod("isBroken");
        this.hasDrawnMethod = iso.getMethod("hasDrawn");
        this.disposeMethod = iso.getMethod("dispose");
    }

    static void setModulePath(String path) {
        if (path != null && !path.isEmpty()) modulePathHint = path;
    }

    /** 用 Dock 的隔离类加载器在宿主 Activity 内显示设置 Dialog。 */
    static boolean showSettings(Activity activity) {
        if (activity == null) return false;
        try {
            Class<?> base = isoClass;
            if (base == null) {
                String apk = moduleApk(activity);
                if (apk == null) {
                    WechatGlass.warn("设置面板:找不到模块 APK");
                    return false;
                }
                File opt = new File(activity.getCodeCacheDir(), OPT_DIR);
                if (!opt.isDirectory() && !opt.mkdirs()) {
                    WechatGlass.warn("设置面板:隔离缓存目录不可写");
                }
                String libs = nativeLibraryDir(activity, apk, opt, message -> WechatGlass.log(message));
                base = loadClass(apk, opt, libs, message -> WechatGlass.log(message));
                if (base == null) return false;
                isoClass = base;
            }
            Class<?> dialogClass = Class.forName(
                    "com.zaka.wechatglass.iso.IsoSettingsOverlay", true, base.getClassLoader());
            Method show = dialogClass.getMethod("show", Activity.class, String.class);
            show.invoke(null, activity, WechatGlass.VER);
            return true;
        } catch (Throwable t) {
            WechatGlass.error("设置面板隔离加载失败", t);
            return false;
        }
    }

    /**
     * 建一个在隔离类加载器里跑的玻璃层。任何一步失败都返回 null，
     * 由调用方保留抖音原生底栏，绝不在宿主里硬塞一套可能撞版本的 Compose。
     */
    static IsoDock create(Context context, Consumer<String> log, IntConsumer onTab, Runnable onPlus,
                          Runnable onLongPress, Consumer<android.graphics.Canvas> hostPainter) {
        if (context == null) return null;
        Class<?> iso = isoClass;
        if (iso == null) {
            // 刚失败过就别每次都重撞一遍：抖音切页面很频繁，重试要节流。
            long now = SystemClock.uptimeMillis();
            if (lastFailureAt != 0L && now - lastFailureAt < RETRY_AFTER_MS) return null;
            String apk = moduleApk(context);
            if (apk == null) {
                lastFailureAt = now;
                log.accept("找不到模块 APK 路径，无法启动隔离渲染");
                return null;
            }
            try {
                File opt = new File(context.getCodeCacheDir(), OPT_DIR);
                if (!opt.isDirectory() && !opt.mkdirs()) {
                    log.accept("隔离加载目录不可写：" + opt);
                }
                String libs = nativeLibraryDir(context, apk, opt, log);
                iso = loadClass(apk, opt, libs, log);
                if (iso == null) {
                    lastFailureAt = now;
                    return null;
                }
                isoClass = iso;
                lastFailureAt = 0L;
            } catch (Throwable t) {
                lastFailureAt = now;
                log.accept("隔离加载失败：" + t);
                return null;
            }
        }
        try {
            Method create = iso.getMethod("create", Context.class,
                    Consumer.class, IntConsumer.class, Runnable.class, Runnable.class,
                    Consumer.class);
            Object overlay = create.invoke(null, context, log, onTab, onPlus, onLongPress,
                    hostPainter);
            if (!(overlay instanceof View)) {
                log.accept("隔离类没有返回 View：" + overlay);
                return null;
            }
            return new IsoDock((View) overlay, iso);
        } catch (Throwable t) {
            log.accept("玻璃层创建失败：" + t);
            return null;
        }
    }

    /** 先用 DexClassLoader（可指定 native 库目录），不行再用 PathClassLoader 兜一次。 */
    private static Class<?> loadClass(String apk, File opt, String libs, Consumer<String> log) {
        Throwable first = null;
        try {
            ClassLoader loader = new DexClassLoader(apk, opt.getAbsolutePath(), libs, null);
            return Class.forName(ISO_CLASS, true, loader);
        } catch (Throwable t) {
            first = t;
        }
        try {
            ClassLoader loader = new PathClassLoader(apk, null);
            Class<?> found = Class.forName(ISO_CLASS, true, loader);
            log.accept("DexClassLoader 失败（" + first + "），PathClassLoader 已装载成功");
            return found;
        } catch (Throwable second) {
            log.accept("隔离加载失败：DexClassLoader=" + first + "；PathClassLoader=" + second);
            return null;
        }
    }

    /**
     * Compose 的 androidx.graphics.path 需要 libandroidx.graphics.path.so。
     *
     * DexClassLoader 的 librarySearchPath 只认**真实目录**，`apk!/lib/abi` 这种写法
     * 在 ART 上是无效路径，System.loadLibrary 会直接 UnsatisfiedLinkError，
     * 而这个错误发生在第一帧绘制里，表现就是「玻璃自己消失」。
     * 所以这里把 APK 里的 .so 解到缓存目录，拿目录路径交给加载器。
     */
    private static String nativeLibraryDir(Context context, String apk, File opt,
                                           Consumer<String> log) {
        String cached = libPath;
        if (cached != null) return cached;

        // 1) 优先用系统已经给模块装好的 nativeLibraryDir。
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(WechatGlass.PKG, 0);
            if (info != null && info.nativeLibraryDir != null) {
                File dir = new File(info.nativeLibraryDir);
                if (dir.isDirectory()) {
                    libPath = dir.getAbsolutePath();
                    return libPath;
                }
            }
        } catch (Throwable ignored) {
            // Android 11+ 包可见性限制，查不到就自己解。
        }

        // 2) 自己从 APK 里解出来。
        File outDir = new File(opt, LIB_DIR);
        try {
            if (!outDir.isDirectory() && !outDir.mkdirs()) {
                log.accept("native 库目录不可写：" + outDir);
                return null;
            }
            String abi = primaryAbi();
            ZipFile zip = new ZipFile(apk);
            try {
                String prefix = "lib/" + abi + "/";
                java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                int count = 0;
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.startsWith(prefix) || !name.endsWith(".so")) continue;
                    File target = new File(outDir, new File(name).getName());
                    if (target.isFile() && target.length() > 0) {
                        count++;
                        continue;
                    }
                    InputStream in = zip.getInputStream(entry);
                    OutputStream out = new FileOutputStream(target);
                    try {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                    } finally {
                        try {
                            out.close();
                        } catch (Throwable ignored) {
                        }
                        try {
                            in.close();
                        } catch (Throwable ignored) {
                        }
                    }
                    count++;
                }
                if (count > 0) log.accept("已从模块 APK 解出 " + count + " 个 native 库");
            } finally {
                try {
                    zip.close();
                } catch (Throwable ignored) {
                }
            }
            libPath = outDir.getAbsolutePath();
            return libPath;
        } catch (Throwable t) {
            log.accept("解 native 库失败：" + t);
            return null;
        }
    }

    @Override
    public void setGeometry(Rect region, Rect tabs, Rect plus) {
        invoke(setGeometryMethod, "setGeometry", region, tabs, plus);
    }

    @Override
    public void setBackdrop(Bitmap bitmap) {
        invoke(setBackdropMethod, "setBackdrop", bitmap);
    }

    /** 采样整片发黑时清掉背景图，别让玻璃变成一块黑玻璃。 */
    @Override
    public void clearBackdrop() {
        invoke(clearBackdropMethod, "clearBackdrop");
    }

    /** 玻璃不透明度 / 隐藏玻璃背景 / 深浅模式：默认值就是原来那套外观。 */
    @Override
    public void setAppearance(int alphaPercent, boolean hideBackground, int themeMode) {
        invoke(setAppearanceMethod, "setAppearance", alphaPercent, hideBackground, themeMode);
    }

    @Override
    public void setTabs(String[] labels, Bitmap[] icons, int selected) {
        invoke(setTabsMethod, "setTabs", labels, icons, selected);
    }

    @Override
    public boolean isBroken() {
        Object value = invoke(isBrokenMethod, "isBroken");
        return value instanceof Boolean && (Boolean) value;
    }

    @Override
    public boolean hasDrawn() {
        Object value = invoke(hasDrawnMethod, "hasDrawn");
        return value instanceof Boolean && (Boolean) value;
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        invoke(disposeMethod, "dispose");
    }

    private Object invoke(Method method, String what, Object... args) {
        if (method == null || disposed && !"dispose".equals(what)) return null;
        try {
            return method.invoke(view, args);
        } catch (Throwable t) {
            WechatGlass.warnOnce("iso-" + what, "玻璃层调用 " + what + " 失败: " + t);
            return null;
        }
    }

    /** 模块 APK 在哪：先问 LSPosed，再翻自己的类加载器，再看类来源，最后问包管理器。 */
    private static String moduleApk(Context context) {
        String hint = modulePathHint;
        if (isFile(hint)) return hint;

        // LSPosed 的 modulePath 字段在个别版本上没有 / 名字不同。
        // 模块自己的类是从「装进本进程的模块 APK」里加载的，
        // 顺着自己的 BaseDexClassLoader 一定能把那个 APK 翻出来。
        String fromLoader = findInOwnClassLoader();
        if (fromLoader != null) return fromLoader;

        try {
            CodeSource source = WechatGlass.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                String path = Uri.decode(source.getLocation().getPath());
                if (isFile(path)) return path;
            }
        } catch (Throwable ignored) {
        }

        try {
            ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(WechatGlass.PKG, 0);
            if (info != null && isFile(info.sourceDir)) return info.sourceDir;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String findInOwnClassLoader() {
        try {
            ClassLoader loader = WechatGlass.class.getClassLoader();
            java.lang.reflect.Field pathListField = Class
                    .forName("dalvik.system.BaseDexClassLoader")
                    .getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(loader);
            if (pathList == null) return null;
            java.lang.reflect.Field elementsField =
                    pathList.getClass().getDeclaredField("dexElements");
            elementsField.setAccessible(true);
            Object[] elements = (Object[]) elementsField.get(pathList);
            if (elements == null) return null;
            for (Object element : elements) {
                String path = elementPath(element);
                if (path != null && path.endsWith(".apk") && isFile(path)) return path;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String elementPath(Object element) {
        if (element == null) return null;
        try {
            java.lang.reflect.Field field = element.getClass().getDeclaredField("path");
            field.setAccessible(true);
            Object value = field.get(element);
            if (value instanceof File) return ((File) value).getAbsolutePath();
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Field field = element.getClass().getDeclaredField("dexFile");
            field.setAccessible(true);
            Object dex = field.get(element);
            if (dex != null) {
                Method getName = dex.getClass().getDeclaredMethod("getName");
                getName.setAccessible(true);
                Object value = getName.invoke(dex);
                if (value instanceof String) return (String) value;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isFile(String path) {
        if (path == null || path.isEmpty()) return false;
        try {
            File file = new File(path);
            return file.isFile() && file.length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String primaryAbi() {
        String[] abis = Build.SUPPORTED_ABIS;
        return abis != null && abis.length > 0 && abis[0] != null ? abis[0] : "arm64-v8a";
    }
}
