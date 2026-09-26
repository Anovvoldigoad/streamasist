# StreamOverlayLite v2.1.4

Android overlay controller ringan untuk livestream hanya dari satu HP.

## Perubahan v2.1.4
- Tidak ada ikon gembok atau hint unlock yang mengambang di luar aplikasi.
- **Text overlay:** ketika locked di luar StreamOverlayLite, tahan **2 jari selama 1,5 detik pada area teks** untuk unlock.
  - 1 jari tidak memicu aksi apa pun.
  - Area Text tetap menjadi touch target agar gesture 2 jari dapat dideteksi; karena itu kontrol game tepat di bawah area Text tidak menerima sentuhan selama Text locked.
- **Image/GIF + Overlay Link:** ketika locked di luar aplikasi, window utama tetap `FLAG_NOT_TOUCHABLE` dan tidak memiliki hotspot unlock. Touch gameplay tetap lewat.
- Foreground notification sekarang memiliki action **Unlock semua** untuk membuka semua overlay secara global tanpa masuk ke aplikasi.
- Free MOVE, full side/corner resize, direct text editing, text alignment, Image/GIF aspect ratio, dan Overlay Link tetap dipertahankan.

## Flow singkat
1. Tambah dan atur overlay.
2. Lock.
3. Saat live/game:
   - Text: 2 jari + tahan 1,5 detik pada teks untuk unlock layer itu.
   - Image/GIF / Overlay Link: tarik notification shade dan tekan **Unlock semua**.
4. Tidak ada ikon/hint overlay unlock di layar saat live.

## Build
Push repository ke GitHub lalu jalankan **Build Android APK** dari GitHub Actions.

Toolchain: minSdk 28, compileSdk/targetSdk 36, JDK 17, AGP 9.4.0, Gradle 9.6.0.
