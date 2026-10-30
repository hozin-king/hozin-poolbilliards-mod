package com.hozin.poolmod;

/**
 * Uji mesin status pemicu Auto Play v1.4 (AutoTrigger) di JVM:
 * urutan tick tiruan meniru keadaan perangkat (giliran berganti, meja
 * berhenti, pencarian sibuk, saklar dimatikan di tengah, PvP).
 *
 * Skenario:
 *  (1) dua giliran berurutan -> START_SEARCH tepat satu kali per
 *      giliran, tanpa sentuhan apa pun;
 *  (2) saklar dimatikan di tengah penantian -> tidak pernah memicu;
 *  (3) pencarian selesai tanpa tembakan -> coba lagi sesudah jeda,
 *      berulang (penghitung gagal ada di Auto, bukan di sini);
 *  (4) input PvP -> tidak pernah memicu;
 *  (5) giliran free ball (state 3) -> memicu;
 *  (6) ronde selesai (winner != -1) -> tidak memicu;
 *  (7) kunci posisi terus berubah -> tidak memicu sampai diam 700 ms.
 */
public final class TestTrigger {
    static int checks = 0;

    static void check(boolean cond, String msg) {
        checks++;
        if (!cond) {
            throw new AssertionError("GAGAL: " + msg);
        }
        System.out.println("  ok: " + msg);
    }

    static AutoTrigger.Input in(long now, boolean on, boolean pvp, boolean busy,
                                  boolean human, int state, int player,
                                  boolean over, long key) {
        AutoTrigger.Input i = new AutoTrigger.Input();
        i.nowMs = now;
        i.enabled = on;
        i.pvp = pvp;
        i.busy = busy;
        i.humanTurn = human;
        i.state = state;
        i.currentPlayer = player;
        i.roundOver = over;
        i.positionKey = key;
        return i;
    }

    /** Jalankan tick tiap 100 ms dari t0..t1 dengan parameter tetap;
     *  kembalikan jumlah START_SEARCH. */
    static int run(AutoTrigger tr, long t0, long t1, boolean on, boolean pvp,
                   boolean busy, boolean human, int state, int player,
                   boolean over, long key) {
        int fires = 0;
        for (long t = t0; t <= t1; t += 100L) {
            if (tr.tick(in(t, on, pvp, busy, human, state, player, over, key)).startSearch) {
                fires++;
            }
        }
        return fires;
    }

    public static void main(String[] args) {
        // (1) dua giliran berurutan
        System.out.println("[1] dua giliran berurutan, satu tembakan per giliran");
        AutoTrigger tr = new AutoTrigger();
        // giliran lawan (AI): state 1 tapi bukan manusia
        check(run(tr, 0, 2000, true, false, false, false, 1, 1, false, 111L) == 0,
                "giliran AI tidak memicu");
        // bola bergerak sesudah tembakan lawan
        check(run(tr, 2100, 3000, true, false, false, false, 2, 1, false, 222L) == 0,
                "bola bergerak tidak memicu");
        // giliran user, meja diam pada kunci 333
        check(run(tr, 3100, 3700, true, false, false, true, 1, 0, false, 333L) == 0,
                "belum stabil 700 ms: belum memicu");
        check(run(tr, 3800, 3900, true, false, false, true, 1, 0, false, 333L) == 1,
                "meja stabil -> START_SEARCH tepat 1x");
        // pencarian + animasi sibuk ~2 detik: tidak boleh memicu lagi
        check(run(tr, 4000, 5900, true, false, true, true, 1, 0, false, 333L) == 0,
                "selama sibuk tidak memicu ulang");
        // tembakan dieksekusi: bola bergerak (state 2), lalu giliran lawan
        check(run(tr, 6000, 7000, true, false, false, false, 2, 0, false, 444L) == 0,
                "sesudah menembak tidak memicu di state 2");
        check(run(tr, 7100, 9000, true, false, false, false, 1, 1, false, 555L) == 0,
                "giliran AI kedua tidak memicu");
        // giliran user kedua, posisi baru 666
        check(run(tr, 9100, 9900, true, false, false, true, 1, 0, false, 666L) == 1,
                "giliran user kedua -> START_SEARCH lagi (rantai berlanjut)");

        // (2) saklar dimatikan di tengah
        System.out.println("[2] saklar OFF di tengah penantian");
        tr = new AutoTrigger();
        run(tr, 0, 500, true, false, false, true, 1, 0, false, 777L);
        check(run(tr, 600, 3000, false, false, false, true, 1, 0, false, 777L) == 0,
                "OFF di tengah -> tidak pernah memicu");
        check(run(tr, 3100, 3900, true, false, false, true, 1, 0, false, 777L) == 1,
                "ON lagi -> siklus bersih dan memicu");

        // (3) pencarian tanpa hasil -> coba lagi berjeda
        System.out.println("[3] tanpa tembakan aman -> percobaan ulang berjeda");
        tr = new AutoTrigger();
        check(run(tr, 0, 900, true, false, false, true, 1, 0, false, 888L) == 1,
                "pemicu pertama");
        // sibuk 500 ms lalu selesai tanpa eksekusi (kunci sama, state sama)
        run(tr, 1000, 1500, true, false, true, true, 1, 0, false, 888L);
        check(run(tr, 1600, 3900, true, false, false, true, 1, 0, false, 888L) == 0,
                "jeda percobaan ulang: belum memicu");
        check(run(tr, 4000, 4300, true, false, false, true, 1, 0, false, 888L) == 1,
                "sesudah jeda 2,5 dtk -> mencoba lagi");

        // (4) PvP
        System.out.println("[4] PvP tidak pernah memicu");
        tr = new AutoTrigger();
        check(run(tr, 0, 5000, true, true, false, true, 1, 0, false, 999L) == 0,
                "input PvP -> nol tembakan");

        // (5) free ball
        System.out.println("[5] giliran free ball (state 3)");
        tr = new AutoTrigger();
        check(run(tr, 0, 900, true, false, false, true, 3, 0, false, 123L) == 1,
                "state 3 giliran user -> memicu");

        // (6) ronde selesai
        System.out.println("[6] ronde selesai tidak memicu");
        tr = new AutoTrigger();
        check(run(tr, 0, 3000, true, false, false, true, 1, 0, true, 321L) == 0,
                "winner != -1 -> tidak memicu");

        // (7) kunci berubah-ubah
        System.out.println("[7] meja belum diam -> tahan");
        tr = new AutoTrigger();
        int fires = 0;
        for (long t = 0; t <= 3000; t += 100L) {
            if (tr.tick(in(t, true, false, false, true, 1, 0, false, 1000L + t)).startSearch) {
                fires++;
            }
        }
        check(fires == 0, "kunci berubah tiap tick -> tidak memicu");
        check(run(tr, 3100, 3900, true, false, false, true, 1, 0, false, 4242L) == 1,
                "begitu diam 700 ms -> memicu");

        System.out.println("TestTrigger: SEMUA " + checks + " pemeriksaan lulus");
    }
}
