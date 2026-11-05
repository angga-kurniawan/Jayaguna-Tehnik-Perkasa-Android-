# Menambahkan banyak logo mitra — 1.2.3

**Mitra → Tambah mitra → Pilih logo** dapat memilih hingga delapan gambar sekaligus. Setiap logo menjadi satu mitra tersendiri, sesuai pilihan pemilik. Nama perusahaan diisi awal dari nama berkas dan dapat diubah sebelum disimpan. Preview memakai rasio asli logo, dan jumlah kolom menyesuaikan lebar layar serta ukuran teks.

Periksa nama setiap mitra. Bagian detail menyediakan teks alternatif, website opsional, urutan tampil, dan status aktif untuk masing-masing mitra. Tekan **Simpan** untuk mengunggah pilihan. PNG, JPEG dan WebP tetap dibatasi 5 MiB per logo. Editor **Edit** pada kartu mitra tetap mengubah satu mitra yang sudah tersimpan.

Jika satu unggahan gagal, mitra yang sudah dikonfirmasi tetap tersimpan dan dikeluarkan dari antrean. Coba lagi untuk melanjutkan pilihan yang tersisa. Logo dengan hasil penyimpanan yang belum pasti dikunci terhadap perubahan metadata agar retry tetap memakai identitas dan isi permintaan yang sama. Kesalahan berkas lokal pada percobaan pertama dapat diperbaiki dengan menghapus dan memilih ulang logo tersebut. Membatalkan antrean tidak menghapus mitra yang sudah tersimpan; periksa daftar setelah hasil jaringan yang belum pasti.

Pilihan, metadata, identitas mitra, request ID dan keadaan terkunci dipertahankan saat layar dibuat ulang. Pemulihan proses tidak mengunggah otomatis; pengguna memeriksa pilihan dan menekan Simpan kembali. Hak akses URI mengikuti izin Android terhadap berkas yang dipilih.

Implementasi memakai `PartnerBatchController` dan transaksi `savePartner` yang sudah ada, satu per logo secara berurutan. Schema `partners/publicPartners` tetap memakai satu `logo` per mitra. Folder Drive, aturan Firebase, audit dan renderer website tetap menggunakan jalur existing. Tidak memerlukan migrasi data atau deployment server/rules.

Pemilih gambar menggunakan [Android Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker) dengan izin hanya pada pilihan pengguna. Hasil pengujian aktual ada di [verifikasi](VERIFICATION.md).
