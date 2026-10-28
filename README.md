# GlassTV Launcher

Prototipe launcher Android TV bergaya dark glassmorphism untuk MiTV-MOSR1 / Android 11.

## Status dan batasan yang harus diketahui

- Ini adalah **starter project**, bukan produk yang sudah diuji di perangkat.
- UI awal menggunakan panel gelap dan kartu semi-transparan sederhana. Blur kaca tingkat lanjut belum diterapkan.
- Android launcher biasa tidak otomatis dapat mengambil output tuner Live TV internal. Integrasi tersebut memerlukan pemeriksaan aplikasi/vendor API pada firmware MiTV.
- Tombol kamera menerima URL RTSP dan meneruskannya ke aplikasi eksternal. Ini belum merupakan pemutar RTSP tertanam dan belum mengimplementasikan ONVIF discovery.
- `config.xml` saat ini merupakan template dokumentasi; aplikasi belum membaca file tersebut secara otomatis.
- Backup script mengumpulkan inventaris dan konfigurasi ADB, bukan backup firmware/data aplikasi penuh.
- Debloat tidak dijalankan oleh skrip mana pun. Jangan menonaktifkan paket sistem secara massal.

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
adb -s 192.168.1.50:5555 shell monkey -p id.glasstv.launcher 1
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

## Roadmap

1. Inventarisasi TV dan identifikasi cara akses sumber Live TV internal.
2. Verifikasi build di GitHub Actions dan instalasi di TV.
3. Tambahkan pemutaran background yang benar-benar teruji.
4. Tambahkan penyimpanan konfigurasi kamera dan pemutar RTSP tertanam yang kompatibel.
5. Perbaiki navigasi remote, accessibility, error states, dan pengaturan launcher.
