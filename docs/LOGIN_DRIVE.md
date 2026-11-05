# Login Google dan izin Drive — 1.2.1

Alur Drive ini dipertahankan pada 1.2.3; APK aktif menggunakan nama versi 1.2.3. OWNER juga dapat [menghapus akses admin](OWNER_ADMIN.md).

Android kembali mengakses Google Drive langsung dari perangkat. Ini mengikuti pilihan terbaru pemilik: setelah login Google, aplikasi meminta izin Drive untuk akun Google yang sama. Backend publik, Blaze, dan kredensial pemilik pada server tidak diperlukan untuk pengelolaan gambar Android.

Alurnya: pilih akun Google → Firebase memeriksa persetujuan akun → jika approved, aplikasi otomatis meminta izin Drive → Google menampilkan dialog bila izin belum pernah diberikan → aplikasi memeriksa akses ke keempat folder. Akun pending tetap menunggu persetujuan manual sebelum diminta mengakses Drive. Login Google dan izin Drive adalah dua panggilan SDK berurutan; Google menentukan dialog persetujuannya. Izin yang sudah diberikan digunakan kembali dan token diperbarui melalui Google Play services.

Jika izin dibatalkan, login aplikasi tetap berlaku. Pesan koneksi menyediakan retry, dan halaman **Akun & perangkat → Izinkan Drive** dapat melanjutkannya. Dialog tidak dibuka berulang otomatis setelah ditolak. Logout dan pergantian akun membuang token sesi lama; token hanya disimpan di RAM. Android tidak menyimpan client secret, refresh token atau private key.

## Konfigurasi pengembang

1. Pertahankan project Firebase `jayagunatehnikperkasa`, provider Google aktif, `app/google-services.json` existing, Web client override pada `gradle.properties`, serta safelist Firebase yang sudah diperbaiki. Detail konfigurasi identitas existing berada pada [catatan OAuth](GOOGLE_LOGIN_COMPANY_DRIVE.md#1-selaraskan-google-oauth-dengan-firebase). Jangan mengganti signing key atau membuat project baru.
2. Pertahankan client Android pada project OAuth `jayagunatehnikperkasa-512819` dengan package `com.jayaguna.admin` dan SHA-1 signing yang sesuai. Signing kit tetap digunakan lintas laptop; tidak ada pendaftaran SHA-1 setiap HP. OAuth release perlu cocok dengan sertifikat release, atau sertifikat app signing Play jika APK berasal dari Play. [Build lintas laptop](PORTABLE_BUILD.md).
3. Aktifkan Google Drive API pada project OAuth tersebut. Scope `https://www.googleapis.com/auth/drive` diperlukan untuk empat folder existing dan gambar lama; jangan menggantinya dengan `drive.file` tanpa migrasi akses berkas. Jika Audience masih **External → Testing**, tambahkan email pengguna yang akan mengizinkan Drive ke **Test users**. Peringatan aplikasi belum diverifikasi mengikuti pengaturan/verifikasi Google, bukan sharing folder.
4. Setujui akun aplikasi melalui `users/{uid}` dengan `status: approved`, atau email grant yang sesuai. Profil UID pending/rejected/inactive tetap mengalahkan grant email. Mekanisme dan rules approval 1.2.0 tetap digunakan; tidak ada kebutuhan deploy ulang rules untuk pemulihan Drive ini. [Persetujuan manual](GOOGLE_LOGIN_COMPANY_DRIVE.md#3-setujui-pengguna-melalui-console).
5. Pemilik `mtahakurniawan@gmail.com` membagikan **empat folder** berikut sebagai **Editor** ke email yang dipakai pengguna untuk login Google. Pertahankan akses umum **Dibatasi**.

| Folder | ID tetap |
| --- | --- |
| Produk | `1RN6CPWKQ2i7rpgvxE3EUG0ndyuEm3NUd` |
| Mitra | `1fEw1ct_IRe9LB0u5n24gzBEOVQ2ekom6` |
| Portofolio perumahan | `1kOqO3wgNmUCujrhb1tqY3kB_kG7jTEjx` |
| Portofolio perusahaan | `10Z9US8nxqTV5AkJrRrEURRsWNhOpvHvK` |

File baru tersimpan pada folder perusahaan, tetapi kepemilikan dan kuota mengikuti akun pengunggah. Editor folder belum tentu boleh memindahkan file milik pengguna lain ke Trash. Jika cleanup tidak diizinkan, katalog yang sudah tersimpan tetap berlaku dan cleanup dipertahankan untuk retry; aplikasi tidak menganggap 404 sebagai bukti file sudah terhapus.

## Build dan pemeriksaan

```sh
python3 scripts/build.py :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:assembleRelease :app:lintDebug --console=plain
```

APK `.artifacts/apk/Jayaguna-1.2.3-development.apk` memperbarui APK development dengan sertifikat yang sama. APK `.artifacts/apk/Jayaguna-1.2.3-release.apk` memakai sertifikat release existing. `backendBaseUrl` tidak diperlukan untuk Drive Android. Notifikasi email tetap ditunda sesuai pilihan pemilik; permohonan tersimpan di Firestore untuk persetujuan manual. Layanan website/operator existing dipertahankan terpisah.

Periksa login akun approved, dialog izin bila diminta, lalu status Drive dan gambar existing. Pengujian cloud yang menambah/mengganti/menghapus gambar tetap opt-in; build/unit/UI fixture tidak membuktikan konfigurasi OAuth atau akses folder nyata. Hasil aktual dicatat pada [verifikasi](VERIFICATION.md).

Referensi: [AuthorizationClient Android](https://developer.android.com/identity/authorization), [scope Drive](https://developers.google.com/workspace/drive/api/guides/api-specific-auth), [pre-generated file IDs](https://developers.google.com/workspace/drive/api/guides/create-file), [izin dan kapabilitas Drive](https://developers.google.com/workspace/drive/api/guides/manage-sharing).
