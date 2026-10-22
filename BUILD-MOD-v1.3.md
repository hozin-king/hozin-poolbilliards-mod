# BUILD MOD v1.3 — Pool Billiards Pro Mod

Tanggal: 22 Okt 2026. Melanjutkan v1.2 (perbaikan prediksi hilang +
pemilihan tembakan) dengan Perintah 1–7 user (21:23–21:47 WIB):
animasi stik, saklar Auto Play, spin AI, penempatan free ball,
perencana expert, tingkat kesulitan, dukungan Arcade.

- Commit kode: `02f5ffe` (lokal `9e7281e`), tag `v1.3`
- CI: run 39804340788 — hijau penuh (uji JVM + build + verify)
- Release: https://github.com/hozin-king/hozin-poolbilliards-mod/releases/tag/v1.3
- APK rilis: 16.572.543 byte, aset id 666251274, diunduh balik & lolos
  verify.py penuh (termasuk asersi 4f penanda v1.3)
- Sertifikat SHA-256 `8682ff3cf4825833e87cb8b178ab07e5e21d65096b64a944cbadc57b2137f2db`
  — identik v1/v1.1/v1.2, timpa pasang aman
- Salinan: `~/workspace/your_files/PoolBilliardsPro-Mod-v1.3.apk`

## Bukti kode game (smali `work/apk/smali/com/forthblue/pool/`)

### Stik = fungsi murni state bidik (Perintah 1)
- `GameScene` (scene/GameScene.smali): field `aimAngle`, `aimStrength`,
  `aimPx`, `aimPy`; metode `getAimAngle()`, `setAimAngle(F)`,
  `calcAimTarget()`, `shoot(Z)`.
- `setAimAngle` → `calcAimTarget()` → `PoolStick.setPosition/setAngle`
  dari `aimAngle`; update per frame menghitung ulang
  `stick.distance = (ballRadius + aimStrength × 0,1) × table.scalex`;
  `PoolStick.commitTo` merender translate(posisi) → rotasi(180−angle)
  → translate(distance, 0). Jadi putaran & tarikan stik digerakkan
  cukup dengan menulis kedua field itu per tick — stik yang bergerak
  adalah STIK ASLI game, bukan replika.
- `shoot(Z)` adalah pintu eksekusi resmi (jalur yang sama dengan
  lepasnya StrengthBar pemain): memanggil
  `table.click(aimStrength, aimPx, aimPy, aimAngle)` tepat satu kali
  lalu me-reset ketiganya ke 0. CATATAN: `setAimStrength` punya
  gerbang toast (`last_shoot_request_id`) — karena itu pada fase
  tarik & momen FIRE field ditulis langsung (Ref.setF), bukan setter.

### Spin (Perintah 3)
- Handler bola spin game (`GameScene$6`) menulis
  `aimPx = baseX + dx/100`, `aimPy = baseY + dy/100` (dijepit ke
  lingkaran satuan) dan `bigPPosition.setPosition(aimPx×100, aimPy×100)`
  — itulah titik merah. Anim menulis persis state yang sama sebelum
  stik bergerak; nilai `click()` berasal dari field yang sama, jadi
  yang dieksekusi = yang ditampilkan, tanpa kecuali.

### Free ball (Perintah 4)
- Fase penempatan = `RoundState.state == 3`; drag manual
  (`GameScene$8.onMotionOver`) menulis field `x/y` PoolBall milik
  `freeballtarget`, memvalidasi `rule.canPut(balls, id, x, y)`, lalu
  `table.putFreeball()` mengeset state ke 1. Anim menggeser field yang
  sama dengan interpolasi ±0,35 dtk, mengecek ulang `canPut` di titik
  akhir, lalu memanggil `putFreeball()` yang sama.

## Yang dibangun

### Anim.java — mesin siklus eksekusi (diganti dari eksekusi instan)
Fase: PLACE (geser free ball) → SPIN (tulis spin + titik merah, tahan
±0,2 dtk) → ROTATE (interpolasi sudut jalur terpendek ±0,3 dtk via
setAimAngle) → PULL (aimStrength 0→target 0,5–0,8 dtk proporsional
kekuatan) → STRIKE (turun ke ±12% dalam ±0,08 dtk) → FIRE (tulis
keempat field persis nilai terpilih, periksa ulang gerbang: PvP
segar, state==1, giliran manusia, meja tidak berubah sejak pencarian;
lalu `scene.shoot(false)` SATU kali) → SETTLE (tunggu dua probe
posisi sama + jeda natural hingga ±0,9 dtk sesudah tembakan).
Saklar dimatikan di tengah siklus: tembakan berjalan diselesaikan,
siklus baru tidak dimulai (Hooks menahan pemicu selama `animBusy`).

### Saklar Auto Play (Perintah 2)
Label menu kini "Auto Play" (tombol sekali-tembak dipertahankan
sebagai tambahan). ON = rantai giliran otomatis. Pengaman:
`ModState.autoFailCount` — 3 kegagalan rencana berturut-turut →
saklar mati sendiri + alasan di readout; checkbox menu disinkronkan
balik dari `ModState.autoOn` (mati-sendiri/PvP terlihat di UI).
Latch PvP kini juga mematikan saklar secara paksa.

### Perencana expert (Perintah 5)
- 5A di `filterReason`: hasil SIMULASI PENUH diperiksa — bola
  non-legal yang masuk dari jalur mana pun (pantulan/tabtrakan
  sekunder) = REJ_ILLEGAL tanpa kecuali; 8 dini = REJ_MONEY;
  scratch = REJ_SCRATCH. Penalti skor (bukan buang) untuk bola lawan
  yang berakhir <170 dari mulut lubang.
- Tangga: Pot → Bank (refleksi lubang terhadap rel; multi-rel di
  Mustahil; syarat cushionHitCount) → Kick/rail-first (refleksi
  ghost; syarat cueRailFirst + kontak pertama legal) → Combination →
  Safety expert (syarat bola mencapai cushion sesudah kontak
  diutamakan; skor sembunyi: jarak ke bola termudah lawan + penghalang
  + cue dekat cushion) → Breakout cluster → Upaya paling tidak buruk
  (celah geometris terkecil; aturan 5A tetap berlaku penuh).
  Sim kini merekam fakta rel: `cushionHit[]`, `potAfterRail[]`,
  `cueRailFirst`, `railAfterContact`.
- Sampling: genPots mode standar untuk Expert, grid spin 8 titik
  (12 di Mustahil); `chooseSpin` kini juga mengadopsi spin yang
  memasukkan LEBIH banyak bola sah (follow multi-pot).
- Position play 2 langkah: `nextShotValue` menilai pot terbaik
  berikutnya dari posisi akhir (rantai legalitas via `chainLegal`).
  Kontribusinya ke skor akhir DIJEPIT (±400 × 0,3) sebagai penentu
  sekunder — pelajaran uji h2: tanpa jepit, nilai posisi mengalahkan
  pot yang jauh lebih enak, menghidupkan lagi keluhan v1.1.
- Readout: `[Tingkat] ...` + tingkat kesulitan + penghitung
  kandidat/ditolak per sebab (scratch/kontak/ilegal/bola8dini/
  blackhole).

### Tingkat kesulitan (Perintah 6) — ModMenu: tombol "Kesulitan: …"
- NORMAL: pot langsung + safety sederhana saja, sampling kasar,
  tanpa 2-ply, spin hanya via penyelamatan pot (tanpa kesalahan
  buatan — yang alami saja).
- EXPERT (bawaan): Perintah 5 apa adanya.
- MUSTAHIL: pencarian rapat (refine sudut ±0,3°/0,15° × kekuatan
  untuk 5 kandidat teratas), bank/kick/combo multi-rel ikut
  bertanding melawan pot, dan PERENCANAAN RUN-OUT (`planRunout`,
  beam search lebar 3): rantai menghabiskan semua sisa grup + bola
  penentu; langkah pertama rantai menang bila skornya (dievaluasi
  ulang + chainLen×700) mengalahkan baseline; eksekusi langkah demi
  langkah dengan perencanaan ulang dari state nyata tiap tembakan.
  Free ball di Mustahil menilai posisi dari panjang rantai awalnya.
  Anggaran ±2,8 dtk; habis → hasil terbaik sejauh ini (turun anggun,
  tercatat di readout "rencana habiskan N bola").

### Arcade (Perintah 7, dari AUDIT-ARCADE.md)
- Snap menangkap `PoolHole.effect_type` per lubang + `restTime` hidup
  dari `LevelRule$GameRoundState` saat rule = LevelRule.
- `arcadeFilterReason`: tidak ada bola salah; yang keras = scratch,
  tanpa kontak, dan bola apa pun masuk lubang hitam (effect 1) →
  REJ_BLACKHOLE. Skor `arcadeBaseScore`: bola masuk ×1000 × urgensi
  restTime, bonus combo multi-pot, +900 lubang +cue (effect 5), +700
  lubang −bola (effect 4). Safety diganti SETUP (nilai tembakan
  berikutnya). Pruning geometri pasangan (bola,lubang) terhalang
  untuk level >15 bola. Readout: `[Arcade • sisa stik N]`.
- Titik sisip objektif per-mode: kelas `Objective` (standar) +
  cabang Arcade di filter/skor — audit mode lain tinggal menambah
  cabang yang sama.

## Bug yang ketemu & dibuktikan selama pembangunan
1. **Run-out mustahil terbentuk**: `Sim.Result.potted` mewarisi
   status potted dari snapshot — bola yang sudah masuk "masuk lagi"
   di snapshot turunan (jenazahnya ditaruh di posisi lubang) lalu
   ditolak saringan sebagai bola ilegal. Dibuktikan dengan debug
   JVM (potted=[1,2] pada tembakan yang hanya menyentuh bola 2).
   Fix: `Result.potted` = masuk OLEH tembakan ini (`wasPotted`),
   `cuePotted` konsisten, `advanceProto` menggabungkan status lama.
2. **Premis uji h2 keliru secara geometri**: bola 12 pada layout
   screenshot ternyata pot lurus ±0° ke kanan-atas (bukan potongan
   16°) — skor dasarnya memang setara pot 10. Uji diperbaiki:
   bola 12 dipindah ke pot potongan ±35° yang terbukti bisa masuk,
   dan pot 10 yang menang.

## Hasil uji (semua lokal, direproduksi CI)
- TestSim: lulus penuh (tidak berubah).
- TestAuto 17 skenario: (a)–(i) lama lulus dengan semantik tangga;
  (j) posisi terjepit screenshot 21:26 → keputusan tangga Bank yang
  bersih (bukan "tidak menembak"); (k) unit 5A: pot legal + bola
  lawan/8 ikut masuk → ditolak; (l) posisi sama: NORMAL = pot
  langsung chain 0, MUSTAHIL = rencana run-out 3 bola; (m) Arcade
  lubang hitam di tengah-atas → dipilih pot bola 2 ke kiri-atas,
  3 kandidat blackhole ditolak; (n) Arcade multi-pot 2 bola dipilih
  di atas pot tunggal; (o) Arcade scratch tetap ditolak.
- Harness animasi (`tests/harness/`, stub GameScene/PoolTable
  bersemantik smali): siklus normal — shoot tepat 1× dengan nilai
  persis, titik merah (30,−45) tertulis sebelum tembakan, tarikan
  bertahap teramati (aimStrength 225 di tengah PULL); free ball —
  bola tergeser bertahap (x tengah 324 dari 500→−300), putFreeball
  1×, lanjut menembak; PvP muncul sebelum FIRE — siklus batal,
  nol tembakan.
- Kompilasi semua file se-repo bersih; build lokal + verify.py
  hijau sebelum push; CI 39804340788 hijau; aset rilis diunduh balik
  + verify ulang hijau.

## Batas jujur
- Stik yang dianimasikan adalah stik ASLI game (digerakkan lewat
  field state bidiknya sendiri — bukti di atas). Hentakan STRIKE
  terlihat lewat derivasi `distance` game dari aimStrength; tidak
  ada klaim di luar itu.
- Rantai run-out panjang & tembakan multi-pantulan ekstrem paling
  peka selisih float klon-vs-native: parameter dihitung eksak dari
  simulasi, bukan janji bola pasti masuk.
- Runtime HP tidak terverifikasi dari VM. Harness v1.2 berkode
  Basket asli hilang bersama /tmp saat VM restart; harness animasi
  pengganti kini disimpan di repo (`tests/harness/`). Jalur Predict
  tidak diubah di v1.3 selain pemanggilan `hideAll` dari Anim —
  perbaikan reset v1.2 utuh. Readout keputusan di menu adalah
  jembatan verifikasi di perangkat.
