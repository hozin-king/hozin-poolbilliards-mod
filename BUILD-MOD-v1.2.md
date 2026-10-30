# Pool Billiards Pro Mod v1.2 — diagnosis & perbaikan dua kegagalan uji HP v1.1

Tanggal: 22 Oktober 2026. Basis: v1.1 (commit remote `9e39f39`).
Keluaran: commit remote `b4e17962`, tag `v1.2`, CI Actions run
`39793374064` **hijau penuh**, Release
https://github.com/hozin-king/hozin-poolbilliards-mod/releases/tag/v1.2,
aset `PoolBilliardsPro-Mod-v1.2.apk` **16.560.255 byte**, sertifikat
SHA-256 `8682ff3cf4825833e87cb8b178ab07e5e21d65096b64a944cbadc57b2137f2db`
— **identik dengan v1/v1.1** (timpa pasang aman). Salinan terverifikasi
dari aset rilis: `~/workspace/your_files/PoolBilliardsPro-Mod-v1.2.apk`.

## Ringkasan

Kedua akar ditemukan dari **kode game asli** (smali dex APK rilis v1.1
yang terpasang di HP user, didekompilasi ulang dari aset Release v1.1)
dan dibuktikan/diuji pada **harness JVM yang menjalankan kelas Basket
asli hasil dekompilasi dex game** (bukan stub tulisan tangan):

1. **Prediksi hilang** — *reset game menghapus container garis mod dari
   meja.* Setiap pergantian game/ronde, `GameScene.showGameEnd` /
   `Main.setRule` membangun rule baru → `GameScene.setRule` →
   `PoolTable.reset()` → `ContainerBase.clear()` (`arrUsed = 0`), yang
   mengosongkan **semua anak** dari instance `PoolTable` **yang sama** —
   termasuk `SimpleContainer` tempat 896 `LineSprite` mod digantung.
   Pemeriksaan v1.1 (`Predict.ensurePool`) hanya memeriksa kehadiran
   container **saat instance table berganti**, jadi sesudah reset
   pertama container tidak pernah dipasang ulang: garis mati permanen
   selama proses hidup, **tanpa gejala lain** (menu & autoplay tidak
   bergantung pada container itu). Konsisten penuh dengan sesi user:
   v1 berakhir kalah (`showGameEnd` pasti jalan), v1.1 dimainkan
   sesudahnya tanpa garis; bidik berubah pun tidak menolong karena
   pemeriksaan kehadiran memang tidak pernah tercapai.
2. **Autoplay "tolol"** — *logika legalitas v1.1 terbukti benar* (lihat
   bukti harness di bawah: oracle grup benar dua arah, target stripe
   sah terpilih, pot bersih tanpa scratch, pemicu otomatis berfungsi).
   Yang terbukti salah adalah **preferensinya**: skor didominasi
   `robust × 250` sehingga tembakan jauh yang "kokoh" mengalahkan pot
   dekat yang paling enak di mata pemain, dan kekuatan terpilih selalu
   yang terbesar yang lolos (bola berhamburan). Ditambah tidak adanya
   readout, user tidak bisa melihat alasan di balik pilihan apa pun.

## 1. Diagnosis prediksi — hipotesis diperiksa satu per satu

| Hipotesis | Vonis | Bukti |
|---|---|---|
| Hook `GameScene.tick` hilang di dex rilis | **Dibantah** | dexdump classes.dex APK rilis v1.1: `tick` memanggil `Hooks.onSceneTick`; decompile smali v1.1 = source v1.1 persis |
| Latch PvP false-positive | **Dibantah** | latch hanya dari `isPVPMode()` hidup; menu MOD terlihat di screenshot (menu disembunyikan total saat latch) — latch mati, gerbang giliran yang sama juga menggerbangi autoplay yang menurut user tetap menembak |
| Gating bola-berhenti tidak pernah true | **Dibantah** | garis bidik bawaan game memakai data bidik yang sama dan tampil; harness: state=1 + bola berhenti → digambar |
| Sprite tergambar tapi tertutup/di luar transform | **Dibantah** | v1 tampil dengan jalur gambar yang sama; harness menjalankan kelas Basket asli: snapshot antrean render berisi 2 masukan (meja + container mod), container mode=1, vertexBuffer terisi 99 segmen |
| Hitung-ulang tidak terpicu (deteksi perubahan bidik) | **Dibantah sebagai akar** | kunci posisi mencakup posisi bola bulat + sudut/strength float mentah; perubahan bidik mengubah kunci (terverifikasi harness) |
| **Container terhapus dari table oleh reset game; pemeriksaan kehadiran hanya saat instance berganti** | **TERBUKTI — AKAR** | rantai smali di atas; `PoolTable.reset()` dipanggil dari `GameScene.setRule` (jalur `showGameEnd` & `Main.setRule`); `Predict.ensurePool` v1.1 membandingkan `attachedTable != table` saja |

Kenapa uji JVM v1.1 lulus: pengujian tidak pernah mensimulasikan
`table.clear()` (reset) — celah cakupan yang sekarang ditutup (uji
harness langkah [6]).

### Perbaikan (Predict.java, Hooks.java)

- `ensurePool` kini memeriksa kehadiran container di daftar anak table
  pada **setiap** panggilan gambar (`isPresent`) dan memasang ulang
  bila hilang.
- `Predict.ensureAttached(table)` dipanggil Hooks pada **setiap tick
  bidik sebelum cek kunci gambar** — cacat turunan yang ditangkap
  harness saat pengujian perbaikan pertama: penegakan yang hanya hidup
  di jalur gambar bisa ditekan tanpa batas oleh cache kunci gambar
  (bidik diam = tidak ada gambar ulang = container tetap lepas).
- Readout: `Predict.lastSegCount`, `poolReady`, `containerAttached`,
  `reattachedOnce` → menu menampilkan, mis. `prediksi: 122 segmen
  tergambar (container dipasang ulang)` atau alasan gerbang
  (`menunggu bidik (state=0)`, `bukan giliran pemain`, `mode PvP —
  fitur mati`, `snapshot meja gagal dibaca`).

## 2. Diagnosis autoplay — hipotesis diperiksa satu per satu

| Hipotesis | Vonis | Bukti |
|---|---|---|
| Oracle refleksi runtime: grup terbalik untuk pemain manusia | **Dibantah** | smali `Vsai.getPlayerType`: indeks < `not_ai_player_count` → 0 (manusia — `MainMenu` mengeset hitungan itu persis jumlah pemain manusia); harness dengan kelas `Vsai8Ball`/`PoolRule` asli: campstate=3 → stripes legal untuk pemain 0, campstate=2 → solids; autoplay memilih sesuai grup pada kedua arah |
| `getClickTarget` bukan bola putih | **Dibantah** | smali: selalu konstanta 0 |
| Saringan perturbasi menolak semua pot → pengaman terus | **Dibantah sebagai pola umum** | layout screenshot: 126 kandidat, yang ditolak tercatat per alasan (scratch 20, kontak ilegal 33, bola 8 dini 2) dan pot bersih tetap terpilih; saringan memang ketat per desain, tetapi bukan penolakan total |
| Pemicu otomatis menembak di momen salah | **Dibantah** | pemicu mensyaratkan giliran manusia + probe posisi kembar stabil ≥900 ms + meja diam ≥500 ms; harness ujung-ke-ujung: `PoolTable.click` terpanggil dengan tembakan yang lolos saringan ulang |
| Status menu menyesatkan | **Terbukti sebagian — diperbaiki** | status lama hanya menampilkan mode/gameState generik; kini ada readout keputusan penuh |
| **Preferensi skor didominasi robust; kekuatan selalu maksimal** | **TERBUKTI — AKAR** | pembacaan kode v1.1 (`baseScore` maks ±~240 vs robust maks 1250); harness layout screenshot: v1.1 memilih pot jauh berkekuatan 1855 karena robust-nya tertinggi, bukan pot yang paling enak |

### Perbaikan (Auto.java)

- `pickMostRobust` ditulis ulang: robust ≥3 menjadi **syarat** bila ada
  kandidat yang mencapainya; antar-kandidat kokoh, **skor kemudahan**
  yang memutuskan (potong ×10, jarak tempuh ×0,08, kekuatan ×0,01,
  robust hanya ×60 sebagai penyeimbang).
- `trimStrength`: kekuatan terpilih diturunkan bertahap (×0,88, maks 3
  langkah) selama pot tetap masuk **dan** lolos saringan pada sudut
  0/±0,15° — tembakan jadi selembut yang masih kokoh (uji preferensi:
  kekuatan terpilih turun ke 640 dari kelas ~1855 untuk pot lurus
  pendek).
- **Readout keputusan** (`Auto.lastReport` → `ModState.autoReport` →
  baris kedua menu), contoh nyata dari uji:
  `Auto: stripes (9-15) • kandidat 126 • ditolak: scratch 20 kontak 33
  bola8dini 2 • dipilih bola 12 → lubang kanan-atas, kekuatan 1855,
  kokoh 5/6`. Alasan penolakan kini berkode (`filterReason`: scratch /
  kontak pertama ilegal / bola lawan / bola penentu dini) dan dihitung
  per pencarian; jalur "tidak menembak" dan "oracle tak terbaca" juga
  menulis alasan eksplisit.

### Skenario uji baru (dari screenshot user)

Layout meja screenshot 20:08 direkonstruksi sebagai koordinat meja
(cue (-700,-60); solids tersebar; stripes mengumpul kanan-atas):

- **(h) stripes legal**: autoplay wajib memilih pot stripe yang sah &
  bersih → terpilih bola 12 (potongan 15,9°, robust 5) — pot bola 10
  pada layout aproksimasi ini ternyata potongan ekstrem 103° yang
  memang tidak lolos; readout menyebut grup & penolakan dengan benar.
- **(h2) preferensi**: cue direposisi hampir segaris di belakang bola
  10 → pot lurus pendek (kekuatan terpotong ke 640, kokoh 6/6)
  **mengalahkan** pot jauh bola 12 — inti keluhan "tidak memilih bola
  yang paling enak" kini teruji.
- **(i) solids legal pada layout sama**: stripe tidak pernah
  ditargetkan; terpilih solid 2, masuk bersih.

## 3. Hasil uji

- **Kompilasi semua file se-repo** (`javac -encoding UTF-8 -proc:none`,
  mod-src + tests, android.jar): bersih.
- **Uji repo**: `TestSim` lulus; `TestAuto` lulus — 7
...[truncated 2328 chars]