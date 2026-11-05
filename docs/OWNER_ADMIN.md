# Menghapus akses admin — 1.2.2

OWNER dapat menghapus akses admin langsung dari aplikasi, tanpa backend tambahan:

1. Masuk memakai akun dengan peran **OWNER**.
2. Buka **Pengguna → Akses pengguna**.
3. Pada admin yang sudah tidak bekerja, tekan **Hapus admin**.
4. Periksa nama/email pada dialog, lalu konfirmasikan **Hapus admin**.

Akun tersebut hilang dari daftar aktif dan kehilangan izin katalog, media, serta perubahan data. Aplikasi yang masih terbuka mengamati perubahan izin dan kembali ke layar akses nonaktif. Login berikutnya tetap ditolak walaupun token lama, custom claim ADMIN, atau email grant masih ada. Catatan pencabutan dipertahankan sehingga akun tidak dapat mengajukan ulang atau memperoleh akses kembali tanpa keputusan pengelola.

Riwayat aktivitas menyimpan aksi **Hapus admin** beserta pelaku, target, waktu server, perubahan status dan perangkat. Akun OWNER dan akun sendiri dilindungi. Tombol memerlukan konfirmasi dan tidak dapat dikirim ulang selama penyimpanan berlangsung. Approval/penetapan role akun baru tetap melalui Firebase Console.

## Akses folder Drive

Pencabutan di aplikasi tidak mengubah sharing Google Drive. Pemilik perlu membuka empat folder perusahaan di Google Drive → **Bagikan**, memilih email mantan admin, lalu **Hapus akses**. Periksa juga akses yang diperoleh melalui grup atau berbagi langsung pada berkas tertentu. File yang dahulu diunggah mantan admin tetap mengikuti kepemilikan Google Drive; pemindahan kepemilikan memerlukan proses Drive yang sesuai.

## Catatan pengembang

Fitur mengelola **hak akses aplikasi**. Identitas Firebase Authentication tetap tersimpan. Penghapusan identitas Authentication secara permanen menggunakan Firebase Console atau Admin SDK di server tepercaya, bukan credential administratif di APK. [Dokumentasi Firebase](https://firebase.google.com/docs/auth/admin/manage-users#delete_a_user).

`NativeAdminWriter` menjalankan transaksi Firestore yang membaca ulang izin OWNER, profil target dan email grant. Transaksi hanya mengubah `status: revoked`, `active: false`, `removedBy`, `removedAt`, `updatedAt`, serta `removalRequestId`. Perubahan tersebut wajib disertai activity `removeAdmin` yang sesuai pada commit yang sama. Nama, email, role dan field profil lain dipertahankan. Retry atas pencabutan yang sudah selesai menjadi no-op.

Security Rules membatasi tindakan pada OWNER aktif dan menolak perubahan terhadap OWNER, UID sendiri, identitas email OWNER, atau profil yang berpotensi memiliki grant OWNER. Approval, pergantian role, penghapusan dokumen profil dan penulisan audit palsu tetap ditolak. Profil pencabutan tidak dihapus karena keputusan UID harus tetap mengalahkan email grant saat pemeriksaan akses.

Rules harus aktif sebelum menggunakan APK 1.2.2. Deployment dibatasi pada `firestore:rules`; tidak memerlukan Functions atau Blaze. Tes keamanan dijalankan pada emulator dengan project demo, bukan dengan menghapus admin produksi. Hasil aktual ada di [verifikasi](VERIFICATION.md).
