package com.hozin.poolmod;

/**
 * State bersama antara thread game (Hooks, dipanggil dari GameScene.tick)
 * dan thread UI (ModMenu overlay). Semua field volatile.
 *
 * Gerbang PvP SATU JALUR (v1.4.2): bool pvpLatched di bawah di-set
 * oleh Keamanan.perbarui() (dipanggil Hooks.tick() sekali per tick;
 * di sanalah satu-satunya panggilan refleksi rule.isPVPMode()).
 * Semua titik masuk fitur (prediksi, autoplay, eksekusi, fitur
 * per-frame, UI) membaca bool ini saja, tanpa refleksi ulang.
 * Alasannya performa: dulu sampai 5 panggilan refleksi per tick.
 */
public final class ModState {
    // saklar pengguna (default sesuai spek: prediksi ON, long line ON,
    // auto play otomatis OFF — hanya jalan bila user menyalakannya)
    public static volatile boolean predictOn = true;
    public static volatile boolean longLineOn = true;
    public static volatile boolean autoOn = false;

    // saklar fitur v1.4 (SEMUA default OFF, di belakang gerbang PvP
    // yang sama): B anti-scratch, D magnet lubang, C prank giliran lawan
    public static volatile boolean antiScratch = false;
    public static volatile boolean magnetOn = false;
    public static volatile boolean prankOn = false;
    public static volatile boolean autoWin = false;
    public static volatile boolean freeCoin = false;
    public static volatile boolean freeCue = false;

    // status mesin pemicu Auto Play (diisi AutoTrigger lewat Hooks,
    // ditampilkan ModMenu sebagai baris status Auto) + status magnet
    // (alasan diam, mis. "grup belum terbaca")
    public static volatile String autoStatus = "";
    public static volatile String magnetStatus = "";

    // tingkat kesulitan Auto Play (Perintah 6): 0 Normal, 1 Expert
    // (bawaan), 2 Mustahil — mengubah kedalaman pencarian & gaya,
    // tidak pernah mengubah aturan keras legalitas.
    public static volatile int difficulty = 1;

    // pengaman Perintah 2: jumlah kegagalan merencanakan berturut-turut
    // pada mode Auto Play ON; mencapai 3 -> saklar mati sendiri.
    public static volatile int autoFailCount = 0;

    // true selama Anim menjalankan satu siklus tembakan (penempatan /
    // spin / putar / tarik / hentak / tunggu berhenti) — pemicu
    // pencarian baru ditahan selama siklus berjalan.
    public static volatile boolean animBusy = false;

    // mesin pemicu auto play otomatis (diisi Hooks di thread game):
    // pasangan (state,giliran) stabil -> dua probe posisi berjarak
    // >=700 ms yang sama -> pencarian; autoHoldKey mencegah pengulangan
    // untuk posisi meja yang sama (termasuk keputusan "tidak menembak").
    public static volatile int autoPair = -1;
    public static volatile long autoPairSinceMs = 0L;
    public static volatile long autoProbeKey = Long.MIN_VALUE;
    public static volatile long autoProbeMs = 0L;
    public static volatile long autoHoldKey = Long.MIN_VALUE;
    public static volatile long statusMs = 0L;

    // ditulis thread game tiap tick
    public static volatile long lastTickMs = 0L;
    public static volatile boolean inGame = false;
    public static volatile Object sceneRef = null;
    public static volatile Object ruleRef = null;
    public static volatile boolean pvpLatched = false;
    public static volatile int stateNow = -1; // RoundState.state terakhir
    public static volatile boolean humanTurn = false;
    public static volatile String modeLabel = "-";
    public static volatile String statusText = "";
    // readout diagnosis (menu baris kedua): kenapa prediksi tidak
    // menggambar / apa keputusan autoplay terakhir — jembatan bukti
    // runtime dari HP user (diisi Hooks & Auto, dibaca ModMenu)
    public static volatile String predictDiag = "";
    public static volatile String autoReport = "";

    // permintaan dari UI -> dieksekusi thread game
    public static volatile boolean autoRequested = false;
    public static volatile boolean autoBusy = false;

    // strength terakhir yang benar-benar diatur pemain (untuk pratinjau
    // prediksi saat strength bar belum ditarik)
    public static volatile float lastStrength = 1500.0f;

    private ModState() {
    }
}
