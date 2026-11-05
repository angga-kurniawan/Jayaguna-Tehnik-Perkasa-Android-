# Konfigurasi Google Drive perusahaan untuk pengembang

**Dokumen backend 1.2.0, bukan setup Android aktif.** Versi 1.2.1 meminta izin Drive setelah login dan mengakses Drive langsung dari perangkat sesuai pilihan pemilik. Ikuti [panduan login dan Drive aktif](LOGIN_DRIVE.md). Backend di bawah tidak diperlukan untuk fitur gambar Android 1.2.1.

Panduan ini untuk APK 1.2.0 dan backend `functions/src/central-server.ts`. Pesan **Layanan Drive perusahaan belum dikonfigurasi** muncul karena `backendBaseUrl` pada APK belum diisi. Menambahkan URL adalah tahap terakhir: backend harus terlebih dahulu mempunyai credential Firebase, konfigurasi OAuth Drive, dan grant pemilik yang valid.

Alurnya: **APK dengan Firebase ID token → backend HTTPS memeriksa approval → Google Drive milik perusahaan**. Pengguna approved memakai sambungan perusahaan yang sama.

## 1. Siapkan credential Firebase server

Gunakan project Firebase **jayagunatehnikperkasa** (889896586147). Pulihkan JSON service account backend yang valid, atau buat credential server melalui Project settings → Service accounts sesuai [panduan Firebase Admin](https://firebase.google.com/docs/admin/setup#initialize_the_sdk_in_non-google_environments).

Untuk service account khusus backend ini, hak yang dibutuhkan mencakup pembacaan Firebase Auth dan baca/tulis Firestore, misalnya Firebase Authentication Viewer dan Cloud Datastore User. Hindari memakai akun dengan hak Owner hanya untuk menjalankan API.

Simpan JSON di lokasi privat di luar source, misalnya `/Users/NAMA_ANDA/.config/jayaguna/firebase-admin.json`. Pada workstation, isi path yang benar melalui `GOOGLE_APPLICATION_CREDENTIALS`. Path lama dalam konfigurasi lokal saat diperiksa menunjuk file yang tidak tersedia. JSON ini merupakan credential server; `app/google-services.json` tetap merupakan konfigurasi client Android.

## 2. Gunakan OAuth Web Drive existing

Buka Google Cloud Console pada project **jayagunatehnikperkasa-512819** (179144038580):

1. Pastikan Google Drive API aktif.
2. Google Auth Platform → Clients → buka **Web client 1** existing, ID `179144038580-p4ipmkgjfmdope6f7jtq98i0q1coiv0e.apps.googleusercontent.com`.
3. Pastikan Authorized redirect URIs memuat `http://localhost:8787/api/drive/oauth/callback`.
4. Jika Audience masih Testing, akun pemilik `mtahakurniawan@gmail.com` perlu diizinkan sebagai test user.

Gunakan client ID, client secret, dan `DRIVE_TOKEN_ENCRYPTION_KEY` existing. Kunci enkripsi yang sama diperlukan untuk membuka refresh token yang sudah tersimpan. Jangan menimpa file `.env` existing dengan template kosong.

## 3. Periksa `functions/.env` pada workstation

Contoh berikut berisi placeholder untuk nilai privat. Pertahankan nilai asli yang sudah ada:

```dotenv
NODE_ENV=development
HOST=127.0.0.1
PORT=8787
FIREBASE_PROJECT_ID=jayagunatehnikperkasa
GOOGLE_APPLICATION_CREDENTIALS=/Users/NAMA_ANDA/.config/jayaguna/firebase-admin.json
BACKEND_PUBLIC_URL=http://localhost:8787
GOOGLE_OAUTH_REDIRECT_URI=http://localhost:8787/api/drive/oauth/callback
ENFORCE_APP_CHECK=false

GOOGLE_DRIVE_ACCOUNT_EMAIL=mtahakurniawan@gmail.com
GOOGLE_DRIVE_PRODUCTS_FOLDER_ID=1RN6CPWKQ2i7rpgvxE3EUG0ndyuEm3NUd
GOOGLE_DRIVE_PARTNERS_FOLDER_ID=1fEw1ct_IRe9LB0u5n24gzBEOVQ2ekom6
GOOGLE_DRIVE_PORTFOLIO_RESIDENTIAL_FOLDER_ID=1kOqO3wgNmUCujrhb1tqY3kB_kG7jTEjx
GOOGLE_DRIVE_PORTFOLIO_CORPORATE_FOLDER_ID=10Z9US8nxqTV5AkJrRrEURRsWNhOpvHvK

GOOGLE_OAUTH_CLIENT_ID=179144038580-p4ipmkgjfmdope6f7jtq98i0q1coiv0e.apps.googleusercontent.com
GOOGLE_OAUTH_CLIENT_SECRET=GUNAKAN_SECRET_EXISTING
DRIVE_TOKEN_ENCRYPTION_KEY=GUNAKAN_KUNCI_EXISTING
```

Keempat folder dan akun pemilik di atas memang ditetapkan oleh source. Backend akan memeriksa kepemilikan, akses privat dan kemampuan menambahkan gambar. Service account Firebase digunakan untuk Auth/Firestore; sambungan Drive memakai OAuth akun manusia pemilik.

## 4. Hubungkan akun pemilik melalui helper lokal

Dari root proyek, dengan Node 22:

```sh
cd functions
npm ci
npm run build
npm run connect:company-drive -- --open
```

Helper otomatis membaca `.env`. Port 8787 harus kosong selama helper berjalan. Pilih **mtahakurniawan@gmail.com** dan selesaikan persetujuan Google. Tool memeriksa pemilik serta folder lalu menyimpan refresh token terenkripsi pada `backendSecrets/googleDrive` di Firestore project Firebase yang sama.

Grant existing yang masih valid dapat digunakan kembali; helper dipakai bila koneksi belum ada atau perlu diotorisasi ulang. [OAuth Google](https://developers.google.com/identity/protocols/oauth2#refresh-token-expiration) menjelaskan bahwa refresh token dapat kedaluwarsa; aplikasi External berstatus Testing dengan scope Drive dapat mempunyai batas tujuh hari. Untuk penggunaan rutin, selesaikan persyaratan publikasi/verifikasi OAuth yang berlaku.

Helper memerlukan akun utama dengan profil OWNER yang sah. Profil OWNER legacy tanpa status sudah didukung jika active dan claim OWNER sesuai. Jangan menjalankan bootstrap ulang atau menambahkan status pada profil legacy hanya untuk menjalankan helper. Akun admin aplikasi yang berbeda tidak perlu menjadi OWNER Drive.

Untuk menguji server lokal setelah helper selesai, dari folder `functions`:

```sh
node --env-file=.env lib/central-server.js
```

Perintah hosting `npm run start:central` mengandalkan environment runtime; perintah itu tidak membaca `.env` otomatis. Server central tidak membuka callback OAuth publik. Consent pemilik menggunakan helper localhost di atas.

## 5. Jalankan backend pada hosting Node 22 dengan HTTPS

Source dapat disiapkan dari root tanpa credential atau signing kit:

```sh
python3 scripts/prepare-admin-deploy.py --target render
```

Untuk contoh Render, unggah hanya folder staging yang dicetak script ke repository deployment. Buat **Web Service** dari repository tersebut, root directory `functions`, Node 22, build command `npm ci && npm run build`, dan start command `npm run start:central`. Gunakan URL HTTPS yang benar-benar diberikan host. [Panduan Render](https://render.com/docs/deploy-node-express-app).

Atur environment/secret host:

| Variable | Isi |
| --- | --- |
| `NODE_ENV` | `production` |
| `HOST` | `0.0.0.0` |
| `FIREBASE_PROJECT_ID` | `jayagunatehnikperkasa` |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | Isi JSON credential Firebase server, dimasukkan sebagai secret |
| `BACKEND_PUBLIC_URL` | Origin HTTPS aktual dari hosting |
| `ENFORCE_APP_CHECK` | `false`, sesuai client Android saat ini |
| `GOOGLE_DRIVE_ACCOUNT_EMAIL` | `mtahakurniawan@gmail.com` |
| Empat `GOOGLE_DRIVE_*_FOLDER_ID` | Nilai existing dari contoh lokal |
| `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET` | Client Web existing yang sama dengan workstation |
| `DRIVE_TOKEN_ENCRYPTION_KEY` | Kunci existing yang sama dengan workstation |

Biarkan host menyediakan `PORT`. Ketika menggunakan JSON secret, jangan mewariskan `GOOGLE_APPLICATION_CREDENTIALS` yang menunjuk file workstation atau Windows yang tidak ada pada host. Pada host, kosongkan/hapus `GOOGLE_OAUTH_REDIRECT_URI` lokal; source akan memakai default yang sesuai origin HTTPS. Persetujuan tetap melalui helper lokal, sehingga tidak perlu membuka callback tersebut pada server central.

Secret hanya disimpan pada workstation tepercaya dan secret storage hosting. APK menerima URL publik API; credential JSON, client secret, refresh token dan kunci enkripsi tetap di server. Arsip portable privat Android membawa signing key dan tidak digunakan sebagai paket hosting.

Notifikasi email tidak wajib untuk konfigurasi Drive. `RESEND_API_KEY` dan `ACCESS_REQUEST_FROM` dapat tetap kosong sesuai pilihan persetujuan manual. Cloud Functions tidak diperlukan untuk entrypoint Node eksternal ini. Paket Workers Cloudflare memerlukan penyesuaian runtime dan belum menjadi target source ini.

Render menyediakan pilihan Free untuk uji coba, dengan batas penggunaan dan penghentian proses saat idle. Sesudah sekitar 15 menit tanpa traffic, permintaan pertama dapat menunggu proses hidup kembali sekitar satu menit. Ini bukan jaminan layanan produksi selalu siap. [Batas Render Free](https://render.com/docs/free).

## 6. Masukkan URL backend ke APK dan periksa koneksi

Di `gradle.properties` pada root, tambahkan origin HTTPS aktual:

```properties
backendBaseUrl=https://HOST_BACKEND_AKTUAL
```

Bangun APK dari root:

```sh
python3 scripts/build.py debug
```

Untuk distribusi gunakan `python3 scripts/build.py release` setelah konfigurasi Google client sertifikat release juga diverifikasi. Kedua build tetap memakai signing kit proyek yang sama; konfigurasi URL tidak memerlukan sertifikat baru per HP.

Pada aplikasi, masuk dengan akun Google yang sudah approved lalu pilih **Periksa koneksi Drive**. Endpoint server `/api/drive/status` harus mengonfirmasi `connected: true` dan `foldersAccessible: true`. Endpoint publik `/healthz` hanya membuktikan proses server berjalan. Uji upload/baca/ganti/hapus setelah pemeriksaan Drive berhasil.

Jika pesan masih **belum dikonfigurasi**, periksa apakah URL masuk ke build APK terbaru. Jika proses hidup tetapi Drive gagal, periksa credential server, pasangan OAuth/kunci enkripsi, grant pemilik, serta izin keempat folder. Persetujuan akun aplikasi dan sambungan Drive perusahaan merupakan dua pemeriksaan terpisah.
