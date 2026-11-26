package com.zaka.wechatglass;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * 顶栏 GPU 模糊：直接复用微信自带的 BlurView。
 *
 * 这不是截屏后渐变，BlurView 自己会采样窗口背后的内容并在 GPU 上做模糊；
 * 失败时返回 null，调用方回退到原来的低分辨率采样。
 */
final class WxTopBlur {

    private WxTopBlur() {
    }

    static View create(Context context, ViewGroup decor, int widthPx, int heightPx, int tint) {
        try {
            ClassLoader cl = context.getClassLoader();
            Class<?> bvCls = Class.forName("com.tencent.mm.ui.blur.BlurView", false, cl);
            Object bv = bvCls.getConstructor(Context.class).newInstance(context);
            bvCls.getMethod("c", ViewGroup.class).invoke(bv, decor);

            Object ctl = bvCls.getMethod("getBlurController").invoke(bv);
            Class<?> f = Class.forName("ll5.f", false, cl);
            Object algo = Class.forName("ll5.k", false, cl)
                    .getConstructor(Context.class).newInstance(context);
            f.getMethod("b", Class.forName("ll5.e", false, cl)).invoke(ctl, algo);
            f.getMethod("j", Drawable.class).invoke(ctl, decor.getBackground());
            f.getMethod("h", float.class).invoke(ctl, 15f);
            f.getMethod("k", boolean.class).invoke(ctl, true);
            f.getMethod("e", boolean.class).invoke(ctl, false);
            f.getMethod("i", boolean.class).invoke(ctl, true);

            bvCls.getField("f").setFloat(bv, 0f);
            bvCls.getField("e").setInt(bv, tint);
            bvCls.getField("i").set(bv, new float[]{0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f});
            bvCls.getField("m").setBoolean(bv, false);

            View view = (View) bv;
            view.setClickable(false);
            view.setFocusable(false);
            view.setLayoutParams(new FrameLayout.LayoutParams(widthPx, heightPx));
            return view;
        } catch (Throwable t) {
            WechatGlass.warnOnce("topbar-blur", "顶栏 GPU 模糊不可用: " + t);
            return null;
        }
    }
}
