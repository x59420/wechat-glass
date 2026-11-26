package com.zaka.wechatglass;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 设置页用的日志文件。
 *
 * 文件在模块自己的私有目录里（files/host.log），只有微信进程发过来的那份运行日志：
 * 加载、认到底栏 / 没认到、玻璃挂上 / 没挂上、页面切换之类的关键节点。
 * 不涉及任何用户数据，纯排障用。
 */
public final class LogStore {

    private static final String FILE = "host.log";
    /** 超过这个大小就把旧的一半丢掉，别让日志无限增长。 */
    private static final long MAX_BYTES = 256 * 1024L;

    private LogStore() {
    }

    static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE);
    }

    /** 追加并打时间戳，接收端调用。 */
    static void append(Context ctx, String lines) {
        try {
            File f = file(ctx);
            if (f.isFile() && f.length() > MAX_BYTES) {
                // 简单裁剪：只留后半部分。
                byte[] tail = readTail(f, (int) (MAX_BYTES / 2));
                FileOutputStream cut = new FileOutputStream(f, false);
                try {
                    cut.write(tail);
                } finally {
                    close(cut);
                }
            }
            String stamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
            StringBuilder sb = new StringBuilder();
            for (String line : lines.split("\n")) {
                if (line.length() == 0) continue;
                sb.append(stamp).append("  ").append(line).append('\n');
            }
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                out.write(sb.toString().getBytes("UTF-8"));
            } finally {
                close(out);
            }
        } catch (Throwable ignored) {
        }
    }

    static String read(Context ctx) {
        try {
            File f = file(ctx);
            if (!f.isFile() || f.length() == 0) return "";
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] buffer = new byte[(int) Math.min(f.length(), MAX_BYTES)];
                int read = 0;
                while (read < buffer.length) {
                    int n = in.read(buffer, read, buffer.length - read);
                    if (n <= 0) break;
                    read += n;
                }
                return new String(buffer, 0, read, "UTF-8");
            } finally {
                close(in);
            }
        } catch (Throwable t) {
            return "";
        }
    }

    static void clear(Context ctx) {
        try {
            File f = file(ctx);
            if (f.isFile()) {
                FileOutputStream out = new FileOutputStream(f, false);
                close(out);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 导出一份到用户拿得到的地方。
     *
     * 优先系统「下载」目录（Android 10+ 走 MediaStore，不需要任何权限），
     * 不行就退到应用的外部目录 —— 两条路都是用户用文件管理器能翻到的，
     * 不往私有目录里一放就完事。
     *
     * @return 给用户看的落地位置；失败返回 null
     */
    static String export(Context ctx) {
        String text = read(ctx);
        if (text.length() == 0) return null;
        String name = "wxglass-log-" + new SimpleDateFormat("MMdd-HHmm", Locale.US)
                .format(new Date()) + ".txt";
        byte[] data;
        try {
            data = text.getBytes("UTF-8");
        } catch (Throwable t) {
            return null;
        }

        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentResolver resolver = ctx.getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS);
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    OutputStream out = resolver.openOutputStream(uri);
                    if (out != null) {
                        try {
                            out.write(data);
                        } finally {
                            close(out);
                        }
                        return "下载/" + name;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) dir = ctx.getFilesDir();
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            File out = new File(dir, name);
            FileOutputStream stream = new FileOutputStream(out);
            try {
                stream.write(data);
            } finally {
                close(stream);
            }
            return out.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] readTail(File f, int want) {
        try {
            long length = f.length();
            long from = Math.max(0, length - want);
            FileInputStream in = new FileInputStream(f);
            try {
                long skipped = 0;
                while (skipped < from) {
                    long n = in.skip(from - skipped);
                    if (n <= 0) break;
                    skipped += n;
                }
                byte[] buffer = new byte[(int) Math.min(want, length - from)];
                int read = 0;
                while (read < buffer.length) {
                    int n = in.read(buffer, read, buffer.length - read);
                    if (n <= 0) break;
                    read += n;
                }
                return read == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, read);
            } finally {
                close(in);
            }
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    private static void close(java.io.Closeable c) {
        try {
            if (c != null) c.close();
        } catch (Throwable ignored) {
        }
    }
}
