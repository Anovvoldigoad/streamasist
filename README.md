# StreamOverlayLite v2.1.7

## Perubahan v2.1.7 — Floating Controller per overlay

Saat keluar aplikasi, bubble controller sekarang tidak lagi hanya punya tombol global Lock All / Unlock All.

Tap bubble untuk melihat semua overlay yang terpasang. Setiap overlay memiliki kontrol sendiri:

- **LOCK / UNLOCK** — hanya mengubah mode lock overlay tersebut.
- **ON / OFF** — hanya menampilkan/menyembunyikan overlay tersebut.

Contoh menu:

- Text · T — LOCK/UNLOCK — ON/OFF
- Image / GIF · IMG — LOCK/UNLOCK — ON/OFF
- Overlay Link · LINK — LOCK/UNLOCK — ON/OFF

Notification tetap menjadi master **Overlay ON/OFF** untuk menyembunyikan atau menampilkan seluruh overlay sekaligus.

Layer yang LOCK di luar aplikasi tetap click-through sehingga tidak mengambil sentuhan gameplay. Bubble controller tetap satu-satunya area kontrol yang touchable dan bisa dipindah.
