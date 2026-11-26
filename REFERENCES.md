# 参考实现

新增功能前先看这两个仓库，避免重复试错。

## 1. 官方微信液态玻璃 UI

- https://github.com/Xposed-Modules-Repo/io.github.yingxingyswx_design.wechatglass
- 本地源码：`/root/workspace/official-wxglass/src`
- 版本参考：`1.3.3`

关键文件：

- `app/src/main/java/com/wechatglass/WeChatBlurBar.java`
  - 反射微信自带的 `com.tencent.mm.ui.blur.BlurView`
  - 用 `ll5.k` / `ll5.f` 配 BlurController
  - 这是 GPU 实时模糊，不是截图后渐变
- `app/src/main/java/com/wechatglass/GlassRenderer.java`
  - `RenderNode` + `RenderEffect` 做 GPU 模糊
  - 失败时回退纯色
- `app/src/main/java/com/wechatglass/HomeGlassHook.java`
  - 顶栏锚点是 `androidx.appcompat.widget.ActionBarContainer`
  - 不要在全树里猜“最后两个可点 View”
- `app/src/main/java/com/wechatglass/ChatBubbleStyler.java`
  - 聊天时间不是只读 adapter
  - 递归扫行 View 的 `tag`、字段、MVVM/ViewModel 对象图
- 官方没有实现消息防撤回

## 2. OKK

- https://github.com/lovkem1/OKK
- 用途待补充；新增功能前先拉下来看结构和实现。

## 结论

- 顶栏：优先反射微信 `BlurView` + `RenderNode`，不要自己截图做渐变
- 聊天时间：优先递归扫 View tag / MVVM 对象图
- Dock 页面判断：聊天/添加朋友是 `LauncherUI` 内的 Fragment，不能只 hook Activity
- 防撤回：官方无参考，需要单独做
