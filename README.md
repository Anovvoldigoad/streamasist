# StreamOverlayLite v2.2.2

Deep fix untuk Overlay Link dan floating controller.

## Overlay Link
- Browser Source sekarang memakai desktop user-agent, bukan Android/mobile UA.
- WebView memakai kanvas tetap 1280x720 px (16:9) dan tidak di-resize saat drag.
- Resize handle `↘` mentransform seluruh browser surface secara proporsional seperti scene transform di OBS.
- Source tidak lagi memakai base canvas 320x180dp yang memicu responsive/mobile layout.
- Viewport source dipaksa 1280 dan background halaman tetap transparan.
- Batas resize Overlay Link sampai sekitar 4x ukuran layar.

## Floating controller
- Bubble diperkecil dari 40dp menjadi 34dp.
- Opacity avatar menjadi 48%.
- Tetap selalu dipromosikan ke z-order paling atas.

## Catatan
Gunakan URL Overlay/Browser Source dari provider, bukan URL halaman dashboard/pengaturan.
