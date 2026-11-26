# wxglass 状态

## 当前版本
0.1.3（versionCode 4），产物 `output/wxglass-0.1.3.apk`，签名与 0.1.2 相同，可直接覆盖安装。

## 0.1.3 改了什么
1. 设置入口修到真正的「＋」菜单页：`com.tencent.mm.ui.FindMoreFriendsUI`。
   0.1.2 挂的 `AddMoreFriendsUI` 是「添加朋友」页（其偏好布局里是 find_friends_by_input 等条目），
   所以入口一直不出现。
2. 入口实现方式换掉：不再调微信内部的 PreferenceScreen/adapter 插入接口
   （`g0.d(pref,int)`、`h(String)`、`m(String)` 这些每个版本都在变，对不上就静默失败），
   改成包一层 ListView 的 adapter，在最末尾多返回一行自绘 View。只用 Android 公开 API。
3. 设置界面改成模块自己的 `SettingsActivity`（LAUNCHER 入口），不再往微信里注入 Compose 浮层。
   改动通过广播 + XSharedPreferences 双通道同步进微信进程。
4. 加载可见化：
   - 模块进微信 → Toast「0.1.3 已加载」
   - 挂上 Dock → Toast「wxglass：Dock 已挂上」
   - 21 秒仍没认到底栏 → Toast「wxglass：没认到微信底栏」
5. 触发路径加了一条最稳的：`LauncherUIBottomTabView` 构造函数 hook（不依赖首页 Activity 类名 / onResume）。
6. `isHost` 不再只看类名 `LauncherUI`，类名对不上时按底栏 id 0x7f0a3dd1 判定。
7. xposedminversion / module.prop minApiVersion 从 93 降到 82（老框架也能加载）。

## 已排除过的原因
- 打包层面：xposed_init、meta-data(xposedmodule/xposedscope/xposedminversion)、
  META-INF/xposed/module.prop、签名、入口类未被 R8 裁掉 —— 0.1.2 均已核对正常。
- 入口类在 dex 里的接口实现 `IXposedHookLoadPackage`/`IXposedHookZygoteInit` 正常。
- 官方包 8.0.80(3220) 核对：LauncherUIBottomTabView 存在，setId(0x7f0a3dd1) 确认；
  FindMoreFriendsUI extends AbstractTabChildPreference，有 w0/y0 点击方法。

## 待验证（无真机）
只弹「已加载」不弹后两条 = 底栏没认到；三条都不弹 = 微信进程里模块没被加载，
要查 LSPosed 的启用状态/作用域/微信是否强制停止重开。
