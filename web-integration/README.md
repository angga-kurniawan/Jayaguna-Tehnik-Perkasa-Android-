# Integrasi website

Gambar Google Drive kini didukung melalui backend: isi `backendBaseUrl` di `firebase-config.js` dan tambahkan origin website ke `ALLOWED_ORIGINS` backend. Integrasi memakai endpoint `/api/media/{mediaId}` yang memeriksa proyeksi publik, bukan URL thumbnail Drive. Metadata `images`/`logo` dengan `storagePath: drive/{UUID}` ditangani otomatis; gambar Firebase Storage lama tetap memakai `getBlob`. Lihat [panduan Drive](../docs/GOOGLE_DRIVE.md). Contoh `mountCatalog` juga menerima `backendBaseUrl` jika memakai instance Firebase dari website yang sudah ada.

Modul ini dipasang ke **website yang sudah ada**, menggunakan project Firebase yang sama dengan Android. Source website belum tersedia di folder kerja, jadi tidak ada tampilan website lama yang diubah. `index.html` hanya contoh pemasangan mandiri.

## Konfigurasi

1. Daftarkan aplikasi **Web** pada project Firebase yang digunakan Android.
2. Salin `firebase-config.example.js` menjadi `firebase-config.js`; isi `apiKey`, `authDomain`, `projectId`, `storageBucket`, `appId` dari Firebase Console. Ini konfigurasi client publik. Jangan memasukkan service account, private key, refresh token, atau kredensial Admin SDK.
3. Daftarkan web app pada **App Check**, gunakan reCAPTCHA Enterprise, izinkan domain website pada site key, lalu isi `recaptchaEnterpriseSiteKey`.
4. Deploy backend, Firestore Rules, Storage Rules, dan index dari root proyek terlebih dahulu. Tunggu index selesai dibuat.
5. Salin `storage-cors.example.json` ke file konfigurasi deployment, ganti origin dengan domain website sesungguhnya, hapus origin localhost untuk produksi, lalu jalankan oleh operator yang memiliki akses bucket:

```sh
gcloud storage buckets update gs://YOUR_STORAGE_BUCKET --cors-file=storage-cors.production.json
```

CORS diperlukan karena gambar diunduh dengan SDK `getBlob`, yang mengevaluasi Storage Rules pada setiap unduhan. Tidak ada `getDownloadURL` atau bearer download token yang mengizinkan gambar draft dibaca melalui tautan permanen. CORS membatasi origin browser, sedangkan Security Rules menentukan hak akses data.

6. Aktifkan enforcement App Check untuk Firestore dan Storage setelah semua website/Android resmi terdaftar dan metrik menunjukkan request terverifikasi. Callable backend memverifikasi App Check sendiri; App Check melengkapi autentikasi dan aturan akses.

## Preview lokal

Memerlukan Node.js 22 atau lebih baru, browser modern, dan akses internet ke SDK resmi `www.gstatic.com`.

```sh
cd web-integration
npm test
npm run check
npm run dev
```

Buka `http://localhost:5173`. Modul browser menggunakan Firebase SDK **13.1.0** yang dipin konsisten pada seluruh import. Tidak ada npm dependency atau proses bundling untuk contoh ini. File `firebase-config.js` sengaja tidak disediakan dengan nilai fiktif yang terlihat seperti konfigurasi produksi.

Untuk emulator, isi project ID demo yang sama dengan Firebase Emulator Suite, `apiKey: "demo-key"`, `appId: "demo-web-app"`, `storageBucket: "demo-jayaguna.firebasestorage.app"`, dan set `useEmulators: true`; App Check dilewati hanya pada localhost. Emulator host/port: Firestore `127.0.0.1:8080`, Storage `127.0.0.1:9199`. Untuk localhost yang terhubung ke project sungguhan, gunakan `appCheckDebug: true`, daftarkan token debug yang dicetak SDK di Firebase Console, dan jangan memublikasikan token tersebut. Mode debug/emulator ditolak pada hostname nonlokal.

## Memasang pada website yang ada

Salin `catalog.js`, `helpers.js`, `firebase-client.js`, konfigurasi yang telah diisi, dan `catalog.css` ke direktori aset website. Tambahkan stylesheet dan dua mount point pada lokasi katalog dan logo mitra:

```html
<link rel="stylesheet" href="/assets/jayaguna/catalog.css">
<section class="jt-catalog" aria-label="Produk AC"><div id="jt-products"></div></section>
<section class="jt-catalog" aria-label="Mitra kami"><div id="jt-partners"></div></section>
<script type="module" src="/assets/jayaguna/integration.js"></script>
```

Isi `integration.js`:

```js
import { createCatalogFirebase } from "./firebase-client.js";
import { catalogConfig } from "./firebase-config.js";
import { mountCatalog } from "./catalog.js";

const catalog = mountCatalog({
  ...createCatalogFirebase(catalogConfig),
  productsRoot: document.getElementById("jt-products"),
  partnersRoot: document.getElementById("jt-partners"),
});
// Saat route SPA dilepas: catalog.destroy();
```

Jika website sudah menginisialisasi Firebase dan App Check pada project yang sama, gunakan ulang `{ db, storage }` milik website pada `mountCatalog`; tidak perlu memanggil `createCatalogFirebase`. Jangan menggunakan service/admin session untuk pengunjung. Tidak ada login atau anonymous sign-in pada modul publik ini.

Seluruh CSS komponen dibatasi `.jt-catalog`; header, footer, navigasi, dan stylesheet website lama tetap dapat dipakai. Nama/description/spesifikasi dari Firestore dipasang dengan `textContent`, bukan HTML. Tautan mitra hanya menerima HTTPS dan memakai `noopener noreferrer`. Logo menggunakan `object-fit: contain`, sehingga PNG transparan, JPEG, dan WebP tidak terpotong.

Jika website menggunakan bundler, ganti import URL Firebase dengan import paket modular `firebase/app`, `firebase/app-check`, `firebase/firestore`, dan `firebase/storage`, lalu install `firebase@13.1.0` dan masukkan perubahan lockfile pada proyek website. Jangan mencampur versi Firebase browser/module yang berbeda. SDK CDN di contoh ini mengikuti bentuk import browser resmi; bundler disarankan Firebase untuk optimasi ukuran produksi.

## Query dan siklus data

| Tampilan | Koleksi | Predikat server | Urutan |
|---|---|---|---|
| Produk | `publicProducts` | `status == "active"`, `archived == false` | `updatedAt desc`, ID desc |
| Mitra | `publicPartners` | `active == true`, `archived == false` | `displayOrder asc`, ID asc |

Backend hanya menulis proyeksi field publik ke kedua koleksi. UID pembuat/pengubah, perangkat, audit, akun, versi internal, dan draft berada pada koleksi privat. Client publik tidak mengambil seluruh `products`/`partners` untuk memfilter di browser. Security Rules memverifikasi predikat query.

Listener `onSnapshot` menampilkan perubahan publik setelah transaksi backend berhasil. Perubahan status menjadi draft/nonaktif atau arsip menghapus proyeksi publik; kartu hilang dari listener, dan Storage Rules menolak unduhan baru gambar yang tidak lagi direferensikan proyeksi publik. Salinan gambar yang sudah diunduh pengunjung tidak dapat ditarik kembali.

Pagination memakai jendela realtime berurutan yang bertambah 12 item, dengan satu record tambahan sebagai penanda halaman berikutnya. Batas 96 item yang ditampilkan bersamaan per bagian menjaga listener dan unduhan tetap terbatas serta memastikan query maksimum 97 record berada di bawah batas Security Rules 100 record. Tombol **Halaman berikutnya** meneruskan katalog setelah jendela penuh melalui cursor `startAfter`; **Kembali ke awal** mengulang dari awal. Semua record katalog dapat dijelajahi tanpa satu query tak terbatas. Setiap perluasan jendela membaca ulang hasil sebelumnya.

Perubahan urutan ditangani realtime di dalam jendela aktif. Saat pindah ke jendela berikutnya, cursor merekam posisi terakhir; produk yang kemudian berpindah ke sebelum cursor perlu dilihat dengan **Kembali ke awal**. Perilaku ini menjaga query terbatas ketika data berubah selama pengunjung menjelajah.

Status loading, kosong, error, retry, dan fallback gambar tersedia. Snapshot dari cache tidak ditampilkan sebelum dikonfirmasi server. Blob URL dilepas ketika snapshot berganti atau komponen dilepas. Batas unduhan gambar adalah 5 MiB per gambar; Storage dan backend membatasi ukuran serta tipe file saat upload.

## Pengujian integrasi yang harus dilakukan setelah konfigurasi

1. Masuk Android sebagai OWNER/ADMIN aktif; buat produk draft dengan foto. Pengunjung web dan request Firestore/Storage tanpa role tidak boleh membaca data/foto draft.
2. Aktifkan produk; setelah save dikonfirmasi server, kartu dan gambar muncul tanpa refresh. Ubah nama, foto utama, atau harga; listener memperbarui kartu.
3. Nonaktifkan/arsipkan produk; kartu hilang. Request baru terhadap storagePath lama ditolak bila tidak direferensikan publik lagi.
4. Tambah mitra PNG transparan; aktifkan dan ubah displayOrder. Logo tampil utuh dan urutan berubah realtime. Nonaktifkan/arsipkan: logo hilang.
5. Buat lebih dari 12 record publik; tombol berikutnya memperluas daftar tanpa duplikat. Ubah urutan/status di Android ketika daftar diperluas.
6. Uji akun tanpa claims, ADMIN, dan pengunjung membaca `users`, `activityLogs`, draft/private metadata. Pengunjung selalu ditolak; OWNER dan ADMIN mengikuti aturan masing-masing.
7. Putuskan koneksi; katalog menampilkan status koneksi. Sambungkan kembali dan gunakan retry jika diperlukan.

Tes Node dalam folder ini memverifikasi helper murni (batas pagination, format harga, path gambar, penolakan tautan berbahaya, pilihan gambar utama, pesan error). Tes tersebut tidak menggantikan pengujian browser realtime pada project atau Emulator Suite. Tidak ada deployment dilakukan oleh modul ini.

## Dokumentasi resmi yang diperiksa

- [Firebase modular Web SDK setup](https://firebase.google.com/docs/web/setup)
- [Firebase JavaScript release notes — 13.1.0](https://firebase.google.com/support/release-notes/js)
- [Storage direct SDK downloads and CORS](https://firebase.google.com/docs/storage/web/download-files)
- [App Check dengan reCAPTCHA Enterprise](https://firebase.google.com/docs/app-check/web/recaptcha-enterprise-provider)

Versi serta API diperiksa pada 27 Oktober 2026. Integrasi ini belum tersambung project sungguhan sebelum konfigurasi diisi.
