# Laporan Bangun — Pool Billiards Pro Mod v1

Tanggal: 22 Okt 2026. Dasar: laporan REA `rea-poolbilliards/REPORT.md`
(bedah APK asli v5.3 — file REA berakhir terpotong di akhir §5c, tetapi
seluruh rancangan yang dibutuhkan — identitas, peta kelas, konstanta
fisika, rancangan klon §4–§5 — utuh dan cukup; fisika diverifikasi
ulang langsung dari sumber JADX PoolTable saat pembangunan).

## Arsitektur patch

Mod DIGABUNG ke APK (tanpa root). Hanya 3 sisipan smali
(`apply_patches.py`, asersi pola persis, bentuk `invoke-static/range`
karena register p0 method tick berada di atas v15):

| Titik | Sisipan | Fungsi |
|---|---|---|
| `GameScene.tick(J)V` (awal) | `Hooks.onSceneTick(this)` | seluruh pekerjaan fitur di thread game |
| `Main.onResume()V` (awal) | `Hooks.onActivityResume(this)` | pasang menu WindowManager |
| `Main.onPause()V` (awal) | `Hooks.onActivityPause(this)` | lepas menu |

Kode mod (dex baru `classes5.dex`, paket `com.hozin.poolmod`, diakses
ke kelas game murni via refleksi — tanpa dependensi kompilasi):

- `Sim` — klon fisika PoolTable: porting setia `workout` (5 & 7 arg),
  `getNextHitTime` x3 + pemindaian matriks penuh, `doRunning`,
  `doHit` bola–bola (java.lang.Math verbatim), `doHit` bola–cushion
  (restitusi 0,8 + kopling spin `blocker.force`), `processRunning`
  (termasuk pemotongan waktu 1e-5 khas asli), blok friksi
  `updateRunning` (ROLL_K 1,5811388; peluruhan w 0,15×force; roll
  force), dan rumus `click()`. Dibuang: SoundManager, callback aturan,
  animasi visual (quaternion/shadow), animasi hilang bola potted.
  Parameter (force 1500, rollForce 100, holeGravity 3600, jari-jari
  47,15, geometri blocker/lubang) dibaca dari rule/objek hidup saat
  snapshot — bukan hardcode.
- `Snap` — snapshot state meja (posisi/kecepatan/spin tiap bola,
  geometri blocker & lubang, parameter rule) + validasi `canPut()`
  rule asli untuk freeball + sidik jari posisi.
- `Predict` — pool 896 `LineSprite` asli game dalam satu
  `SimpleContainer` anak PoolTable (koordinat satuan meja, ikut
  transformasi + scissor meja, tergambar di atas bola). Long line dari
  `PoolTable.calcAimTarget()` public; prediksi penuh menggambar
  polyline per bola dengan warna bola itu + tanda X titik berhenti.
- `Auto` — pencarian tembakan (lihat README) di thread pekerja dari
  snapshot; eksekusi diserahkan ke thread game.
- `Hooks` — orkestrasi per-frame, gerbang PvP, eksekusi lewat
  `PoolTable.click(strength, spinX, spinY, angle)` (jalur resmi yang
  sama dengan pemain & AI) setelah menyamakan field bidik scene dan
  memanggil `calcAimTarget()`; freeball: set posisi bola (lolos
  `canPut`) → `putFreeball()` → `click`.
- `ModMenu` — overlay WindowManager `TYPE_APPLICATION_PANEL` pada
  token activity (tanpa izin overlay), tombol MOD bisa digeser, panel
  berisi saklar Prediksi Penuh (default ON), Long Line (default ON),
  tombol AUTO PLAY, dan teks status mode/giliran. Watchdog 300 ms.

## Gerbang PvP (batas keras)

`PVP8Ball.isPVPMode() = true` → semua fitur mati total:
latch per instance rule di `Hooks.tick` (menu disembunyikan, antrean
eksekusi dibersihkan), pemeriksaan `isPVPMode()` SEGAR di
`Hooks.updatePrediction` (sebelum menghitung), di `Auto.startSearch`
(titik masuk pencarian), di `Hooks.executeShot` (titik eksekusi),
dan `Hooks.gateAllows()` untuk tombol menu. Bukti statis: `verify.py`
memastikan rujukan `isPVPMode` ada di smali Hooks & Auto pada APK
final; decompile-balik mengonfirmasi.

## Cara prediksi bekerja

Saat state bidik (RoundState.state==1, giliran manusia) dan parameter
berubah (sudut/strength/spin/posisi bola — sidik jari di `Hooks`),
snapshot diambil, rumus click diterapkan ke salinan bola target, dan
klon dijalankan sampai semua bola berhenti (langkah tetap 16 ms persis
game). Titik direkam di setiap event & langkah → polyline eksak per
bola. Strength pratinjau = strength yang sedang diatur pemain; sebelum
bar kekuatan ditarik dipakai strength terakhir (awal 1500) dan
dihitung ulang seketika begitu berubah.

## Cara autoplay mencari tembakan

Kandidat geometris: per (bola objek × lubang) sudut ghost-ball × 3
strength di sekitar perkiraan jarak. Skor per simulasi klon:
+1000/bola masuk (9-ball: target terendah +1000, lain +250, kontak
pertama wajib target −900), bola putih masuk −100000, tanpa kontak
−700, bonus posisi akhir bola putih dekat bola berikutnya & jauh dari
lubang, penalti strength kecil. Bila tak ada pot: fase spin
(spinY ±0,45 / spinX ±0,35) pada 6 kandidat terbaik; lalu sapuan halus
±selebar bola di sekitar arah tiap bola objek. Freeball: titik di
garis ghost-ball (jarak 300/600/1000) yang lolos `canPut`, disimulasikan
dari titik itu; strength diperhalus di titik terbaik.

## Verifikasi yang dijalankan

1. **Uji JVM klon** (`tests/TestSim.java`, juga di CI): konstanta
   audit; `workout` analitik vs brute-force 300 sampel acak (cocok
   < 4 ms); pot lurus (kontak pertama benar, bola masuk); break shot
   rack 15 bola (kontak pertama = apex, 14 bola tersebar, simulasi
   berhenti sendiri dalam 411 langkah); pantulan cushion. — LULUS
   semua. Temuan uji: pada strength 1500 tembakan lurus membuat bola
   putih ikut masuk karena follow-through kopling `rv` khas mesin ini
   — perilaku asli game yang direproduksi klon dengan benar (diuji
   ulang pada 700: bola masuk, putih berhenti aman).
2. **Build lokal**: BUILD OK — `PoolBilliardsPro-Mod-v1.apk`
   16.556.159 byte (asli 13.948.850 byte).
3. **verify.py pada APK final**: zip utuh (393 entri, classes5.dex
   ada); apksigner Verifies (sertifikat mod.keystore `CN=Hozin Pool
   Mod`, SHA-256 8682ff3c…7f2db — konsisten untuk rilis berikutnya);
   badging identik dengan asli (`com.forthblue.pool`, versionCode 53,
   versionName 5.3); decompile-balik: 8 kelas mod tertanam, 3 hook
   tertanam, gerbang `isPVPMode` dirujuk Hooks & Auto, konstanta Sim
   ada. Konstanta terdecompile dari APK final: 1,5811388f, 0,8f,
   3600,0f, 0,016f, 0,15f — cocok dengan sumber PoolTable/RuleImplBase.

## Batas jujur

- Belum diuji di perangkat: perilaku runtime persis (presisi garis vs
  lintasan nyata di HP, posisi menu terhadap UI game, performa
  pencarian autoplay di SoC HP) menunggu uji HP user. Klaim bit-exact
  TIDAK dibuat: klon memakai `(float)Math.*` sebagai padanan MathUtil
  native (berderajat); selisih di bawah presisi float mungkin ada.
- Penilaian autoplay umum (grup solids/stripes 8-ball tidak dibaca);
  khusus 9-ball kontak/pot bola terendah dibobot khusus.
- Prediksi hanya tampil pada state bidik (state 1); pada freeball
  (state 3) garis tidak digambar — autoplay menyediakan penempatan.
