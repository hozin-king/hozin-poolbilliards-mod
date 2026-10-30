# AUDIT PRA-RILIS v1.5.0 — Pool Billiards Pro Mod

Tanggal: 24 Okt 2026. Repo: `hozin-king/hozin-poolbilliards-mod`
(publik sementara untuk build, lalu kembali privat — sesuai
perintah user 24 Okt 2026).

## Hasil: BERSIH, layak flip publik + push + tag

### 1. Pindai secret di tree yang akan di-push
- `git status` final: file baru = 5 berkas level
  (`mod-assets/data/level_stage_10..14.txt`), `Keamanan.java`
  (restorasi, lihat §3), `tools_make_atlas_v150.py`,
  `BUILD-MOD-v1.5.0.md`, `AUDIT-PRA-RILIS-v1.5.0.md`;
  diubah = workflow, `apply_patches.py`, `build.sh`, `verify.py`,
  `README.md`, `BUILD-MOD-v1.4.md`, `.gitignore`, atlas
  `menu_button.png/.pack`, `start_angle_rows.txt`,
  `tests/GenLevels.java`, `tests/TestLevels.java`, berkas
  `mod-src` (konten v1.4.2 yang belum pernah ter-commit lokal);
  dihapus = `mod.keystore` (sudah tidak ada di remote sejak
  v1.4.2 — penghapusan ini ikut ter-commit, sesuai remote).
- Grep pola secret (`password|secret|token|api_key|keystore|
  BEGIN .* PRIVATE KEY`) atas tree: hanya temuan jinak —
  referensi env (`POOL_KEYSTORE`, `POOL_KEYSTORE_BASE64` dari
  GitHub Secrets, bukan nilai), `getWindowToken()` Android,
  dan penyebutan historis pemindahan keystore di
  `BUILD-MOD-v1.4.md`. Tidak ada token `ghp_`/`github_pat_`,
  blok private key, kunci AWS, atau nilai kredensial apa pun.
- Signing aman secara desain: keystore HANYA di
  `~/workspace/hozin-pool-keys/mod.keystore` (lokal, di luar repo)
  dan GitHub Secrets `POOL_*` (CI); `build.sh` membaca dari env.
  `.gitignore` menutup `*.keystore`/`*.jks`/`*.idsig`.
- Intermediate generator TIDAK ikut ter-commit:
  `mod-assets/levels_report_*.txt` dan
  `mod-assets/start_angle_rows_*.txt` (fragmen) ditambahkan ke
  `.gitignore`; yang ter-commit hanya gabungan
  `start_angle_rows.txt` (8 baris × 30 nilai 0..3).

### 2. Riwayat (sejak audit v1.4.2)
- Audit v1.4.2 sudah menyatakan tree + seluruh riwayat bersih.
- Sejak itu hanya 2 commit: `ee21c99` (v1.4.2) dan `36fa346b`
  ("Update Keamanan.java", edit web). Keduanya diperiksa
  isinya via API — tidak ada secret; `36fa346b` hanya 1 baris
  (lihat §3).

### 3. TEMUAN KRITIS — gerbang PvP sempat dilumpuhkan, DIPULIHKAN
- `36fa346b` mengubah `Keamanan.java` baris 40 di `perbarui()`
  dari `ModState.pvpLatched = true` menjadi `false`. Dengan kode
  itu, saat game berada di mode PvP, latch dipaksa false —
  gerbang TIDAK PERNAH aktif dan semua fitur mod tetap jalan
  di PvP (melawan manusia online). Ini BUKAN versi rilis v1.4.2
  (baseline awal sempat mengira demikian; riwayat membuktikan
  sebaliknya — edit web pasca-rilis).
- v1.5.0 MENGEMBALIKAN `Keamanan.java` persis ke versi rilis
  v1.4.2 (commit `ee21c99`, blob `ea937b28…`, diverifikasi
  byte-identik via `git hash-object`). Ini restorasi, bukan
  redesign: gerbang kembali mati-total di PvP, fail-closed,
  satu jalur bool seperti yang dirilis. Bila edit `36fa346b`
  itu disengaja untuk menghapus batasan PvP, permintaan itu
  tetap DITOLAK (batasan permanen: PvP = lawan manusia online).
- Catatan ini juga ditulis di `README.md`, `BUILD-MOD-v1.5.0.md`,
  dan body rilis workflow — transparan, tidak diam-diam.

### 4. Tes lokal (pra-push)
- Suite JVM (JDK 17, pola langkah CI): `TestSim`, `TestAuto`,
  `TestTrigger` (18), `TestAntiScratch` (30), `TestMagnet` (11),
  `TestPrank` (6), `TestBreak` (9) — SEMUA LULUS.
- Level: `TestLevels` resmi lokal sempat jalan 73/240 tanpa
  gagal lalu dihentikan untuk menghemat CPU; verifikasi penuh
  240/240 dilakukan driver per-stage (8 proses JVM terpisah,
  `verifyLevel` DIFF_EXPERT + assertion struktur yang sama
  persis seperti `TestLevels`): 8/8 file 30/30 LULUS, nol gagal.
  `TestLevels` resmi tetap berjalan penuh di CI sebagai gerbang
  rilis (job timeout 300 menit).
- `verify.py` adalah verifier pasca-build (butuh APK jadi) —
  berjalan di CI, bukan lokal.

### 5. Kontinuitas signing
- CI memakai Secrets `POOL_*` yang sama seperti v1.4.2, sehingga
  sidik jari sertifikat APK rilis HARUS tetap `8682ff3c…`
  (timpa pasang). Diverifikasi setelah CI selesai, sebelum
  pengumuman rilis.
