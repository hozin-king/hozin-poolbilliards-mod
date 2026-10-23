# Pool Billiards Pro Mod v1.4.2 — Gerbang PvP Satu Jalur

Perintah user (24 Okt 2026): "bikin 1 jalur aja bikin lag soalnya" —
gerbang PvP yang dulu diperiksa berlapis di banyak titik masuk fitur
disatukan jadi satu jalur demi performa.

## Perubahan

- Logika gerbang dipindah ke kelas khusus **`Keamanan.java`**
  (penyempurnaan atas perintah yang sama, 24 Okt 07:16: "kek bool
  gitu aja... kodenya di-set di Keamanan.java"). Di sanalah
  satu-satunya panggilan refleksi `rule.isPVPMode()` di seluruh mod.
- Bentuknya bool sederhana: `Keamanan.perbarui(rule)` dipanggil
  `Hooks.tick()` tepat 1x per tick dan men-set bool
  `ModState.pvpLatched` — true = PvP (fitur mati total), false =
  offline. Bool menempel per instance rule, lengket sekali true,
  dan di-set OTOMATIS dari mode game (bukan saklar manual).
- Pembaca bool (tanpa refleksi sendiri): `Auto.startSearch`,
  `Features.tick`, `Anim.fire`, `Hooks.gateAllows` (UI),
  `Hooks.autoTriggerTick`/`updatePrediction` (mengandalkan tick yang
  sudah berhenti lebih dulu saat bool menyala).
- Sebelumnya: sampai 5 panggilan refleksi per tick (tick Hooks,
  Features, updatePrediction saat membidik, pemicu Auto, momen
  eksekusi Anim, plus gateAllows dari thread UI).
- Perilaku gerbang TIDAK berubah: PvP = semua fitur mati total +
  menu tersembunyi; bool dievaluasi ulang tiap tick dan menempel
  per instance rule seperti sebelumnya.
- `verify.py` 4c diperbarui: APK final wajib membuktikan isPVPMode
  HANYA ada di smali Keamanan, dan Auto/Features/Anim membaca
  `pvpLatched`.

## Dasar v1.4.1

Dibangun di atas pohon sumber v1.4.1 yang terkirim (toggle pil
ON/OFF gambar-sendiri, sertifikat sama `8682ff3c…` → timpa pasang).
