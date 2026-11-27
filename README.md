# wxglass · 微信液态玻璃 Dock

面向微信 Android 客户端的 LSPosed 模块：把微信底部导航栏换成悬浮液态玻璃 Dock。

> 当前版本：**0.9.1（正式版）**（versionCode 103）
>
> 正式版：只保留稳定 Dock；顶栏、悬浮胶囊和＋菜单玻璃已取消。
>
> 玻璃渲染移植自 [KernelSU](https://github.com/tiann/KernelSU) 管理器的 `FloatingBottomBar`
> （Compose + Miuix 的 `drawBackdrop` / `lens` / `vibrancy`）。

---

## 当前功能

| 位置 | 行为 |
| --- | --- |
| 底部导航 | 换成悬浮液态玻璃 Dock；图标、文字取自微信原生导航项 |
| 原生底栏 | 只把 alpha 归零，不 `GONE`、不改布局、不改点击逻辑 |
| 点击 | 在原生底栏坐标系里派发真实 DOWN/UP，走微信自己的点击链路 |
| 微信 / 通讯录 / 发现 / 我 | 四个主 tab 都保留 Dock，切 tab 不再丢 |
| 聊天页 | 消息列表/头像/输入栏一出现就收起，不等键盘弹起 |
| 添加朋友 / 发起群聊 | Dock 收起，原生底栏也压掉 |
| 微信支付 / 微信运动 / 微信团队 | 按二级页识别，Dock 收起 |
| 公众号 / 新闻等二级页 | 标题不再属于主 tab 时收起 |
| 从聊天返回 | 输入框消失后立即补装 Dock，显示动画走 80ms 快速路径 |
| 冷启动 | 先藏原生底栏，再挂玻璃 Dock，不再先露原生栏 |
| 尺寸设置 | Dock 高度、左右留白、离底部距离会即时重排 |
| 设置页 | 主题弹窗在父控件下方、展开动画 |

### 已取消的功能

- 消息防撤回
- 聊天头像上方显示时间

这两项已从功能和设置页移除，避免继续和微信新版消息结构冲突。


---

## 安装

1. 从 Releases 下载 `wxglass-0.9.1.apk`
2. 覆盖安装；签名冲突时先卸载旧版
3. LSPosed 里启用 **wxglass**，作用域勾「微信」（`com.tencent.mm`）
4. 强行停止微信，再打开

装好后进微信，Dock 应该直接出现。若没出现，打开桌面 **wxglass** 图标，
在「日志」页导出日志定位。

---

## 设置

入口：

- 微信「我 → 设置」里的 wxglass 设置
- 桌面 / LSPosed 里的 wxglass 图标

| 分组 | 可调 |
| --- | --- |
| 开关 | 启用玻璃 Dock、显示文字标签、底栏区域透明、记录日志 |
| 尺寸 | Dock 高度、左右留白、离底部距离、恢复默认 |
| 外观 | 深浅模式（跟随系统 / 浅色 / 深色） |
| 日志 | 查看、导出到「下载」、清空 |

「左右留白」和「离底部距离」现在会直接改变 Dock 几何，不再被固定居中/导航栏 clamp 吃掉。

---

## 已知限制

- 顶栏、悬浮胶囊、＋菜单玻璃均已移除，不再处理。
- 通讯录、添加朋友、支付/团队/运动靠标题、底栏选中项和页面标记综合判断，极少数新页面可能需要补充。
- Android 11 及以下没有 RuntimeShader，玻璃是磨砂回退，没有折射与色散。
- 当前 release 关闭了 R8，APK 约 12MB。
- 测试版只保证当前测试机上的主链路，其他机型/微信版本需要自行验证。

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
- 当前 release 使用 `isMinifyEnabled = false` / `isShrinkResources = false`，优先保证测试构建稳定。

---

## 参考实现

新增功能前先看 [REFERENCES.md](REFERENCES.md)：

- KernelSU `FloatingBottomBar`：Dock 尺寸/材质来源
- 官方微信液态玻璃 UI：`WeChatBlurBar`、`GlassRenderer`
- JMComicX：`BlurredBar`、`textureBlur` / `progressiveTextureBlur`
- Peekaboo：只有功能说明，无源码

---

## 版本记录

- **0.8.51（测试版）**：＋菜单面板玻璃、设置弹窗位置/动画、Dock 底部距离修复、测试发行
- **0.8.48**：Dock 底部距离改为以可见窗口底边为基准
- **0.8.46**：左右留白真正生效，Dock 宽度按屏幕减边距
- **0.8.43**：聊天返回 80ms 快速动画；关闭防撤回/头像时间并删除设置项
- **0.8.42**：通讯录列表不再把「群聊」误判成二级页
- **0.8.41**：聊天根节点出现即收 Dock，不等输入框
- **0.8.35**：统一二级页/主 tab 分类，通讯录与添加朋友稳定
- **0.8.6**：早期稳定 Dock 版本

## 开源致谢

- [tiann/KernelSU](https://github.com/tiann/KernelSU)：`FloatingBottomBar` 及液态玻璃参数
- [compose-miuix-ui](https://github.com/miuix-kotlin-multiplatform/compose-miuix-ui)：底栏材质
- [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)：折射着色器与高光
- [HapeLee/JMComicX](https://github.com/HapeLee/JMComicX)：`BlurredBar` / 渐进模糊参考

## 许可

Apache License 2.0，见 [LICENSE](LICENSE)。第三方衍生部分见 [NOTICE](NOTICE) / [NOTICE-KERNELSU](NOTICE-KERNELSU.md)。

本项目仅供学习、研究与个人非商业用途；为非官方第三方模块，与腾讯及微信官方无隶属关系。
