# LSPosed 按 assets/xposed_init 里的全名反射加载入口类，名字必须保留。
-keep class com.zaka.wechatglass.** { *; }

# 玻璃层是在独立类加载器里按名字反射加载的（IsoDock），R8 必须原样保留整个 iso 包；
# 它引用的 Compose / Miuix 组件（ksu 包）同样只能按名字用，不能被裁掉。
-keep class com.zaka.wechatglass.iso.** { *; }
-keep class com.zaka.wechatglass.ksu.** { *; }

# Xposed API 由 LSPosed 运行时提供，不打进包里。
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**

# RecyclerView 只在编译期取个类型，运行期用微信进程里那一份（compileOnly），
# 所以 R8 会看到"引用但没有实现"，告诉它这是故意的。
-dontwarn androidx.recyclerview.**
-dontwarn androidx.annotation.**

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# 只做收缩和优化，不做重命名：避免 Compose/LSPosed 任何按名字走的路径被改坏。
-dontobfuscate
