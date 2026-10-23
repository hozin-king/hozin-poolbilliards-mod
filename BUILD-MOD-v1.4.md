# BUILD MOD v1.4 — Pool Billiards Pro Mod

Tanggal: 23 Okt 2026. Melanjutkan v1.3 dari DUA KELUHAN NYATA uji HP
user (23 Okt 20:14–20:15 WIB) + koreksi & ekspansi user (20:17,
20:21, 20:22, 20:24, 20:25 WIB).

- Commit kode: `b4d02de2` (hasil tulis-ulang riwayat 23 Okt 2026
  dari `de14e2f`; lokal `5f6af99`), tag `v1.4`
- CI: HIJAU — run `39989712681` attempt 2 (tag `v1.4`) SUCCESS:
  seluruh uji JVM + build + `verify.py` hijau di runner.
  Jalan menuju hijau: run awal `39984360660` gugur dalam 4 detik
  tanpa step karena kuota Actions repo privat Oktober habis
  (BUKAN kegagalan kode — semua tahap CI sudah direplikasi lokal
  dengan toolchain identik: JDK 17 Temurin, apktool 2.10.0,
  build-tools 34.0.0, semua hijau). Repo lalu DIRESTRUKTUR atas
  perintah user (23 Okt 2026): `mod.keystore` dipindah ke GitHub
  Secrets (`POOL_KEYSTORE_BASE64`, `POOL_STORE_PASS`,
  `POOL_KEY_ALIAS`, `POOL_KEY_PASS`), build.sh membaca kredensial
  dari env, dan SELURUH riwayat ditulis ulang (replay 9 commit,
  urutan+pesan dipertahankan; tree lama minus keystore, literal
  password di build.sh diganti referensi env) sampai tidak ada
  ref yang menunjuk tree berisi keystore — terverifikasi via API
  untuk main + tag v1–v1.4. Repo diflip publik hanya selama
  jendela CI, lalu kembali privat (terverifikasi 2×). Sertifikat
  TIDAK diganti: keystore yang sama, APK CI tetap `8682ff3c…`.
  Backup pra-restruktur: `~/workspace/hozin-poolbilliards-mod-backup-20261023/`
  + keystore di `~/workspace/hozin-pool-keys/`.
- Release: https://github.com/hozin-king/hozin-poolbilliards-mod/releases/tag/v1.4
  (id 422049964; aset APK SUDAH dilampirkan CI dari dalam
  workflow: `PoolBilliardsPro-Mod-v1.4.apk`, 16.662.880 byte,
  SHA-256 `330dc1cf9bf15d01666cd36f5d8a89a75eb253e2a9e298fc0c769dd53d0eefd6`
  — beda dari build lokal karena build tidak bit-reproducible;
  sertifikat APK CI terverifikasi `8682ff3c…` via apksigner)
- APK terkirim (build lokal terverifikasi penuh): 16.761.184 byte,
  SHA-256 `2b88a5892e20cf28685d32910e04f8625974a0767ad074649979e377906b8d96`
- Sertifikat SHA-256 `8682ff3cf4825833e87cb8b178ab07e5e21d65096b64a944cbadc57b2137f2db`
  — identik v1–v1.3, timpa pasang aman
- Salinan: `~/workspace/your_files/PoolBilliardsProMod-v1.4.apk`

## Keluhan 1 — Auto Play tidak punya saklar persisten

### Akar masalah (dari kode v1.3)
ModMenu v1.3 SEBENARNYA punya `Switch` "Auto Play"
(`swAuto` → `ModState.autoOn`, persisten di prefs `poolmod`) dan
verify.py membuktikan string itu ada di APK rilis — jadi saklarnya
tidak hilang. Yang rusak adalah PEMICUNYA (`Hooks.maybeAutoTrigger`):

1. Tidak pernah diuji host ujung-ke-ujung dan tidak punya
   observabilitas: selama "menunggu", ia tidak menulis status apa
   pun ke menu. Di perangkat ia tidak bisa dibedakan dari saklar
   yang tidak ada.
2. Syarat tembaknya rantai rapuh: pasangan (state,player) stabil
   ≥900 ms DAN dua probe `Snap.capture` PENUH yang terkuantisasi
   identik ≥700 ms terpisah DAN tidak ada kunci posisi yang
   tertahan. Kegagalan diam di titik mana pun = tidak pernah
   menembak, tanpa penjelasan.
3. `autoHoldKey` menahan posisi secara permanen sesudah satu
   putusan "tidak ada tembakan" pada posisi itu — termasuk putusan
   dari pencarian yang gagal karena alasan sesaat.

Kandidat akar lain disingkirkan dengan bukti: field `winner`
selalu di-reset ke −1 oleh `resetRoundState` semua rule; 
`getPlayerType` (oracle giliran) adalah panggilan yang sama
dengan jalur tombol AUTO PLAY yang bekerja; penangkapan Snap null,
`autoBusy` macet, dan basi-nya build CI masing-masing berbagi
jalur dengan tombol yang terbukti bekerja.

### Perbaikan
Mesin status eksplisit baru `AutoTrigger` (kelas murni, teruji
JVM): fase WAIT → STABILIZE (kunci posisi ringan dari bola hidup,
tanpa snapshot penuh; diam 700 ms) → tembak → WORKING → RETRY
(jeda 2,5 dtk lalu cari lagi bila pencarian tanpa hasil). Status
manusiawi ditulis ke `ModState.autoStatus` di SETIAP fase dan
tampil di menu. Saklar OFF di tengah menunggu membatalkan bersih;
ON lagi memulai dari awal. Pengaman lama dipertahankan:
3 rencana gagal beruntun → saklar mati sendiri (di `Auto`,
dengan alasan tertulis di readout keputusan). Gerbang PvP:
pemeriksaan `isPVPMode()` segar di Hooks sebelum pemicu DAN
tepat sebelum eksekusi; saklar dipaksa mati + panel disembunyikan
di PvP. Uji: `TestTrigger` 18/18 (dua giliran berurutan menembak
tepat sekali, OFF di tengah, retry sesudah gagal, PvP tidak
pernah, free-ball, ronde selesai, posisi bergeser menahan picu).

## Keluhan 2 — Arcade "COMING SOON"

### Penyebab pasti (bukti kode `LevelScene.smali` bawaan)
Konstruktor `LevelScene` membangun `ScrollHContainer(8)` dengan
8 kartu. Kartu indeks 7 (id tombol 17) dikodekan keras memakai
region tekstur `"tablebx"` dan judul `"stagetitle7"`. Di atlas
`assets/menu_button.pack`: `tablebx` (xy 1,138, 526×64) ADALAH
gambar banner "COMING SOON" itu sendiri, dan region
`stagetitle7` TIDAK ADA (stagetitle0..6 saja). `onButtonClick`
hanya membuka panel level untuk id 10..16 (stage 0..6); id 17
jatuh lewat tanpa aksi. Kunci pendukung: `MAX_TABLE = 0x7`;
`initLevelPanel` menolak stage ≥ 7; array skor `int[7][30]`;
`LevelRule.start_angle` statis `int[7][30]` yang diindeks
`getDefaultAimAngle()` TANPA cek batas (stage ≥ 7 = crash).
Sebaliknya loader TIDAK terkunci: `LevelManager.loadData`
membuka `data/level_stage_<stage>.txt` dan mengambil baris ke-
`level` tanpa cek batas stage; `Main.startGameInArcadeMode`
tanpa penjepit. Jadi 210 level bawaan memang selalu bisa
dimainkan dari kartu 0–6 (dengan buka-progres bawaan), dan yang
mati hanyalah kartu ke-8 — plang "segera hadir" untuk stage
yang tidak pernah dirilis pengembangnya.

### Perbaikan (patch smali via apply_patches.py, semua berasersi)
- Konstruktor: dimensi skor [7][30]→[10][30]; kapasitas
  ScrollHContainer 8→11 (juga memperpanjang idealPos + batas
  loop kartu, satu konstanta yang sama); sentinel `tablebx`
  7→10 (kartu teaser pindah ke ujung sebagai kartu ke-11);
  batas gulir 6000→9000.
- `initLevelPanel`/`initTable`/`loadPreference`: batas 7→10.
- `onButtonClick`: batas atas id kartu 0x11→0x14 (id 10..19 =
  stage 0..9; id 20 teaser tetap tanpa aksi).
- `pageIndexChange`: halaman terakhir 7→10. `MAX_TABLE` 7→10.
- `LevelRule.<clinit>`: `start_angle` dibangun ulang menjadi
  [10][30] (7 baris lama disalin via System.arraycopy, 3 baris
  baru dari payload fill-array-data mod).
- Atlas: region `stagetitle7/8/9` ("8"/"9"/"10") digambar
  (PIL, gaya digit bawaan: serif tebal cokelat sangat gelap)
  ke ruang kosong kanvas 1024×1024 yang terverifikasi tidak
  tumpang tindih piksel MAUPUN rect region lain; kanvas TIDAK
  diperbesar (UV atlas dihitung dari ukuran tekstur).
- Hasil: 210 level bawaan terjangkau penuh dari menu normal
  (gerbangnya dibuka, datanya tidak diubah), ditambah 90 level
  baru di bawah.

### 90 level baru (Stage 8/9/10 × 30)
Format baris identik bawaan (`ballCount blackHoleCount
[holeIdx...] (texId x y)*`), dibuat `tests/GenLevels.java`
dengan kesulitan progresif (Stage 8: 16–20 bola, Stage 9:
19–23, Stage 10: 22–25; black hole 0–3 mengikuti pola bawaan;
koordinat dalam rentang bawaan). SETIAP level diverifikasi bisa
TAMAT oleh klon Sim + perencana Auto (DIFF_EXPERT) dengan
penghitungan restTime persis LevelRule (mulai 6; tanpa pot −1;
scratch −2; tanpa kontak −2; black hole −2/bola) — dua kali:
saat dibuat dan ulang dari berkas final (`TestLevels`, juga
jalan di CI — total 21.221 pemeriksaan struktur+simulasi).
Per level: `mod-assets/levels_report.txt` (bola, black hole,
jumlah tembakan perencana, sisa cue). Ringkasan: sisa cue
minimum 1 (14 level tamat dengan sisa tepat 1 — ketat tapi
terbukti), 58 level sisa ≥3. Tepat SATU level disederhanakan
oleh jalur fallback generator sesudah 25 kandidat penuh gagal:
stage 9 level 26 (19 bola + 2 black hole, dari titik kurva
22 + 3); 89 level lain tepat pada kurva kesulitan targetnya.
Catatan jujur: lubang +cue/−bola TIDAK ada di berkas level —
game menaburkannya sendiri secara kondisional pada (stage,
level) tertentu saat transisi ronde; stage baru umumnya di luar
rentang kondisional itu, jadi mekanik spesial level baru adalah
black hole, sama seperti pola berkas bawaan.

## Fitur A — Break shot khusus
Posisi break dikenali dari state game (`PoolTable.isFirstShoot`
+ rack penuh ≥9 bola objek, bukan Arcade). Perencana memakai
`planBreak`: kekuatan maksimum/94%, bidik titik ghost
tabrakan-penuh ke bola apex dengan offset lateral {0,±10,±20},
dinilai dari simulasi (jatuh ×1500 + bergeser jauh ×25, scratch
ditolak mutlak). Uji `TestBreak` pada rack standar 8-ball:
2 bola jatuh, 12/15 bola bergeser >120 satuan, tanpa scratch;
variasi sudut ±0,4° tetap ≥8 bergeser. Auto Play memakainya
otomatis di tembakan pertama.

## Fitur B — Anti-Scratch Total (saklar, default OFF)
Patch smali di kepala `PoolTable.getNextHitTime(PoolBall,
PoolHole)`: bila `ModState.antiScratch` menyala dan bola yang
diuji adalah bola bidik (`rule.getClickTarget(roundstate)` —
panggilan yang sama dengan `PoolTable.click`), kembalikan
MAX_TIME (3600.0f). Satu-satunya corong penangkapan lubang
(`PoolBall.inHole` hanya ditulis dari jalur ini) membuat bola
putih tidak pernah masuk zona lubang: tidak ada gravitasi
lubang, tidak ada penangkapan, bola menggelinding melewati
lubang. Pelengkap Java: bila saklar dinyalakan di tengah
luncuran, `inHole` bola putih yang telanjur terisi dikosongkan.
Cermin Sim (`Sim.cueHoleImmune`) diuji `TestAntiScratch` 30/30:
6 lubang × kecepatan {500,1500,3000} + berhenti tepat di atas
lubang → tidak pernah tertangkap; kontrol OFF → scratch.

## Fitur C — Prank giliran lawan (saklar, khusus VS Computer, default OFF)
Selama giliran AI (rule `Vsai*`, pemain ≠ 0, state bidik/
bergulir), semua `PoolBlocker` dibuat tidak terlihat lewat
`setVisible(false)` — fisika game sendiri melewati tabrakan
cushion untuk blocker tak terlihat. Perilaku bola yang keluar
area DARI KODE GAME (blok wrap di `updateRunning`): posisi
di-wrap ke sisi berlawanan meja sampai friksi menghentikan —
tidak hilang, tidak respawn, tidak crash. Visibilitas dipulihkan
di giliran user / saklar mati / keluar meja / PvP (banyak jalur
pemulihan, termasuk `onSceneGone` dari Hooks). Uji `TestPrank`
6/6 di Sim: kontrol memantul normal, prank menembus + ter-wrap,
sesudah pulih normal lagi. Batas jujur: uji perangkat tetap
perlu — perilaku wrap dibaca dari port setia updateRunning di
Sim dan kode smali-nya, bukan dari rekaman HP.

## Fitur D — Magnet Lubang (saklar, default OFF)
Selama bola bergulir sesudah tembakan USER, bola target menerima
delta kecepatan homing ke lubang terdekat tiap tick
(`Magnet.steerDelta`, akselerasi dibatasi + kecepatan minimum).
Kelayakan dibaca SEGAR dari oracle aturan (`Snap.capture` +
`isAvailableTarget`, pola v1.1) pada awal luncuran dan DIBACA
ULANG tiap jumlah bola berubah di tengah luncuran (penetapan
grup terjadi saat bola pertama masuk):
- Mode berkelompok (VS 8-ball): HANYA bola grup user sendiri.
  Bola lawan tidak pernah. Meja terbuka (grup belum terbentuk):
  semua bola objek selain bola 8 sah — dan berhenti begitu grup
  terbentuk. Bola 8 (dan bola uang mode lain) TIDAK PERNAH;
  bola putih TIDAK PERNAH.
- Mode tanpa grup (Arcade/Time): semua bola objek.
- Grup/state tak terbaca → target null → magnet diam
  (fail-closed) dan menu menulis alasannya persis:
  "grup belum terbaca".
Uji `TestMagnet` 11/11: matriks kelayakan solids/stripes/
terbuka/tak terbaca + hasil simulasi (bola sendiri masuk,
lawan pada lintasan sama tidak diarahkan, bola putih tidak
ikut, bola 8 dikecualikan bahkan di meja terbuka).

## UI menu (permintaan 20:22)
Panel bisa digeser dari header-nya (≡); tombol "—" minimize ke
ikon MOD; ikon juga bisa digeser; posisi panel + ikon dan
keadaan tampil/sembunyi dipertahankan selama sesi game (state
statis proses + intent user, watchdog hanya mencocokkan dengan
gerbang sesi). Di PvP menu hilang total termasuk ikon (gerbang
yang sama). Saklar/readout lama tidak berubah perilaku; readout
kini juga memuat baris status pemicu Auto dan status Magnet.

## Disiplin rilis
Uji host: TestSim + TestAuto (warisan) hijau, TestTrigger 18/18,
TestAntiScratch 30/30, TestMagnet 11/11, TestPrank 6/6,
TestBreak 9/9, TestLevels 90 level (21.221 pemeriksaan).
Build lokal toolchain-identik-CI: BUILD OK + VERIFY OK penuh
(termasuk semua penanda v1.4 poin 4g/4h + aset poin 5).
CI Actions HIJAU (lihat header): restruktur keystore → Secrets
+ tulis-ulang riwayat membuka rute flip-publik-aman, dan aset
Release v1.4 sudah dilampirkan CI. Repo kembali privat
(terverifikasi via API 2×).

## Yang masih menunggu uji HP
Koneksi semua fitur di perangkat nyata: pemicu Auto Play pada
giliran sungguhan (termasuk free-ball), kartu Stage 8/9/10 +
panel levelnya di menu Arcade, memainkan level baru sampai
tamat di game asli, patch Anti-Scratch di mesin asli, magnet
pada laju frame HP (delta per tick dikalibrasi dt), prank +
wrap di mesin asli, seret/minimize panel di HyperOS.

## v1.4.1
Perbaikan UI menu saja: baris saklar digambar sendiri sebagai pil
ON/OFF (hijau ON / abu OFF, ketuk baris atau pil untuk mengubah)
menggantikan saklar Switch bawaan Android yang tidak tergambar di
HP user (HyperOS) — di v1.4 baris menu tampil hanya sebagai teks
dengan label kecil tanpa tombol yang terlihat. Isi fitur sama
persis dengan v1.4; gerbang PvP, kunci setelan (prefs), dan
perilaku semua saklar tidak berubah.
APK build lokal (toolchain identik CI): 16.662.880 byte,
SHA-256 ee36d9099bd28a57b03cdcc03f2603ea376b4a01690f2c9650b8b00900007438,
sertifikat sama 8682ff3c... (bisa timpa pasang di atas v1.4).
