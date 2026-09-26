# StreamOverlayLite v2.1.2

Android overlay controller for one-phone livestream setups.

## What changed in 2.1.2
- **Free/unbounded MOVE:** overlay position is no longer clamped to the phone screen. You can park part of a layer beyond any edge and the coordinates are persisted exactly.
- Main overlay windows use `FLAG_LAYOUT_NO_LIMITS` so Android is less likely to pull editor windows back inside the display.
- **Long-press unlock now works outside StreamOverlayLite too.**
  - The locked content itself remains `FLAG_NOT_TOUCHABLE`, so gameplay/live controls under the overlay still receive touches.
  - A tiny transparent 48dp long-press hotspot is created at the old lock-button position (top-right editor area).
  - Hold that invisible hotspot to unlock the layer.
  - No “hold to unlock” hint is shown outside the controller app.
- Inside StreamOverlayLite, the existing visible locked-editor hint/long-press behavior remains available.
- Overlay Link, full side/corner resize, text alignment, Image/GIF aspect-ratio resize, and direct text editing remain unchanged from 2.1.1.

## Basic flow
1. Grant Draw over other apps permission.
2. Turn Overlay Engine ON.
3. Add Text, Image/GIF, or Overlay Link.
4. Use `✥ MOVE` to position it — including partly outside the display if desired.
5. Drag any side or corner handle to resize.
6. Lock it.
7. Outside StreamOverlayLite, long-press the invisible area where the lock button was (top-right of that overlay) to unlock without opening the app.

## Build
Push the repository to GitHub and run **Build Android APK** from GitHub Actions.

Toolchain: minSdk 28, compileSdk/targetSdk 36, JDK 17, AGP 9.4.0, Gradle 9.6.0.
