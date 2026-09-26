# StreamOverlayLite v2.1.1

Android overlay controller for one-phone livestream setups.

## What changed in 2.1.1
- **Donation Alert / Donation URL renamed to Overlay Link.** Paste one HTTPS overlay/widget link from Saweria, Trakteer, Streamlabs, or another provider.
- **Full resize frame:** drag the left, right, top, bottom, or any of the four corners.
- **Dedicated `✥ MOVE` handle:** moving an overlay is separate from resizing it.
- **Image/GIF corner resize keeps the original aspect ratio.** Side handles can freely adjust the frame without stretching the bitmap itself (`FIT_CENTER`).
- Text remains editable directly on the overlay, with horizontal + vertical alignment controls.
- Long-press a locked overlay while the StreamOverlayLite controller is open to unlock it.
- Locked overlays remain click-through when you leave the controller and return to your game/live app.

## Basic flow
1. Grant Draw over other apps permission.
2. Turn Overlay Engine ON.
3. Add Text, Image/GIF, or **Overlay Link**.
4. Use `✥ MOVE` to position it.
5. Drag any side or corner handle to resize.
6. Lock it when the position is final.

## Build
Push the repository to GitHub and run **Build Android APK** from GitHub Actions.

Toolchain: minSdk 28, compileSdk/targetSdk 36, JDK 17, AGP 9.4.0, Gradle 9.6.0.
