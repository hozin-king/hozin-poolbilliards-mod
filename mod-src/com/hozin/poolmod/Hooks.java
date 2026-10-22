package com.hozin.poolmod;

/**
 * Titik masuk mod dari patch smali:
 *
 *   GameScene.tick(long)  -> Hooks.onSceneTick(this)   (awal method)
 *   Main.onResume()       -> Hooks.onActivityResume(this)
 *   Main.onPause()        -> Hooks.onActivityPause(this)
 *
 * Semua pekerjaan fitur berjalan di thread game lewat onSceneTick.
 * GERBANG PvP ditegakkan berlapis: latch per instance rule di sini,
 * pemeriksaan isPVPMode() segar di Predict/Auto/eksekusi, dan menu
 * disembunyikan total selama PvP.
 *
 * Tidak ada satu pun jalur di kelas ini yang boleh melempar keluar —
 * mod tidak boleh menjatuhkan game.
 */
public final class Hooks {
    private static final Class<?>[] SIG_INT = {int.class};
    private static final Class<?>[] SIG_FFFF = {float.class, float.class, float.class, float.class};

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

    /** Gerbang untuk UI: true hanya bila scene hidup & BUKAN PvP. */
    public static boolean gateAllows() {
        try {
            Object scene = ModState.sceneRef;
            if (scene == null) {
                return false;
            }
            Object rule = Ref.get(scene, "rule");
            if (rule == null) {
                return false;
            }
            return !Ref.invokeB(rule, "isPVPMode");
        } catch (Throwable t) {
            return false;
        }
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
            return;
        }
        Object roundstate = Ref.get(table, "roundstate");
        if (roundstate == null) {
            ModState.inGame = false;
            Predict.hideAll();
            return;
        }
        ModState.lastTickMs = System.currentTimeMillis();
        ModState.inGame = true;
        ModState.sceneRef = scene;

        // --- gerbang PvP (latch per instance rule) ---
        boolean pvp = Ref.invokeB(rule, "isPVPMode");
        if (rule != ModState.ruleRef) {
            ModState.ruleRef = rule;
            ModState.pvpLatched = pvp;
        }
        if (pvp) {
            ModState.pvpLatched = true;
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
            Auto.pendingExec = null;
            return;
        }

        // --- eksekusi autoplay tertunda (dari thread pencarian) ---
        Auto.Shot shot = Auto.pendingExec;
        if (shot != null) {
            Auto.pendingExec = null;
            executeShot(scene, table, rule, roundstate, shot);
        }

        // --- permintaan autoplay baru dari menu ---
        if (ModState.autoRequested) {
            ModState.autoRequested = false;
            if (!ModState.autoBusy
                    && (state == 3 || (state == 1 && ptype == 0))) {
                Auto.startSearch(table, rule, roundstate, state);
            }
        }

        // --- auto play OTOMATIS (saklar menu, default OFF) ---
        maybeAutoTrigger(table, rule, roundstate, state, ptype);

        // --- prediksi / long line saat membidik ---
        updatePrediction(scene, table, rule, roundstate, state, ptype);
    }

    /**
     * Pemicu mode otomatis: hanya pada giliran manusia di mode offline
     * (gerbang PvP sudah ditegakkan latch + cek segar sebelum titik
     * ini, dan Auto.startSearch memeriksa isPVPMode() lagi). Meja
     * harus stabil: pasangan (state, tipe pemain) tidak berubah
     * >=900 ms, lalu dua probe posisi berjarak >=700 ms harus sama
     * persis — jadi tembakan pertama keluar ±1,6 detik setelah meja
     * berhenti, dan tidak pernah saat bola/posisi masih bergerak.
     * autoHoldKey menahan pemicu untuk posisi yang sama (termasuk
     * sesudah keputusan "tidak ada tembakan aman"), sehingga mode
     * otomatis tidak mengulang pencarian tanpa perubahan meja.
     */
    private static void maybeAutoTrigger(Object table, Object rule, Object roundstate,
                                         int state, int ptype) {
        boolean humanAim = state == 1 && ptype == 0;
        boolean humanFree = state == 3 && ptype == 0;
        if (!ModState.autoOn || ModState.autoBusy || Auto.pendingExec != null
                || !(humanAim || humanFree)) {
            ModState.autoPair = -1;
            ModState.autoProbeKey = Long.MIN_VALUE;
            return;
        }
        if (Ref.getI(roundstate, "winner") != -1) {
            return; // ronde sudah selesai
        }
        long now = System.currentTimeMillis();
        int pair = state * 4 + Ref.getI(roundstate, "currentPlayer");
        if (pair != ModState.autoPair) {
            ModState.autoPair = pair;
            ModState.autoPairSinceMs = now;
            ModState.autoProbeKey = Long.MIN_VALUE;
            return;
        }
        if (now - ModState.autoPairSinceMs < 900L) {
            return;
        }
        if (ModState.autoProbeKey != Long.MIN_VALUE && now - ModState.autoProbeMs < 700L) {
            return;
        }
        Snap probe = Snap.capture(table, rule, roundstate);
        if (probe == null) {
            return;
        }
        long key = probe.positionKey() * 31L + state;
        if (key == ModState.autoProbeKey) {
            // posisi stabil pada dua probe -> tembak sekarang
            if (key != ModState.autoHoldKey) {
                ModState.autoHoldKey = key;
                // GERBANG PvP segar tepat sebelum memicu pencarian
                if (!Ref.invokeB(rule, "isPVPMode")) {
                    Auto.startSearch(table, rule, roundstate, state);
                }
            }
        } else {
            ModState.autoProbeKey = key;
            ModState.autoProbeMs = now;
        }
    }

    private static void executeShot(Object scene, Object table, Object rule,
                                    Object roundstate, Auto.Shot s) {
        // GERBANG PvP di titik eksekusi (pemeriksaan segar)
        if (Ref.invokeB(rule, "isPVPMode")) {
            return;
        }
        Snap snap = Auto.pendingSnap;
        Auto.pendingSnap = null;
        int state = Ref.getI(roundstate, "state");
        if (s.freeball) {
            if (state != 3) {
                return;
            }
            if (snap == null || !snap.stillMatches(table)) {
                return;
            }
            Object[] ballsArr = (Object[]) Ref.get(table, "balls");
            if (ballsArr == null || snap.clickTarget >= ballsArr.length) {
                return;
            }
            Object cue = ballsArr[snap.clickTarget];
            if (cue == null || !snap.canPut(s.spotX, s.spotY)) {
                return;
            }
            Ref.setF(cue, "x", s.spotX);
            Ref.setF(cue, "y", s.spotY);
            Ref.invoke(table, "putFreeball", new Class<?>[0]);
            state = Ref.getI(roundstate, "state");
        } else {
            if (snap != null && !snap.stillMatches(table)) {
                return; // meja sudah berubah sejak pencarian
            }
        }
        if (state != 1) {
            return;
        }
        int current = Ref.getI(roundstate, "currentPlayer");
        if (Ref.invokeI(rule, "getPlayerType", SIG_INT, current) != 0) {
            return;
        }
        // samakan visual bidik scene dengan tembakan terpilih
        Ref.setF(scene, "aimAngle", s.angle);
        Ref.setF(scene, "aimStrength", s.strength);
        Ref.setF(scene, "aimPx", s.spinX);
        Ref.setF(scene, "aimPy", s.spinY);
        Ref.invoke(scene, "calcAimTarget", new Class<?>[0]);
        // jalur resmi yang sama dengan pemain & AI: PoolTable.click
        Ref.invoke(table, "click", SIG_FFFF, s.strength, s.spinX, s.spinY, s.angle);
        Predict.hideAll();
        lastPredictKey = Long.MIN_VALUE;
    }

    private static void updatePrediction(Object scene, Object table, Object rule,
                                         Object roundstate, int state, int ptype) {
        boolean wantPredict = ModState.predictOn;
        boolean wantLong = ModState.longLineOn;
        if (state != 1 || ptype != 0 || (!wantPredict && !wantLong)) {
            Predict.hideAll();
            lastPredictKey = Long.MIN_VALUE;
            return;
        }
        // GERBANG PvP segar sebelum menghitung apa pun
        if (Ref.invokeB(rule, "isPVPMode")) {
            Predict.hideAll();
            return;
        }
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
            return;
        }
        long key = snap.positionKey();
        key = key * 31L + (int) (angle * 100.0f);
        key = key * 31L + (int) effStrength;
        key = key * 31L + (int) (spinX * 100.0f);
        key = key * 31L + (int) (spinY * 100.0f);
        key = key * 31L + (wantPredict ? 1 : 0);
        if (key == lastPredictKey) {
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
    }
}
