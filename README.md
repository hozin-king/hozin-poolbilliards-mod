# Hozin Pool Billiards Mod

Mod **gabung APK** (tanpa root) untuk **Pool Billiards Pro 5.3**
(`com.forthblue.pool`, versionCode 53) dari ForthBlue/FruitsMobile.
APK asli ada di `original/`; hasil bedah lengkapnya dirangkum dari
laporan REA internal (mesin Basket, fisika Java murni di `PoolTable`).

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
3. **Auto play pintar** — pencarian tembakan di atas klon yang sama:
   kandidat sudut ghost-ball per (bola, lubang) × tingkat strength,
   diskor (bola masuk, bola putih tidak ikut masuk/foul, posisi akhir
   bola putih untuk tembakan berikutnya), fase spin bila perlu, lalu
   dieksekusi lewat jalur resmi `PoolTable.click(...)` — jalur yang
   sama dengan pemain dan AI. Pada state freeball, titik penempatan
   dicari dan divalidasi `rule.canPut()` asli sebelum `putFreeball()`.
4. **Menu melayang** — tombol MOD (jendela WindowManager
   `TYPE_APPLICATION_PANEL`, bisa digeser) dengan saklar Long Line /
   Prediksi Penuh dan tombol AUTO PLAY. Default: keduanya ON,
   autoplay manual lewat tombol.

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
python3 verify.py PoolBilliardsPro-Mod-v1.apk original/Pool_Billiards_Pro_5.3.apk
```

Uji konsistensi klon fisika (JVM, tanpa Android):

```
javac -d /tmp/t mod-src/com/hozin/poolmod/Sim.java tests/TestSim.java
java -cp /tmp/t com.hozin.poolmod.TestSim
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
- Penilaian autoplay bersifat umum (bola objek mana pun dihitung);
  khusus 9-ball, kontak pertama & pot bola terendah diberi bobot
  aturan. Grup 8-ball (solids/stripes) tidak dibaca dari state aturan.
