# wxglass · 微信液态玻璃 Dock

面向微信 Android 客户端的 LSPosed 模块，把微信底部导航栏换成悬浮液态玻璃 Dock。

> 当前版本：**0.9.7**（versionCode 109）
>
> 玻璃渲染参考 [KernelSU](https://github.com/tiann/KernelSU) 的 `FloatingBottomBar`
> 与 Miuix 的模糊、折射参数。

---

## 功能

| 位置 | 行为 |
| --- | --- |
| 底部导航 | 悬浮液态玻璃 Dock，图标和文字取自微信原生导航项 |
| 原生底栏 | 只把 alpha 归零，不改布局、不改微信点击逻辑 |
| 点击 | 在原生底栏坐标派发真实触摸，走微信自己的点击链路 |
| 微信 / 通讯录 / 发现 / 我 | 四个主 tab 都保留 Dock |
| 聊天页 | 消息列表、头像或输入栏出现时立即收起 Dock |
| 从聊天返回 | 输入框消失后立即恢复 Dock，带快速显示动画 |
| 添加朋友 / 发起群聊 | Dock 收起，原生底栏也隐藏 |
| 微信支付 / 运动 / 团队 | 按二级页识别，Dock 收起 |
| 公众号 / 新闻等页面 | 标题不属于主 tab 时收起 |
| 冷启动 | 先隐藏原生底栏，再加载玻璃 Dock |
| 尺寸 | Dock 高度、左右留白、离底部距离即时生效 |
| 主题 | Dock 跟随微信深浅模式；设置页可单独选主题 |
| ＋菜单 | 保持微信原生菜单 |
| 设置页 | 主题弹窗、玻璃滑块、日志、在线检查更新 |

---

## 安装

1. 从 Releases 下载 `wxglass-0.9.7.apk`
2. 覆盖安装；签名冲突时先卸载旧版
3. LSPosed 启用 wxglass，作用域勾选「微信」（`com.tencent.mm`）
4. 强行停止微信后重新打开

Dock 没出现时，打开桌面 wxglass 图标，在「其它」页导出日志定位。

---

## 设置

入口：

- 微信「我 → 设置」中的 wxglass
- 桌面 / LSPosed 中的 wxglass 图标

| 分组 | 可调 |
| --- | --- |
| 开关 | 玻璃 Dock、文字标签、底栏区域透明、记录日志 |
| 尺寸 | Dock 高度、左右留白、离底部距离、恢复默认 |
| 外观 | 主题（跟随系统 / 浅色 / 深色） |
| 其它 | 检测更新、查看 / 导出 / 清空日志 |

---

## 已知限制

- 微信改版可能改变内部结构；认不到底栏时保持原生底栏。
- Android 11 及以下没有 RuntimeShader，玻璃为磨砂回退，没有折射和色散。
- 当前 release 开启 R8 收缩；构建失败时先核对 `app/proguard-rules.pro`。
- 主要在当前测试机和微信 8.0.80 上验证，其他版本需自行确认。

---

## 构建

需要 JDK 21、Gradle 9.7.1、Android SDK Platform `android-37.0` + Build Tools `37.0.0`。

```bash
ANDROID_HOME=/path/to/android-sdk bash build.sh
```

产物在 `out/wxglass-<版本>.apk`。

注意：

- `versionCode` 必须大于设备上已装版本。
- `keystore/wxglass.jks` 要保持同一份，否则不能覆盖安装。
- 当前 release 使用 R8：`isMinifyEnabled = true`、`isShrinkResources = true`。

---

## 参考

- [KernelSU](https://github.com/tiann/KernelSU)：悬浮底栏与液态玻璃参数
- [compose-miuix-ui](https://github.com/miuix-kotlin-multiplatform/compose-miuix-ui)：模糊、vibrancy、lens
- [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)：折射与高光

## 许可

Apache License 2.0，见 [LICENSE](LICENSE)。第三方部分见 [NOTICE](NOTICE) / [NOTICE-KERNELSU](NOTICE-KERNELSU.md)。

本模块与微信官方无关，仅限个人学习、研究与非商业用途。
