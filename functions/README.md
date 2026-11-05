# Backend Firebase Jayaguna

Profil Android **1.2.0** memakai `src/central-server.ts`: login Google dan persetujuan Firestore, notifikasi permohonan akses ke pemilik, serta akses Drive perusahaan melalui token server. Jalankan `npm run build` lalu `npm run start:central` pada host yang sudah memiliki secret/config. Operator menghubungkan Drive melalui `npm run connect:company-drive -- --open` pada workstation tepercaya; pengguna aplikasi tidak memberi consent Drive. Persiapan source Render: `python3 scripts/prepare-admin-deploy.py --target render` dari root. Lihat [panduan 1.2.0](../docs/GOOGLE_LOGIN_COMPANY_DRIVE.md) untuk konfigurasi Google provider, rules, akun, callback lokal, dan email.

Bagian callable, bootstrap, dan bridge website di bawah menjelaskan jalur legacy yang dipertahankan untuk kompatibilitas. Gunakan entrypoint central untuk APK baru; jangan menjalankan bootstrap OWNER ulang atau menyalin konfigurasi App Check bridge ke APK.

Integrasi Google Drive menambahkan API Node.js standalone pada `src/server.ts`, memakai service bisnis yang sama. Panduan konfigurasi, OAuth, cleanup dan deployment ada di [docs/GOOGLE_DRIVE.md](../docs/GOOGLE_DRIVE.md). Jalankan `npm run build`, lalu `npm run start:api` setelah `.env` dan ADC diisi. Jalur Cloud Functions/Storage di bawah tetap untuk kompatibilitas lama; backend Drive dapat dihosting pada server Node.js HTTPS terpisah.

Backend TypeScript menggunakan callable Cloud Functions generasi 2, Firebase Admin SDK, Cloud Firestore dan Storage. Region `asia-southeast2` harus sama dengan konfigurasi Android. Tidak ada endpoint publik untuk membuat akun berperan. Semua callable memeriksa token Firebase, custom claim `role`, dokumen `users/{uid}.active`, kesamaan role, status Auth, dan waktu pencabutan sesi. Semua operasi sensitif memperbarui otorisasi lagi di dalam transaksi.

## Konfigurasi dan deploy

1. Buat/pilih project Firebase **yang sama** untuk Android dan website. Aktifkan Firestore default database, Storage, Authentication Email/Password dan Cloud Functions. Cloud Functions/Storage memerlukan billing yang sesuai. Jangan menjalankan `firebase init` dengan menimpa rules/schema produksi yang sudah ada.
2. Gunakan Node.js **22** untuk development/deployment; versi runtime dipatok di `firebase.json` dan `package.json`. Mesin yang membuat proyek ini memiliki Node24; lihat catatan verifikasi di README utama.
3. Dari folder ini: `npm.cmd ci` di Windows, atau `npm ci`. Dari root: `firebase login`, lalu `firebase use --add` dan pilih project. `.firebaserc.example` hanya contoh; jangan deploy ke placeholder.
4. Daftarkan Android package `com.jayaguna.admin` sesuai `applicationId` proyek, unduh `google-services.json` ke `app/google-services.json`. Tidak diperlukan service-account key di aplikasi/website.
5. Aktifkan App Check Android dengan Play Integrity, daftarkan SHA-256 sertifikat signing dan konfigurasi integritas. Untuk build debug, daftarkan debug token yang dihasilkan SDK pada Console; jangan menaruh token debug pada release. Callable memaksa App Check di produksi. Aktifkan enforcement Firestore/Storage sesudah aplikasi terdaftar; App Check tidak menggantikan Auth/Rules. Untuk website, konfigurasi App Check provider dan domain sesuai README integrasi web sebelum enforcement.
6. `npm.cmd --prefix functions run build` dari root. Tinjau `firestore.rules`, `storage.rules`, `firestore.indexes.json`, lalu jalankan `firebase deploy --only functions,firestore:rules,firestore:indexes,storage --project PROJECT_ID`. Deploy merupakan tindakan operator, belum dijalankan dalam implementasi ini.
7. Storage Rules memakai Firestore untuk otorisasi. Saat deploy pertama, izinkan konfigurasi IAM lintas layanan yang diminta Firebase CLI. Jangan membuat bucket menjadi publik. Konfigurasi CORS origin website seperti README `web-integration` agar `getBlob` dapat mengambil gambar melalui Rules.

Referensi resmi yang diperiksa: [runtime Functions Node22](https://firebase.google.com/docs/functions/manage-functions), [App Check callable](https://firebase.google.com/docs/app-check/cloud-functions), [Storage Rules lintas Firestore](https://firebase.google.com/docs/storage/security/rules-conditions), [pencabutan sesi Auth](https://firebase.google.com/docs/auth/admin/manage-sessions). Versi library dipatok dan lockfile tersedia; dependensi backend `firebase-admin 13.5.0`, `firebase-functions 6.4.0`, `sharp 0.35.5`, `zod 3.25.76`, `typescript 5.8.3`. Sharp diperbarui pada refaktor 1.2.0 untuk memperbaiki advisori decoder gambar; format dan byte unggahan central tetap dipertahankan.

## Bootstrap OWNER pertama

Di workstation tepercaya dengan IAM minimum yang diperlukan, gunakan Application Default Credentials dari credential server di luar repo atau attached IAM. ADC end-user untuk Firebase Auth Admin memerlukan OAuth Desktop client sendiri; login gcloud dengan client bawaan tidak cukup. Detail ada pada panduan Drive. Lalu dari `functions`:

```powershell
npm.cmd run bootstrap -- PROJECT_ID owner@perusahaan.co.id "Nama Owner"
```

Script meminta project ID sebagai konfirmasi, memperoleh lock transaksi `system/bootstrapOwner`, menolak bootstrap jika sudah ada OWNER, membuat/menonaktifkan sementara Auth user, memasang custom claim OWNER dan membuat profil sebelum mengaktifkan akun. Owner menggunakan **Lupa Password** di aplikasi untuk menerima email dan mengatur password. Password maupun reset link tidak dicetak. ADC disimpan alat Google Cloud di workstation, tidak disalin ke repo/APK/website.

Jika script gagal, lock sengaja tetap ada. Operator perlu memeriksa Auth user, profil `users`, IAM, dan lock sebelum pemulihan manual. Jangan menghapus lock lalu menjalankan ulang tanpa pemeriksaan. Jangan membuat OWNER melalui client atau menulis claim dari aplikasi.

OWNER membuat ADMIN melalui `manageAdmin`. Backend memberi claim ADMIN dan password acak yang tidak pernah dikembalikan. Android dapat meminta Firebase Auth mengirim email reset sesudah akun berhasil dibuat; bila email gagal, akun tetap sudah dibuat dan pengguna dapat memakai Lupa Password. Tidak ada formulir pendaftaran publik. Firebase Auth user yang dibuat tanpa claim/profil tidak memiliki akses data internal.

## Kontrak callable

Nama callable: `getSession`, `getDashboard`, `beginUpload`, `saveProduct`, `savePartner`, `archiveProduct`, `archivePartner`, `manageAdmin`. Semua mutasi memerlukan `requestId` UUID dan `device`:

```json
{
  "installationId": "UUID-acak-lokal",
  "deviceName": "Ponsel Kantor",
  "brand": "Samsung",
  "model": "SM-…",
  "androidVersion": "15",
  "appVersion": "1.0"
}
```

`getSession` mengembalikan `{uid,email,name,role}`. `getDashboard` mengembalikan `{activeProducts,draftProducts,activePartners,recent}`; draftProducts mencakup draft dan inactive yang belum diarsipkan. OWNER melihat aktivitas semua akun, ADMIN hanya aktivitasnya sendiri.

`saveProduct`/`savePartner`: `{requestId,device,id,expectedVersion,data}`. `id` UUID stabil yang dibuat client; `expectedVersion:0` untuk baru, versi yang terakhir dibaca untuk edit. Hasil `{id,version}`. Client harus membaca ulang dokumen agar memperoleh storage path yang sudah dipromosikan. Jangan mengirim metadata server seperti createdAt, version, UID atau archived dalam `data`; schema ketat akan menolak field tambahan.

`archiveProduct`/`archivePartner`: `{requestId,device,id,expectedVersion}`; hasil `{id,version}`. Arsip menghapus proyeksi publik secara atomik dan menyimpan dokumen internal beserta audit.

`manageAdmin`: `{requestId,device,operation:"create",name,email}` atau `{requestId,device,operation:"setActive",uid,active}`. Hanya OWNER, target harus ADMIN, akun sendiri dan OWNER tidak dapat diubah. Hasil `{uid,email}`. Auth dan Firestore tidak dapat ditransaksikan bersama: create memulai Auth disabled, transaksi profil+audit+receipt kemudian rekonsiliasi Auth; perubahan status profil adalah otoritas akses langsung. Menonaktifkan memasang cutoff `revokedBeforeSeconds` dan active=false atomik, kemudian menonaktifkan Auth dan mencabut refresh token. Mengaktifkan mempertahankan cutoff sehingga sesi lama tidak hidup lagi. Bila rekonsiliasi Auth gagal, **retry isi request dan requestId yang sama**. Profil/audit yang telah committed tetap sah; Auth yang masih disabled menyebabkan akun gagal masuk secara aman. Tidak ada promosi role melalui API ini.

Semua requestId di-scope per aktor. Receipt menyimpan fingerprint payload lengkap: retry identik mengembalikan hasil sebelumnya, requestId dengan isi berbeda ditolak `already-exists`. Receipt audit disimpan permanen; jangan memasang TTL tanpa menetapkan batas retry dan retensi yang disepakati. Konflik versi menghasilkan `aborted`; client harus memuat data terbaru dan meminta pengguna memeriksa edit, tidak mengganti versi diam-diam. Kesalahan jaringan tidak membuktikan gagal commit: pertahankan requestId saat retry. Tidak ada antrean mutasi offline.

## Skema koleksi

| Koleksi | Isi dan akses |
| --- | --- |
| `products/{id}` | Data lengkap produk, `searchTokens`, `archived`, `version`, `createdAt`, `updatedAt`, `createdBy`, `updatedBy`. Hanya staff aktif membaca; client tidak menulis. |
| `partners/{id}` | Data lengkap mitra, `archived`, `version`, timestamp dan UID. Hanya staff aktif membaca. |
| `publicProducts/{id}` | Field produk untuk website, `status:"active"`, `archived:false`, `mediaPaths`, timestamp. Tidak ada UID/perangkat/version/audit/searchTokens. |
| `publicPartners/{id}` | Field mitra untuk website, `active:true`, `archived:false`, `mediaPaths`, timestamp. Tidak ada UID/perangkat/audit. |
| `users/{uid}` | `uid,name,email,role,active,version,revokedBeforeSeconds`, timestamp. OWNER membaca akun lain; ADMIN hanya profil sendiri. |
| `activityLogs/{actorUid_requestId}` | Identitas terverifikasi, role saat operasi, action/entity/changedFields/before/after, timestamp server, requestId dan device. OWNER membaca semua; ADMIN hanya miliknya. Tidak dapat ditulis/diubah/dihapus client. |
| `operationReceipts/{actorUid_requestId}` | Fingerprint payload dan hasil idempoten. Backend saja. |
| `uploadSessions/{uid_requestId}` | Lease24jam, MIME/bytes/path/entity yang diizinkan. Backend saja. |
| `system/bootstrapOwner` | Lock bootstrap OWNER. Backend/operator saja. |

Product `data`: `name`, `brand`, `model`, `category` (`split,cassette,standing,ducted,central,other`), `capacityPk` string, `condition` (`new,used`), `price` number/null, `description`, `specifications` map string, `images` array `{storagePath,url:""}`, `mainImagePath`, `status` (`draft,active,inactive`). Maksimum8foto, harga0–1e12, deskripsi10000karakter, spesifikasi50field. Produk aktif wajib foto. `searchTokens` dibentuk backend untuk prefix nama/merek dan kata-katanya, lowercase, hingga50karakter per token dan maksimum1000token. Ini pencarian prefix, bukan substring/full-text search; website tidak mengakses metadata pencarian internal.

Partner `data`: `name`, `logo:{storagePath,url:""}`, `altText`, `website` HTTPS atau kosong, `displayOrder` integer0–1000000, `active`. Logo selalu wajib agar aktivasi tidak menghasilkan data rusak. Semua query client memiliki `limit<=100`; website menambahkan status/active dan archived pada query. Rules bukan filter. Pagination internal menggunakan cursor snapshot; indeks mencakup semua kombinasi search/category/status produk, pengguna bernama (single index otomatis), tanggal dan kombinasi aktor/jenis/aksi aktivitas, serta query publik.

Audit `changedFields` berbentuk `{namaField:{before,after}}` dengan salinan before/after di top-level. Aksi: `add`, `edit`, `status`, `archive`; jenis: `product`, `partner`, `user`. Metadata perangkat tersedia nested `device` dan flat untuk kompatibilitas. Informasi nama/model/OS berasal dari client, **bukan bukti identitas perangkat**. installationId acak dapat berubah setelah reinstall/reset data, tidak memakai IMEI/MAC/telepon. Password, token, secret dan reset link tidak dicatat.

## Siklus gambar dan kegagalan

1. Client memvalidasi PNG/JPEG/WebP, maksimum **5 MiB** tiap berkas. `beginUpload` menerima `{requestId,device,entityType,entityId,contentType,bytes}` dan mengembalikan `storagePath` staging.
2. Client mengunggah menggunakan Storage SDK ke path tersebut, menampilkan progres dan baru memanggil save setelah unggahan lengkap. Rules hanya mengizinkan create oleh pemilik sesi, byte/MIME cocok, sesi belum kadaluarsa, akun masih aktif. Tidak ada overwrite/delete client. Retry upload yang belum selesai diperbolehkan; jika objek sudah lengkap, gunakan referensi sebelumnya atau sesi baru.
3. Save memeriksa kepemilikan sesi/entity, jumlah byte, MIME, magic signature dan decoding gambar. Sharp membatasi40MP input, menormalkan orientasi, membatasi4096px, menghapus metadata/EXIF dan menulis ulang PNG/JPEG/WebP. Decode dilakukan berurutan untuk membatasi memori. Hasil melebihi5MiB ditolak.
4. Backend menyimpan media immutable `media/{product|partner}/{entityId}/{sha256}.{ext}` tanpa Firebase download token, dengan cache privat. Setelah itu transaksi Firestore menulis entity + proyeksi publik + audit + receipt. Sukses hanya setelah server commit. Kegagalan promosi/konflik tidak mengubah entity atau menghasilkan audit palsu.
5. Website memakai `getBlob`, Android memakai `getBytes` melalui Storage SDK. **Jangan memakai getDownloadURL atau menyimpan bearer download token**: URL itu dapat melewati pencabutan publikasi Rules. Field url dipertahankan kosong, storagePath sumber utama. File draft hanya staff; file publik hanya path yang masih ada di `mediaPaths` proyeksi aktif.
6. Jadwal `cleanupUploads` membersihkan staging setelah48jam; sesi save hanya valid24jam. Media final yang menjadi orphan karena konflik/replacement sengaja dipertahankan untuk keamanan referensi dan audit. Gambar yang diganti/diarsip tetap tersimpan privat, tidak lagi dapat dibaca publik. Tidak ada penghapusan otomatis final: menghindari race menghapus file yang dipakai versi/operasi lain. Tentukan retensi biaya operasional. Jika pembersihan final diperlukan, lakukan maintenance saat penulisan dibekukan, inventaris semua referensi internal+publik+operasi yang masih berjalan, beri masa tenggang dan verifikasi ulang sebelum menghapus; jangan menghapus berdasarkan hilangnya satu foto dari UI.

## Pengujian lokal

```powershell
npm.cmd --prefix functions ci
npm.cmd --prefix functions test
firebase.cmd emulators:exec --only auth,firestore,storage --project demo-jayaguna "npm.cmd --prefix functions run test:emulator"
```

Emulator memerlukan Java21 dan unduhan JAR pertama kali. Menggunakan project demo sehingga layanan yang tidak diemulasi tidak mengakses produksi. Pengujian unit memeriksa validasi, proyeksi aman, diff, hash idempotensi dan prefix. Pengujian integrasi menjalankan transaksi Admin SDK nyata pada emulator bersama Rules client: tanpa role, eskalasi ADMIN, audit palsu, metadata privat, query publik, revokasi sesi lama, retry dan konflik, unggah gagal/palsu, foto publik berubah privat dan realtime produk+mitra. App Check produksi dan pengiriman reset email perlu pengujian staging manual, bukan emulator yang mem-bypass App Check.

Sebelum rollout, lakukan uji perangkat Android nyata dengan akun OWNER/ADMIN/nonrole, jaringan terputus, App Check release, beberapa foto besar, dua perangkat mengedit versi sama, dan website pada origin CORS sebenarnya. Jangan menyimpulkan deploy atau build Android berhasil dari lulusnya unit backend.
