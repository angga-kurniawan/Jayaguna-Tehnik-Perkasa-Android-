# Build dari laptop dan perangkat berbeda

Proyek memakai dua signing key tetap: development untuk Android Studio/emulator dan release untuk APK yang dibagikan. Identitas APK mengikuti key tersebut, sehingga laptop lain harus memakai salinan kit yang sama. Tidak perlu membuat key atau mendaftarkan sertifikat baru untuk setiap HP.

Konfigurasi autentikasi versi 1.2.1 menggunakan [Google Sign-In dan izin Drive setelah login](LOGIN_DRIVE.md). Kit portable berisi konfigurasi publik Firebase serta signing key, tanpa token Drive atau credential server.

## Menyiapkan laptop

Pasang Python 3, Android Studio beserta Android SDK, dan **JDK 21**. `gradle/gradle-daemon-jvm.properties` menetapkan daemon Gradle ke JDK 21 yang tersedia pada laptop, termasuk ketika Android Studio memakai Java 25 sebagai launcher. Tidak ada path Mac yang ditanam pada pengaturan ini. Jika JDK 21 belum terpasang, pasang JDK 21 melalui Android Studio atau penyedia JDK sebelum build. [Kriteria JVM daemon Gradle](https://docs.gradle.org/current/userguide/gradle_daemon.html#sec:daemon_jvm_criteria).

Buka terminal pada folder proyek:

```sh
python3 scripts/build.py --check
python3 scripts/build.py debug
python3 scripts/build.py release
```

Pada Windows, gunakan `py` sebagai pengganti `python3`. Pemeriksaan `--check` menampilkan Java/SDK yang ditemukan serta ketersediaan konfigurasi signing. Build memilih Java/SDK laptop itu dan menyesuaikan `sdk.dir` lokal; tidak perlu menyalin path Mac atau mengubah source untuk laptop lain. Jika deteksi belum menemukan SDK/JDK, atur `ANDROID_HOME`/`JAVA_HOME` ke instalasi laptop tersebut.

Hasil debug: `app/build/outputs/apk/debug/app-debug.apk`. Hasil release: `app/build/outputs/apk/release/app-release.apk`. Bagi pengguna aplikasi, kirim **APK release**.

## Memindahkan proyek beserta signing kit

Pada laptop yang sudah memiliki kit lengkap, buat salinan privat:

```sh
python3 scripts/package-portable.py
```

Hasilnya `.artifacts/portable/Jayaguna-project-private.zip`. Arsip memuat source, `app/google-services.json`, tiga file kit berikut, dan konfigurasi release `keystore.properties`:

- `.signing/development.keystore`
- `.signing/jayaguna-release.jks`
- `.signing/public-fingerprints.json`

**ZIP berisi private key dan password signing, serta tidak dienkripsi.** Simpan cadangan privat di tempat terpisah dan transfer secara pribadi hanya kepada pengembang yang diberi wewenang. Jangan bagikan ZIP kepada pengguna aplikasi atau unggah ke repository publik. Folder `.signing/` dan `keystore.properties` sudah diabaikan Git; salinan dari Git saja belum membawa kit.

Ekstrak ZIP ke **folder proyek baru** pada laptop tujuan, lalu jalankan pemeriksaan dan build di atas. Arsip tidak membawa cache, konfigurasi IDE, `local.properties`, `.env` privat, atau credential backend. File konfigurasi Google Firebase native tetap disertakan. File `PORTABLE-PRIVATE-README.txt` di dalam ZIP menjelaskan isi privatnya.

Jangan menimpa atau mengganti key yang sudah ada. Jika kit hilang, pulihkan salinan yang sama dari cadangan. Packaging APK akan berhenti ketika key yang diperlukan belum tersedia atau fingerprint berbeda dari `signing-certificates.properties`. File ini berisi fingerprint publik, ikut dibawa dalam arsip, dan dapat disimpan dalam source. Membuat key baru akan mengubah identitas aplikasi. Script arsip hanya membaca kit dan tidak menghasilkan atau mengganti key.

## Sertifikat dan login Google

Nilai publik berikut dibaca dari `.signing/public-fingerprints.json` pada penyiapan **4 November 2026**:

| Build | SHA-1 |
|---|---|
| Development | `23:73:7C:72:1A:8A:74:21:0B:BA:C9:59:9A:DF:ED:38:97:AB:BD:F5` |
| Release | `40:1A:51:26:BA:5D:00:68:EA:23:F9:8D:78:D5:BC:26:D8:D1:D0:84` |

Pada project OAuth `jayagunatehnikperkasa-512819`, client Android harus cocok dengan package **`com.jayaguna.admin`** dan SHA-1 build yang dipakai. Client development existing sudah diketahui; client release masih perlu diperiksa. Karena project OAuth berbeda dari project Firebase, provider Google Firebase harus mengizinkan client eksternal melalui safelist. `googleWebClientId` pada `gradle.properties` memilih client Web publik existing dalam project OAuth tersebut. Langkah Console ada di [panduan login Google](GOOGLE_LOGIN_COMPANY_DRIVE.md#1-selaraskan-google-oauth-dengan-firebase).

Setiap HP/emulator memerlukan Google Play services dan akun Google; pengguna harus disetujui melalui Firestore. Setelah login approved, aplikasi otomatis meminta izin Drive untuk akun yang sama. Pemilik membagikan empat folder ke email tersebut sebagai Editor. Izin Google yang sudah diberikan digunakan kembali; token hanya berada di RAM. Backend publik tidak diperlukan untuk Drive Android. Lihat [konfigurasi login dan Drive](LOGIN_DRIVE.md).

## Pembaruan APK

Gunakan **key release yang sama** untuk semua pembaruan dan naikkan `versionCode` sebelum membagikan versi baru. Development dan release saat ini memakai package sama tetapi sertifikat berbeda; Android tidak menerima pembaruan langsung di antara keduanya. Tidak ada langkah uninstall otomatis. Untuk pengujian release, gunakan perangkat/profil pengujian yang belum memasang varian development, atau rencanakan perpindahan dengan mempertimbangkan data lokal. [Aturan signing dan pembaruan Android](https://developer.android.com/studio/publish/app-signing).

## Jika emulator terhubung Wi-Fi tetapi login gagal

Emulator yang tetap berjalan saat jaringan laptop berganti dapat masih memakai DNS jaringan lama. Pada pemeriksaan 4 November 2026, hal ini menyebabkan `securetoken.googleapis.com` tidak dapat di-resolve dan refresh sesi Firebase gagal, meskipun laptop dapat menjangkau Firebase. Memulai ulang emulator dengan DNS yang benar memulihkan sesi OWNER tanpa menghapus data.

Tutup emulator secara normal, kemudian lihat nama perangkat dan jalankan kembali dengan launcher proyek:

```sh
python3 scripts/start-emulator.py --list
python3 scripts/start-emulator.py NAMA_AVD
```

Pada Windows gunakan `py`. Launcher memakai SDK laptop, `-dns-server 8.8.8.8,1.1.1.1`, serta cold boot tanpa memuat snapshot lama. Isi penyimpanan aplikasi tetap dipertahankan. Pada jaringan yang memerlukan resolver lain, isi `--dns ALAMAT_DNS` dengan DNS yang diizinkan jaringan tersebut. Launcher tidak mengubah DNS laptop, menghapus akun, atau melakukan wipe data. [Panduan DNS Android Emulator](https://developer.android.com/studio/run/emulator-troubleshooting).
