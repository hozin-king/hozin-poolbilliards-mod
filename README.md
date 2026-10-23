# Hozin Pool Billiards Mod

Mod **gabung APK** (tanpa root) untuk **Pool Billiards Pro 5.3**
(`com.forthblue.pool`, versionCode 53) dari ForthBlue/FruitsMobile.
APK asli ada di `original/`; hasil bedah lengkapnya dirangkum dari
laporan REA internal (mesin Basket, fisika Java murni di `PoolTable`).

## v1.4 — dua keluhan uji HP + Stage 8/9/10 + fitur A–D (detail: `BUILD-MOD-v1.4.md`)

1. **Saklar Auto Play persisten ditulis ulang:** pemicu v1.3
   (pasangan stabil + dua probe Snap penuh + latch posisi, tanpa
   status terlihat, tanpa uji host) diganti mesin status
   `AutoTrigger` yang teruji JVM (TestTrigger 18/18): giliran
   terdeteksi → meja diam 700 ms dari kunci posisi ringan →
   tembak → coba lagi berjeda bila gagal; status tertulis di
   menu pada setiap fase; pengaman mati-sendiri 3x gagal tetap
   ada dengan alasan di readout.
2. **Arcade "COMING SOON" dibuka:** penyebabnya kartu ke-8
   LevelScene dikodekan keras ke region `tablebx` (gambar banner
   COMING SOON itu sendiri) dan tidak ada region `stagetitle7`;
   sisanya kunci literal 7 di LevelScene/LevelRule. Patch smali
   membuka 10 stage (+1 kartu teaser): 210 level bawaan bisa
   dimainkan dari menu normal, DITAMBAH **90 level baru**
   (Stage 8/9/10 × 30, `mod-assets/data/`) yang setiap levelnya
   terbukti tamat oleh klon Sim + perencana dengan aturan
   restTime asli (TestLevels, juga di CI).
3. **Fitur baru (semua di belakang gerbang PvP yang sama,
   default OFF kecuali break):** (A) break shot khusus —
   kekuatan maksimum ke apex, rack terbukti buyar di simulasi;
   (B) Anti-Scratch Total — patch `PoolTable.getNextHitTime`
   membuat bola putih tidak pernah tertangkap lubang;
   (C) Prank VS CPU — rel meja mati selama giliran AI, bola
   keluar area di-wrap game ke sisi berlawanan; (D) Magnet
   Lubang — bola grup user sendiri (VS) / semua bola objek
   (tanpa grup) diarahkan ke lubang; bola lawan, bola 8, dan
   bola putih tidak pernah; grup tak terbaca = magnet diam +
   alasan tertulis di menu.
4. **Menu:** panel bisa digeser dari header, minimize ke ikon
   MOD, posisi & keadaan panel dipertahankan selama sesi game.

## v1.2 — perbaikan dua kegagalan uji HP v1.1 (detail: `BUILD-MOD-v1.2.md`)

1. **Prediksi hilang sesudah game selesai — akar terbukti:** pergantian
   game/ronde menjalankan `GameScene.setRule/showGameEnd →
   PoolTable.reset() → ContainerBase.clear()` yang menghapus container
   garis mod dari instance meja yang sama; pemeriksaan v1.1 hanya
   berjalan saat instance meja berganti, jadi garis mati permanen.
   v1.2 memverifikasi dan memasang ulang container pada setiap tick
   bidik dan setiap panggilan gambar.
2. **Autoplay "tolol" — akar terbukti:** skor v1.1 didominasi
   `robust×250`, sehingga pot jauh yang "kokoh" mengalahkan pot dekat
   yang enak dan kekuatan terpilih selalu besar. v1.2: robust ≥3
   menjadi *syarat* bila ada yang mencapainya, skor kemudahan
   (potong/jarak/kekuatan) yang memutuskan antar tembakan kokoh,
   kekuatan terpilih dilunakkan bertahap selama tetap kokoh.
3. **Readout keputusan di menu:** grup legal terdeteksi, jumlah
   kandidat, penolakan per alasan (scratch/kontak/bola lawan/bola 8
   dini), pilihan bola + lubang + kekuatan — atau alasan tidak
   menembak; plus baris diagnosis prediksi (segmen tergambar / alasan
   tidak menggambar), supaya screenshot berikutnya menjelaskan dirinya.

## Fitur

1. **Long line** — garis bidik diperpanjang ke titik kontak pertama
   memakai `PoolTable.calcAimTarget()` milik game sendiri.
2. **Prediksi penuh** — klon fisika game (`Sim`, porting setia
   `processRunning`/`updateRunning`/`doHit`/`workout` dari PoolTable)
   dijalankan in-process pada salinan state meja setiap bidikan
   berubah. Polyline lintasan digambar per bola **dengan warna bola
   itu** (rantai tabrakan bercabang muncul alami), titik berhenti
   ditandai X, pantulan cushion memakai restitusi 0,8 + kopling spin
   versi game.
3. **Auto play pintar (v1.1, ditulis ulang)** — pencarian tembakan di
   atas klon yang sama, dengan disiplin aturan game sendiri:
   target legal dibaca dari `PoolRule.isAvailableTarget()` yang hidup
   (grup solids/stripes pemain yang giliran, meja terbuka, bola 8
   hanya bila grup habis, bola terendah di 9-ball); setiap kandidat
   melewati **saringan keras** (dibuang total bila men-scratch bola
   putih, kontak pertama bukan target legal, atau bola lawan masuk
   tanpa target sendiri); yang dipilih adalah tembakan paling "enak"
   (lolos uji ketahanan perturbasi sudut ±0,3° & strength ±5%, sudut
   potong kecil, jarak pendek). Bila tidak ada pot aman: tembakan
   pengaman lembut; bila tidak ada juga: tidak menembak. Eksekusi
   lewat jalur resmi `PoolTable.click(...)` — jalur yang sama dengan
   pemain dan AI. Pada state freeball, titik penempatan dicari dan
   divalidasi `rule.canPut()` asli sebelum `putFreeball()`.
4. **Menu melayang** — tombol MOD (jendela WindowManager
   `TYPE_APPLICATION_PANEL`, bisa digeser) dengan saklar Long Line /
   Prediksi Penuh (default ON), tombol AUTO PLAY manual, dan saklar
   **Auto Play Otomatis** (default OFF — pada giliranmu, sesudah meja
   stabil ±1 detik, tembakan dijalankan sendiri).

## Batas keras: mode PvP

Game punya mode PvP online melawan manusia asli (`PVP8Ball`,
`isPVPMode() = true`). **Semua fitur mod mati total selama PvP**:
latch per instance rule + pemeriksaan `isPVPMode()` segar di setiap
titik masuk fitur (prediksi, pencarian, eksekusi, penempatan freeball),
dan menu disembunyikan. Fitur hanya bekerja di mode offline:
VS Computer, Time, Arcade/Level, Tutorial. IAP/koin/stik dan jaringan
tidak disentuh sama sekali.

## Bangun

```
bash build.sh        # butuh JDK 17, ANDROID_HOME, APKTOOL_JAR
python3 verify.py PoolBilliardsPro-Mod-v1.1.apk original/Pool_Billiards_Pro_5.3.apk
```

Uji JVM (tanpa Android) — konsistensi klon fisika + logika autoplay:

```
javac -d /tmp/t mod-src/com/hozin/poolmod/Sim.java \
  mod-src/com/hozin/poolmod/Snap.java mod-src/com/hozin/poolmod/ModState.java \
  mod-src/com/hozin/poolmod/Ref.java mod-src/com/hozin/poolmod/Auto.java \
  mod-src/com/hozin/poolmod/Objective.java \
  tests/TestSim.java tests/TestAuto.java
java -cp /tmp/t com.hozin.poolmod.TestSim
java -cp /tmp/t com.hozin.poolmod.TestAuto
```

GitHub Actions (`.github/workflows/build.yml`) menjalankan uji yang
sama + build + verify pada setiap push berkas terkait; tag `v*`
menerbitkan GitHub Release berisi APK.

## Catatan jujur

- Klon memakai `(float)Math.*` sebagai padanan MathUtil native game
  (berderajat); perbedaan dibawah presisi float tidak kasat mata,
  tetapi klaim bit-exact di perangkat menunggu uji runtime di HP.
- Prediksi memakai strength yang sedang diatur pemain; sebelum bar
  kekuatan ditarik, dipakai strength terakhir (awal 1500).
- Autoplay v1.1 membaca grup & target legal dari aturan game yang
  hidup (`isAvailableTarget`), menilai kandidat dengan saringan
  keras + uji ketahanan perturbasi. Saringan dinilai sebagai
  himpunan bola masuk (klon tidak merekam urutan pot); bola 8
  ditolak ketat. Selisih sub-presisi float klon vs native tetap
  mungkin membalikkan tembakan yang persis di tepi lubang — uji
  ketahanan dirancang menolak tembakan semacam itu, tetapi garansi
  mutlak tidak diklaim. Rincian: `BUILD-MOD-v1.1.md`.
