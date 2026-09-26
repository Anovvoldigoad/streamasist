# Stream Overlay Lite v2

Android overlay companion ringan untuk streamer yang ingin live **cukup dari satu HP** tanpa panel setting yang ribet.

## Flow v2

1. Beri izin **Display over other apps**.
2. Nyalakan **Overlay Engine**.
3. Tambahkan salah satu:
   - **Text**
   - **Image / GIF**
   - **Donation URL**
4. Saat **UNLOCK**, atur langsung di layar:
   - `↕` = drag/pindah
   - `↘` = resize
   - Text: tap tulisan untuk mengetik langsung
   - Text: `A−`, `A+`, `●`, `BG` untuk ukuran dan warna sederhana
   - `🔒` = lock langsung
5. Saat **LOCK**, editor hilang, posisi tetap di koordinat terakhir, dan overlay menjadi tidak bisa disentuh.

## Donation alert v2

Donation tidak lagi memakai Notification Bridge atau JSON feed.

Cukup tekan **+ Donation URL** lalu paste **widget/source URL HTTPS** dari provider seperti Saweria, Trakteer, Streamlabs, dan provider lain yang menyediakan browser/widget source.

Aplikasi merender source tersebut melalui WebView transparan:
- background WebView transparan,
- HTML/body dipaksa transparan setelah source selesai dimuat,
- JavaScript + DOM storage aktif karena widget alert umumnya membutuhkannya,
- media autoplay diizinkan untuk alert sound,
- HTTP biasa ditolak; gunakan HTTPS.

Saat source widget sedang idle, area overlay akan transparan selama widget/provider tersebut memang menggunakan desain alert transparan. Pengaturan style alert tetap dilakukan di dashboard provider.

## Lock v2: posisi tidak boleh bergeser

Lock v2 tidak melakukan `removeView()` lalu membuat overlay baru.

Urutannya:
1. simpan `x`, `y`, width, height dari `WindowManager.LayoutParams` yang sedang tampil,
2. ubah flag window yang sama,
3. panggil `updateViewLayout()` tanpa menghitung ulang posisi.

Jadi lock/unlock tidak melakukan center, snap, grid, atau reset koordinat.

### Catatan Android 12+

Android 12 membatasi touch-through untuk third-party application overlay. Agar locked overlay tetap dapat meneruskan sentuhan ke aplikasi di bawahnya, v2 memakai `LayoutParams.alpha = 0.80` pada Android 12+ ketika LOCK.

## Text overlay

Tidak ada lagi editor form dengan:
- kode warna HEX,
- angka width/height,
- angka font size,
- koordinat x/y.

Text diedit langsung pada overlay. Ukuran dan warna memakai kontrol visual sederhana.

## Image / GIF

- Pilih langsung dari Android document picker.
- GIF memakai `ImageDecoder` / `AnimatedImageDrawable` native Android 9+.
- Drag dan resize langsung ketika unlocked.
- Tidak memakai Glide/Coil.

## Arsitektur

- Java + Android Views.
- `TYPE_APPLICATION_OVERLAY`.
- Satu `OverlayService` foreground.
- SharedPreferences untuk state kecil.
- Satu WindowManager window kecil per overlay.
- WebView hanya dibuat untuk layer Donation URL.
- Tidak memakai Compose, Room, Retrofit, OkHttp, Glide, atau Coil.

## Build di GitHub

Project sudah dilengkapi workflow GitHub Actions.

Toolchain:
- JDK 17
- Android API 36
- Build Tools 36.0.0
- Android Gradle Plugin 9.4.0
- Gradle 9.6.0

Langkah:
1. Upload isi project ke root repository.
2. Buka **Actions**.
3. Jalankan **Build Android APK**.
4. Download artifact `StreamOverlayLite-debug-apk`.

## Compatibility note

Beberapa aplikasi Android dapat menyembunyikan third-party overlays. Selain itu, Android 14 single-app screen sharing dapat mengecualikan overlay aplikasi lain. Untuk livestream, gunakan full-display/screen capture bila aplikasi streaming menyediakan pilihan tersebut dan selalu tes output dari sisi viewer.
