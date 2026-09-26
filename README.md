# Stream Overlay Lite

Native Android MVP untuk membuat livestream dari **satu HP** terasa seperti setup streaming desktop: overlay teks, gambar/GIF, dan donation alert di atas aplikasi livestream/game.

## Fitur MVP

- Overlay Engine ON/OFF.
- Layer **Text** yang editable setelah di-apply.
- Layer **Image/GIF** dari storage/document picker.
- Layer **Donation Alert** dengan template `{name}`, `{amount}`, `{message}`.
- ON/OFF per layer.
- LOCK / UNLOCK per layer.
  - LOCK: overlay memakai `FLAG_NOT_TOUCHABLE`, jadi sentuhan diteruskan ke aplikasi di bawah.
  - UNLOCK: layer bisa di-drag; tap cepat layer untuk membuka editor lagi.
- Posisi layer disimpan otomatis.
- Width/height, text size, foreground/background color editable.
- Test Donation built-in.
- Donation Notification Bridge.
- Generic JSON donation feed.
- Native Android API saja: tidak memakai Compose, image loader, database, atau library networking pihak ketiga.
- GIF menggunakan `ImageDecoder`/`AnimatedImageDrawable` bawaan Android (min Android 9 / API 28).

## Donation source

### A. Notification Bridge

Aktifkan dari **Donation Source > Notification Bridge**, lalu beri Notification Access.

Filter yang tersedia:
- package aplikasi sumber (opsional),
- keyword comma-separated.

Notifikasi yang cocok diubah menjadi donation alert **secara lokal**. Cocok sebagai bridge untuk layanan/aplikasi yang memang menghasilkan notifikasi Android saat support/donasi masuk.

### B. Generic JSON Feed

Aplikasi melakukan GET ke endpoint yang kamu isi. Format paling sederhana:

```json
{
  "event_id": "abc123",
  "name": "Budi",
  "amount": 25000,
  "message": "Semangat!"
}
```

`event_id` harus unik. Aplikasi juga mengenali beberapa alias seperti `id`, `transaction_id`, `donor_name`, `nominal`, `text`, dan `note`.

> Integrasi provider tertentu seperti Saweria/Trakteer sengaja dipisahkan sebagai adapter. Jangan menaruh token rahasia/provider secret langsung di APK. Bila provider menyediakan webhook/server-side secret, gunakan relay endpoint yang aman lalu arahkan Generic JSON Feed ke endpoint relay tersebut.

## Build langsung di GitHub

1. Buat repository GitHub baru.
2. Upload seluruh isi folder project ini ke root repository.
3. Buka tab **Actions**.
4. Jalankan workflow **Build Android APK** atau push commit ke `main`/`master`.
5. Setelah build sukses, buka run tersebut > **Artifacts** > `StreamOverlayLite-debug-apk`.

Workflow menggunakan:
- JDK 17
- Android API 36
- Android Build Tools 36.0.0
- Android Gradle Plugin 9.4.0
- Gradle 9.6.0

Tidak perlu Android Studio untuk menghasilkan APK lewat GitHub Actions.

## Cara pakai

1. Install APK.
2. Buka aplikasi dan izinkan **Display over other apps**.
3. Nyalakan **Overlay Engine**.
4. Tambah layer.
5. Atur ukuran/teks lalu **Save & Apply**.
6. Selama layer UNLOCKED, pindahkan dengan drag dan tap cepat untuk edit.
7. Setelah posisi final, LOCK layer supaya sentuhan tidak mengganggu game/aplikasi livestream.
8. Untuk donation alert, tambahkan layer Donate lalu gunakan **Test Donation** untuk memastikan posisi dan tampilannya benar.

## Fokus performa

MVP ini menghindari WebView, Compose, Room, Retrofit/OkHttp, Glide/Coil, dan animation engine tambahan. Overlay hanya berupa beberapa `View` native yang dikelola `WindowManager`. JSON settings disimpan kecil di `SharedPreferences`.

Untuk HP RAM kecil, tetap disarankan:
- gunakan GIF beresolusi kecil,
- jangan memasang terlalu banyak GIF sekaligus,
- optimalkan GIF sebelum dimasukkan,
- matikan layer yang tidak dipakai.

## Catatan Android

Overlay membutuhkan permission khusus `SYSTEM_ALERT_WINDOW`. Foreground service dipakai agar overlay tetap berjalan ketika aplikasi pengaturan ditutup. Android 14+ juga membutuhkan deklarasi foreground-service type; project menggunakan `specialUse` untuk user-controlled persistent overlay.

Beberapa aplikasi dapat memilih untuk **memblokir third-party overlay** pada layar tertentu (Android 12+ menyediakan mekanisme `HIDE_OVERLAY_WINDOWS`). Karena itu, sebelum dipakai serius, tes sekali dengan aplikasi livestream target kamu untuk memastikan layer terlihat di layar yang akan disiarkan.

## Next milestone yang paling masuk akal

- resize handle langsung di overlay,
- opacity slider,
- rotate / scale gesture,
- duplicate/reorder layer,
- scene/profile presets,
- custom font,
- alert sound,
- queue donation agar alert tidak saling menimpa,
- provider adapter resmi jika API/provider contract tersedia,
- release signing workflow.
