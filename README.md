# Jayaguna Admin — Android dan Firebase

Aplikasi internal berbahasa Indonesia untuk produk AC, logo mitra, dan portofolio. **Versi 1.2.3** mendukung [banyak logo mitra sekaligus](docs/PARTNER_BULK.md), masing-masing menjadi mitra tersendiri. Login Google, persetujuan Firestore, izin Drive setelah login dan [penghapusan akses admin oleh OWNER](docs/OWNER_ADMIN.md) dipertahankan. Drive diakses langsung dari perangkat tanpa backend publik. Project Firebase tetap **`jayagunatehnikperkasa`**. Ikuti [panduan login dan izin Drive](docs/LOGIN_DRIVE.md).

**Animasi dan identitas aplikasi:** perpindahan menu bawah memakai slide horizontal sesuai urutan **Beranda → Produk → Mitra → Portofolio → Riwayat**. Memilih menu di sebelah kanan menggeser halaman ke kiri; memilih menu di sebelah kiri menggesernya ke kanan. Navbar terang, Save/Restore State, cache, preview serta logo perusahaan pada ikon/splash tetap dipertahankan. Detail transisi dan konfigurasi logo ada pada [panduan antarmuka](docs/NAVIGATION_STATE.md#animasi-dan-logo-rilis-111); hasil pengujian aktual dicatat pada [verifikasi](docs/VERIFICATION.md).

**Navigasi dan loading:** navbar bawah dikembalikan ke tampilan terang sebelumnya, dengan ikon/label aktif merah dan indikator lembut. Perbaikan state halaman, filter, isian, posisi scroll dan loading dari versi 1.0.6 tetap dipertahankan. Data yang sudah dimuat tidak diambil ulang setiap tab dibuka. Pemulihan setelah layar/proses dibuat ulang dijelaskan pada [panduan state](docs/NAVIGATION_STATE.md).

**Cache gambar:** gambar yang sudah dimuat dipakai bersama oleh halaman produk, mitra, portofolio dan editor. Bitmap di RAM langsung ditampilkan saat kembali ke halaman; cache byte privat di RAM/disk menghindari permintaan Firestore/Drive berulang. Cache dibatasi ukurannya, dipisahkan per sesi pengguna, dan dibersihkan saat logout. Unduhan pertama, gambar baru, atau gambar yang sudah terbuang dari cache tetap dapat memerlukan loading. Detail ada pada [panduan cache](docs/NATIVE_DRIVE.md#cache-gambar-pada-hp).

**Preview gambar:** tekan foto produk, logo mitra, gambar portofolio, atau foto lokal yang dipilih untuk membuka tampilan layar penuh. Gunakan cubit dua jari, ketuk dua kali, atau tombol zoom; tutup melalui tombol **Tutup** atau Back Android. Preview menggunakan cache yang sama dan tidak mengubah file maupun data katalog.

**Mitra:** Tambah mitra dapat memilih hingga delapan logo sekaligus. Setiap logo mempunyai preview, nama dan detail sendiri, lalu disimpan sebagai satu mitra. Retry melanjutkan antrean yang tersisa dengan ID tetap. Editor kartu tetap mengubah satu mitra existing. [Panduan unggah banyak logo](docs/PARTNER_BULK.md).

**Portofolio:** kategori **Perumahan** dan **Perusahaan** berisi gambar saja, dengan Photo Picker, preview, unggah hingga delapan gambar per pilihan, progres/retry, ganti, dan konfirmasi hapus. Folder yang dipakai adalah `1kOqO3wgNmUCujrhb1tqY3kB_kG7jTEjx` (Perumahan) dan `10Z9US8nxqTV5AkJrRrEURRsWNhOpvHvK` (Perusahaan). Kategori gambar tetap setelah disimpan. Detail metadata dan akses gambar ada di [panduan native](docs/NATIVE_DRIVE.md#galeri-portofolio).

**Integrasi Drive perusahaan:** setelah login approved, aplikasi menjalankan AuthorizationClient untuk akun Google yang sama dan memeriksa empat folder existing. Izin yang sudah diberikan digunakan kembali. Upload, unduh, dan hapus gambar memakai Google Drive API langsung, dengan registry asset UUID dan transaksi metadata Firestore. Pemilik membagikan empat folder sebagai Editor ke email login. Kepemilikan file dan kuota mengikuti akun pengunggah. [Panduan aktif](docs/LOGIN_DRIVE.md).

**Mulai menjalankan aplikasi:** [periksa OAuth Google/Drive, setujui pengguna, lalu bagikan empat folder](docs/LOGIN_DRIVE.md). Package Android tetap `com.jayaguna.admin`; konfigurasi publik berasal dari `app/google-services.json`.

Untuk pemeliharaan bridge website legacy, script `scripts/run-local.ps1` tetap tersedia. Implementasi backend pusat 1.2.0 dipertahankan terpisah sebagai source; Android 1.2.1 tidak memakainya untuk gambar.

`backendBaseUrl` tidak diperlukan untuk Drive Android. Properti tersebut hanya mengaktifkan layanan opsional existing, misalnya notifikasi email jika kelak ada backend publik.

**Login:** Continue with Google menukar identitas Google dengan sesi Firebase, kemudian memeriksa profil UID atau email grant dari Firestore server. Profil pending tidak dapat membaca katalog. Permohonan dibuat sekali untuk persetujuan manual; notifikasi email masih ditunda sesuai pilihan pemilik. Perubahan keputusan saat aplikasi terbuka memicu pemeriksaan ulang. Akun approved dilanjutkan ke izin Drive. Tidak ada form password, pemulihan password, atau pemberian role oleh client.

APK development dapat memperbarui instalasi development dengan signing kit yang sama. APK release memakai sertifikat release tersendiri. Tidak ada registrasi setiap HP untuk hak akses Firebase atau akses Drive perusahaan.

**Status konfigurasi:** login Google development dan rules approval existing dipertahankan. Pemulihan 1.2.1 menghapus ketergantungan backend dari jalur gambar. Izin Drive, OAuth dan akses empat folder tetap diperiksa terhadap akun nyata; build/test saja tidak membuktikan koneksi produksi. Persetujuan tetap melalui Firebase Console. Lihat [panduan konfigurasi](docs/LOGIN_DRIVE.md) dan [hasil verifikasi](docs/VERIFICATION.md).

Pemilik memilih **persetujuan manual tanpa email untuk saat ini**. Aplikasi tetap menyimpan permohonan pending; pengelola membuka `users/{UID}` di Firebase Console dan mengubah status menjadi approved. Saat backend belum dikonfigurasi, aplikasi menampilkan pesan persetujuan manual dan tidak mencoba mengirim email.

Mode demo telah dilepas dari aplikasi. Build debug maupun release memakai Firebase asli; flag lama `-PuseEmulators=true` tidak mengubah tujuan koneksi. Emulator Suite hanya dipertahankan sebagai alat pengujian backend/rules yang terisolasi dari cloud.

Tema mengikuti [website perusahaan](https://jayagunatehnikperkasa.com/): merah `#C32639`, navy `#11265D`, biru `#0058C6`, aksen emas `#CC9F13`, latar putih/abu terang, dan logo JTP asli. Logo disimpan lokal agar tetap tampil tanpa jaringan.

Folder kerja awal kosong, tanpa Android, website, konfigurasi Firebase, atau skema lama. Karena itu implementasi ini membuat skema baru dan menyediakan integrasi website terpisah. Jika ada database produksi di luar folder ini, inventarisasi dan petakan skemanya dahulu sebelum deployment; jangan menimpa rules produksi tanpa menilai semua aplikasi yang memakainya.

## Isi proyek dan arsitektur

```text
app/src/main/java/com/jayaguna/admin/
  core/{components,device,navigation,security,theme,validation}
  data/{model,remote,repository}
  features/
    auth/{controller,view}
    dashboard/{controller,view}
    products/view
    partners/view
    portfolio/{controller,view}
    activity/view
    users/view
functions/
  src/{index,server,service,schema}.ts
  src/drive/                 # OAuth, Drive v3, registry, validasi dan cleanup
  scripts/bootstrap-owner.mjs
  test/
apps-script/                # referensi percobaan yang dibatalkan, tidak aktif
web-integration/             # modul JS yang dipasang pada website
firestore.rules
storage.rules
firestore.indexes.json
firebase.json
docs/VERIFICATION.md
```

MVC disesuaikan Compose: model dan repository memetakan dokumen serta menjalankan SDK Firebase; view hanya menerima state dan callback; `AuthController`, `AdminController`, dan `PortfolioController` mengelola validasi, pagination, upload, dan interaksi pengguna. Controller mewarisi `ViewModel` untuk mempertahankan state/coroutine saat rotasi. State UI menggunakan `StateFlow`, dikumpulkan dengan `collectAsStateWithLifecycle`. State katalog berada dalam scope sesi dan dibuang saat logout. Draft formulir menggunakan saver Android, tanpa menyimpan password. Dependency injection dilakukan melalui constructor dan composition local untuk repository gambar; `AdminApplication` adalah composition root.

Login Google membaca izin UID/email dari Firestore; approval dan cutoff pencabutan sesi menjadi otoritas akun baru tanpa kewajiban custom claim ADMIN. Izin legacy yang sudah sah dipertahankan untuk migrasi. OWNER membaca daftar pengguna/aktivitas; persetujuan dan role ditetapkan pengelola melalui Firebase Console. ADMIN mengelola katalog dan membaca aktivitas sendiri. Backend dan rules menerapkan pemeriksaan yang sama.

## Konfigurasi Firebase yang harus diisi

1. Gunakan project Firebase yang sama untuk Android dan website, dengan Firestore Native dan provider Google untuk Android. Konfigurasi provider website existing tetap dipertahankan. Alur baru tidak memerlukan deployment Cloud Functions.
2. Untuk mengakses cloud, daftarkan Android dengan application ID **`com.jayaguna.admin`**. Unduh `google-services.json`, letakkan di **`app/google-services.json`**. File ini tidak dibuat dengan nilai fiktif. Build cloud tanpa file tersebut menampilkan petunjuk konfigurasi.
3. Unduh ulang konfigurasi setelah provider Google aktif, lalu terapkan rules approval yang sudah diuji. UID atau email terverifikasi harus diberi izin melalui Firestore; identitas Firebase saja tidak memberikan akses katalog.
4. Build Android saat ini tidak memasang provider App Check dan tidak memerlukan pendaftaran perangkat. Jangan mengaktifkan enforcement layanan untuk build ini tanpa implementasi client yang sesuai.
5. Aktifkan Drive API pada project OAuth existing dan bagikan empat folder ke akun login sebagai Editor. Persetujuan Drive diminta otomatis setelah akun approved; tidak memerlukan server atau Blaze.
6. Daftarkan aplikasi Web dan isi **`web-integration/firebase-config.js`** dari template. Isi project ID, bucket, client app config, domain dan reCAPTCHA Enterprise site key yang benar. Lihat [panduan web](web-integration/README.md) untuk mount point dan CORS bucket.
7. Siapkan Firebase CLI, autentikasi operator, IAM backend, dan pilih project eksplisit pada setiap perintah deployment. Tidak ada service-account key, private key, atau kredensial backend yang perlu dimasukkan ke Android/website.

OWNER dan katalog existing dipertahankan. Jangan menjalankan bootstrap ulang. Login Google, rules approval dan izin Drive mengikuti [panduan 1.2.1](docs/LOGIN_DRIVE.md); credential administratif tidak dimasukkan ke APK.

## Build Android

Memerlukan JDK 21, Android SDK Platform 36 dan Build Tools 35.0.0, serta internet untuk dependency awal. Atur SDK melalui Android Studio, `ANDROID_HOME`, atau `local.properties` lokal:

```properties
sdk.dir=C\:\\Users\\NAMA_USER\\AppData\\Local\\Android\\Sdk
```

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK distribusi: **`.artifacts/apk/Jayaguna-1.2.3-release.apk`**. APK pengembangan: **`.artifacts/apk/Jayaguna-1.2.3-development.apk`**. Build memakai sertifikat proyek tetap dan menolak fingerprint yang berbeda. Laptop lain memakai kit privat yang sama; jalankan `python3 scripts/build.py debug` atau `release`. Lihat [build lintas laptop](docs/PORTABLE_BUILD.md) dan [verifikasi](docs/VERIFICATION.md). OAuth Google/Drive dan izin folder perlu sesuai akun yang digunakan.

Dependency dipin: AGP 8.13.2, Gradle 8.13, Kotlin dan Compose compiler 2.1.20, Compose BoM 2025.04.01, Material 3 dari BoM, Activity 1.10.1, Lifecycle 2.9.0, Core SplashScreen 1.2.0, Coroutines 1.10.2, Coil 2.7.0, Firebase Android BoM 34.0.0, Credential Manager 1.6.0 dan Google ID 1.1.1. Ini pilihan versi stabil yang kompatibel, bukan klaim versi paling baru. Firebase memakai modul utama `firebase-auth`, `firebase-firestore`, dll.; modul Firebase `-ktx` tidak dipakai. Konfigurasi splash mengikuti [panduan Android SplashScreen](https://developer.android.com/develop/ui/views/launch/splash-screen) dan dependency tersedia pada [catatan rilis AndroidX Core](https://developer.android.com/jetpack/androidx/releases/core).

Dasar verifikasi resmi: [AGP 8.13/Gradle/JDK](https://developer.android.com/build/releases/agp-8-13-0-release-notes), [Compose compiler dan Kotlin](https://kotlinlang.org/docs/compose-compiler-migration-guide.html), [Compose BoM](https://developer.android.com/develop/ui/compose/bom/bom-mapping), [Lifecycle](https://developer.android.com/jetpack/androidx/releases/lifecycle), [Firebase BoM dan perubahan KTX](https://firebase.google.com/support/release-notes/android), [callable functions](https://firebase.google.com/docs/functions/callable), dan [Play Integrity App Check](https://firebase.google.com/docs/app-check/android/play-integrity-provider).

## Backend operator dan jalur lama

Backend Node.js **22** memiliki implementasi `central-server.ts` dari 1.2.0, tetapi Drive Android 1.2.1 memakai akses langsung. Bridge website dan callable lama dipertahankan untuk kompatibilitas terpisah; deployment seluruh layanan di bawah bukan syarat alur Android. Untuk aplikasi aktif, ikuti [panduan 1.2.1](docs/LOGIN_DRIVE.md).

```powershell
cd functions
npm ci
npm test
cd ..
firebase login
firebase deploy --project YOUR_PROJECT_ID --only firestore:rules,firestore:indexes,storage,functions
```

Deploy tersebut harus dijalankan operator terhadap project yang dimaksud, setelah review rules. Firebase akan meminta izin integrasi cross-service Storage Rules → Firestore; berikan izin layanan resmi yang diperlukan. Tunggu seluruh composite index berstatus siap. Konfigurasikan CORS bucket sesuai domain website di panduan web.

Bootstrap dilakukan **sekali** pada workstation/CI tepercaya dengan Application Default Credentials dan IAM yang dapat mengelola Firebase Auth/Firestore:

```powershell
$env:GOOGLE_APPLICATION_CREDENTIALS="C:\lokasi-aman\firebase-backend.json"
cd functions
npm run bootstrap -- YOUR_PROJECT_ID owner@perusahaan.com "Nama Owner"
```

Script legacy meminta pengetikan project ID, mengunci `system/bootstrapOwner` dalam transaksi, menolak jika owner/lock sudah ada, membuat/menetapkan claim OWNER dan profil, lalu mengaktifkan akun. Jika langkah eksternal gagal, lock tetap ada agar operator memeriksa Auth/profil/IAM sebelum pemulihan manual. Tidak ada endpoint bootstrap publik. APK 1.2.0 menggunakan Google dan approval manual sesuai panduan baru; OWNER existing tidak perlu dibootstrap ulang. Jangan mencetak link reset ke log atau menyimpan password bootstrap.

Pada alur legacy, endpoint `manageAdmin` membuat akun ADMIN dengan secret acak dan email pemulihan; penonaktifan mencabut token serta akses profil. Endpoint ini dipertahankan untuk kompatibilitas. APK 1.2.0 memakai tombol permohonan akses, dan pemilik menyetujui status melalui Firebase Console.

Lihat juga [panduan backend](functions/README.md) untuk detail callable, transaksi, pemulihan, dan emulator.

## Koleksi Firestore

| Koleksi | Isi dan akses |
|---|---|
| `products` | Semua produk, termasuk draft/nonaktif/arsip. Field katalog, `images[{storagePath,url}]`, `mainImagePath`, `status`, `archived`, `version`, `createdAt/updatedAt`, `createdBy/updatedBy`, `searchTokens`. Staff aktif saja. |
| `partners` | Nama, `logo{storagePath,url}`, alt text, website, urutan, aktif/arsip, versi dan metadata internal. Staff aktif saja. |
| `portfolioImages` | Satu foto per dokumen: `category` (`residential`/`corporate`), `image`, arsip, versi, timestamp server, pembuat/pengubah, dan request ID. Staff aktif saja. |
| `publicProducts` | Proyeksi field katalog produk aktif yang tidak diarsipkan; tanpa UID, perangkat, role, atau versi internal. Query publik wajib `status == active`, `archived == false`. |
| `publicPartners` | Proyeksi mitra aktif; tanpa metadata pengguna. Query publik wajib `active == true`, `archived == false`. |
| `publicPortfolioImages` | `id`, `category`, `image`, `archived:false`, timestamp server dan `mediaPaths`. Tanpa identitas pengguna; query publik wajib `archived == false`. Metadata publik tidak membuka akses anonim ke file Drive privat. |
| `users` | UID, nama, email, role, active, versi, cutoff pencabutan token. OWNER membaca semua; ADMIN hanya profilnya sendiri. |
| `activityLogs` | Snapshot perubahan dengan actor UID/nama/email/role, aksi, tipe/ID entitas, before/after/changedFields, server timestamp, request ID, dan perangkat. Penulisan native divalidasi Rules bersama commit entitas. OWNER membaca semua; ADMIN hanya miliknya. |
| `audit_logs` | Catatan upload/ganti/hapus media native yang terikat perubahan status media oleh Rules. Rules memvalidasi identitas/waktu/status, tetapi tidak membuktikan panggilan Drive berlangsung. |
| `operationReceipts` | Hash payload dan hasil mutasi untuk deduplikasi; perubahan native wajib sesuai transaksi entitas dan riwayat. |
| `mediaAssets`, `driveUploadRequests` | Registry gambar Drive dan reservasi unggah native yang terikat akun, entitas, folder, MIME, ukuran dan checksum. |
| `uploadSessions` | Lease unggah berumur 24 jam: pemilik, tipe/ID entitas, ukuran, MIME, path, hash. Backend saja; Storage Rules memvalidasi lease. |
| `system` | Lock bootstrap, hanya backend/operator IAM. |

Firestore Rules membatasi penulisan native ke transaksi staff aktif yang memiliki claim/profil sesuai, versi benar, snapshot perubahan, proyeksi dan receipt yang cocok. Role pengguna dan custom claim tetap hanya dapat dikelola operator tepercaya. Semua daftar dibatasi dan memakai cursor. Composite indexes disertakan dalam `firestore.indexes.json`, termasuk kategori portofolio, filter aktivitas dan pencarian produk. Pencarian produk adalah prefix nama/merek atau kata dalam nama/merek, maksimal 50 karakter query; tekan **Terapkan pencarian** untuk query server.

## Upload, retry, konflik, dan audit

Setiap mutasi memerlukan internet; aplikasi memakai transaksi SDK Firestore langsung tanpa antrean perubahan offline. Pembacaan daftar meminta `Source.SERVER`; cache Firestore hanya memori. Sukses ditampilkan sesudah commit dikonfirmasi. Proyeksi publik tersedia untuk integrasi website; tampilan portofolio website belum termasuk menu Android ini.

Gambar PNG (termasuk alpha), JPEG, dan WebP dibatasi **5 MiB** dan 40 megapiksel. Produk mendukung maksimal **8 foto**; portofolio menerima hingga **8 pilihan** yang disimpan berurutan sebagai entri terpisah. Layanan Drive native memeriksa MIME, ukuran, magic bytes, decode aktual dan checksum. Bytes asli dipertahankan, termasuk metadata EXIF.

Alur Drive native: aplikasi mencadangkan File ID dan registry Firestore, mengunggah langsung ke folder perusahaan melalui Google Drive API v3, lalu menyimpan entitas/proyeksi/riwayat/receipt secara atomik. Retry memakai ID yang sama. Registry attachment dan cleanup berjalan dalam transaksi kecil sesudah commit katalog. File lama baru dipindahkan ke Trash setelah referensi penggantian tersimpan dan pemeriksaan memastikan file tidak digunakan lagi. Kegagalan cleanup mempertahankan metadata yang bisa dicoba ulang. Lihat [detail native](docs/NATIVE_DRIVE.md).

Media Drive di Android dibaca langsung menggunakan akun Google yang terhubung dan memiliki akses ke folder perusahaan, berdasarkan referensi logis `drive/{mediaId}` dan registry Firestore. File tetap privat dan tidak memakai URL thumbnail Drive. Hak menghapus file mengikuti kapabilitas Google Drive; cleanup file milik admin lain dapat tertunda meskipun perubahan katalog sudah tersimpan. Gambar Storage lama dibaca melalui SDK/Rules. Jalur pembaca gambar bagi pengunjung website anonim masih memerlukan integrasi terpisah yang diuji; proyeksi metadata publik tidak membuka file Drive secara otomatis.

Cleanup native dijalankan setelah simpan/ganti/hapus yang dikonfirmasi. Gambar yang masih direferensikan entitas, termasuk arsip produk/mitra, dipertahankan. Hapus portofolio mengarsipkan entri dengan `image:null` agar file yang tidak lagi direferensikan dapat dibersihkan. Jalur Storage lama tetap memiliki staging dan cleanup tersendiri.

Retry menggunakan `requestId` yang sama selama sesi controller, termasuk rotasi. Writer memeriksa hash payload dan membaca receipt tanpa membuat perubahan ganda; ID yang sama untuk payload berbeda ditolak. Upload dengan respons hilang diverifikasi ulang memakai ID/ukuran/MIME/checksum sebelum digunakan kembali. Setelah proses aplikasi dihentikan, muat ulang entitas dari server jika hasil sebelumnya belum diketahui; receipt tetap disimpan dan versi mencegah create/edit kedua menimpa data.

`expectedVersion` harus sama dengan versi server. Jika pengguna lain menyimpan dahulu, transaksi menolak dengan konflik dan formulir menampilkan instruksi membuka ulang data. Operasi Google Drive berada di luar transaksi Firestore; reservasi file dan metadata cleanup mempertahankan jalur retry ketika terjadi kegagalan sebagian.

Identitas akun audit berasal dari token terverifikasi dan profil server. ID instalasi adalah UUID acak pada SharedPreferences, backup aplikasi dinonaktifkan, dan ID berubah setelah reinstall/reset data. Nama perangkat, merek/model, versi Android/aplikasi adalah **metadata dari client**, bukan bukti identitas perangkat. Tidak memakai IMEI, nomor telepon, atau MAC address. Audit tidak mencatat password atau token.

## Pengujian

Instruksi dan hasil verifikasi ada di [docs/VERIFICATION.md](docs/VERIFICATION.md). Pengujian backend emulator memakai project demo terisolasi dan tidak menggantikan uji login HP fisik, email, CORS, dan sinkronisasi pada project Firebase yang sudah dikonfigurasi.
