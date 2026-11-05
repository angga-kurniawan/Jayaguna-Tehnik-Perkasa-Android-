# Google Drive langsung dari Android

**Panduan aktif 1.2.1:** gunakan [login Google dan izin Drive setelah login](LOGIN_DRIVE.md). Referensi penyimpanan/cache/portofolio di bawah tetap berguna; langkah login password dan pemilihan akun Drive terpisah merupakan catatan versi lama.


Build 1.1.3 memakai sertifikat development/release proyek yang tetap agar identitas aplikasi tidak berubah saat berganti laptop. APK dan paket privat untuk pengembang dijelaskan pada [panduan build lintas laptop](PORTABLE_BUILD.md). Hasil pengujian aktual dicatat pada [verifikasi](VERIFICATION.md).

Versi 1.1.2 memakai Firebase Authentication/Firestore dan Google Drive API v3 langsung dari HP. Setiap admin dapat memilih akun Google sendiri yang diberi akses Editor ke folder perusahaan. Upload, baca gambar, simpan/edit/arsip produk, mitra, dan portofolio tidak memanggil `10.0.2.2`, Node lokal, atau Apps Script. Slide horizontal antar-menu, cache, preview serta ikon/splash perusahaan tetap dipertahankan. Lihat [panduan antarmuka](NAVIGATION_STATE.md#animasi-dan-logo-rilis-111) dan hasil aktual pada [verifikasi](VERIFICATION.md).

Login Firebase ADMIN/OWNER tetap memakai akun masing-masing. Folder perusahaan tetap dimiliki **`mtahakurniawan@gmail.com`**, sedangkan admin mengotorisasi akun Google mereka sendiri pada HP masing-masing. Pemilik tidak perlu menambahkan akun pribadinya ke HP admin. File baru berada di folder perusahaan, tetapi **kepemilikan file dan pemakaian kuota mengikuti akun pengunggah**. Aplikasi tidak menyimpan kata sandi, client secret, atau refresh token; access token hanya berada di RAM dan diperoleh ulang melalui Google Play services. [Kepemilikan file Drive](https://developers.google.com/workspace/drive/api/guides/transfer-file).

## Membagikan folder ke admin

Pemilik membuka setiap folder berikut di Google Drive, memilih **Bagikan**, memasukkan email Google admin, memberi peran **Editor**, lalu mengirim undangan:

| Folder | Tautan |
|---|---|
| Produk-AC | [Buka folder](https://drive.google.com/drive/folders/1RN6CPWKQ2i7rpgvxE3EUG0ndyuEm3NUd) |
| Logo-Mitra | [Buka folder](https://drive.google.com/drive/folders/1fEw1ct_IRe9LB0u5n24gzBEOVQ2ekom6) |
| Portofolio-Perumahan | [Buka folder](https://drive.google.com/drive/folders/1kOqO3wgNmUCujrhb1tqY3kB_kG7jTEjx) |
| Portofolio-Perusahaan | [Buka folder](https://drive.google.com/drive/folders/10Z9US8nxqTV5AkJrRrEURRsWNhOpvHvK) |

Gunakan **Akses umum: Dibatasi**. Bagikan ke email admin secara langsung; jangan mengubah folder menjadi publik. Aplikasi memeriksa akses Editor untuk keempat folder sebelum menampilkan status terhubung. Ulangi pemberian akses untuk setiap admin. [Panduan berbagi folder Google](https://support.google.com/drive/answer/7166529?hl=id).

Akun Firebase admin juga harus sudah dibuat, aktif, dan memiliki claim/profil ADMIN yang sesuai. Undangan folder Drive tidak membuat akun Firebase atau memberikan role aplikasi. Akun Google yang dipilih untuk Drive harus sama dengan email penerima undangan folder; akun tersebut dapat berbeda dari email login Firebase.

## Memakai APK

1. Pasang `.artifacts/apk/Jayaguna-1.1.2.apk` sebagai pembaruan; lihat hasil build aktual dan batas pengujian pada [VERIFICATION.md](VERIFICATION.md).
2. Masuk menggunakan akun Firebase OWNER/ADMIN yang sudah aktif.
3. Buka **Akun & perangkat → Pilih akun Google**, pilih akun admin yang sudah menerima akses Editor, lalu selesaikan persetujuan Google. Akun dapat ditambahkan melalui Pengaturan HP → Akun → Tambah akun → Google jika belum tersedia pada HP.
4. Pastikan status menampilkan **Terhubung pada HP ini:** diikuti email Google admin yang dipilih. Gunakan **Ganti akun Google** jika salah memilih akun.
5. Tambahkan foto pada produk draft, logo mitra, atau menu **Portofolio**, lalu simpan. Buka kembali data untuk memeriksa gambar tersimpan.

Google tetap menentukan persetujuan OAuth. Scope `drive` diperlukan untuk folder yang sudah ada dan gambar lama; scope ini dapat memunculkan peringatan aplikasi belum diverifikasi. Menghapus Application restrictions API key tidak menghapus persetujuan atau proses verifikasi OAuth Google. [Otorisasi Android](https://developer.android.com/identity/authorization), [scope Drive](https://developers.google.com/workspace/drive/api/guides/api-specific-auth).

Jika project OAuth masih berstatus **External → Testing**, pengelola perlu menambahkan email Google setiap admin ke **Google Auth Platform → Audience → Test users** pada project OAuth `jayagunatehnikperkasa-512819`. Ini berbeda dari akun Firebase dan undangan folder. Tambahkan hanya jika status Testing masih digunakan; tidak perlu mendaftarkan HP atau mengganti SHA-1 untuk setiap admin.

### Folder sudah dibagikan, tetapi persetujuan Google gagal

Akses **Editor** pada folder memberi izin kepada akun Google. Persetujuan OAuth memberi izin kepada aplikasi untuk memakai akun tersebut. Pesan persetujuan gagal dapat muncul sebelum aplikasi memeriksa folder; `firebase.json` mengatur layanan Firebase, bukan client OAuth Android.

Pemeriksaan pada **4 November 2026** menemukan APK yang terpasang di emulator memakai sertifikat debug Mac dengan SHA-1 `23:73:7C:72:1A:8A:74:21:0B:BA:C9:59:9A:DF:ED:38:97:AB:BD:F5`, berbeda dari SHA-1 debug sebelumnya pada tabel di bawah. Perbedaan sertifikat sudah terverifikasi dari APK; keberadaan client untuk sertifikat Mac di Google Cloud **belum diperiksa** karena Console tidak diakses.

1. Buka [Google Auth Platform → Clients](https://console.cloud.google.com/auth/clients?project=jayagunatehnikperkasa-512819) pada project OAuth `jayagunatehnikperkasa-512819`.
2. Periksa client Android untuk package `com.jayaguna.admin` dan SHA-1 Mac tersebut. Jika belum ada, buat **client Android tambahan** dengan pasangan package dan SHA-1 itu. Pertahankan client sertifikat sebelumnya agar APK lama tetap dapat dipakai. [Panduan konfigurasi OAuth Android](https://developer.android.com/identity/authorization#set-up-your-google-cloud-console-project).
3. Jika Audience masih **External → Testing**, tambahkan email Google yang dipilih dalam aplikasi ke [Audience → Test users](https://console.cloud.google.com/auth/audience?project=jayagunatehnikperkasa-512819). Gunakan akun penerima akses Editor ke keempat folder.
4. Buka kembali **Akun & perangkat → Pilih akun Google**, pilih akun tersebut, lalu selesaikan persetujuan Drive. Periksa sampai status menampilkan email yang terhubung.

## Konfigurasi yang dipakai

| Pengaturan | Nilai |
|---|---|
| Firebase | `jayagunatehnikperkasa` |
| Package Android | `com.jayaguna.admin` |
| OAuth Android existing | Project `jayagunatehnikperkasa-512819` |
| SHA-1 debug sebelumnya | `23:56:25:73:4F:79:EB:88:CE:CF:62:74:01:DA:5F:AF:66:B3:CF:26` |
| SHA-1 debug Mac, APK emulator 4 November 2026 | `23:73:7C:72:1A:8A:74:21:0B:BA:C9:59:9A:DF:ED:38:97:AB:BD:F5` |
| Folder Produk-AC | `1RN6CPWKQ2i7rpgvxE3EUG0ndyuEm3NUd` |
| Folder Logo-Mitra | `1fEw1ct_IRe9LB0u5n24gzBEOVQ2ekom6` |
| Folder Portofolio-Perumahan | `1kOqO3wgNmUCujrhb1tqY3kB_kG7jTEjx` |
| Folder Portofolio-Perusahaan | `10Z9US8nxqTV5AkJrRrEURRsWNhOpvHvK` |

Folder tetap privat, dimiliki akun perusahaan, dan dibagikan sebagai Editor ke akun Google admin. File gambar tidak diwajibkan dimiliki pemilik folder; akses file, folder tujuan, format, ukuran dan checksum tetap diperiksa. Tidak dibuat folder pengganti. Sertifikat APK dan package merupakan identitas aplikasi yang sama untuk seluruh HP; bukan registrasi setiap perangkat. Release yang ditandatangani dengan key berbeda memerlukan OAuth Android untuk package dan SHA-1 release tersebut.

Jika Google tidak menyertakan rincian izin pada metadata akun Editor, aplikasi mencoba membaca daftar izin secara lengkap. Pemeriksaan ditolak jika daftar izin tidak dapat diperoleh; izin yang tidak diketahui tidak dianggap privat. Aplikasi tidak mengubah izin berbagi folder atau file secara otomatis.

## Cache gambar pada HP

`MediaRepository` menyimpan byte gambar yang sudah diunduh pada RAM maksimal **16 MiB** dan direktori cache privat aplikasi maksimal **64 MiB**. Salinan disk memakai checksum dan penulisan sementara sebelum diganti atomik; salinan yang rusak dibuang dan diunduh ulang. Permintaan bersamaan untuk gambar yang sama berbagi satu pekerjaan unduh. Saat cache cocok, gambar tidak membaca registry Firestore atau Google Drive lagi.

Satu `ImageLoader` Coil dipakai bersama oleh halaman katalog dan editor. Cache bitmap dibatasi **20% heap, maksimal 48 MiB**. Key mencakup sesi pengguna dan path gambar yang tetap. Bitmap yang masih tersedia langsung menjadi gambar/placeholder saat halaman dikomposisikan kembali, sehingga perpindahan tab tidak memulai spinner dari keadaan kosong. Jika RAM sudah dieviksikan, byte disk dapat dibaca dan didekode lagi tanpa jaringan.

Cache dipisahkan berdasarkan UID/sesi Firebase. Logout atau perubahan akun membersihkan cache byte dan bitmap; hasil unduhan/decode dari sesi lama ditolak. Cache menyimpan gambar beserta metadata integritas, bukan password atau token OAuth. Salinan disk akun yang sama dapat digunakan sesudah proses dibuat ulang jika sesi tetap berlaku. Pemeriksaan akses akun aplikasi tetap berjalan seperti sebelumnya.

Tombol **Pilih/Ganti akun Google** juga membersihkan cache dan membatalkan hasil gambar dari sesi sebelumnya sebelum membuka pilihan akun. Email Google yang telah diverifikasi disimpan per pengguna Firebase agar pembaruan token tetap memakai akun yang dipilih; kata sandi dan token Google tidak disimpan pada preferensi aplikasi. Perpindahan halaman biasa tetap memakai cache yang sama.

Penggantian gambar menghasilkan path `drive/{UUID}` baru, sehingga gambar pengganti tidak memakai bitmap lama. Setelah penghapusan file lama dikonfirmasi, cache path terkait diinvalidasi. Tombol **Muat ulang gambar** juga menginvalidasi salinan terkait sebelum mencoba kembali. Navigasi biasa tidak membersihkan cache.

Cache dibatasi ukurannya dan dapat dibersihkan oleh Android, pengguna, atau kebijakan eviksi. Gambar pertama kali, gambar baru, atau gambar yang tidak lagi tersimpan tetap memerlukan unduhan dan loading. Cache bukan jaminan penyimpanan offline permanen; data daftar tetap mengikuti mekanisme ViewModel/refresh yang dijelaskan pada [panduan state](NAVIGATION_STATE.md).

## Preview layar penuh

Tekan gambar produk, logo mitra, atau foto portofolio untuk membukanya pada preview layar penuh. Foto lokal yang baru dipilih pada formulir atau daftar pilihan portofolio juga dapat ditekan sebelum diunggah. Cubit dua jari untuk zoom, geser saat diperbesar, ketuk dua kali untuk mengubah zoom, atau gunakan tombol zoom dan reset. Tombol **Tutup** maupun Back Android menutup preview dan kembali ke halaman semula.

Preview memakai `StorageImage` dan cache bersama yang sama dengan thumbnail. Membuka preview tidak memaksa unduhan ulang; jika perlu ukuran decode lebih besar, bitmap cache dapat tetap menjadi placeholder dan byte cache digunakan lagi. Preview hanya membaca gambar dan tidak mengupload, mengganti, atau menghapus file maupun metadata.

## Galeri portofolio

Menu **Portofolio** berisi kategori **Perumahan** dan **Perusahaan**. Pilih hingga delapan gambar melalui Photo Picker, periksa preview, lalu tekan **Simpan**. Setiap foto menjadi satu entri tanpa formulir nama atau harga. Progres ditampilkan selama unggah; gambar yang sudah berhasil disimpan dikeluarkan dari pilihan agar retry hanya memproses sisanya. Kartu galeri menyediakan **Ganti** dan **Hapus** dengan konfirmasi.

Kategori ditetapkan saat penambahan dan tidak dapat dipindahkan saat edit. Perumahan menggunakan folder `Portofolio-Perumahan`; Perusahaan menggunakan folder `Portofolio-Perusahaan`. Penggantian mengunggah foto baru dahulu, menyimpan metadata, lalu mencoba membersihkan foto lama. Penghapusan mengarsipkan entri dan mengosongkan referensi gambar sebelum mencoba memindahkan file ke Trash, sesuai izin akun Google yang terhubung.

Galeri membaca metadata Firestore yang dicatat saat gambar disimpan melalui aplikasi:

| Koleksi | Field |
|---|---|
| `portfolioImages/{UUID}` | `id`, `category` (`residential`/`corporate`), `image` (metadata `Media`), `archived`, `version`, `createdAt`, `createdBy`, `updatedAt`, `updatedBy`, `lastRequestId`. Hanya staff aktif. |
| `publicPortfolioImages/{UUID}` | `id`, `category`, `image`, `archived:false`, `createdAt`, `updatedAt`, `mediaPaths`. Proyeksi tanpa identitas pengguna; dihapus ketika entri diarsipkan. |

`image` memuat `storagePath`, `provider`, `driveFileId`, `fileName`, `mimeType`, dan `sizeBytes`. Timestamp menggunakan waktu server Firestore. Proyeksi publik hanya membuka metadata; file Drive tetap privat. Penyajian foto bagi pengunjung website anonim memerlukan jalur pembaca gambar terpisah yang sudah dijelaskan di akhir panduan ini.

## Penyimpanan, retry, dan keamanan

Drive File ID direservasi dan dicatat pada `mediaAssets`/`driveUploadRequests` sebelum upload. Retry memakai ID yang sama, memverifikasi ukuran, format, Google MD5 dan SHA-256 agar tidak membuat file ganda. PNG/JPEG/WebP maksimal 5 MiB dan 40 megapiksel; gambar didekode untuk validasi. Bytes asli dipertahankan tanpa normalisasi atau penghapusan EXIF. Gambar tidak disimpan sebagai Base64 di Firestore.

Produk/mitra/portofolio, proyeksi publik, snapshot aktivitas dan receipt ditulis atomik. Firestore Rules membatasi role aktif, kecocokan claim/profil, sesi dicabut, field yang boleh ditulis, versi, pembuat, waktu server dan isi snapshot. Referensi `drive/{mediaUUID}` lama dipertahankan; file ID sebenarnya dibaca dari registry. Dukungan delapan gambar dan 50 spesifikasi produk tetap ada.

Attachment/detachment media menggunakan transaksi kecil sesudah commit katalog agar tidak melewati batas evaluasi rules. File lama baru dipindahkan ke Trash setelah metadata penggantian berhasil dan dokumen tidak mereferensikannya lagi; referensi pada dokumen arsip juga dihitung. Kegagalan cleanup mempertahankan metadata yang bisa dicoba ulang dan tidak membatalkan commit katalog yang sudah dikonfirmasi.

Peran Editor pada folder My Drive tidak otomatis memberikan hak memindahkan file milik orang lain ke Trash. Aplikasi memeriksa kapabilitas file; jika akun yang sedang terhubung tidak boleh menghapusnya, perubahan katalog tetap tersimpan dan cleanup lama tetap tertunda. Pemilik file dapat membersihkannya melalui Google Drive setelah memastikan gambar tidak dipakai lagi. Status arsip/hapus katalog berbeda dari penghapusan fisik file Drive; aplikasi tidak menghapus referensi registry seolah-olah file sudah terhapus jika Google belum mengonfirmasinya.

Audit native merupakan data dari client yang **dibatasi rules server**, termasuk identitas Firebase, timestamp, snapshot dan perubahan status atomik. Rules tidak dapat membuktikan sendiri bahwa panggilan Google Drive benar-benar terjadi, atau bahwa model/nama perangkat yang dilaporkan benar. Ini berbeda dari audit yang dibuat backend. Token Google dan credential backend tidak dapat dibaca melalui Firestore client.

## Menjalankan dan menguji

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:compileReleaseKotlin
firebase emulators:exec --project demo-jayaguna --only auth,firestore,storage "npm.cmd --prefix functions run test:emulator"
```

`NativeDriveCloudTest` adalah tes instrumentasi dengan opt-in `nativeDriveLive=true`. Tes existing memakai sesi OWNER dan akun Google pemilik pada emulator, membuat gambar/katalog teknis sementara, lalu memeriksa upload, metadata, baca ulang, retry, penggantian dan cleanup. Kelulusannya tidak membuktikan alur akun admin lain sudah diuji. Uji multiakun perlu akun Google admin nyata yang telah diundang ke keempat folder dan, bila diperlukan, terdaftar sebagai test user OAuth. Hasil aktual dicatat di [VERIFICATION.md](VERIFICATION.md).

`NativePortfolioCloudTest` memakai opt-in `nativePortfolioLive=true` untuk pemeriksaan dua kategori portofolio pada layanan asli. Laporan ID sementara ditulis pada penyimpanan privat aplikasi (`native-portfolio-live-check.json`); operator menjalankan cleanup terbatas `functions/scripts/cleanup-portfolio-test.cjs --report PATH_LAPORAN` dari folder `functions` dengan konfigurasi server lokal, setelah mengambil laporan tersebut. Script memeriksa project, pembuat, kategori, ukuran/MIME gambar teknis dan folder sebelum membersihkan data uji; tidak menjadi jalur upload aplikasi.

Pengelolaan identitas Firebase/custom claim ADMIN masih memakai jalur operator/API existing; fitur native ini tidak menaruh kewenangan Firebase Admin pada APK. Website tetap membaca proyeksi aktif dari Firestore. Penyajian gambar privat untuk pengunjung website anonim belum digantikan oleh jalur native, dan perlu integrasi pembaca gambar terpisah yang diuji; file/folder tidak dibuka publik secara otomatis.
