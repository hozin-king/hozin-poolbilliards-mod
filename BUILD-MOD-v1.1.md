# Laporan Bangun — Pool Billiards Pro Mod v1.1 (perbaikan Auto Play)

Tanggal: 22 Okt 2026. Basis: v1 (commit remote `16bd4bff`, Release v1)
+ laporan kegagalan uji HP user 22 Okt 19:40 WIB.

## Kegagalan v1 di HP user (bukti: tangkapan layar + pengakuan rancangan v1)

1. Autoplay menembak **bola putih ke dalam lubang** (scratch).
2. Autoplay menargetkan **semua bola** — di 8-ball ia memasukkan bola
   lawan juga; user kalah ronde ("YOU LOSE", menu menunjukkan
   "VS CPU 8-Ball • ronde selesai").
3. User meminta auto play bekerja **otomatis pada gilirannya**, bukan
   hanya tombol sekali tembak.

Laporan bangun v1 sendiri sudah mencatat keterbatasan "grup
solids/stripes 8-ball tidak dibaca dari state aturan" — v1.1 menutup
lubang itu dari akarnya, bukan menambal skor.

## Akar masalah yang terbukti (dari kode game asli, decompile JADX dex game)

### 1. v1 menilai target dari skor, bukan dari aturan game

`Auto.score()` v1 memberi +1000 untuk bola objek APA PUN yang masuk di
8-ball; legalitas kontak pertama tidak diperiksa sama sekali. Padahal
game punya oracle target legal resmi di interface
`com.forthblue.pool.rules.PoolRule`:

```java
boolean isAvailableTarget(RoundState roundState, PoolBall[] balls, int i);
```

AI bawaan game sendiri (`VsaiRuleBase$1.think`, VsaiRuleBase.java
baris 42/77/117/142/172) hanya mempertimbangkan bola yang lolos metode
ini. Implementasinya:

- **Vsai8Ball** — dibaca dari `GameRoundState.campstate` +
  `currentPlayer`:
  - campstate 0 (PoolRule.CAMP_STATE_BREAKOFF, meja terbuka/break):
    semua bola selain cue adalah target legal — **termasuk bola 8**.
  - campstate 1 (CAMP_STATE_OPEN, transisi sesudah pot pertama):
    semua bola non-cue; bola 8 hanya bila tidak ada bola lain tersisa.
  - campstate 2 (CAMP_STATE_PLAYER1_COLOR): pemain 0 = solids (1..7);
    campstate 3: pemain 0 = stripes (9..15). Untuk pemain yang sedang
    giliran, target = grupnya sendiri; bola 8 hanya bila seluruh bola
    grup itu sudah tidak `isAvailable`.
- **Vsai9Ball** — `isAvailableTarget` = bola itu adalah bola bernomor
  terendah yang masih `isAvailable` (i != 0 dan tidak ada bola
  bernomor lebih kecil yang tersedia).
- **RuleImplBase** (dipakai Time8Ball1P/Time9Ball1P/LevelRule/
  TutorialRule — tidak meng-override): `return i != 0;` — semua bola
  non-cue legal (mode 1 pemain, memang tidak ada lawan).

Semantik foul/menang yang menyertai (Vsai8Ball/Vsai9Ball):
`onHitBall` menandai `fouled=true` bila kontak pertama bukan target
legal; `onPotBall` menandai foul bila cue masuk; di Vsai8Ball bola 8
yang masuk padahal bukan target legal -> `fouled=true` +
`pooledball8=true` -> `onRoundOver` memanggil `setLose` (kalah
langsung). Di Vsai9Ball bola 9 yang masuk sesudah kontak pertama
legal -> `pooledball9 && !fouled` -> `setWin` (menang sah, termasuk
lewat kombinasi).

### 2. Scratch di v1 hanya penalti skor, dan tembakan selalu dieksekusi

Skor scratch v1 = -100000, tetapi hasil pencarian **selalu**
dieksekusi selama ada kandidat; tidak ada penolakan mutlak. Ditambah
fallback buta v1 ("tidak ada bola objek -> tembak lurus sudut 0
strength minimum"). Fisika follow-through mesin ini (kopling `rv`
pada bola putih — direproduksi setia oleh klon Sim, terverifikasi di
TestSim: tembakan lurus 1500 memasukkan bola objek DAN bola putih)
membuat kandidat "terbaik menurut skor" bisa tetap scratch di tepi
lubang, apalagi dengan selisih sub-presisi float antara MathUtil
native dan java.lang.Math.

### 3. Audit konvensi parameter click: BERSIH (bukan tersangka)

- `GameScene.java:2314,2327` (decompile): jalur tembak pemain adalah
  `table.click(this.aimStrength, this.aimPx, this.aimPy, this.aimAngle)`
  persis.
- `PoolTable.click(float strength, float spinX, float spinY, float angle)`:
  `vx = strength*cos(angle)`, `rvx/rvy` dari spinY, `w = -strength*spinX/radius`;
  MathUtil berderajat. `tickAI` memanggil
  `click(aistrength, 0, 0, aiangle)` — sudut derajat yang sama.
- Auto v1/v1.1 dan `Sim.applyClick` memakai urutan, satuan, dan rumus
  yang identik. Jadi scratch v1 BUKAN salah satuan/skala — murni
  tidak adanya saringan keras + pemilihan kandidat yang rapuh.

## Perbaikan v1.1

### Snap — target legal ditangkap dari rule hidup

`Snap.capture` kini memanggil `rule.isAvailableTarget(roundstate,
balls, i)` (refleksi, sig `(PoolRule$RoundState, PoolBall[], int)`)
untuk tiap bola saat snapshot dibuat di thread game, dan menyimpan
`legal[]`, `legalKnown`, `moneyBall` (8 untuk Vsai8*, 9 untuk Vsai9*),
`currentPlayer`. Bila oracle tidak menjawab, `legalKnown=false` dan
autoplay menolak menembak.

### Auto — ditulis ulang (lihat header kelas untuk rancangan penuh)

- Kandidat HANYA untuk bola `legal` (termasuk penempatan freeball).
- **Saringan keras** `passesFilter` atas hasil klon dengan parameter
  persis yang akan dieksekusi: buang bila cuePotted; kontak pertama
  bukan target legal / tidak ada kontak; bola non-target masuk tanpa
  satu pun target legal masuk; bola 8 masuk padahal belum legal
  (pengecualian 9-ball: bola 9 sesudah kontak legal = kemenangan).
  Catatan jujur: `Sim.Result` tidak merekam urutan bola masuk, jadi
  aturan bola non-target dinilai sebagai himpunan; bola 8 tetap
  ditolak ketat tanpa kecuali.
- **Pemilihan "paling enak"**: kandidat pot yang lolos diuji
  ketahanan — perturbasi sudut ±0,15°/±0,3° dan strength ±5% harus
  tetap memasukkan target yang sama dan lolos saringan; jumlah
  perturbasi lolos (0..6) adalah kunci utama, lalu skor kemudahan
  (sudut potong kecil, jarak pendek, posisi akhir bola putih).
  Tembakan pisau-di-tepi-lubang tersingkir di sini — inilah jawaban
  untuk keluhan "tidak nge-track bola yang paling dekat dan paling
  enak yang sesuai ada di player".
- **Pengaman**: bila tak ada pot lolos — tembakan lembut ke target
  legal (kontak pertama legal, tanpa scratch, tanpa bola liar masuk,
  bola putih berakhir jauh dari lubang). Bila itu pun tak ada —
  **tidak menembak** (menu menampilkan "Auto: tidak ada tembakan
  aman — main manual"). Fallback buta v1 dan fase spin v1 dihapus
  (spin adalah penguat risiko scratch; tembakan terpilih sekarang
  selalu spin 0).
- Freeball: kandidat penempatan hanya untuk target legal, lolos
  saringan yang sama, titik divalidasi `rule.canPut()` seperti v1.

### Mode otomatis (baru)

Saklar menu **"Auto Play Otomatis"** (default OFF, tersimpan di
SharedPreferences bersama saklar lain; tombol AUTO PLAY manual tetap
ada). Di `Hooks.maybeAutoTrigger`: hanya saat giliran manusia di
mode offline dan ronde belum selesai; pasangan (state, pemain)
stabil >=900 ms, lalu dua probe posisi (Snap.positionKey) berjarak
>=700 ms harus identik — tembakan pertama keluar ±1,6 detik sesudah
meja berhenti. `autoHoldKey` mencegah pencarian ulang untuk posisi
yang sama (termasuk keputusan "tidak menembak"); menyalakan saklar
me-reset probe/hold. **Gerbang PvP tidak berubah**: latch v1 +
`isPVPMode()` segar di `maybeAutoTrigger` tepat sebelum
`startSearch`, di `startSearch`, dan di titik eksekusi.

## Uji JVM (tests/TestAuto.java — jalan lokal + CI, semua lulus)

Memakai harness geometri TestSim; snapshot Snap dibangun manual
dengan `legal[]` sesuai semantik oracle asli:

- (a) hanya bola lawan bisa dipot -> jebakan terverifikasi (pot lurus
  bola 9 masuk di klon) tetapi saringan menolaknya; autoplay jatuh ke
  pengaman, tidak memilih bola 9.
- (b) jebakan scratch follow-through (1500: bola 1 + cue masuk) ->
  saringan menolak; tembakan terpilih terbukti memasukkan bola 1
  tanpa scratch pada simulasi ulang.
- (c) grup solids -> stripe lawan yang TERMUDAH sekalipun tidak
  ditargetkan; solid terpilih & masuk. (c2) grup habis -> bola 8
  ditargetkan & masuk bersih.
- (d) meja terbuka -> pot termudah (bola 12) terpilih, lolos saringan.
- (e) 9-ball -> pot lurus bola 2 ditolak (kontak pertama bukan bola
  terendah); autoplay hanya menargetkan bola 1.
- (f) unit saringan: bola 8 masuk sebelum legal -> ditolak mutlak;
  bola 9 sesudah kontak legal -> sah (menang); bola 9 dengan kontak
  pertama foul -> ditolak.
- (g) `legalKnown=false` -> `searchShot` mengembalikan null (diam).
- TestSim v1 (konstanta, workout vs brute-force, pot lurus, break,
  cushion) tetap lulus tanpa perubahan.

## Yang berubah

- `mod-src/.../Snap.java` — tangkapan target legal + moneyBall.
- `mod-src/.../Auto.java` — tulis ulang: saringan keras, pemilihan
  robust, pengaman, tanpa fallback buta/spin.
- `mod-src/.../Hooks.java` — pemicu mode otomatis (probe stabilitas).
- `mod-src/.../ModState.java` — `autoOn` + state pemicu + status.
- `mod-src/.../ModMenu.java` — saklar "Auto Play Otomatis" + status
  hasil pencarian.
- `tests/TestAuto.java` — uji skenario baru (CI menjalankan TestSim
  + TestAuto).
- `verify.py` — asersi tambahan v1.1 (isAvailableTarget di Snap,
  passesFilter di Auto, saklar otomatis di ModMenu).
- `build.sh`, `.github/workflows/build.yml` — nama keluaran v1.1,
  langkah uji diperluas, badan rilis v1.1.

## Batas jujur

- Belum diuji di HP: presisi runtime garis/tembakan terhadap meja
  nyata, rasa jeda mode otomatis (±1,6 detik), dan posisi menu tetap
  menunggu uji perangkat user — sama seperti v1.
- Saringan dinilai dari klon Sim; selisih sub-presisi float klon vs
  MathUtil native masih mungkin membalikkan hasil pada tembakan yang
  persis di tepi lubang. Uji ketahanan perturbasi memang dirancang
  untuk menolak tembakan semacam itu, tetapi "tidak akan pernah
  scratch dalam kondisi apa pun" bukan klaim yang dibuat.
- Penilaian bola non-target memakai himpunan (bukan urutan masuk)
  karena Sim tidak merekam urutan pot; bola 8 ditolak ketat.
- Grup solids/stripes dibaca lewat oracle game pada snapshot saat
  state bidik; bila game mengubah campstate di tengah state itu
  (tidak terjadi dalam alur normal), snapshot berikutnya yang
  menangkapnya.
