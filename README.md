# StreamOverlayLite v2.2.0

Resize fix untuk Overlay Link:
- WebView sekarang memakai fixed browser canvas.
- Handle kanan-bawah menskalakan seluruh canvas sebagai satu visual surface.
- DOM/halaman tidak di-resize pada setiap gerakan, sehingga tidak reflow/pecah/dobel saat ditarik.
- Project lama v2.1.9 otomatis dimigrasi: ukuran frame terakhir menjadi canvas dasar baru pada skala 1x.
- Overlay Link tetap transparan, MOVE/LOCK tetap seperti v2.1.9, dan floating controller per-overlay tetap ada.

Catatan: gunakan URL Browser Source/Overlay Link dari provider, bukan URL halaman dashboard/pengaturan.
