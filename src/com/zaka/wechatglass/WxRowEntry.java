package com.zaka.wechatglass;

import android.app.Activity;
import android.database.DataSetObserver;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 设置入口：微信首页右上角「＋」弹出的那一页，列表最后一行。
 *
 * 上一版走的是微信内部那套偏好接口（往 PreferenceScreen 里插一条），
 * 那个 adapter 的类名和插入方法每个版本都在变，对不上就整段静默失败——
 * 这就是「入口一直看不到」的原因。
 *
 * 这一版换了个不碰微信内部结构的做法：等那一页显示出来之后，
 * 把它里面的列表 adapter 包一层，在最末尾多返回一行自己画的 View。
 * 用的全是 Android 公开 API（BaseAdapter / ListView.setAdapter），
 * 微信改自己的类名、改内部方法都不影响这一行。
 *
 * 点这一行 → 打开模块自己的设置页（SettingsActivity）。
 */
final class WxRowEntry {

    /** 首页「＋」点开的那一页（原生包里叫 com.tencent.mm.ui.FindMoreFriendsUI）。 */
    private static final String CLS_PLUS = "com.tencent.mm.ui.FindMoreFriendsUI";

    private static final String TITLE = "wxglass 液态玻璃";
    private static final String SUMMARY = "点开调整 Dock 外观与位置";

    private WxRowEntry() {
    }

    static void hook(final ClassLoader cl) {
        if (cl == null) return;
        try {
            Class<?> ui = XposedHelpers.findClass(CLS_PLUS, cl);
            XposedBridge.hookAllMethods(ui, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!(param.thisObject instanceof Activity)) return;
                        final Activity act = (Activity) param.thisObject;
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                patch(act);
                            }
                        }, 350L);
                    } catch (Throwable t) {
                        WechatGlass.warnOnce("row-resume", "入口:处理失败: " + t);
                    }
                }
            });
            WechatGlass.log("入口:已挂上「＋」菜单页");
        } catch (Throwable t) {
            WechatGlass.warnOnce("row-hook", "入口:挂「＋」菜单页失败: " + t);
        }
    }

    /** 给这一页的列表末尾接一行。重复调用是安全的（已经接过就不重复接）。 */
    private static void patch(Activity act) {
        try {
            if (act == null || act.isFinishing()) return;
            View decor = act.getWindow() == null ? null : act.getWindow().getDecorView();
            ListView list = findList(decor);
            if (list == null) {
                WechatGlass.warnOnce("row-list", "入口:「＋」菜单页没找到列表控件");
                return;
            }
            ListAdapter current = list.getAdapter();
            if (current instanceof RowAdapter) return;
            if (!(current instanceof BaseAdapter) || current.getCount() <= 0) {
                // 列表还没填充好：下一轮再试一次。
                final ListView target = list;
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        ListAdapter again = target.getAdapter();
                        if (again instanceof BaseAdapter && again.getCount() > 0
                                && !(again instanceof RowAdapter)) {
                            try {
                                target.setAdapter(new RowAdapter(act, (BaseAdapter) again));
                                WechatGlass.log("入口:「＋」菜单已加上 wxglass 一行（延迟）");
                            } catch (Throwable t) {
                                WechatGlass.warnOnce("row-late", "入口:延迟接管失败: " + t);
                            }
                        }
                    }
                }, 500L);
                return;
            }
            list.setAdapter(new RowAdapter(act, (BaseAdapter) current));
            WechatGlass.log("入口:「＋」菜单已加上 wxglass 一行");
        } catch (Throwable t) {
            WechatGlass.warnOnce("row-patch", "入口:接一行失败: " + t);
        }
    }

    private static ListView findList(View root) {
        if (root == null) return null;
        ArrayDeque<View> queue = new ArrayDeque<View>();
        IdentityHashMap<View, Boolean> seen = new IdentityHashMap<View, Boolean>();
        queue.add(root);
        ListView fallback = null;
        int budget = 4000;
        while (!queue.isEmpty() && budget-- > 0) {
            View v = queue.poll();
            if (v == null || seen.put(v, Boolean.TRUE) != null) continue;
            if (v instanceof ListView) {
                ListView lv = (ListView) v;
                if (lv.getVisibility() == View.VISIBLE && lv.getAdapter() != null
                        && lv.getAdapter().getCount() > 0) {
                    return lv;
                }
                if (fallback == null) fallback = lv;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    if (c != null) queue.add(c);
                }
            }
        }
        return fallback;
    }

    // ------------------------------------------------------------ 那一行

    /** 把原生 adapter 包一层，末尾多返回一行自己的。 */
    private static final class RowAdapter extends BaseAdapter {

        private final Activity act;
        private final BaseAdapter inner;
        private final DataSetObserver observer;

        RowAdapter(Activity act, BaseAdapter inner) {
            this.act = act;
            this.inner = inner;
            this.observer = new DataSetObserver() {
                @Override
                public void onChanged() {
                    notifyDataSetChanged();
                }

                @Override
                public void onInvalidated() {
                    notifyDataSetChanged();
                }
            };
            try {
                inner.registerDataSetObserver(observer);
            } catch (Throwable ignored) {
            }
        }

        @Override
        public int getCount() {
            return inner.getCount() + 1;
        }

        @Override
        public Object getItem(int position) {
            return position < inner.getCount() ? inner.getItem(position) : null;
        }

        @Override
        public long getItemId(int position) {
            return position < inner.getCount() ? inner.getItemId(position) : -0x5f5f5f5fL;
        }

        @Override
        public int getViewTypeCount() {
            try {
                return inner.getViewTypeCount() + 1;
            } catch (Throwable t) {
                return 1;
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position >= inner.getCount()) {
                try {
                    return inner.getViewTypeCount();
                } catch (Throwable t) {
                    return 0;
                }
            }
            try {
                return inner.getItemViewType(position);
            } catch (Throwable t) {
                return 0;
            }
        }

        @Override
        public boolean isEnabled(int position) {
            if (position >= inner.getCount()) return true;
            try {
                return inner.isEnabled(position);
            } catch (Throwable t) {
                return true;
            }
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean hasStableIds() {
            try {
                return inner.hasStableIds();
            } catch (Throwable t) {
                return false;
            }
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (position < inner.getCount()) {
                return inner.getView(position, convertView, parent);
            }
            return row(convertView, parent);
        }

        private View row(View convertView, ViewGroup parent) {
            if (convertView instanceof LinearLayout && convertView.getTag() == WechatGlass.TAG) {
                return convertView;
            }
            float density = act.getResources().getDisplayMetrics().density;

            LinearLayout box = new LinearLayout(act);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackgroundColor(Color.WHITE);
            box.setPadding((int) (18 * density), (int) (14 * density),
                    (int) (18 * density), (int) (14 * density));

            TextView title = new TextView(act);
            title.setText(TITLE);
            title.setTextSize(16f);
            title.setTextColor(0xFF111111);
            box.addView(title);

            TextView summary = new TextView(act);
            summary.setText(SUMMARY);
            summary.setTextSize(12f);
            summary.setTextColor(0xFF888888);
            summary.setPadding(0, (int) (2 * density), 0, 0);
            box.addView(summary);

            box.setClickable(true);
            box.setFocusable(true);
            box.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!SettingsActivity.open(act)) {
                        IsoDock.showSettings(act);
                    }
                }
            });
            box.setTag(WechatGlass.TAG);

            // 不要自己塞 ViewGroup.LayoutParams：ListView 会按 AbsListView.LayoutParams
            // 强转，塞错了列表一显示就崩。留 null，由 ListView 自己生成。
            return box;
        }
    }
}
