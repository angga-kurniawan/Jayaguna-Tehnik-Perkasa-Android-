# Navigasi dan pemulihan state — 1.1.1

Navigasi bawah dikembalikan ke navbar Material berlatar terang seperti sebelum versi 1.0.6, dengan ikon/label aktif merah, indikator lembut, dan lima label menu. Menu aktif ditandai untuk pembaca layar. Perbaikan Save/Restore State dan loading versi 1.0.6 tetap dipertahankan. Navigasi dinonaktifkan selama mutasi yang sedang menunggu konfirmasi agar pengguna tidak meninggalkan proses simpan tanpa penjelasan.

## Animasi dan logo rilis 1.1.1

Menu bawah memakai slide horizontal selama 280 ms, mengikuti urutan **Beranda → Produk → Mitra → Portofolio → Riwayat**. Memilih menu dengan posisi lebih kanan menggeser halaman lama ke kiri dan memasukkan halaman baru dari kanan; memilih menu lebih kiri membalik arah tersebut. Menekan menu yang sedang aktif tidak mengulang transisi. Halaman akun/admin dan editor berpindah langsung tanpa slide menu bawah.

Selama transisi, halaman keluar dan masuk memakai key rute yang berbeda agar state masing-masing tetap tersimpan. Perpindahan cepat memakai kembali key halaman yang sama, sehingga provider state tidak diduplikasi; arah mengikuti tab terakhir yang dipilih. Saat transisi diinterupsi, Compose mempertahankan momentum singkat lalu membalik gerakan tanpa melompat ke posisi akhir. Kontrol halaman yang sedang keluar tidak menerima interaksi, dan fokus/keyboard dilepas saat pindah halaman. Pembukaan pertama dan pemulihan Activity langsung menampilkan konten tersimpan. Ikon menu aktif tetap membesar halus menjadi 1,08× selama 160 ms; warna dan tata letak navbar tetap terang. Preferensi durasi animasi sistem tetap diperhatikan, tanpa menunda permintaan data.

Ikon launcher dan splash screen memakai logo perusahaan existing `app/src/main/res/drawable-nodpi/jayaguna_logo.png` berukuran 530×420 piksel, dengan latar putih. Foreground mempertahankan rasio gambar menggunakan inset kiri/kanan 20,37% dan atas/bawah 26,52%; file PNG aslinya tidak digambar ulang. Pemisahan foreground/background mengikuti [panduan adaptive icon Android](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).

Splash native memakai `androidx.core:core-splashscreen:1.2.0`, tema awal `AppStartingTheme` dan `installSplashScreen()` sebelum `super.onCreate()`. Saat konten siap ditampilkan, splash keluar dengan fade dan perubahan skala kecil selama 180 ms; tidak ada timer penahan splash atau Activity pembuka tambahan. Jika animasi sistem dinonaktifkan, splash langsung dilepas. Pendekatan ini mengikuti [panduan SplashScreen Android](https://developer.android.com/develop/ui/views/launch/splash-screen); versi dependency tercatat pada [rilis AndroidX Core](https://developer.android.com/jetpack/androidx/releases/core). Hasil pemeriksaan aktual tersedia di [VERIFICATION.md](VERIFICATION.md).

## Yang dipertahankan

`rememberSaveableStateHolder` menyimpan state UI setiap halaman ketika berpindah menu. `rememberSaveable`, saver formulir, serta state daftar/grid mempertahankan isian, filter yang sedang diketik, posisi scroll, dan halaman aktif. Editor memakai key unik per pembukaan sehingga draft produk atau mitra yang berbeda tidak tercampur. State editor dibersihkan setelah simpan berhasil atau pembatalan yang telah dikonfirmasi; logout membuang state sesi.

`AdminController` dan `PortfolioController` menyimpan halaman data yang sudah dikonfirmasi beserta cursor dalam ViewModel selama perpindahan navigasi dan rotasi. Kembali ke menu yang sudah dimuat menggunakan data tersebut. Gunakan **Perbarui** untuk mengambil perubahan dari perangkat lain. Mutasi lokal yang berhasil tetap memperbarui daftar terkait.

Snapshot kecil controller menyimpan filter yang sudah diterapkan, kategori portofolio, pilihan URI gambar, serta ID permintaan yang diperlukan untuk retry. Filter yang masih diketik boleh berbeda dari filter yang sudah diterapkan; pemulihan mempertahankan keduanya pada tempatnya.

Gambar mempunyai cache bersama terpisah dari state navigasi. Byte asli disimpan pada RAM maksimal 16 MiB dan disk privat maksimal 64 MiB; bitmap Coil memakai 20% heap dengan batas 48 MiB. Kembali ke tab memakai bitmap yang masih ada, atau byte yang sudah tersimpan, tanpa membaca Firestore/Drive lagi. Permintaan bersamaan berbagi satu unduhan. Key mencakup sesi dan path; gambar pengganti memakai path baru. Logout membersihkan cache dan hasil sesi lama tidak boleh mengisinya kembali. Detail ada di [panduan cache](NATIVE_DRIVE.md#cache-gambar-pada-hp).

Setelah proses aplikasi dibuat ulang oleh sistem, hanya state UI kecil yang dipulihkan; data server dan cursor dimuat ulang melalui SDK. Job jaringan, status sibuk, token, dan kata sandi tidak diserialisasi. Pemulihan tidak menjalankan upload, simpan, atau hapus secara otomatis. Periksa pilihan yang dipulihkan lalu tekan **Simpan** atau **Coba lagi** untuk melanjutkan. Bila izin URI foto tidak lagi tersedia, pilih ulang foto tersebut.

Daftar menunggu data pertama sebelum mengukur posisi yang dipulihkan. Saat Activity dibuat ulang, konten juga menunggu inset bilah sistem tersedia agar viewport awal tidak membatasi anchor scroll ke posisi yang keliru. Posisi pada halaman data yang belum dimuat ulang setelah proses mati tetap bergantung pada data yang kembali tersedia; perpindahan tab dan rotasi mempertahankan halaman yang sudah dimuat dalam ViewModel.

Saved instance state bergantung pada state yang disediakan Android. Hapus data aplikasi, uninstall, dan logout bukan jalur pemulihan draft. Pemisahan state UI kecil dan data server mengikuti [panduan penyimpanan state Compose](https://developer.android.com/develop/ui/compose/state-saving).

Cache gambar juga dapat dieviksikan atau dibersihkan Android; gambar pertama atau yang sudah tidak tersimpan masih dapat menampilkan loading. Ini tidak mengubah navbar terang versi 1.0.7 ataupun pemulihan data ViewModel dari versi 1.0.6.

Tekan gambar untuk membuka preview layar penuh pada foto produk, logo mitra, portofolio, dan foto lokal yang dipilih. Preview memakai cache bersama, mendukung cubit/ketuk dua kali serta tombol zoom/reset, dan ditutup melalui tombol **Tutup** atau Back Android. Membuka atau menutup preview tidak mengubah data maupun file gambar; halaman asal tetap menggunakan state navigasinya.

## Loading yang terlihat

- Pemuatan awal mempertahankan heading, toolbar dan filter halaman. Placeholder mengikuti bentuk konten: baris aktivitas/admin, kartu produk, grid mitra/portofolio, serta ringkasan beranda. Tidak menampilkan status kosong sebelum hasil diterima.
- Refresh dan pagination mempertahankan data lama dengan garis progres tipis di atas area halaman; indikator tetap terlihat ketika halaman di-scroll dan tidak menutupi kartu. Tidak ada panel mengambang atau spinner ganda di footer.
- Placeholder memakai state scroll terpisah agar daftar yang pendek saat loading tidak memotong posisi scroll data asli yang dipulihkan. Grid portofolio tetap menyimpan posisi per kategori.
- Upload dan simpan formulir memakai dialog berukuran maksimal 420 dp, menampilkan operasi dan progres sebenarnya, serta menahan interaksi sampai perubahan dikonfirmasi. Setelah transfer mencapai 100%, proses penyimpanan tetap ditampilkan sampai commit selesai.
- Pemeriksaan Drive berada di dalam kartu Google Drive; identitas dan formulir perangkat tetap tersedia. Error dan retry tetap berada di bagian halaman yang terkait.

## Pemeriksaan manual

1. Buka Produk, isi pencarian/filter, terapkan, lalu scroll. Pindah ke Mitra dan kembali; periksa isian, hasil, serta posisi scroll.
2. Buka formulir produk atau mitra, ubah beberapa isian, lalu rotasi HP. Periksa draft tetap ada. Batalkan melalui konfirmasi, buka formulir baru, dan pastikan draft sebelumnya tidak terbawa.
3. Di Portofolio, pilih kategori dan beberapa foto. Pindah menu lalu kembali; periksa kategori dan preview. Rotasi kembali untuk memeriksa pilihan.
4. Untuk pemulihan proses oleh sistem, simpan aplikasi di latar belakang lalu gunakan alat pengujian untuk menghentikan proses latar belakang dan buka dari Recent Apps. Periksa state UI pulih, data dimuat ulang, dan tidak ada mutasi otomatis. Jangan memakai **Force stop** sebagai padanan pengujian ini.
5. Dengan jaringan lambat, periksa loading awal, refresh, muat lebih banyak, serta transisi upload menuju penyimpanan. Putuskan jaringan dan periksa pesan gagal/retry.
6. Logout lalu login kembali dan pastikan draft sesi sebelumnya tidak muncul. Periksa navbar pada layar kecil, font besar, dan pembaca layar.
7. Pindah dari Beranda ke Produk/Mitra dan pastikan halaman bergeser ke kiri. Kembali ke Produk/Beranda dan periksa slide ke kanan; menekan tab aktif tidak mengulang animasi. Pindah tab cepat, rotasi layar dan nonaktifkan animasi sistem; periksa halaman, scroll dan cache tetap benar. Ikon dan splash logo perusahaan tetap diperiksa melalui launcher.

Tes instrumentasi Compose menggunakan Espresso `3.7.0`, runner `1.7.0`, dan ext.junit `1.3.0` dari keluarga rilis yang dicontohkan pada [catatan rilis AndroidX Test](https://developer.android.com/jetpack/androidx/releases/test). Dependency ini hanya untuk pengujian. Hasil build dan tes aktual dicatat terpisah di [VERIFICATION.md](VERIFICATION.md); langkah di atas adalah prosedur pemeriksaan, bukan klaim seluruhnya sudah dijalankan.
