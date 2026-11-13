# AGENTS.md

Panduan untuk agent yang bekerja di repo ini. Isinya disusun dari memory Jules dan dikelompokkan per topik.

## 1. Gambaran Umum

- Aplikasi Android (Kotlin, Jetpack Compose), package `com.astral.stitchapp`.
- Dua fitur utama:
  - **Scraping / Rawloader**: mengambil gambar chapter dari berbagai sumber webtoon/manga.
  - **Stitching**: menyambung dan memotong gambar secara otomatis maupun manual.
- File penting: `MainActivity.kt`, `ScraperUi.kt`, `BatoEngine.kt`, `WebtoonsApi.kt`, `SmartStitcher.kt`, `ManualStitchActivity.kt`.

## 2. Build & Dependensi

- `settings.gradle.kts` memuat repository Mozilla Maven: `https://maven.mozilla.org/maven2/`.
- `app/build.gradle.kts` memakai `org.mozilla.geckoview:geckoview-omni` untuk engine GeckoView.
- Decoder JXL: `io.github.awxkee:jxl-coder:2.2.0` (`JxlCoder.decode`).

## 3. Arsitektur Scraper

### Routing dialog (`ScraperUi.kt`)
- `ScraperWebViewDialog` mengarahkan sumber **Manta, KakaoPage, Ridibooks** (`manta`, `kakao`, `ridi`) ke `GeckoScraperDialog`.
  - Memakai Mozilla GeckoView (`GeckoRuntime` + `GeckoSession`) agar popup OAuth, anti-bot, dan WebExtension berfungsi.
- Sumber lain memakai `StandardWebViewScraperDialog`.
- Kedua dialog punya tombol **Reload** (`session.reload()` / `webView?.reload()`).
- Scraping Ridibooks, Bomtoon, Lezhin, Manta, dan MrBlue bergantung pada `ScraperWebViewDialog` untuk autentikasi dan penangkapan data dinamis lewat request browser.

### Progress & tombol Scrape
- `updateProgress(loaded, total, isReady)` menampilkan progress bar dan jumlah gambar.
- `isScrapeReady` default `true` untuk semua sumber (termasuk MrBlue) supaya tombol Scrape aktif di awal.
- Tombol dinonaktifkan sementara selama scraping gambar berlangsung.

### Daftar sumber bawaan
- Tab 'Rawloader' (`BatoTab`) memakai `builtInSources` di `MainActivity.kt`: Ridibooks, Bomtoon, Lezhin, MrBlue, KakaoPage, Naver Webtoon.
- Ditambah patch sumber kustom dinamis (`.asp`).

## 4. Autentikasi & Cookie

- Cookie/session tiap sumber disimpan terpisah di SharedPreferences: `ridi_cookie`, `bomtoon_cookie`, `lezhin_cookie`, `mrblue_cookie`, `kakaopage_cookie`, `webtoons_session`.

### `CookieWebViewDialog` (`MainActivity.kt`) untuk login OAuth Kakao
- `fixKakaoUrl`: menghapus `#webTalkLogin` dan menambah `talk_login_error=true` agar login ID/password web standar yang tampil.
- Menangani custom intent scheme (`kakaokompassauth://`, `intent://`) dengan fallback.
- Mengaktifkan third-party cookies.
- Mengatur `MIXED_CONTENT_ALWAYS_ALLOW`.

## 5. Catatan per Sumber

### KakaoPage
- Ekstraksi gambar memakai `ScraperWebViewDialog` dengan `ScraperScripts.KAKAOPAGE` di GeckoView.
- Mencegat respons REST `fetch` dan `XMLHttpRequest` dari `bff-page.kakao.com/api/gateway/api/v1/viewer/data`.
- Mem-parse `ImageViewerData` yang sudah diurutkan (URL gambar di `secureUrl`) plus judul chapter untuk diproses `BatoEngine`.
- Di `BatoEngine.kt`, KakaoPage **wajib** memakai gambar hasil pre-scrape dari GeckoView. Jika tidak ada, melempar exception. Tidak ada fallback ke fetch REST API langsung.

### Manta
- `MantaGeckoScraperDialog` (`ScraperUi.kt`) mengonfigurasi GeckoView dengan:
  - User-Agent perangkat nyata yang dinamis (`Build.VERSION.RELEASE`, `Build.MODEL`),
  - evasi anti-bot JS (`navigator.webdriver`, `navigator.languages`, `navigator.plugins`).
- Popup/OAuth ditangani di `onNewSession` dengan mengembalikan `GeckoSession` yang belum dibuka, dilengkapi handler `ContentDelegate.onCloseRequest`.
- Patch `manta.asp` (di `patches/` dan `app/src/main/assets/patches/`):
  - mem-parse payload JSON `__NEXT_DATA__`,
  - memakai pelacakan posisi Y DOM (`yPos >= 0` dan `topY >= 0` agar panel pertama di posisi 0 tetap terjaga),
  - scroll progresif untuk mengekstrak gambar berurutan tanpa scroll manual dari user.

### MrBlue
- Scraper di `ScraperUi.kt` memakai fetch API langsung: `/home/api/gateway/api/v4/contents/access/{contentId}/{episode}`.
- Hook XHR/Fetch untuk menangkap header sesi.
- Cek panjang array (`hasItems`) untuk tier kualitas `hd`/`sd`, sehingga aman fallback ke SD jika HD berupa array kosong `[]`.
- Deobfuscate blok gambar 32-byte yang diacak:
  - permutasi indeks `ORDER` = `[29, 15, 24, 8, 4, 26, 0, 12, 14, 23, 17, 1, 9, 5, 20, 28, 11, 6, 19, 2, 16, 21, 30, 27, 25, 13, 7, 18, 3, 10, 22, 31]`,
  - operasi XOR `0xFF`.
- Download dan deobfuscate halaman berjalan paralel per chunk (batas concurrency 6) dengan `Promise.all`, sambil menjaga urutan halaman dan update progress.

### Webtoons (LINE Webtoon / Naver)
- File terkait: `WebtoonsApi.kt`, `BatoEngine.kt`, `MainActivity.kt`.
- State login disimpan di SharedPreferences (`webtoons_session`).
- Enkripsi RSA untuk autentikasi Naver/Webtoon, menyimpan cookie sesi (`NEO_SES`, `NEO_CHK`) dan kunci perangkat (`device_key`).
  - `RSAPublicKeySpec`: `evalue` Naver sebagai modulus N, `nvalue` sebagai eksponen E.
- Memanggil mobile API LINE Webtoon (`global.apis.naver.com`) dengan request bertanda tangan HMAC-SHA1:
  - `titleInfo` (info series),
  - `episodeList` (daftar episode),
  - `episodeInfoWithLogin` (URL gambar chapter).
- Mengambil semua episode, termasuk chapter berbayar/fast-pass yang ditandai `isAdRequired`. Fallback ke web scraping.
- Menampilkan username dan saldo koin setelah login.
- Menyediakan dialog pemilihan chapter untuk URL/ID series Webtoons.
- Fase simulasi iklan (`simulating ad view`) beserta request pembelian produk di `BatoEngine.kt` sebelum mengunduh chapter yang terkunci iklan.

### Bilibili
- File terkait: `BilibiliApi.kt`, `BatoEngine.kt`, `MainActivity.kt`.
- State login disimpan di SharedPreferences (`bilibili_session`) dengan dukungan reusable session.
- Login via scan QR code resmi Bilibili (polling endpoint `/x/passport-login/web/qrcode/poll`) menggunakan renderer QR berbasis ZXing.
- Mengambil info seri dan daftar episode melalui API Twirp Android Comic (`ComicDetail`) dengan query identity `APP_QUERY` dan fingerprint anti-abuse `buvid3`/`buvid4`.
- Mengambil URL gambar via Twirp `GetImageIndex` dan `ImageToken`.
- Dekripsi gambar format CPX secara native menggunakan AES-CBC (`javax.crypto.Cipher`) dengan decoding parameter token `cpx`.
- Dialog pemilihan chapter interaktif (multi-select, rentang episode, info episode terkunci/koin) sebelum dimasukkan ke antrean scraping `BatoEngine`.

## 6. Patch Sumber (`.asp`)

- Lokasi: `patches/` dan `app/src/main/assets/patches/` (jaga keduanya tetap sinkron).
- Patch bawaan: `manta.asp`, `kagane.asp`, `kakaopage.asp`, `xcomic.asp`, `myreadingmanga.asp`, `yaoimangaonline.asp`, `yaoiscan.asp`.
- `kagane.asp` (domain `https://kagane.si/`):
  - mengekstrak UUID yang tertanam di segmen path URL berawalan slug, memakai regex,
  - resolve slug series ke UUID via `POST /api/v2/search/series`,
  - resolve UUID chapter via `/api/v2/series/{uuid}/books/index?fields=List`,
  - mengambil data chapter via `/api/v2/books/{uuid}?intent=Read&is_datasaver=false`.

## 7. Stitching Otomatis (`SmartStitcher.kt`)

Class: `com.astral.stitchapp.SmartStitcher`.

| `splitMode` | Nama | Perilaku |
|---|---|---|
| 0 | Smart (Legacy) | Analisis luminansi 1D |
| 1 | Direct (Fixed) | Potong pada tinggi tetap |
| 2 | Super Smart (**default**) | Analisis jendela vertikal & gutter 2D agar teks/balon dialog tidak terpotong |
| 3 | Manganism | Mengakumulasi panel input utuh secara berurutan sampai tinggi split, tanpa memotong panel di tengah |

- Decode gambar paralel dan penyimpanan slice memakai Kotlin Coroutines `Dispatchers.IO`.

## 8. Stitching Manual

### `ManualStitchActivity.kt`
- Stitching/slicing interaktif dengan pengecekan batas hemat memori (`inJustDecodeBounds`).
- Compose `LazyColumn` untuk preview yang di-downsample.
- Overlay garis potong yang bisa digeser, menampilkan tinggi bagian atas dan bawah.
- Chip navigasi cepat untuk melompat ke garis potong.
- Decode piksel per chunk (`VirtualCanvas`) agar penyimpanan slice hemat memori.

### Penamaan output ManualStitch
- Nama folder/file output diturunkan dari judul folder/file input dengan akhiran ` [Stitched]` (contoh: `[Input Folder] [Stitched]`).
- Output sementara ditaruh di direktori cache induk terisolasi (`manual_out_parent_<timestamp>`) agar nama direktori target tetap terjaga saat diekspor.

## 9. Format Gambar

- **Input**: mendukung JXL (`.jxl`) via `jxl-coder`.
- **Output**: PNG, JPG, WebP, AVIF. `.jxl` **bukan** pilihan output.
- Encoding AVIF memakai `Bitmap.CompressFormat.valueOf("AVIF")` dengan kontrol kualitas, dan fallback ke PNG di versi Android yang tidak mendukung.
