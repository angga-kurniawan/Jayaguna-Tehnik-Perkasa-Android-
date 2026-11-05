# Login Google, persetujuan akun, dan Drive perusahaan — 1.2.0

**Catatan historis 1.2.0. Panduan aktif adalah [login dan izin Drive 1.2.1](LOGIN_DRIVE.md).** Pemilik memilih izin Drive langsung setelah login Google. Android 1.2.1 tidak memakai backend pusat untuk gambar; bagian backend di bawah hanya untuk implementasi server yang tidak diaktifkan. Konfigurasi login Google dan persetujuan manual tetap berlaku.

APK memakai Credential Manager dan Firebase Google Authentication. Login email/password dan persetujuan Drive per pengguna telah dilepas. Produk, mitra, portofolio, proyeksi website, dan empat folder gambar tetap menggunakan schema/lokasi yang sama.

Alur akun: Google → Firebase → pemeriksaan izin dari server Firestore → masuk jika disetujui. Akun yang belum disetujui tetap berada di layar permohonan. Notifikasi perubahan izin memicu pemeriksaan server; cache lokal tidak dapat memberikan akses.

## 1. Selaraskan Google OAuth dengan Firebase

Project aplikasi tetap **jayagunatehnikperkasa**, bukan project OAuth Drive yang berbeda.

Provider Google terkonfirmasi aktif pada 5 November 2026. `app/google-services.json` sudah diperbarui dari Firebase; client Web bawaannya adalah `889896586147-161a2safndg3d82abuco7dact25sddvk.apps.googleusercontent.com`.

Pendaftaran SHA-1 development ditolak dengan HTTP 409 karena paket dan sertifikat yang sama sudah mempunyai client Android pada project Google **jayagunatehnikperkasa-512819** (nomor **179144038580**). Jangan menghapus client atau membuat keystore pengganti. Google mendukung mempertahankan client lama melalui **Safelist client IDs from external projects** pada provider Google Firebase.

Konfigurasi yang dipilih source ini menggunakan project OAuth existing tersebut:

1. Google Cloud Console → pilih **jayagunatehnikperkasa-512819** → Google Auth Platform → Clients. Pastikan client **Jayaguna Mac Debug**, tipe Android, memakai package `com.jayaguna.admin` dan SHA-1 development `23:73:7C:72:1A:8A:74:21:0B:BA:C9:59:9A:DF:ED:38:97:AB:BD:F5`. Client existing: `179144038580-nu8g5pc0qpchhl4pradgopspkev5dej1.apps.googleusercontent.com`.
2. Periksa client Android release pada project OAuth yang sama: package yang sama, SHA-1 `40:1A:51:26:BA:5D:00:68:EA:23:F9:8D:78:D5:BC:26:D8:D1:D0:84`. Jika belum ada, buat client Android untuk sertifikat release tersebut. Pendaftarannya belum terverifikasi pada sesi ini.
3. Firebase Console → project **jayagunatehnikperkasa** → Authentication → Sign-in method → Google → **Safelist client IDs from external projects**. Tambahkan client yang sesuai, termasuk Web existing `179144038580-p4ipmkgjfmdope6f7jtq98i0q1coiv0e.apps.googleusercontent.com`, lalu Save. Pemilik mengonfirmasi Web client tersebut sudah ditambahkan; login Google nyata pada APK development kemudian berhasil pada 5 November 2026. Periksa juga client Android development/release sesuai sertifikat yang digunakan; konfigurasi release belum diuji melalui login nyata.
4. `gradle.properties` sudah memilih client **Web** existing tersebut melalui `googleWebClientId`. Nilainya publik dan menghasilkan resource `google_web_client_id`; tidak memasukkan client secret ke Android. Pertahankan pasangan project/client ini saat memakai safelist. ID Android tidak boleh dipasang sebagai `googleWebClientId`. Jika operator kelak menyatukan semua client pada project Firebase, sesuaikan override dan bangun ulang APK.

Build memakai signing kit proyek yang sama di semua laptop. Registrasi sertifikat berlaku untuk APK, bukan setiap HP. Seluruh pengguna tidak perlu ditambahkan sebagai test user Drive hanya untuk login Google; login aplikasi meminta identitas dasar, sedangkan otorisasi Drive penuh dilakukan pemilik di server.

Provider email/password pada layanan Firebase/website tidak dinonaktifkan oleh perubahan Android ini. Jangan mengubah provider website yang masih digunakan. Profil, UID, custom claim lama, OWNER, dan katalog tidak dihapus atau dibuat ulang. Jika Google menghasilkan UID berbeda atau konflik provider pada akun lama, tinjau penautan akun melalui operator; jangan menghapus akun/profil lama untuk mencoba login.

Referensi: [Google + Firebase Android](https://firebase.google.com/docs/auth/android/google-signin), [Credential Manager](https://developer.android.com/identity/sign-in/credential-manager-siwg), [konflik SHA-1 dan safelist client eksternal](https://support.google.com/firebase/answer/6401008?hl=en), [pengecualian scope identitas dasar untuk Testing](https://developers.google.com/identity/protocols/oauth2/production-readiness/overview).

## 2. Publikasikan aturan akses yang baru

Source `firestore.rules` menambahkan aturan untuk izin akun dan permohonan. Aturan ini sudah dipublikasikan ke project existing pada 5 November 2026 setelah 50 tes emulator lulus, termasuk kompatibilitas akun password legacy dan katalog website. Pembacaan ulang rules produksi mengonfirmasi isinya cocok dengan source. Untuk deployment ulang versi source yang telah ditinjau, gunakan target terbatas:

```sh
firebase deploy --project jayagunatehnikperkasa --only firestore:rules
```

Index dan schema produk/mitra tidak diubah. `storage.rules` memiliki kebijakan izin yang sama untuk gambar Storage legacy; deploy terpisah hanya bila bucket existing memang dipakai. Tidak perlu membuat bucket atau men-deploy Cloud Functions untuk alur baru ini. Deployment sesi ini hanya `firestore:rules`, tanpa mengubah profil/role/approval pengguna, katalog, Storage atau Functions. Salinan rules sebelumnya disimpan privat pada `.artifacts/google-login-live-firestore.rules`.

## 3. Setujui pengguna melalui Console

**Mode yang dipilih pemilik saat ini: persetujuan manual tanpa notifikasi email.** Permohonan tetap disimpan di Firestore. Ketika URL backend belum dikonfigurasi, aplikasi melewati panggilan email dan menampilkan bahwa pengelola dapat menyetujui akun melalui Firebase Console. Status pending tidak berarti email sudah dikirim atau pengelola sudah mulai meninjau.

Saat pengguna menekan **Ajukan Permohonan Akses**, satu transaksi membuat:

- `accessRequests/{uid}`: `uid`, nama Google, email terverifikasi, `status: pending`, dan `requestedAt` server.
- `users/{uid}` dengan payload pending yang sama, hanya jika profil belum tersedia.

Client tidak dapat memperbarui profil, menetapkan role, atau menyetujui dirinya sendiri. Permohonan pending tidak dapat dibuat berulang.

Di Firebase Console → Firestore → `users` → UID pengguna, ubah hanya **status** dari `pending` menjadi **approved**. Tanpa field role, akses menjadi ADMIN; tanpa field active, akun approved dianggap aktif. Role OWNER hanya ditetapkan operator secara sengaja pada profil tepercaya. Jika profil memiliki `active: false`, status approved tidak mengabaikan penonaktifan tersebut.

Permohonan yang ditemukan pada 5 November 2026: `users/ONDfaMV9CucxxhE9NB1HUsAhDow2`, email `nur.hayani0310@gmail.com`, status `pending`. Pemilik dapat membuka [dokumen akun tersebut](https://console.firebase.google.com/project/jayagunatehnikperkasa/firestore/databases/-default-/data/~2Fusers~2FONDfaMV9CucxxhE9NB1HUsAhDow2) dan menyimpan perubahan status menjadi approved. Pemeriksaan agen hanya membaca dokumen; approval tidak diubah oleh agen.

Pengguna yang aplikasinya masih terbuka akan diperiksa ulang otomatis. Logout, pergantian identitas, perubahan izin, serta hasil jaringan lama tidak boleh membuka kembali sesi yang sudah dicabut.

Untuk memberikan izin sebelum pengguna mengajukan permohonan, operator dapat membuat `accessGrants/{email-google-huruf-kecil}` dengan `status: approved` dan optional `role: ADMIN`. Email harus cocok dengan Google ID terverifikasi. Keputusan UID lebih tinggi prioritasnya: jika `users/{uid}` sudah pending/rejected/inactive, setujui atau perbaiki profil UID tersebut; email grant tidak melewati penolakan UID. Akses lama yang sudah sah tetap dipertahankan selama migrasi bila belum ada keputusan status baru.

Untuk mencabut akses, ubah status menjadi `rejected` atau active menjadi false. Field `revokedBeforeSeconds` yang ada tetap menjadi batas waktu sesi; jangan mengisinya dengan string/null.

## 4. Jalankan backend perusahaan

Untuk langkah pengembang yang berurutan beserta contoh konfigurasi workstation dan hosting terpisah, gunakan [Konfigurasi Drive perusahaan untuk pengembang](SETUP_DRIVE_DEVELOPER.md).

Entrypoint baru adalah `functions/src/central-server.ts`, dengan perintah:

```sh
npm --prefix functions ci
npm --prefix functions run build
npm --prefix functions run start:central
```

Pada hosting, gunakan Node 22, HOST `0.0.0.0`, PORT yang disediakan host, dan origin HTTPS. Kredensial dimasukkan melalui secret storage host. Local operator dapat memuat file environment privat secara eksplisit; perintah produksi tidak otomatis membaca `.env` workstation lama.

Untuk uji server lokal setelah build, jalankan `node --env-file=.env lib/central-server.js` dari folder `functions`. Pada hosting yang memasok environment, gunakan `npm run start:central`. Jangan menyalin `GOOGLE_APPLICATION_CREDENTIALS` workstation yang tidak ada pada host atau callback localhost ke environment produksi; bila memakai JSON secret, cukup isi `FIREBASE_SERVICE_ACCOUNT_JSON` dan biarkan `GOOGLE_OAUTH_REDIRECT_URI` host memakai default origin HTTPS.

Endpoint hanya menerima Firebase ID token. `/api/access/request` melayani identitas Google yang belum disetujui, sedangkan endpoint `/api/drive/*` mensyaratkan izin akun approved. Verifikasi token memeriksa pencabutan sesi. Drive hanya dapat diakses melalui UUID `mediaAssets`, bukan URL/file ID arbitrer dari client. `/healthz` menunjukkan proses berjalan; bukan bukti seluruh credential/provider siap.

Untuk membuat staging Render tanpa menyertakan `.env`, credential, signing key, atau cache:

```sh
python3 scripts/prepare-admin-deploy.py --target render
```

Gunakan hanya folder staging tersebut untuk repository deployment. Blueprint memilih Free dan start:central. Jangan mengunggah arsip portable privat Android ke hosting. Firebase tetap dapat memakai Spark; hosting Node eksternal tidak memerlukan deployment Cloud Functions. Cloudflare Workers memerlukan adapter runtime tersendiri dan belum menjadi target deployment paket Node ini.

Isi server secret/config berikut melalui operator:

| Nilai | Fungsi |
| --- | --- |
| FIREBASE_PROJECT_ID | jayagunatehnikperkasa |
| FIREBASE_SERVICE_ACCOUNT_JSON atau attached IAM | Firebase server credential; untuk profil central cukup hak Auth user-read dan Firestore yang diperlukan, misalnya Firebase Authentication Viewer serta Cloud Datastore User |
| GOOGLE_OAUTH_CLIENT_ID / GOOGLE_OAUTH_CLIENT_SECRET | OAuth Web perusahaan yang sudah digunakan untuk Drive |
| GOOGLE_DRIVE_ACCOUNT_EMAIL | mtahakurniawan@gmail.com |
| GOOGLE_DRIVE_PRODUCTS_FOLDER_ID / GOOGLE_DRIVE_PARTNERS_FOLDER_ID | Dua ID produk/mitra existing yang tercantum di bawah; wajib diisi saat konfigurasi host manual |
| JTP_ADMIN_ATTACHED_ADC | true hanya bila memakai attached IAM pada runtime tepercaya; tidak diperlukan bila JSON secret server sudah diisi |
| DRIVE_TOKEN_ENCRYPTION_KEY | Kunci yang sama dengan encrypted grant existing; jangan diganti bila grant masih akan dipakai |
| BACKEND_PUBLIC_URL atau RENDER_EXTERNAL_URL | Origin server HTTPS |
| RESEND_API_KEY / ACCESS_REQUEST_FROM | Notifikasi email server; pengirim harus diizinkan akun/provider |

Folder produk `1RN6CPWKQ2i7rpgvxE3EUG0ndyuEm3NUd`, mitra `1fEw1ct_IRe9LB0u5n24gzBEOVQ2ekom6`, perumahan `1kOqO3wgNmUCujrhb1tqY3kB_kG7jTEjx`, dan perusahaan `10Z9US8nxqTV5AkJrRrEURRsWNhOpvHvK` tetap. Profil central menolak folder/akun pengganti. Jangan menyalin path credential Windows yang hilang ke hosting.

Setelah origin aktif dan diverifikasi, simpan URL publik pada `gradle.properties`, misalnya `backendBaseUrl=https://HOST-AKTUAL`, lalu bangun APK. Jangan menyimpan token atau key dalam file Gradle. Seluruh HP menggunakan endpoint yang sama.

## 5. Otorisasi Drive oleh pemilik, sekali di server

Backend memakai mekanisme existing: OAuth Web pemilik → refresh token terenkripsi pada `backendSecrets/googleDrive` → server mengakses Drive. Refresh token, client secret dan kunci enkripsi tidak pernah dikirim ke Android.

Jika grant existing cocok, gunakan kembali. Jika belum terhubung, operator memakai helper `connect:company-drive` di workstation tepercaya dengan credential server dan file `.env` privat yang benar. Helper menggunakan callback loopback yang sudah terdaftar pada OAuth Web client: `http://localhost:8787/api/drive/oauth/callback` (atau varian 127.0.0.1 yang terdaftar). File environment operator lokal harus memakai `NODE_ENV=development`, `BACKEND_PUBLIC_URL=http://localhost:8787` serta `GOOGLE_OAUTH_REDIRECT_URI` callback lokal yang sama. Port 8787 harus tersedia; tutup proses backend lokal yang menggunakan port tersebut selama helper berjalan. Konfigurasi hosting produksi tidak perlu diubah. Profil UID pemilik harus `status: approved`, `role: OWNER` dan tidak dinonaktifkan; profil legacy tanpa status tetap memerlukan active:true dan claim OWNER existing. Helper tidak membuat atau menyetujui akun. Jalankan dari folder functions:

```sh
npm run build
npm run connect:company-drive -- --open
```

Tool membuka persetujuan untuk pemilik Drive perusahaan; refresh token disimpan melalui mekanisme enkripsi existing. Drive API pada project OAuth Web harus aktif, redirect terdaftar, dan pemilik diizinkan sebagai test user jika aplikasi OAuth masih Testing. Lihat [prasyarat OAuth Drive existing](GOOGLE_DRIVE.md). Grant pada aplikasi Testing dapat kedaluwarsa sesuai ketentuan Google; koneksi pusat mungkin perlu diotorisasi ulang oleh pemilik, sementara pengguna aplikasi tetap tidak menjalani consent Drive. Persetujuan ini milik pemilik Drive untuk konfigurasi server, bukan setiap pengguna aplikasi.

Jangan menjalankan ulang bootstrap OWNER, membuat keystore baru, mengubah folder, atau menghasilkan kunci enkripsi pengganti agar koneksi terlihat berhasil. Akun server perlu izin baca/unggah/hapus yang sesuai untuk gambar lama; hak penghapusan file tetap mengikuti Google Drive. Kegagalan cleanup disimpan sebagai operasi yang bisa diulang, bukan diklaim sudah terhapus.

## 6. Notifikasi email

Layanan email belum diaktifkan, sesuai pilihan pemilik untuk memakai persetujuan manual. Pemeriksaan permohonan nyata menemukan satu request pending dan belum ada catatan `accessRequestMail`. APK debug sebelumnya masih mewarisi alamat server lokal lama `10.0.2.2:8787`; server publik dan konfigurasi pengiriman belum tersedia. Properti lokal lama tersebut sudah dihapus agar debug dan release menggunakan mode manual selama endpoint publik belum dikonfigurasi. Karena itu belum ada email otomatis yang dikirim ke pengelola. Pengajuan yang tersimpan tetap dapat disetujui langsung di Console tanpa menunggu email.

API mengirim ke penerima tetap **mtahakurniawan@gmail.com**, berisi nama, email, UID, serta waktu permohonan dalam WIB. Tidak menerima penerima bebas dari APK. `accessRequestMail/{uid}` adalah catatan server-only untuk status kirim dan lease; provider idempotency key berasal dari UID dan waktu permohonan. Tidak ada password email di aplikasi.

Jika provider/config/koneksi gagal, permohonan Firestore tetap tersimpan, dan UI menyatakan email belum terkirim. API aman untuk retry atas permohonan yang sama; tidak membuat permohonan baru. Modul ini belum memakai antrean/background trigger, sehingga pending yang gagal perlu retry notifikasi setelah konfigurasi diperbaiki. Jangan menganggap status pending sebagai bukti email sudah diterima. [Resend idempotency](https://resend.com/docs/dashboard/emails/idempotency-keys).

## 7. Diagnosis kegagalan login Google

Pada pemeriksaan 5 November 2026, log `GoogleApiManager` disertai `Unknown calling package name 'com.google.android.gms'` muncul ketika UI gagal login. Diagnostik pada tahap pertukaran identitas Firebase mengonfirmasi **ERROR_INVALID_CREDENTIAL**, alasan **oauth-audience**: Web client eksternal belum diterima Firebase. Setelah pemilik menyimpan safelist, login Google berhasil pada emulator yang sama. Tidak perlu mengganti signing key atau membersihkan data aplikasi untuk perbaikan tersebut.

Masalah berikutnya adalah aturan Firestore lama yang belum mempunyai `accessGrants` dan `accessRequests`. Setelah deployment rules terbatas dan aplikasi dibuka ulang dengan sesi yang sama, layar **Ajukan Permohonan Akses** tampil untuk akun yang belum disetujui. Itu adalah alur izin yang diharapkan. Pemilik tetap memberikan approval manual; agen tidak mengubah status akun menjadi approved.

Build debug mencatat tag `GoogleLogin` dengan tahap, kode Firebase yang disanitasi, kategori alasan tetap, serta nama kelas exception. SDK message, token, credential, dan data akun tidak dicetak. UI menampilkan kode kegagalan yang dapat disampaikan kepada pengelola.

## Status pengujian dan konfigurasi

Build debug/release, unit test Android/backend, fixture UI dan rules emulator dijalankan; hasil aktual ada di [VERIFICATION.md](VERIFICATION.md). Login Google nyata pada development dan pemeriksaan akun belum disetujui berhasil diverifikasi pada emulator. Rules Firestore sudah aktif; persetujuan akun tetap manual. Client Android release belum diverifikasi melalui login nyata. Backend publik dan layanan email belum dideploy/dikonfigurasi, serta grant Drive perusahaan belum diverifikasi melalui backend baru. Email sungguhan dan CRUD Drive end-to-end masih menunggu konfigurasi backend tersebut.
