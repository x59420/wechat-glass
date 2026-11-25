# wxglass · 微信底栏液态玻璃

面向微信 Android 客户端的 LSPosed 模块：**只把底部导航栏换成悬浮液态玻璃 Dock**，
微信的页面、顶栏、聊天、朋友圈、菜单、手势全部保持原样。

> 当前版本：**0.1.0**（versionCode 1）
>
> 外观与参数沿用同作者的 [dydock（抖音底栏液态玻璃）](https://github.com/x59420/douyin-liquid-glass)：
> 同一套 KernelSU + Miuix 渲染器、同一组折射 / 色散 / 高光 / 弹簧参数。

---

## 它到底改了什么

| 区域 | 行为 |
| --- | --- |
| 底部四个导航项（微信 / 通讯录 / 发现 / 我） | 换成一块悬浮液态玻璃 Dock，图标和文字从微信原生导航项上取 |
| 原生底栏 | **只把 alpha 归零**，不 `GONE`、不改布局、不改点击逻辑 |
| 点击 | 在原生底栏坐标系里派发一对真实的 DOWN/UP，走微信自己的点击链路 |
| 顶栏、聊天页、朋友圈、设置页、菜单、输入框 | 完全不动 |
| 其它 Activity（聊天 / 详情 / 设置…） | 不注入，Dock 跟着微信首页一起不可见 |

为什么要「模拟触摸」而不是直接调内部方法：微信的 tab 切换、重复点击回顶、
未读角标刷新都在它自己的点击回调里，模块只当一根手指，行为才和原生完全一致。

---

## 外观

- 四个图标装在一块长条玻璃里，整组水平居中；微信没有「＋」，所以没有右边那颗圆球
- 默认 64dp 高、每格 76dp 宽、左右 24dp 安全边距、离底 8dp（都可在设置里调）
- 玻璃实时采 Dock 背后那块真实画面，交给 Miuix 做 blur / lens
  - Android 13+：真折射（RuntimeShader）
  - Android 8–12：磨砂回退，没有折射与泛光
- 按压缩放、左右拖动、弹簧回位、选中高光，与 dydock 一致
- 文字标签取自微信原生导航项，可在设置里关掉（关掉只留图标）

位置：按整屏宽度居中，**永远不压进系统手势条**；量一次就钉住，
只有转屏 / 分屏才会重新量。

---

## 安装

1. 从 [Releases](../../releases) 下载最新的 `wxglass-x.y.z.apk`
2. 安装后打开一次（桌面图标是 wxglass 设置页）
3. LSPosed 里启用 **wxglass**，作用域只勾「微信」（`com.tencent.mm`）
4. **强制停止微信**，重新打开

第一次进微信如果几秒内没看到玻璃 Dock，说明没认到底栏。去 LSPosed 日志里搜
`wxglass`，会看到类似：

```
wxglass 0.1.0 装载进微信（只替换底部导航栏）
底栏:重试 20 次仍未认到微信底栏（类名=...）
```

把那一行发出来就能定位是版本差异还是别的原因。

---

## 设置

点桌面上的 **wxglass** 图标进入（微信里没有塞任何入口，避免动它的菜单）。

| 分组 | 可调 |
| --- | --- |
| 外观 | 深浅模式（跟随系统 / 浅色 / 深色）、玻璃透明度、隐藏玻璃背景、启用开关、显示文字标签 |
| 尺寸 | Dock 高度、单个图标宽度、左右留白、离底部距离 |
| 调试 | 记录日志（关掉只留警告 / 错误） |
| — | 恢复默认尺寸 |

改完一秒内生效，不用重开微信。

---

## 兼容性

- Android 8.0（API 26）及以上
- LSPosed（Xposed API 82 或兼容接口）
- 微信：以 `com.tencent.mm.ui.LauncherUIBottomTabView` 作为锚点，
  识别不到类名时按形状兜底（贴底、等宽、3~5 个横向导航项、不含输入框）

微信更新可能改内部结构。一旦底栏类名或布局变了，模块会**保持不接管**
（微信原生底栏照常显示），不会把某个普通控件当成底栏处理掉。

> 效果与稳定性仍需在目标机型 / 微信版本上实测。作者没有真机，
> 代码层面能验证的是：编译、包结构、签名、类保留、注入点与作用域。

---

## 构建

需要 JDK 21、Gradle 9.7.1、Android SDK Platform `android-37.0` + Build Tools `37.0.0`。

```bash
ANDROID_HOME=/path/to/android-sdk bash build.sh
```

`build.sh` 会补 Xposed API jar、跑 `assembleRelease`，产物在 `out/wxglass-<版本>.apk`。

三个必须注意的点：

- `iso/**` 是按名字反射加载的，`app/proguard-rules.pro` 里要 `-keep`（连它引用的 `ksu/**`），
  否则 R8 会把玻璃层裁光，表现是「装了没反应」。
- `versionCode` 必须大于设备上已装的版本，否则安卓以「降级」为由拒绝覆盖安装。
- `keystore/wxglass.jks` 必须一直用同一份，换了就不能覆盖安装。

---

## 开源致谢

本项目的玻璃渲染不是从零写的，绝大部分视觉功劳属于下面这些项目：

- [tiann/KernelSU](https://github.com/tiann/KernelSU)：`FloatingBottomBar` 及 `liquid/*`、`animation/*`、`modifier/*` 的移植来源（见 [NOTICE-KERNELSU](NOTICE-KERNELSU.md)）
- [compose-miuix-ui](https://github.com/miuix-kotlin-multiplatform/compose-miuix-ui)：KernelSU 那份底栏改编自其 `IosLiquidGlassNavigationBar` 示例（Apache-2.0）
- [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（Apache-2.0）：折射着色器、高光、色散、弹簧动画
- [lengroo/pdd-LiquidGlass](https://github.com/lengroo/pdd-LiquidGlass)（MIT）：只接管指定区域、用宿主内容当玻璃背景的思路
- 同作者的 [dydock](https://github.com/x59420/douyin-liquid-glass)：本项目的渲染层、几何参数与设置界面直接复用自它

液态玻璃相关的折射、色散、内阴影、泛光、拖动阻尼等效果均来自上述项目，**版权归原作者**。

---

## 许可

Apache License 2.0，见 [LICENSE](LICENSE)。第三方衍生部分的版权与许可见 [NOTICE](NOTICE) / [NOTICE-KERNELSU](NOTICE-KERNELSU.md)。

本项目仅供学习、研究与个人**非商业**用途；为非官方第三方模块，与腾讯及微信官方无隶属关系。
「微信」「WeChat」及相关商标归其各自所有者所有。修改第三方应用可能因宿主更新而失效，
也可能带来兼容性或账号方面的风险，请自行判断是否使用。

---

## 版本历史

| 版本 | 要点 |
| --- | --- |
| 0.1.0 | 首个版本：替换微信底部导航栏为液态玻璃 Dock；点击转发原生；位置钉死；设置页可调尺寸与外观 |
