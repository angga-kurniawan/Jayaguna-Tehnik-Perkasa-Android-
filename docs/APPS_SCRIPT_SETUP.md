# Percobaan Apps Script — dibatalkan

Pengguna menolak jalur Apps Script. Dokumen ini bukan panduan aktivasi dan tidak memerlukan persetujuan OAuth tambahan.

- APK kembali memakai integrasi Node.js/Google Drive yang sudah ada; Apps Script bukan dependency atau tujuan koneksi aktif aplikasi.
- Tidak ada URL script yang dipublikasikan ke konfigurasi Firestore aplikasi.
- Kode pada `apps-script/` hanya referensi percobaan yang dibatalkan.
- Deployment percobaan sudah dihapus dan pemeriksaan ulang mengembalikan not-found. API pengelolaan script dikembalikan ke status awal DISABLED; credential pengelolaan lokal dihapus. Grant OAuth Google Drive asli tetap dipertahankan.
- Tidak ada upload HP melalui Apps Script yang dinyatakan berhasil.

Login langsung Firebase tanpa App Check tetap berlaku. API key Android yang benar-benar dipakai APK sudah diperiksa dan Application restrictions kini NONE dengan jenis API tetap dibatasi. Kegagalan Drive HP masih terkait alamat API lokal `10.0.2.2:8787`; upload HP mandiri belum dinyatakan berhasil.

Panduan integrasi existing tersedia pada [GOOGLE_DRIVE.md](GOOGLE_DRIVE.md); hasil pemeriksaan aktual dicatat pada [VERIFICATION.md](VERIFICATION.md).
