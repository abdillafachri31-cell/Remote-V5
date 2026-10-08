# Arunika Remote — Tanpa Hosting (Prototype v0.2)

**Target:** Redmi Note 10 Pro MIUI 14/Android 13 sebagai perangkat yang dikendalikan, Safari pada iPhone sebagai pengendali. **Tidak ada hosting Render/Netlify/relay yang harus kamu deploy.** Server halaman kontrol ada **di dalam aplikasi Android** dan hanya membuka port pada antarmuka VPN Tailscale. Koneksi VPN Tailscale dipakai agar Redmi dan iPhone bisa berbeda jaringan, tanpa membuka port router.

> **STATUS NYATA:** ini **proyek sumber yang disiapkan untuk dibangun menjadi APK**, bukan file APK siap instal. Belum bisa dikompilasi atau diuji di Redmi/iPhone asli pada lingkungan penyusunan ini karena Android SDK/Gradle dan perangkat fisik tidak tersedia. Tidak dijamin sudah berfungsi sampai APK berhasil dibangun, diperbaiki jika perlu, dan diuji.

## Apa yang berbeda dari v0.1

- Tidak lagi ada Node.js, token server, WSS relay, konfigurasi domain, atau hosting.
- Arunika Android membangkitkan kode akses acak 128-bit (32 karakter heksadesimal).
- Android melayani halaman Safari pada `http://ALAMAT_TAILSCALE_REDMI:18765` dengan server HTTP lokal.
- Server **hanya** mendengarkan di alamat VPN Tailscale pada interface Android `tun*`, `wg*`, atau `tailscale*`, bukan di 0.0.0.0, Wi-Fi publik atau internet terbuka.
- Sesi diotorisasi per permintaan menggunakan kode akses di header `X-Arunika-Key`. Tidak ada token pada URL dan tidak ada penyimpanan screenshot di server eksternal.
- Screenshot menggunakan `AccessibilityService.takeScreenshot()`; tap/swipe melalui `dispatchGesture()`. Tanpa root dan tidak memakai MediaProjection.
- Notifikasi tampil selama layanan aktif; tombol hentikan secara lokal; kode akses bisa diganti di Android.

## Cara pakai setelah APK tersedia

1. Instal **Tailscale** dari Play Store pada **Redmi** dan dari App Store pada **iPhone**. Login kedua perangkat dengan akun Tailscale yang sama, lalu aktifkan koneksi VPN-nya. Tailscale menyiapkan jaringan privat antarkedua HP. Ini konfigurasi satu kali, bukan hosting.
2. Instal **APK Arunika Remote** di Redmi (setelah APK berhasil dibangun). Buka, izinkan notifikasi, lalu buka pengaturan Aksesibilitas dan aktifkan layanan `Arunika Remote - kontrol jarak jauh` atas persetujuanmu. Pada beberapa perangkat MIUI, perlu mengizinkan `Allow restricted settings` untuk aplikasi sideload.
3. Tekan `Aktifkan remote` di Redmi. Jika VPN aktif dan sistem mengizinkan, aplikasi menampilkan alamat mirip `http://100.x.y.z:18765` serta **kode akses**.
4. Buka alamat itu di Safari iPhone. Masukkan kode akses yang tampil di Android. Sekarang kamu dapat melihat screenshot berkala dan melakukan tap/swipe, Back, Home, Recent, serta membuka notifikasi.
5. Untuk berhenti, tekan `HENTIKAN REMOTE` di Redmi atau notifikasinya. Jika ingin membatalkan kode yang mungkin bocor, tekan `Ganti kode akses`.

### Bukan jaminan unattended selamanya

- Ini menghilangkan **alur persetujuan MediaProjection per koneksi**, bukan seluruh proteksi Android. Pengaktifan Aksesibilitas di awal tetap wajib.
- Tailscale harus tersambung pada kedua HP. MIUI Battery Saver bisa memutus Tailscale atau layanan aksesibilitas: atur **tanpa pembatasan baterai** dan **autostart** sesuai izin yang kamu setujui.
- Android tetap dapat meminta PIN/sidik jari. Layar aplikasi berproteksi `FLAG_SECURE` tetap gelap.
- Setelah Redmi restart, sistem dapat menghentikan layanan atau menuntut tindakan lokal. Perlu pengujian langsung.
- Screenshot sekitar satu kali tiap 1,5 detik, bukan video halus dengan audio. Belum ada kendali keyboard, audio, atau file transfer.

### Keamanan

Port hanya terbuka pada jaringan privat Tailscale. HTTP *di atas* VPN Tailscale terlindungi enkripsi koneksi VPN, tetapi tidak menggunakan TLS terpisah untuk sesi Safari; jangan menonaktifkan Tailscale atau membuka port ini ke internet. Gunakan akun Tailscale dengan autentikasi kuat; jangan sebarkan kode akses atau IP VPN. Karena kode akses disimpan pada iPhone hanya selama sesi halaman terbuka, kamu perlu memasukkannya lagi ketika membuka ulang halaman. Tidak ada indikator stealth: akses aktif selalu terlihat di Redmi.

## Struktur

- `android/app/src/main/java/id/arunika/remote/` — aplikasi Android, layanan accessibility, dan server HTTP privat.
- `android/app/src/main/assets/` — halaman kontrol Safari yang disajikan oleh aplikasi Android.
- `android/app/src/main/AndroidManifest.xml` — izin notifikasi, internet, layanan Aksesibilitas.

## Kompilasi APK (bila developer mengerjakan build)

1. Instal Android Studio terkini dengan Android SDK platform 35, Build Tools, JDK 17, serta Gradle 8.9 (berdasarkan AGP 8.7.3).
2. Buka direktori `android` di Android Studio. Jika diminta gunakan Gradle dari instalasi lokal; proyek belum berisi Gradle Wrapper.
3. Build > Build Bundle(s) / APK(s) > Build APK(s); hasil berada di `android/app/build/outputs/apk/debug/app-debug.apk` **hanya setelah berhasil build**.
4. Lakukan pengujian koneksi dari iPhone ke IP tailnet Redmi, uji screenshot, gestures, reboot, MIUI background, dan audit keamanan sebelum mengandalkan aplikasi untuk remote sungguhan.

**Penting:** jangan menyamakan file ZIP kode ini dengan APK yang bisa diinstal.
