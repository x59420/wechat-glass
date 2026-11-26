# wxglass · 微信液态玻璃 Dock

面向微信 Android 客户端的 LSPosed 模块：把微信底部导航栏换成悬浮液态玻璃 Dock。

> 当前版本：**0.8.6**（versionCode 52）
>
> 玻璃渲染移植自 [KernelSU](https://github.com/tiann/KernelSU) 管理器的悬浮底栏
> （Compose + Miuix 的 `drawBackdrop` / `lens` / `vibrancy`）。

---

## 当前功能

| 位置 | 行为 |
| --- | --- |
| 底部导航 | 换成悬浮液态玻璃 Dock；图标、文字取自微信原生导航项 |
| 原生底栏 | 只把 alpha 归零，不 `GONE`、不改布局、不改点击逻辑 |
| 点击 | 在原生底栏坐标系里派发真实 DOWN/UP，走微信自己的点击链路 |
| 聊天页 | Dock 收起；由底部输入框 / 聊天行判断，不依赖 Activity 生命周期 |
| 添加朋友 / 发起群聊 | Dock 收起；按二级页标题识别 |
| 微信支付 / 微信运动 / 微信团队 | Dock 收起；按二级页标题识别 |
| 公众号文章 / 新闻等二级页 | Dock 收起；标题不再属于三个主 tab 时保持收起 |
| 返回首页 | 重新识别主 tab 标题，恢复 Dock |
| ＋菜单 | 使用和 Dock 同一套半透明玻璃底、圆角和高光 |
| 冷启动 | 先藏原生底栏，再挂玻璃 Dock，不再先露原生栏 |

### 已取消的功能

- 首页右上角搜索 / ＋ 悬浮玻璃胶囊
- 顶栏渐变模糊
- 聊天消息防撤回
- 聊天头像上方时间

这些功能的代码入口已停用，避免和微信新版页面结构冲突。

### 玻璃背景

Dock 的玻璃材质来自 KernelSU 的悬浮底栏实现，Dock 会把当前页面内容画进图层后采样。
顶栏胶囊已取消，因此不再有顶栏渐变或顶栏采样。

---

## 安装

1. 从 Releases 下载最新的 `wxglass-x.y.z.apk`
2. 直接覆盖安装；签名冲突时先卸载旧版
3. LSPosed 里启用 **wxglass**，作用域勾「微信」（`com.tencent.mm`）
4. **强行停止微信**，再打开

装好后进微信，Dock 应该直接出现。若没出现，打开桌面 **wxglass** 图标，
在「日志」页导出日志定位。

---

## 设置

入口：

- 微信「我 → 设置」里的 wxglass 设置
- 桌面 / LSPosed 里的 wxglass 图标

| 分组 | 可调 |
| --- | --- |
| 开关 | 启用玻璃 Dock、显示文字标签、底栏区域透明、弹层背景透明、记录日志 |
| 尺寸 | Dock 高度、单个图标宽度、左右留白、离底部距离 |
| 外观 | 深浅模式（跟随系统 / 浅色 / 深色）、恢复默认尺寸 |
| 日志 | 查看、导出到「下载」、清空 |

旧版遗留的顶栏、防撤回、聊天时间开关仍可能显示，但对应功能已停用。

---

## 已知限制

- 微信更新可能改内部结构；认不到底栏时保持微信原生底栏，不会强行改布局。
- 聊天 / 添加朋友 / 服务号页面通过页面标题和输入框识别，极少数新页面可能需要补充标题。
- Android 11 及以下没有 RuntimeShader，玻璃是磨砂回退，没有折射与色散。
- release 已重新启用 R8 收缩；如果某次构建失败，先核对 `app/proguard-rules.pro` 的 keep 规则。

---

## 兼容性

- Android 8.0（API 26）及以上
- LSPosed（Xposed API 82 或兼容接口）
- 微信：以 `com.tencent.mm.ui.LauncherUIBottomTabView` 为锚点；
  聊天页是 `LauncherUI` 内的 Fragment，通过底部输入框识别

---

## 构建

需要 JDK 21、Gradle 9.7.1、Android SDK Platform `android-37.0` + Build Tools `37.0.0`。

```bash
ANDROID_HOME=/path/to/android-sdk bash build.sh
```

产物在 `out/wxglass-<版本>.apk`。

注意：

- `versionCode` 必须大于设备上已装版本，否则 Android 会拒绝降级安装。
- `keystore/wxglass.jks` 必须一直使用同一份，换了就不能覆盖安装。
- release 使用 R8（`isMinifyEnabled = true`；资源收缩暂时关闭，优先保证构建稳定）。

---

## 参考实现

新增功能前先看 [REFERENCES.md](REFERENCES.md)：

- 官方微信液态玻璃 UI（`WeChatBlurBar` / `GlassRenderer` / `ChatBubbleStyler`）
- `lovkem1/OKK`

---

## 开源致谢

- [tiann/KernelSU](https://github.com/tiann/KernelSU)：`FloatingBottomBar` 及 `liquid/*`、`animation/*`、`modifier/*` 的移植来源
- [compose-miuix-ui](https://github.com/miuix-kotlin-multiplatform/compose-miuix-ui)：底栏材质参考
- [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)：折射着色器、高光、色散
- [lengroo/pdd-LiquidGlass](https://github.com/lengroo/pdd-LiquidGlass)：只接管指定区域、用宿主内容当玻璃背景的思路

## 许可

Apache License 2.0，见 [LICENSE](LICENSE)。第三方衍生部分的版权与许可见 [NOTICE](NOTICE) / [NOTICE-KERNELSU](NOTICE-KERNELSU.md)。

本项目仅供学习、研究与个人非商业用途；为非官方第三方模块，与腾讯及微信官方无隶属关系。
