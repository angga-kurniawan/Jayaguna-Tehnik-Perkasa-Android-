# Menjalankan aplikasi dengan Firebase asli

**Panduan aktif 1.2.1:** gunakan [login Google dan izin Drive setelah login](LOGIN_DRIVE.md). Konfigurasi login password/claim wajib dan backend di bawah merupakan catatan jalur lama; persetujuan akun aplikasi kini mengikuti aturan Google/approval yang sudah diterapkan.


**Versi 1.1.2:** lihat [panduan Drive native](NATIVE_DRIVE.md) dan [navigasi/state](NAVIGATION_STATE.md). Setiap admin memakai akun Google sendiri yang mendapat akses **Editor** ke keempat folder perusahaan; langkah pembagiannya ada pada [panduan berbagi folder](NATIVE_DRIVE.md#membagikan-folder-ke-admin). File baru dimiliki dan memakai kuota akun pengunggah, sementara folder tetap milik perusahaan. Upload, baca gambar dan simpan katalog menggunakan Google Drive/Firestore langsung dari HP. Slide menu bawah, cache gambar, preview dengan zoom serta logo perusahaan pada ikon/splash tetap dipertahankan. Hasil aktual dicatat pada [verifikasi](VERIFICATION.md). Tahap Node/Storage/Cloud Functions berikut berlaku untuk kompatibilitas lama atau pengelolaan identitas Firebase ADMIN, bukan syarat upload katalog native.

Aplikasi Android menggunakan project **JayagunaTehnikPerkasa**, ID **`jayagunatehnikperkasa`**, dari `app/google-services.json`. Mode demo sudah dilepas dari APK. Akun contoh yang sebelumnya dibuat di emulator tidak berlaku pada project asli.

Login dan pembacaan data memakai Firebase Auth/Firestore langsung. App Check Android telah dihapus; HP tidak perlu didaftarkan atau disambungkan ke komputer untuk login. Claim OWNER/ADMIN, profil aktif dengan role cocok, dan cutoff pencabutan sesi tetap diperiksa dari server. Pada 1.0.4, simpan katalog dan gambar juga memakai Firestore/Drive langsung; rules transaksi native sudah diuji dan diterapkan.

## Status project yang sudah diverifikasi

- Firebase CLI sudah login sebagai `mtahakurniawan@gmail.com`.
- Database Firestore Native `(default)` tersedia di `asia-southeast2` (Jakarta).
- `firestore.rules` termasuk audit Drive sudah dideploy dan cocok dengan source lokal; **22 composite index** tersedia, 19 READY dan 3 tambahan CREATING pada pemeriksaan pertama.
- Sebelum penambahan portofolio, seluruh **22 index READY**. Versi 1.0.5 menambahkan dua index galeri; hasil deployment dan pemeriksaan terbarunya dicatat pada [verifikasi](VERIFICATION.md). Katalog native dijalankan dengan memasang APK, tanpa menyalakan server Node lokal.
- OWNER `mtahakurniawan@gmail.com` sudah dibuat, beserta custom claim OWNER dan profil aktif pada `users`. Jangan menjalankan bootstrap ulang.
- `system/schema` dan `system/bootstrapOwner` tersedia pada database asli. Koleksi katalog/portofolio muncul setelah data pertama disimpan melalui aplikasi.
- Registrasi App Check pengembangan pernah diuji pada versi sebelumnya; versi saat ini tidak memakainya sebagai syarat login.
- Billing masih belum aktif pada pemeriksaan terakhir; bucket Storage belum ada dan Cloud Functions belum dideploy. Keduanya bukan syarat login Firebase Auth/Firestore pada versi ini.
- Credential Firebase Admin asli lolos pemeriksaan Auth/Firestore. Upload, baca ulang, ganti/retry dan hapus produk/logo melalui Android native telah diuji. Hasil portofolio terbaru tersedia pada [verifikasi](VERIFICATION.md); pengujian HP pribadi tetap dilakukan pemilik akun.

Periksa status aktual secara baca saja:

```powershell
npm.cmd --prefix functions run project:status
npm.cmd --prefix functions run bootstrap:cli -- --project jayagunatehnikperkasa --email mtahakurniawan@gmail.com --name "Owner Jayaguna"
```

Perintah kedua tanpa `--apply` hanya memeriksa akun dan profil. Semua kredensial operator tetap berada dalam sesi Firebase CLI; tidak ada service-account key yang perlu diunduh.

## Login pada HP

Bangun dan pasang APK versi 1.1.2 sebagai pembaruan, lalu gunakan email dan kata sandi Firebase akun OWNER/ADMIN yang sudah aktif. Tidak perlu mengubah paket Firebase atau melakukan registrasi perangkat untuk langkah ini. Tombol **Coba periksa akses lagi** mencoba ulang pembacaan profil jika jaringan sementara gagal.

Untuk gambar, pemilik membagikan keempat folder Drive ke email Google admin sebagai Editor. Admin membuka **Akun & perangkat → Pilih akun Google**, memilih email penerima undangan, lalu menyelesaikan persetujuan Google. Status terhubung menampilkan email yang dipilih. Akun Google pemilik tidak perlu ditambahkan ke HP admin. Role Firebase dan izin folder Drive diberikan secara terpisah; mengundang email ke folder tidak mengaktifkan akun ADMIN aplikasi. Jika OAuth masih berstatus Testing, email Google admin juga perlu ada pada daftar test user project OAuth; lihat [panduan native](NATIVE_DRIVE.md#memakai-apk).

## Backend untuk pengelolaan ADMIN

**Tambah admin** dan **Aktifkan/Nonaktifkan admin** memerlukan backend aktif untuk membuat akun Firebase, menetapkan custom claim ADMIN, dan mencabut sesi. Login, katalog native, serta Google Drive langsung dari Android tidak memerlukan backend ini. Alamat `10.0.2.2:8787` hanya menunjuk komputer pengembangan dari Android Emulator pada laptop yang sama; alamat tersebut bukan layanan untuk HP atau laptop lain.

### Tanpa Blaze: Render Free

Backend ADMIN dapat berjalan di luar Firebase dengan Firebase Admin SDK, sementara project Firebase tetap pada Spark dan mengikuti kuota layanan yang digunakan. Akun Render dan repository privat untuk source backend diperlukan. Buat paket backend terisolasi dari akar proyek:

```sh
python3 scripts/prepare-admin-deploy.py --target render
```

Script mencetak lokasi staging di `.artifacts/admin-deploy/` beserta perintah instalasi dependensi dan build. Gunakan hanya isi folder staging tersebut sebagai repository deployment; arsip privat proyek Android berisi signing key dan tidak cocok untuk diunggah ke hosting. Paket Render memakai Node 22, blueprint `render.yaml`, plan `free`, build `npm ci && npm run build`, dan start `npm run start:admin`.

Layanan ini melayani `GET /healthz` serta `POST /api/call/manageAdmin`. ID token Firebase, status pencabutan sesi, profil aktif, dan peran OWNER tetap diperiksa. Kredensial Firebase Admin diberikan melalui secret `FIREBASE_SERVICE_ACCOUNT_JSON` di server Render, dengan project `jayagunatehnikperkasa` serta izin Firebase Authentication Admin dan Cloud Datastore User. Kredensial tidak disimpan dalam repository atau APK. OAuth Drive dan sertifikat signing Android tidak diperlukan oleh backend ADMIN ini.

Setelah service aktif dan endpoint diverifikasi, gunakan origin HTTPS yang diberikan Render sebagai `backendBaseUrl`. Simpan URL publik tersebut di `gradle.properties` agar build dari laptop lain menggunakan endpoint yang sama, lalu bangun APK dengan signing kit proyek yang sudah ada. Operasi admin memakai protokol Node `/api/call/manageAdmin`; flag callable `useLegacyFunctions` tidak diperlukan untuk jalur ini.

Render Free tidur setelah 15 menit tidak digunakan, memerlukan sekitar satu menit untuk aktif kembali, dan memiliki batas pemakaian bulanan. Pertimbangkan waktu tunggu ini ketika mencoba operasi admin pertama. Ketentuan aktual tersedia pada [dokumentasi Render Free](https://render.com/docs/free). Login dan upload katalog native tetap mengakses Firebase/Google Drive langsung.

### Pilihan Cloud Functions dengan Blaze

Untuk memakai callable Firebase, operator yang memiliki akses deployment perlu menyiapkan paket **Blaze**, lalu men-deploy hanya fungsi pengelolaan admin di region `asia-southeast2`:

Pada Mac, pasang Firebase CLI dengan `npm install -g firebase-tools`, lalu jalankan `firebase login --interactive`. Pilih akun pemilik project, tinjau persetujuan Google, dan periksa akses melalui `firebase projects:list`. Login CLI ini untuk operator deployment; pengguna APK tidak perlu melakukannya.

Siapkan paket terisolasi dari akar proyek:

```sh
python3 scripts/prepare-admin-deploy.py
```

Script hanya menyiapkan folder unik pada `.artifacts/admin-deploy/` dan mencetak tiga perintah: instalasi dependensi dengan `npm ci`, build, lalu `firebase deploy --project jayagunatehnikperkasa --config ... --only functions:manageAdmin`. Tinjau dan jalankan ketiganya setelah akses project dan Blaze tersedia. Entrypoint paket hanya mengekspor `manageAdmin`, dengan wrapper dan pemeriksaan OWNER dari source existing. Fungsi katalog dan scheduler tidak ikut menjadi target deployment.

`functions/.env` existing dipakai oleh Node lokal. Paket terisolasi tidak menyalin file tersebut, kredensial, secret OAuth Drive, atau path `GOOGLE_APPLICATION_CREDENTIALS`. Functions menggunakan service account/IAM runtime pada server, bukan file kredensial workstation; service account itu perlu izin Firebase Authentication Admin dan Cloud Datastore User. Konfigurasi Node lokal tetap tersimpan di tempat semula.

Setelah endpoint tersedia, build APK dengan `-PuseLegacyFunctions=true`. Sebelum build, kosongkan `backend.debugBaseUrl` pada `local.properties` dan jangan memberikan `-PbackendBaseUrl` atau environment `BACKEND_BASE_URL`. URL backend yang tidak kosong selalu diprioritaskan oleh aplikasi, sehingga flag callable saja tidak mengalihkan permintaan dari server lokal.

Alternatifnya, jalankan backend Node existing pada origin HTTPS yang dapat dijangkau perangkat dan isi `backendBaseUrl` dengan origin tersebut; lihat [panduan backend](GOOGLE_DRIVE.md). Kedua jalur tetap memeriksa token, claim OWNER, profil aktif, dan pencabutan sesi di server. Kredensial Firebase Admin tetap berada di server. Bagian ini menjelaskan kebutuhan konfigurasi, bukan konfirmasi bahwa deployment atau billing saat ini sudah siap.

## 1. Login Firebase CLI

Di VS Code, buka **Terminal → New Terminal**, lalu jalankan:

```powershell
firebase.cmd login
```

Pilih akun Google yang memiliki project `jayagunatehnikperkasa` di browser dan setujui akses Firebase CLI. Jika CLI menampilkan Session ID serta tautan `auth.firebase.tools`, cocokkan ID sesi, selesaikan login pada tautan itu, lalu salin **seluruh perintah `firebase login ...`** yang diberikan halaman ke terminal. Kode otorisasi cukup dipakai pada terminal; jangan menyimpan kode tersebut di source.

Setelah muncul `Success! Logged in as ...`, periksa akses:

```powershell
firebase.cmd projects:list
firebase.cmd firestore:databases:list --project jayagunatehnikperkasa
```

Nama dan ID project tidak memberikan izin operator. Login CLI diperlukan untuk memeriksa database, rules dan deployment.

## 2. Periksa layanan project yang sudah ada

Buka [Firebase Console project Jayaguna](https://console.firebase.google.com/project/jayagunatehnikperkasa/overview).

- **Firestore Database**: gunakan database Native `(default)`. Jika belum ada, pilih Create database, production mode, dan lokasi yang sesuai. Periksa database/website yang sudah berjalan sebelum membuat database atau mengganti rules.
- **Authentication → Sign-in method**: aktifkan Email/Password.
- **Storage/Cloud Functions**: hanya diperlukan jika jalur lama tersebut digunakan; bukan syarat login saat ini.
- **App Check**: provider Android sudah dilepas. Jangan mewajibkan enforcement layanan untuk APK saat ini tanpa implementasi client yang sesuai. Backend memakai `ENFORCE_APP_CHECK=false`; role dan ID token tetap wajib.

Jangan menaruh service-account key di APK, website, atau repository.

## 3. Build dan deploy backend

Dari folder utama proyek:

```powershell
npm.cmd --prefix functions ci
npm.cmd --prefix functions run build
firebase.cmd deploy --project jayagunatehnikperkasa --only "firestore:rules,firestore:indexes,storage,functions"
```

Perintah deployment lengkap di atas berlaku bila jalur Storage/Functions dipilih. Untuk aturan dan index Firestore saja, gunakan `--only "firestore:rules,firestore:indexes"`. Tinjau rules terhadap semua aplikasi sebelum deployment dan tunggu index siap. App Check backend hanya aktif jika `ENFORCE_APP_CHECK=true` dikonfigurasi secara eksplisit.

## 4. Siapkan OWNER pertama

**OWNER project ini sudah tersedia**: `mtahakurniawan@gmail.com`. Setelah konfigurasi backend selesai, gunakan **Lupa kata sandi** pada halaman login untuk menerima email pengaturan password. Password akun Google tidak otomatis menjadi password Firebase Authentication.

Pada workstation Windows ini, alternatif aktivasi yang sudah disiapkan: `npm.cmd --prefix functions run owner:activate`. Halaman resmi Firebase dibuka langsung agar pemilik menetapkan sandi tanpa perlu menunggu email. Script tidak mencetak/menyimpan URL reset atau mengganti sandi sendiri. Halaman ini sudah dibuka; setelah selesai, login pada APK di Android Emulator.

Script `bootstrap:cli` telah digunakan pada workstation ini dengan sesi CLI yang Anda otorisasi. Jangan memakai `--apply` lagi untuk membuat ulang OWNER. Untuk deployment di project baru yang belum memiliki owner, opsi bootstrap berikut memakai Application Default Credentials pada workstation tepercaya, terpisah dari login Firebase CLI:

```powershell
$env:GOOGLE_APPLICATION_CREDENTIALS="C:\lokasi-aman\firebase-backend.json"
cd functions
npm.cmd run bootstrap -- jayagunatehnikperkasa EMAIL_OWNER "Nama Owner"
cd ..
```

Ganti `EMAIL_OWNER` dan `Nama Owner`, serta gunakan credential server yang sesuai project di luar repo. Alternatif ADC tanpa key memerlukan OAuth Desktop client sendiri atau impersonation IAM; login gcloud biasa tidak cukup untuk Firebase Auth Admin (lihat panduan Drive). Script meminta pengetikan project ID sebagai konfirmasi, menolak jika OWNER/lock sudah ada, serta menetapkan custom claim dan profil server. Setelah selesai, gunakan **Lupa kata sandi** di aplikasi untuk menerima email pengaturan password.

OWNER kemudian membuat ADMIN lewat aplikasi. Role tidak dapat diberikan hanya dengan menambahkan akun dari layar login atau mengubah field dari aplikasi.

Script App Check lama dipertahankan sebagai alat operator untuk integrasi lama; jangan menjalankannya untuk mengatasi login APK 1.0.1. Registrasi perangkat tidak digunakan versi ini.

## 5. Pasang dan buka Android

Untuk pemasangan lewat Android Studio/ADB, nyalakan emulator atau hubungkan perangkat pengembangan. Dari folder utama:

```powershell
.\gradlew.bat :app:installDebug
```

Buka **Jayaguna Admin**, lalu masuk dengan akun OWNER/ADMIN project asli. Android Emulator di sini hanya perangkat virtual untuk menjalankan APK; semua layanan Firebase tetap memakai cloud `jayagunatehnikperkasa`.

Untuk HP pribadi tanpa kabel, bangun dan salin `app/build/outputs/apk/debug/app-debug.apk` versi 1.1.2 lalu pasang sebagai pembaruan. Login dan pembacaan Firestore hanya memerlukan koneksi internet. Gambar memakai akun Google admin yang telah diberi izin folder; operasi API operator lokal mempunyai kebutuhan koneksi tersendiri.

## Struktur database

Firestore membuat koleksi saat dokumen pertama ditulis. Koleksi tidak perlu diisi placeholder agar terlihat. Struktur tersedia dalam [README utama](../README.md#koleksi-firestore), validasi `functions/src/schema.ts`, dan `firestore.indexes.json`. `users` dibuat melalui bootstrap OWNER/pengelolaan admin; produk, mitra, proyeksi publik, audit, receipt, dan sesi upload dibuat backend saat operasi berhasil.

## Referensi tema

Warna dan logo dibaca dari [website resmi](https://jayagunatehnikperkasa.com/) dan [stylesheet website](https://jayagunatehnikperkasa.com/Page/home/home.css). Tema Android memakai merah `#C32639`, navy `#11265D`, biru `#0058C6`, aksen emas `#CC9F13`, kartu putih, batas abu terang, serta logo JTP asli yang dibundel dalam APK.
