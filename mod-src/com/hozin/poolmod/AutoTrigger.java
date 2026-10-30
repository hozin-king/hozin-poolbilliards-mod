package com.hozin.poolmod;

/**
 * Mesin status pemicu Auto Play (v1.4) — logika MURNI tanpa dependensi
 * Android/game, supaya bisa diuji JVM dengan urutan tick tiruan.
 *
 * Menggantikan pemicu v1.3 (pasangan (state,giliran) stabil 900 ms +
 * dua probe identik berjarak 700 ms + autoHoldKey) yang TIDAK PERNAH
 * diuji host dan tidak menulis status apa pun selagi menunggu — di HP
 * user saklar terasa tidak ada: tidak ada tembakan, tidak ada
 * penjelasan. Mesin ini:
 *
 *  - mendeteksi awal giliran manusia secara eksplisit (state 1 bidik /
 *    state 3 free ball, pemain tipe 0, ronde belum selesai);
 *  - menunggu meja diam (kunci posisi tidak berubah >= 700 ms) lalu
 *    memerintahkan pencarian (START_SEARCH) — latensi total dari meja
 *    berhenti ke pencarian < 1 detik, bukan 1,6 detik;
 *  - selama pencarian/animasi berjalan: diam (fase WORKING);
 *  - bila pencarian selesai TANPA tembakan (tidak ada tembakan aman)
 *    dan meja tidak berubah: coba lagi sesudah jeda 2,5 detik
 *    (RETRY_WAIT) — penghitung gagal 3x di Auto yang memutuskan
 *    saklar mati sendiri, lengkap dengan alasan di readout;
 *  - menulis status yang bisa dibaca user di SETIAP fase
 *    ("Auto: menunggu giliranmu", "Auto: meja mulai diam…",
 *    "Auto: menghitung tembakan…", dst.) lewat kolom status hasil;
 *  - saklar dimatikan di tengah siklus / masuk PvP / ronde selesai ->
 *    kembali ke WAIT_TURN dan tidak pernah memicu.
 *
 * Hooks adalah satu-satunya pemanggil: ia menerjemahkan state game
 * hidup ke Input tiap tick dan mengeksekusi Output.
 */
public final class AutoTrigger {
    public static final long STABLE_MS = 700L;
    public static final long RETRY_MS = 2500L;

    public static final int PH_WAIT = 0;
    public static final int PH_STABILIZE = 1;
    public static final int PH_WORKING = 2;
    public static final int PH_RETRY = 3;

    /** Masukan satu tick (diisi Hooks dari state game hidup). */
    public static final class Input {
        public long nowMs;
        public boolean enabled;     // saklar Auto Play
        public boolean pvp;         // status PvP jalur tunggal Hooks (ModState.pvpLatched)
        public boolean busy;        // pencarian berjalan / animasi berjalan / ada tembakan menunggu
        public boolean humanTurn;   // getPlayerType(currentPlayer) == 0
        public int state;           // RoundState.state (1 bidik, 2 bergerak, 3 free ball, 4 selesai)
        public int currentPlayer;
        public boolean roundOver;   // winner != -1
        public long positionKey;    // sidik jari posisi bola saat ini
    }

    /** Keluaran satu tick. */
    public static final class Output {
        public boolean startSearch;
        public String status = "";
    }

    private int phase = PH_WAIT;
    private int turnState = -1;
    private int turnPlayer = -1;
    private long lastKey = Long.MIN_VALUE;
    private long keySinceMs = 0L;
    private long retryAtMs = 0L;
    private boolean wasBusy = false;

    public int phase() {
        return phase;
    }

    /** Dipanggil Hooks saat saklar baru dinyalakan: mulai bersih. */
    public void reset() {
        phase = PH_WAIT;
        turnState = -1;
        turnPlayer = -1;
        lastKey = Long.MIN_VALUE;
        wasBusy = false;
    }

    public Output tick(Input in) {
        Output out = new Output();
        boolean myTurn = in.humanTurn && (in.state == 1 || in.state == 3) && !in.roundOver;

        if (!in.enabled) {
            phase = PH_WAIT;
            out.status = "Auto: saklar mati";
            wasBusy = in.busy;
            return out;
        }
        if (in.pvp) {
            phase = PH_WAIT;
            out.status = "Auto: mati di PvP";
            wasBusy = in.busy;
            return out;
        }
        if (!myTurn) {
            phase = PH_WAIT;
            turnState = -1;
            turnPlayer = -1;
            lastKey = Long.MIN_VALUE;
            out.status = in.roundOver ? "Auto: ronde selesai — menunggu ronde berikut"
                    : (in.state == 2 ? "Auto: bola bergerak — menunggu giliranmu"
                    : "Auto: menunggu giliranmu");
            wasBusy = in.busy;
            return out;
        }

        // giliran manusia: perubahan (state,pemain) = giliran baru
        if (phase == PH_WAIT || in.state != turnState || in.currentPlayer != turnPlayer) {
            phase = PH_STABILIZE;
            turnState = in.state;
            turnPlayer = in.currentPlayer;
            lastKey = in.positionKey;
            keySinceMs = in.nowMs;
            out.status = "Auto: giliranmu — menunggu meja diam…";
            wasBusy = in.busy;
            return out;
        }

        switch (phase) {
            case PH_STABILIZE: {
                if (in.busy) {
                    phase = PH_WORKING;
                    out.status = "Auto: menghitung / menembak…";
                    break;
                }
                if (in.positionKey != lastKey) {
                    lastKey = in.positionKey;
                    keySinceMs = in.nowMs;
                    out.status = "Auto: meja masih bergerak…";
                    break;
                }
                if (in.nowMs - keySinceMs >= STABLE_MS) {
                    phase = PH_WORKING;
                    out.startSearch = true;
                    out.status = "Auto: menghitung tembakan…";
                } else {
                    out.status = "Auto: meja mulai diam…";
                }
                break;
            }
            case PH_WORKING: {
                if (in.busy) {
                    out.status = in.state == 2 ? "Auto: menembak…"
                            : "Auto: menghitung / menembak…";
                } else if (wasBusy) {
                    // sibuk baru saja selesai dan meja TIDAK berubah
                    // (tidak ada tembakan yang dieksekusi — eksekusi
                    // mengubah posisi & state) -> jadwalkan coba lagi.
                    if (in.positionKey == lastKey) {
                        phase = PH_RETRY;
                        retryAtMs = in.nowMs + RETRY_MS;
                        out.status = "Auto: belum ada tembakan aman — mencoba lagi sebentar…";
                    } else {
                        phase = PH_STABILIZE;
                        lastKey = in.positionKey;
                        keySinceMs = in.nowMs;
                        out.status = "Auto: meja berubah — menilai ulang…";
                    }
                } else {
                    // tidak pernah sibuk sesudah START_SEARCH (pencarian
                    // ditolak gerbang) -> perlakukan seperti coba lagi.
                    phase = PH_RETRY;
                    retryAtMs = in.nowMs + RETRY_MS;
                    out.status = "Auto: pencarian tertahan — mencoba lagi sebentar…";
                }
                break;
            }
            case PH_RETRY: {
                if (in.busy) {
                    phase = PH_WORKING;
                    out.status = "Auto: menghitung / menembak…";
                    break;
                }
                if (in.positionKey != lastKey) {
                    phase = PH_STABILIZE;
                    lastKey = in.positionKey;
                    keySinceMs = in.nowMs;
                    out.status = "Auto: meja berubah — menilai ulang…";
                    break;
                }
                if (in.nowMs >= retryAtMs) {
                    phase = PH_WORKING;
                    out.startSearch = true;
                    out.status = "Auto: menghitung tembakan (percobaan ulang)…";
                } else {
                    out.status = "Auto: belum ada tembakan aman — mencoba lagi sebentar…";
                }
                break;
            }
            default: {
                phase = PH_STABILIZE;
                lastKey = in.positionKey;
                keySinceMs = in.nowMs;
                out.status = "Auto: giliranmu — menunggu meja diam…";
                break;
            }
        }
        wasBusy = in.busy;
        return out;
    }
}
