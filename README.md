# GlassTV Launcher

Prototipe launcher Android TV bergaya dark glassmorphism untuk MiTV-MOSR1 / Android 11.

## Status dan batasan yang harus diketahui

- Ini adalah **starter project**, bukan produk yang sudah diuji di perangkat.
- UI awal menggunakan panel gelap dan kartu semi-transparan sederhana. Blur kaca tingkat lanjut belum diterapkan.
- Tombol Live TV mencoba aplikasi tuner Xiaomi/Android TV yang umum, lalu mencari aplikasi TV yang terpasang jika package vendor berbeda. Ini membuka aplikasi tuner sistem/vendor, bukan mengambil output tuner langsung di dalam launcher.
- Halaman Kamera CCTV menyediakan monitor RTSP 2×2 di dalam launcher, pemilihan kamera layar penuh, pengaturan nama, dan penyimpanan URL secara lokal di TV. Ini belum mengimplementasikan penemuan kamera otomatis melalui ONVIF. Dukungan stream bergantung pada codec kamera dan decoder perangkat; Media3 mendukung format RTSP umum seperti H.264, tetapi tidak semua stream akan kompatibel.
- `config.xml` saat ini merupakan template dokumentasi; aplikasi belum membaca file tersebut secara otomatis.
- Backup script mengumpulkan inventaris dan konfigurasi ADB, bukan backup firmware/data aplikasi penuh.
- Debloat tidak dijalankan oleh skrip mana pun. Jangan menonaktifkan paket sistem secara massal.
- APK **debug** thumbnail-test memakai ID paket `id.glasstv.launcher.dashboardtest` supaya dapat dipasang berdampingan dengan aplikasi lama `id.glasstv.launcher`. Dengan begitu, pengujian tidak memerlukan uninstall atau penghapusan data aplikasi lama. Data kedua aplikasi terpisah.

## URL kamera dan kredensial (lokal saja)

- Jangan simpan URL RTSP lengkap, alamat IP kamera, username, atau password kamera di source code, `config.xml`, README, screenshot, atau commit Git.
- `config.xml` pada repositori publik sengaja berisi empat slot kosong sebagai contoh. Aplikasi saat dijalankan menyimpan URL yang dimasukkan pengguna di SharedPreferences lokal pada TV; nilai yang sudah tersimpan tidak dihapus oleh perubahan template source.
- File lokal seperti `config.local.xml` dan `camera-config.local.xml` diabaikan oleh Git melalui `.gitignore`. Jangan menghapus aturan tersebut untuk mengunggah konfigurasi lokal.
- Jika suatu password/URL pernah dipublikasikan di commit lama, menghapusnya dari file terbaru tidak menghapus salinan pada riwayat Git. Ganti password kamera yang pernah terpapar, kemudian perbarui URL RTSP dari menu **Kamera CCTV** pada TV. Jangan uninstall launcher atau hapus data aplikasinya demi langkah ini.

## Prasyarat

- JDK 17
- Android SDK Platform 35 dan Build Tools yang sesuai (untuk build lokal)
- Android platform-tools (`adb`)
- Windows PowerShell 5.1 atau PowerShell 7 untuk skrip `tools/*.ps1`

## Build lokal

Dari root proyek:

```powershell
gradle --version
gradle assembleDebug
```

APK keluaran: `app\build\outputs\apk\debug\app-debug.apk`.

Jika Anda memakai Android Studio, buka folder proyek ini dan biarkan Gradle Sync selesai. Untuk build lokal yang dapat direproduksi, tambahkan Gradle Wrapper melalui instalasi Gradle yang terpercaya:

```powershell
gradle wrapper --gradle-version 8.9
```

## Membuat repositori GitHub

1. Masuk ke GitHub dan pilih **New repository**.
2. Nama: `GlassTV-Launcher`; pilih Private bila ingin source tidak publik.
3. Jangan centang inisialisasi README jika Anda akan mengunggah seluruh folder proyek ini.
4. Ekstrak ZIP proyek, buka terminal di folder `GlassTV-Launcher`, lalu jalankan:

```powershell
git init
git add .
git commit -m "Initial GlassTV Launcher prototype"
git branch -M main
git remote add origin https://github.com/USERNAME/GlassTV-Launcher.git
git push -u origin main
```

Ganti `USERNAME` dengan nama akun GitHub Anda. Jangan memasukkan token GitHub ke file source atau chat.

Workflow `.github/workflows/android-build.yml` akan berjalan setelah push. Buka tab **Actions**, pilih run yang berhasil, lalu unduh artifact `GlassTV-Launcher-debug`. Artifact adalah APK debug untuk pengujian, bukan rilis bertanda tangan untuk distribusi.

## Hubungkan TV melalui ADB

Aktifkan Developer options dan Network debugging/ADB di TV. Dapatkan alamat IP dari pengaturan jaringan TV, lalu:

```powershell
adb connect IP_TV:5555
adb devices
adb -s IP_TV:5555 shell getprop ro.build.version.release
adb -s IP_TV:5555 shell getprop ro.product.model
```

Port dan mekanisme pairing dapat berbeda menurut firmware. Jangan membuka ADB ke internet; gunakan jaringan lokal tepercaya.

## Inventarisasi dan backup sebelum instalasi

```powershell
.\tools\inventory.ps1 -Device "192.168.1.50:5555"
```

Ganti alamat contoh dengan alamat TV Anda. Simpan folder hasil di komputer, bukan hanya di repositori publik. File berisi informasi perangkat dan konfigurasi; jangan unggah tanpa meninjau privasi.

## Instalasi dan screenshot

Build lokal:

```powershell
.\tools\build-install.ps1 -Device "192.168.1.50:5555"
```

Jika APK diunduh dari GitHub Actions, instal secara manual:

```powershell
adb -s 192.168.1.50:5555 install -r .\app-debug.apk
adb -s 192.168.1.50:5555 shell cmd notification allow_listener id.glasstv.launcher.dashboardtest/id.glasstv.launcher.PlaybackListenerService 0
adb -s 192.168.1.50:5555 shell am start -n id.glasstv.launcher.dashboardtest/id.glasstv.launcher.MainActivity
```

Screenshot:

```powershell
.\tools\screenshot.ps1 -Device "192.168.1.50:5555"
```

## Mengatur launcher Home

Jangan langsung mengunci launcher ini sebagai Home default. Pertama uji D-pad, Back, aplikasi lain, dan akses kembali ke launcher sistem. Pilih GlassTV Launcher dari pemilih Home Android jika muncul. Bila launcher gagal, buka Settings aplikasi/Default apps pada TV dan pilih launcher sistem. Skrip build-install menyediakan opsi `-SetAsHome` yang hanya membuka pemilih Home; hasilnya tetap bergantung pada firmware.

## Restore paket yang dinonaktifkan

Tidak ada skrip yang menonaktifkan paket. Jika Anda kelak menonaktifkan paket secara manual, simpan satu nama paket per baris dalam file teks dan jalankan:

```powershell
.\tools\restore.ps1 -Device "192.168.1.50:5555" -DisabledList ".\disabled-packages.txt"
```

Jalankan hanya untuk paket yang sebelumnya Anda nonaktifkan dan verifikasi satu per satu. Perintah `pm enable` tidak memulihkan data aplikasi atau perubahan lain.

## Continue Watching (preview)

Versi 0.3.0 menambahkan baris **Lanjutkan Menonton** yang mencoba membaca metadata dari sesi media Android untuk YouTube dan SmartTube. Ini bukan integrasi riwayat akun privat dan tidak dijamin bekerja pada semua versi aplikasi/firmware.

1. Buka GlassTV Launcher, lalu pilih kartu **Lanjutkan Menonton**.
2. Pada pengaturan Android, aktifkan akses notifikasi untuk **GlassTV Continue Watching**. Launcher hanya membaca metadata sesi media, bukan isi teks notifikasi.
3. Jika memakai APK debug/preview, izinkan akses notifikasi untuk paket preview. Untuk MiTV yang tidak menyediakan halaman pengaturan standar, jalankan:
   ```powershell
   adb -s IP_TV:5555 shell cmd notification allow_listener id.glasstv.launcher.dashboardtest/id.glasstv.launcher.PlaybackListenerService 0
   ```
4. Putar video berdurasi minimal 3 menit dan tunggu sampai posisi pemutaran melewati 15 detik.
5. Kembali ke launcher. Jika aplikasi menerbitkan metadata dan posisi sesi media yang kompatibel, judul dan progress akan muncul. Pilih kartu untuk membuka aplikasi sumber.
6. Melanjutkan tepat di posisi terakhir bergantung pada kemampuan aplikasi sumber; launcher hanya membuka aplikasinya dan tidak dapat memaksa seek/resume.

Batasan:
- Akses notifikasi bersifat opsional dan harus diaktifkan pengguna secara manual.
- Tidak semua build YouTube/SmartTube mempublikasikan judul, durasi, atau posisi dengan cara yang dapat dibaca launcher.
- Jika tidak ada data yang kompatibel, kartu tetap menampilkan petunjuk aktivasi, bukan data tontonan palsu.
- **Uji perangkat (28 Oktober 2026):** pengguna mengonfirmasi kartu berfungsi pada MiTV-MOSR1 Android 11 menggunakan build debug terpisah. Log menunjukkan `Saved real thumbnail for org.smarttube.stable using metadata_uri (640x360)`; pengguna juga mengonfirmasi dashboard menampilkan gambar setelah kembali dari SmartTube.
- Hasil uji ini berlaku untuk kombinasi perangkat, firmware, dan versi SmartTube yang dicoba; aplikasi/firmware lain dapat menerbitkan metadata yang berbeda.

## Roadmap

1. Uji monitor CCTV 2×2 di MiTV-MOSR1 untuk memastikan empat stream dapat didekode bersamaan dan fokus remote nyaman.
2. Verifikasi aplikasi Live TV yang benar pada firmware MiTV; bila belum ditemukan, inventaris package/activity dari perangkat diperlukan.
3. Rapikan alur rilis untuk paket utama `id.glasstv.launcher` tanpa kehilangan data dari instalasi bertanda tangan berbeda.
4. Evaluasi Watch Next Android TV bila firmware/aplikasi menyediakan data yang dapat diakses launcher.
