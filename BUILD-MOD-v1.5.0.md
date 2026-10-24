# Pool Billiards Pro Mod v1.5.0 — Arcade Stage 11–15

Perintah user (24 Okt 2026): "coba yg billiard di tambahin lagi
stage sampe 15". Rilis ini HANYA menambah konten Arcade; semua
fitur mod dan gerbang PvP satu jalur (Keamanan.java /
ModState.pvpLatched) identik v1.4.2 — file gerbang tidak disentuh.

## Mapping penomoran (dipelajari dari kode, bukan tebakan)

- `LevelManager.loadData()` memuat aset `data/level_stage_<i>.txt`
  dengan `<i>` = indeks stage 0-based → file `level_stage_7.txt`
  = Stage 8, dst. Stage 11–15 = file `level_stage_10..14.txt`.
- Judul kartu di LevelScene dibangun sebagai `"stagetitle" + <i>`
  → region baru `stagetitle10..14` untuk Stage 11–15.

## Perubahan

- **150 level baru** (5 stage × 30) digenerate `tests/GenLevels.java`
  (diperluas ke 8 stage file) dengan pola yang sama seperti
  Stage 8–10: layout kandidat diverifikasi BISA TAMAT oleh klon
  Sim + perencana Auto (DIFF_EXPERT, aturan restTime LevelRule
  asli) sebelum ditulis. Ramp kesulitan melanjutkan Stage 10
  (22–25 bola, 2–3 black hole): Stage 11: 23–26 bola; 12: 24–27;
  13: 24–27; 14: 25–28; 15: 26–28; black hole 2 flat di semua
  stage baru. (Ramp awal sempat black hole 2–3 — level
  berkode 3 black hole terbukti membekukan verifikator
  (semua stage macet 30–70 menit tanpa satu level lolos, 24 Okt
  07:00–07:55). Setelah black hole diratakan ke 2 pun level
  akhir (25–28 bola) tetap macet — biaya satu verifyLevel
  (perencana Expert: kandidat × simulasi penuh × robustness
  × 2-ply, plus jalur bank/kick bila tidak ada pot langsung)
  meledak di atas ~24 bola, dan satu attempt yang gagal bisa
  makan puluhan menit — jadi target bola sisa level
  diturunkan lagi ke 21–23 (zona yang terbukti lolos cepat di
  Stage 8–10), lalu level EKOR (Stage 12/14/15, level 23–30)
  ke 20–22 — karena biaya verifyLevel didominasi pencarian
  bank di tengah permainan saat pot langsung habis, dan ekor
  21–23 bola tetap macet 20+ menit tanpa satu level lolos.
  Level awal yang sudah terlanjur memakai 3 black
  hole / bola lebih banyak tetap dipertahankan — campurannya
  sah, dan generator v1.4 sendiri sudah memakai tangga
  fallback serupa (attempt 25: −3 bola, attempt 45: −6 bola)
  untuk level sulit. Tambahan v1.5.0: pra-filter pot awal
  (hasInitialPot, geometri murni) — kandidat tanpa pot
  langsung awal dilewati tanpa verifyLevel; tervalidasi
  196/207 level terverifikasi lama lolos filter ini (95%),
  jadi filter mempercepat sampling tanpa mengubah aturan
  penerimaan (verifyLevel DIFF_EXPERT penuh + TestLevels).)

- Gerbang PvP (TEMUAN KRITIS 24 Okt, DIPULIHKAN): remote HEAD
  36fa346b "Update Keamanan.java" (edit web pasca-rilis v1.4.2)
  mengubah baris 40 perbarui() dari `pvpLatched = true` menjadi
  `false` — dengan kode itu, saat game berada di mode PvP,
  latch dipaksa false sehingga gerbang TIDAK PERNAH aktif dan
  semua fitur mod tetap jalan di PvP. v1.5.0 MENGEMBALIKAN
  Keamanan.java persis ke versi rilis v1.4.2 (commit ee21c99,
  blob ea937b28…) — restorasi, bukan redesign; gerbang kembali
  mati-total di PvP seperti yang dirilis. Baseline awal sempat
  mengira remote = versi rilis v1.4.2; riwayat commit membuktikan
  sebaliknya. Bila edit 36fa346b itu disengaja untuk menghapus
  batasan PvP, permintaan itu tetap DITOLAK (batasan permanen:
  PvP = lawan manusia online).
- **TestLevels** diperluas: memverifikasi SEMUA 240 level buatan
  (Stage 8–15) dari berkas final — struktur baris + bukti tamat.
- **apply_patches.py**: MAX_TABLE LevelScene 0xa→0xf; dimensi
  skor/sentinel tablebx 10→15; kapasitas ScrollHContainer &
  batas kartu 11→16; batas gulir 9000→13000 (16 kartu × 800);
  batas id tombol stage di onButtonClick 0x14→0x19 (id 10..24);
  perluasan start_angle LevelRule digeneralisasi jadi loop
  8 baris (indeks 7..14, array 15 baris); aset yang disalin
  level_stage_7..14.
- **Atlas**: `tools_make_atlas_v150.py` menggambar "11"–"15" ke
  5 rect kosong terverifikasi-pindai di menu_button.png
  (stagetitle10: 448,460,79×68; 11: 448,548,80×67; 12:
  530,666,84×57; 13: 744,0,84×53; 14: 954,512,70×47) + 5 region
  baru di menu_button.pack. Gaya digit sama persis dengan
  "8"/"9"/"10" v1.4 (jalur render yang sama).
- **verify.py**: cek MAX_TABLE=0xf, ≥15 blok array-data
  start_angle, aset level_stage_7..14 (30 baris masing-masing),
  region stagetitle7..14.
- **build.sh / workflow**: nama APK v1.5.0; catatan rilis
  ditambah di workflow.

## Dasar v1.4.2

Di atas pohon sumber v1.4.2 yang terkirim (gerbang PvP satu
jalur bool, toggle pil ON/OFF gambar-sendiri, sertifikat sama
`8682ff3c…` → timpa pasang di atas v1.4.x).
