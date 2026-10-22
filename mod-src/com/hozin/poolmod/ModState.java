package com.hozin.poolmod;

/**
 * State bersama antara thread game (Hooks, dipanggil dari GameScene.tick)
 * dan thread UI (ModMenu overlay). Semua field volatile.
 *
 * Gerbang PvP: pvpLatched menempel pada instance rule yang sedang aktif;
 * selain latch ini, setiap titik masuk fitur (prediksi, autoplay,
 * freeball) MEMERIKSA rule.isPVPMode() secara langsung lagi.
 */
public final class ModState {
    // saklar pengguna (default sesuai spek: prediksi ON, long line ON)
    public static volatile boolean predictOn = true;
    public static volatile boolean longLineOn = true;

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

    // permintaan dari UI -> dieksekusi thread game
    public static volatile boolean autoRequested = false;
    public static volatile boolean autoBusy = false;

    // strength terakhir yang benar-benar diatur pemain (untuk pratinjau
    // prediksi saat strength bar belum ditarik)
    public static volatile float lastStrength = 1500.0f;

    private ModState() {
    }
}
