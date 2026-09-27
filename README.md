# Stream Overlay Lite v2.1.9

Perbaikan utama Overlay Link:
- Ukuran frame dan skala isi browser source sekarang dipisah.
- Resize kanan-bawah ikut memperbesar/memperkecil konten source, bukan hanya kotaknya.
- Jika frame sudah mentok tepi layar, drag keluar tetap melanjutkan zoom konten sampai 4x.
- Default content scale Overlay Link dinaikkan ke ~1.75x agar alert provider tidak terlihat terlalu kecil.
- Background WebView tetap transparan.
- Gunakan link Browser Source/Overlay Link dari provider, bukan URL halaman pengaturan.

Kontrol lain tetap: MOVE + LOCK di dalam kotak, resize di kanan-bawah luar kotak, bubble controller per-overlay, notification master ON/OFF.
