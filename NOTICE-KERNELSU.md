KernelSU Manager Dock source attribution
========================================

This project ports/adapts the following UI components from tiann/KernelSU
(https://github.com/tiann/KernelSU):

- manager/app/src/main/java/me/weishu/kernelsu/ui/component/FloatingBottomBar.kt
- .../ui/component/miuix/animation/DampedDragAnimation.kt
- .../ui/component/miuix/animation/InteractiveHighlight.kt
- .../ui/component/miuix/modifier/DragGestureInspector.kt
- .../ui/component/liquid/Lens.kt
- .../ui/component/liquid/InnerShadow.kt
- .../ui/component/liquid/CombinedBackdrop.kt
- .../ui/component/liquid/Vibrancy.kt

The files were copied into src/com/zaka/douyin/glass/ksu/ with only these
changes: the package declaration, the import paths, and one guarded call so
that InteractiveHighlight (which constructs a RuntimeShader) is skipped below
API 33. Nothing else in the ported code was rewritten, and the glass
parameters (vibrancy, blur 4dp, lens 24dp, highlight 0.75, 64dp bar height,
56dp inner height) are kept as upstream.

KernelSU's FloatingBottomBar.kt carries this header:

    // Adapted from compose-miuix-ui example (IosLiquidGlassNavigationBar) — Apache 2.0.

so the ported portions remain under the Apache License 2.0, and the original
credit for the liquid glass navigation bar design belongs to the
compose-miuix-ui authors. See LICENSE for the full license text and NOTICE for
the other upstream projects referenced by this repository.
