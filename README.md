# wxglass · 微信液态玻璃

面向微信 Android 客户端的 LSPosed 模块。把微信的**底部导航栏**换成悬浮液态玻璃 Dock，
并给首页顶栏、「＋」面板做同一套玻璃材质。

> 当前版本：**0.3.0**（versionCode 6）
>
> 玻璃渲染移植自 [KernelSU](https://github.com/tiann/KernelSU) 管理器的悬浮底栏
> （Compose + Miuix 的 `drawBackdrop` / `lens` / `vibrancy`），
> 参数与上游一致，只把「背景从哪来」换成了微信这一页的真实内容。

---

## 它改了什么

| 位置 | 行为 |
| --- | --- |
| 底部四个导航项 | 换成悬浮液态玻璃 Dock；图标、文字取自微信原生导航项 |
| 原生底栏 | 只把 alpha 归零，不 `GONE`、不改布局、不改点击逻辑 |
| 点击 | 在原生底栏坐标系里派发真实的 DOWN/UP，走微信自己的点击链路 |
| 首页顶栏 | 搜索 / 「＋」合成一颗悬浮玻璃胶囊，顶栏加一层从上到下的透明渐变 |
| 「＋」面板 | 面板底色换成同一套玻璃材质；面板里插一行 **wxglass 设置** |
| 聊天页 / 添加朋友页 / 其它页面 | Dock 立刻收起，不跟着页面走 |
| 设置入口 | 「＋」面板里的 **wxglass 设置**；模块自己也有桌面图标可以打开 |

### 背景是怎么来的

跟 KernelSU 的 Dock 一样：**不是定时截屏**，而是把内容画进一个 GraphicsLayer，
玻璃用 `drawBackdrop` 直接采样这层纹理，在 GPU 上做模糊、折射、泛光。

区别只在于微信的内容不是 Compose，所以由宿主把当前页面的 View 树画进同一个图层。
好处是滚动、翻页、视频都能实时反映到玻璃里，也没有「截屏 → 回读内存」那一步的卡顿。

- Android 12+：真·实时纹理采样（本版本默认路径）
- Android 8–11：退回窗口直采 / 软件采样的兜底路径
- 折射量比上游收小了一档（13dp / 11dp），微信这种大色块页面不会被扭得发晃

---

## 安装

1. 从 [Releases](../../releases) 下载最新的 `wxglass-x.y.z.apk`
2. 直接安装，覆盖旧版即可（签名不变，`versionCode` 递增）
3. LSPosed 里启用 **wxglass**，作用域勾「微信」（`com.tencent.mm`）
4. **强制停止微信**，重新打开

装好后进微信，Dock 应该直接出现；如果没出现，打开桌面的 **wxglass** 图标，
在「日志」页点「导出」，把导出的 txt 发出来即可定位。

---

## 设置

两个入口，任选：

- 微信首页右上角「＋」→ 面板里的 **wxglass 设置**
- 桌面 / LSPosed 里的 **wxglass** 图标

| 分组 | 可调 |
| --- | --- |
| 开关 | 启用玻璃 Dock、显示文字标签、底栏区域透明、弹层背景透明、抓不到画面时用主色垫底、记录日志 |
| 尺寸 | Dock 高度、单个图标宽度、左右留白、离底部距离 |
| 外观 | 深浅模式（跟随系统 / 浅色 / 深色）、恢复默认尺寸 |
| 日志 | 查看最近日志、导出到「下载」、清空 |

改完一秒内生效，不用重开微信。

---

## 已知限制

- 顶栏玻璃只在**首页**生效；认不出那两颗图标（微信改版）时整段静默放弃，顶栏保持原样。
- 「＋」面板的入口优先插进面板本身；面板结构不是竖排列表时，退化成贴着面板下沿的一颗胶囊。
- 顶栏的渐变是**渐变遮罩**，不是逐像素的实时模糊（顶栏底下压着微信自己的标题栏，做实时模糊会把它自己画进去）。
- Android 11 及以下没有 RuntimeShader，玻璃是磨砂回退，没有折射与色散。

---

## 兼容性

- Android 8.0（API 26）及以上
- LSPosed（Xposed API 82 或兼容接口）
- 微信：以 `com.tencent.mm.ui.LauncherUIBottomTabView` 为锚点，识别不到时按形状兜底
  （贴底、等宽、3~5 个横向导航项、不含输入框）
- 认不到就**保持不接管**，微信原生底栏照常显示，不会把普通控件当成底栏改掉

微信更新可能改内部结构。遇到「装了没反应」，先看设置页导出的日志：
里面会写清楚是没装载、没认到底栏，还是玻璃没画出来。

---

## 构建

需要 JDK 21、Gradle 9.7.1、Android SDK Platform `android-37.0` + Build Tools `37.0.0`。

```bash
ANDROID_HOME=/path/to/android-sdk bash build.sh
```

产物在 `out/wxglass-<版本>.apk`。三个必须注意的点：

- `iso/**` 与 `ksu/**` 是按名字反射加载的，`app/proguard-rules.pro` 里要 `-keep`，
  否则 R8 会把玻璃层裁光，表现就是「装了没反应」。
- `versionCode` 必须大于设备上已装的版本，否则安卓以「降级」为由拒绝覆盖安装。
- `keystore/wxglass.jks` 必须一直用同一份，换了就不能覆盖安装。

---

## 开源致谢

- [tiann/KernelSU](https://github.com/tiann/KernelSU)：`FloatingBottomBar` 及 `liquid/*`、`animation/*`、`modifier/*` 的移植来源（见 [NOTICE-KERNELSU](NOTICE-KERNELSU.md)）
- [compose-miuix-ui](https://github.com/miuix-kotlin-multiplatform/compose-miuix-ui)：KernelSU 那份底栏改编自其 `IosLiquidGlassNavigationBar` 示例（Apache-2.0）
- [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（Apache-2.0）：折射着色器、高光、色散、弹簧动画
- [lengroo/pdd-LiquidGlass](https://github.com/lengroo/pdd-LiquidGlass)（MIT）：只接管指定区域、用宿主内容当玻璃背景的思路

液态玻璃相关的折射、色散、内阴影、泛光、拖动阻尼等效果均来自上述项目，**版权归原作者**。

---

## 许可

Apache License 2.0，见 [LICENSE](LICENSE)。第三方衍生部分的版权与许可见 [NOTICE](NOTICE) / [NOTICE-KERNELSU](NOTICE-KERNELSU.md)。

本项目仅供学习、研究与个人**非商业**用途；为非官方第三方模块，与腾讯及微信官方无隶属关系。
「微信」「WeChat」及相关商标归其各自所有者所有。修改第三方应用可能因宿主更新而失效，
请自行判断是否使用。

---

## 版本历史

| 版本 | 要点 |
| --- | --- |
| 0.1.0 | 首个版本：底部导航栏换成液态玻璃 Dock，点击转发原生 |
| 0.1.1 | 去掉桌面图标；设置入口改到微信「我 → 设置」上方 |
| 0.1.3 | 设置入口修到真正的「＋」页；Dock 接管加固；加载状态可见化 |
| 0.2.0 | Dock 跟页面显隐、入口改胶囊、背景改窗口直采、顶栏 / 弹层玻璃、设置页重做 |
| 0.3.0 | 背景改成 KernelSU 同款图层采样；Dock 在聊天页 / 面板页立刻收起；「＋」面板入口重做；顶栏玻璃胶囊；弹层玻璃；设置页深浅模式适配 + 日志导出 / 清空；去掉透明度与隐藏玻璃开关 |
