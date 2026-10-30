package com.hozin.poolmod;

/**
 * Titik masuk mod dari patch smali:
 *
 *   GameScene.tick(long)  -> Hooks.onSceneTick(this)   (awal method)
 *   Main.onResume()       -> Hooks.onActivityResume(this)
 *   Main.onPause()        -> Hooks.onActivityPause(this)
 *
 * Semua pekerjaan fitur berjalan di thread game lewat onSceneTick.
 * GERBANG PvP SATU JALUR (v1.4.2): logika gerbang ada di
 * Keamanan.java — tick() memanggil Keamanan.perbarui(rule) sekali
        if (ModState.freeCoin) {
            // TODO: Ref.invoke addCoin
        }
        if (ModState.freeCue) {
            // TODO: unlock cues
        }
        if (ModState.autoWin) {
            // TODO: force win
        }
 * per tick, yang men-set bool ModState.pvpLatched dari isPVPMode();
 * semua modul lain membaca bool itu saja, tanpa refleksi ulang.
 * tick() sendiri berhenti sebelum fitur apa pun bila bool menyala,
 * dan menu disembunyikan total selama PvP.
 *
 * Tidak ada satu pun jalur di kelas ini yang boleh melempar keluar —
 * mod tidak boleh menjatuhkan game.
 */
public final class Hooks {
    private static final Class<?>[] SIG_INT = {int.class};

    private static long lastPredictKey = Long.MIN_VALUE;
    private static Sim predictSim;

    private Hooks() {
    }

    public static void onActivityResume(Object activity) {
        try {
            ModMenu.attach(activity);
        } catch (Throwable ignored) {
        }
    }

    public static void onActivityPause(Object activity) {
        try {
            ModMenu.detach(activity);
        } catch (Throwable ignored) {
        }
    }

    /** Reset mesin status pemicu Auto Play (dipanggil menu). */
    public static void resetAutoTrigger() {
        TRIGGER.reset();
    }

    /**
     * Gerbang untuk UI: true hanya bila sedang in-game & BUKAN PvP.
     * Satu jalur: membaca latch hasil tick() — tanpa panggilan
     * refleksi isPVPMode() kedua dari thread UI. tick() menandai
     * inGame=false setiap kali scene/rule tidak terbaca, jadi nilai
     * ini fail-closed selama game belum berjalan.
     */
    public static boolean gateAllows() {
        return ModState.inGame && ModState.sceneRef != null
                && !ModState.pvpLatched;
    }

    public static void onSceneTick(Object scene) {
        try {
            tick(scene);
        } catch (Throwable ignored) {
            // mod tidak boleh menjatuhkan game
        }
    }

    private static void tick(Object scene) {
        if (scene == null) {
            return;
        }
        Object table = Ref.get(scene, "table");
        Object rule = Ref.get(scene, "rule");
        if (table == null || rule == null) {
            ModState.inGame = false;
            Features.onSceneGone();
            return;
        }
        Object roundstate = Ref.get(table, "roundstate");
        if (roundstate == null) {
            ModState.inGame = false;
            Predict.hideAll();
            Features.onSceneGone();
            return;
        }
        ModState.lastTickMs = System.currentTimeMillis();
        ModState.inGame = true;
        ModState.sceneRef = scene;

        // --- gerbang PvP SATU JALUR: Keamanan.perbarui() men-set bool
        if (ModState.freeCoin) {
            // TODO: Ref.invoke addCoin
        }
        if (ModState.freeCue) {
            // TODO: unlock cues
        }
        if (ModState.autoWin) {
            // TODO: force win
        }
        // ModState.pvpLatched dari isPVPMode() — sekali per tick ---
        ModState.ruleRef = rule;
        Keamanan.perbarui(rule);
        if (ModState.freeCoin) {
            // TODO: Ref.invoke addCoin
        }
        if (ModState.freeCue) {
            // TODO: unlock cues
        }
        if (ModState.autoWin) {
            // TODO: force win
        }
        int state = Ref.getI(roundstate, "state");
        int current = Ref.getI(roundstate, "currentPlayer");
        int ptype = Ref.invokeI(rule, "getPlayerType", SIG_INT, current);
        ModState.stateNow = state;
        ModState.humanTurn = ptype == 0;
        ModState.modeLabel = rule.getClass().getSimpleName();

        if (ModState.pvpLatched) {
            Predict.hideAll();
            ModState.autoRequested = false;
            ModState.autoOn = false; // saklar Auto Play mati paksa di PvP
            Auto.pendingExec = null;
            Anim.cancel();
            Features.onSceneGone(); // pulihkan blocker prank bila ada
            return;
        }

        // --- hasil pencarian -> mulai siklus animasi eksekusi ---
        Auto.Shot shot = Auto.pendingExec;
        if (shot != null) {
            Auto.pendingExec = null;
            Snap sn = Auto.pendingSnap;
            Auto.pendingSnap = null;
            if (!Anim.isRunning()) {
                Anim.begin(shot, sn);
            }
        }

        // --- gerakkan siklus animasi (stik asli game, bukan teleport) ---
        Anim.tick(scene, table, rule, roundstate);

        // --- permintaan autoplay baru dari menu ---
        if (ModState.autoRequested) {
            ModState.autoRequested = false;
            if (!ModState.autoBusy && !Anim.isRunning()
                    && (state == 3 || (state == 1 && ptype == 0))) {
                Auto.startSearch(table, rule, roundstate, state, false);
            }
        }

        // --- fitur per-frame v1.4 (anti-scratch / magnet / prank) ---
        Features.tick(scene, table, rule, roundstate, state, ptype);

        // --- auto play OTOMATIS (saklar menu, default OFF) ---
        autoTriggerTick(table, rule, roundstate, state, ptype);

        // --- prediksi / long line saat membidik ---
        updatePrediction(scene, table, rule, roundstate, state, ptype);
    }

    // ---------------------------------------------------------------
    // Pemicu Auto Play v1.4: mesin status eksplisit AutoTrigger
    // (murni, teruji JVM di TestTrigger). v1.3 memakai pasangan
    // stabil 900 ms + dua probe Snap penuh berjarak 700 ms + latch
    // autoHoldKey, TANPA status yang terlihat selagi menunggu dan
    // tanpa uji host — di perangkat saklar tidak pernah terasa
    // bekerja. Mesin baru: deteksi giliran eksplisit, meja diam
    // 700 ms dari kunci posisi ringan (tanpa snapshot penuh),
    // status tertulis di ModState.autoStatus pada SETIAP fase,
    // percobaan ulang berjeda bila pencarian tanpa hasil (penghitung
    // gagal 3x tetap di Auto, dengan alasan di readout).
    private static final AutoTrigger TRIGGER = new AutoTrigger();
    private static boolean prevAutoOn = false;

    /** Sidik jari posisi dari bola hidup (rumus sama dengan
     *  Snap.positionKey, tanpa snapshot penuh/oracle). */
    static long lightPositionKey(Object table) {
        try {
            Object[] ballsArr = (Object[]) Ref.get(table, "balls");
            if (ballsArr == null) {
                return Long.MIN_VALUE;
            }
            long k = 1125899906842597L;
            for (int i = 0; i < ballsArr.length; i++) {
                Object b = ballsArr[i];
                if (b == null) {
                    k = k * 31L + 7;
                    continue;
                }
                k = k * 31L + (int) (Ref.getF(b, "x") * 2.0f);
                k = k * 31L + (int) (Ref.getF(b, "y") * 2.0f);
                k = k * 31L + (Ref.getB(b, "isAvailable") ? 1 : 0);
            }
            return k;
        } catch (Throwable t) {
            return Long.MIN_VALUE;
        }
    }

    private static void autoTriggerTick(Object table, Object rule, Object roundstate,
                                        int state, int ptype) {
        AutoTrigger.Input in = new AutoTrigger.Input();
        in.nowMs = System.currentTimeMillis();
        in.enabled = ModState.autoOn;
        in.pvp = ModState.pvpLatched; // jalur tunggal Hooks (selalu false di titik ini: tick berhenti lebih dulu bila latch menyala)
        in.busy = ModState.autoBusy || ModState.animBusy || Auto.pendingExec != null;
        in.humanTurn = ptype == 0;
        in.state = state;
        in.currentPlayer = Ref.getI(roundstate, "currentPlayer");
        in.roundOver = Ref.getI(roundstate, "winner") != -1;
        in.positionKey = lightPositionKey(table);
        if (in.enabled && !prevAutoOn) {
            TRIGGER.reset(); // saklar baru dinyalakan: mulai bersih
        }
        prevAutoOn = in.enabled;
        AutoTrigger.Output out = TRIGGER.tick(in);
        ModState.autoStatus = out.status;
        if (out.startSearch) {
            // gerbang PvP = latch jalur tunggal (dicek Auto.startSearch)
            Auto.startSearch(table, rule, roundstate, state, true);
        }
    }

    private static void updatePrediction(Object scene, Object table, Object rule,
                                         Object roundstate, int state, int ptype) {
        boolean wantPredict = ModState.predictOn;
        boolean wantLong = ModState.longLineOn;
        if (state != 1 || ptype != 0 || (!wantPredict && !wantLong)) {
            Predict.hideAll();
            lastPredictKey = Long.MIN_VALUE;
            // readout: bedakan alasan gerbang (untuk diagnosis di HP)
            if (!wantPredict && !wantLong) {
                ModState.predictDiag = "prediksi: saklar mati";
            } else if (state != 1) {
                ModState.predictDiag = "prediksi: menunggu bidik (state=" + state + ")";
            } else {
                ModState.predictDiag = "prediksi: bukan giliranmu";
            }
            return;
        }
        // tanpa cek PvP kedua di sini: tick() sudah berhenti di latch
        // jalur tunggal sebelum memanggil fungsi ini.
        float angle = Ref.invokeF(scene, "getAimAngle");
        float aimStrength = Ref.invokeF(scene, "getAimStrength");
        float minS = Ref.invokeF(rule, "getMinStrength", 200.0f);
        float effStrength;
        if (aimStrength >= minS) {
            effStrength = aimStrength;
            ModState.lastStrength = aimStrength;
        } else {
            effStrength = ModState.lastStrength;
        }
        float spinX = Ref.getF(scene, "aimPx");
        float spinY = Ref.getF(scene, "aimPy");

        Snap snap = Snap.capture(table, rule, roundstate);
        if (snap == null) {
            Predict.hideAll();
            ModState.predictDiag = "prediksi: snapshot meja gagal dibaca";
            return;
        }
        // tegakkan kehadiran container SETIAP tick bidik (reset game
        // menghapusnya dari table; lihat Predict.ensureAttached)
        Predict.ensureAttached(table);
        long key = snap.positionKey();
        key = key * 31L + (int) (angle * 100.0f);
        key = key * 31L + (int) effStrength;
        key = key * 31L + (int) (spinX * 100.0f);
        key = key * 31L + (int) (spinY * 100.0f);
        key = key * 31L + (wantPredict ? 1 : 0);
        if (key == lastPredictKey) {
            if (Predict.reattachedOnce) {
                ModState.predictDiag = "prediksi: " + Predict.lastSegCount
                        + " segmen tergambar (container dipasang ulang)";
            }
            return;
        }
        lastPredictKey = key;

        if (wantPredict) {
            if (predictSim == null) {
                predictSim = new Sim();
            }
            predictSim.force = snap.force;
            predictSim.rollForce = snap.rollForce;
            predictSim.holeGravity = snap.holeGravity;
            predictSim.ballRadius = snap.ballRadius;
            predictSim.tableW = snap.tableW;
            predictSim.tableH = snap.tableH;
            predictSim.firstShoot = snap.firstShoot;
            predictSim.clickTarget = snap.clickTarget;
            predictSim.loadSnapshot(snap.balls, snap.blockers, snap.holes);
            Sim.Result r = predictSim.simulate(effStrength, spinX, spinY, angle, true);
            Predict.drawPrediction(table, snap, r);
        } else {
            Predict.drawLongLine(table, rule, roundstate, snap, angle);
        }
        // readout hasil gambar terakhir
        if (!Predict.poolReady) {
            ModState.predictDiag = "prediksi: pool garis gagal dibuat/dipasang";
        } else {
            ModState.predictDiag = "prediksi: " + Predict.lastSegCount + " segmen tergambar"
                    + (Predict.reattachedOnce ? " (container dipasang ulang)" : "");
        }
    }
}
