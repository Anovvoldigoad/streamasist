# StreamOverlayLite v2.2.1

Perbaikan utama:
- Floating Controller/bubble selalu dipromosikan menjadi window paling atas setelah overlay baru ditambahkan atau diaktifkan.
- Saat bubble bertabrakan dengan Text, Image/GIF, atau Overlay Link, bubble tetap terlihat dan tetap menerima touch lebih dulu.
- Image/GIF sekarang dapat diperbesar sampai sekitar 4x dimensi layar, tidak lagi mentok pada ukuran layar fisik.
- Resize Image/GIF dari handle kanan-bawah tetap menjaga aspect ratio.
- Posisi media tetap boleh melewati batas layar seperti versi sebelumnya.
- Overlay Link tetap memakai fixed browser canvas dari v2.2.0.

Catatan performa: decoder media tetap membatasi decode gambar/GIF besar agar RAM tidak melonjak. Layer tetap bisa dibesarkan jauh melebihi ukuran decode melalui scaling; sumber beresolusi rendah bisa terlihat lebih blur jika dibesarkan ekstrem.
