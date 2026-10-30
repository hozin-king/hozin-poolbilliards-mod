package com.hozin.poolmod;

/**
 * Fitur per-frame v1.4 — dipanggil Hooks tiap tick GameScene SESUDAH
 * gerbang PvP latch dan SEBELUM pemicu/prediksi. Sejak v1.4.2 gerbang
 * PvP satu jalur: kelas ini membaca latch Hooks (ModState.pvpLatched)
 * saja, tanpa panggilan refleksi isPVPMode() sendiri:
 *
 *  B "Anti-Scratch Total" (ModState.antiScratch): penangkapan lubang
 *     bola putih dimatikan oleh patch smali PoolTable (getNextHitTime
 *     bola-lubang -> MAX_TIME untuk bola putih). Di sini pelengkap
 *     pinggirnya: bila bola putih telanjur berada di zona lubang
 *     (inHole terisi) saat saklar dinyalakan di tengah luncuran,
 *     inHole dikosongkan supaya ia tidak tersedot gravitasi lubang —
 *     patch mencegah ia masuk lagi.
 *
 *  C "Prank Giliran Lawan" (ModState.prankOn, KHUSUS Vsai*): selama
 *     giliran komputer (bidik state 1 / bola bergulir state 2 dengan
 *     pemain AI), semua PoolBlocker dibuat tidak terlihat — mesin
 *     fisika game melewati tabrakan cushion untuk blocker tak
 *     terlihat (PoolTable.getNextHitTime menghormati isVisible,
 *     persis seperti Sim). Bola yang keluar area ditangani kode game
 *     sendiri di updateRunning: posisinya di-wrap ke sisi berlawanan
 *     meja sampai friksi menghentikannya (tidak hilang, tidak
 *     crash). Visibilitas WAJIB dipulihkan begitu bukan giliran AI /
 *     saklar mati / keluar meja — restore diingat di sini dan juga
 *     dipanggil dari jalur keluar Hooks (onSceneGone).
 *
 *  D "Bola Ngejar Lubang" (ModState.magnetOn): selama bola bergulir
 *     (state 2) sesudah tembakan USER, bola target (Magnet.targets —
 *     dari oracle aturan yang dibaca SEGAR pada awal luncuran dan
 *     dibaca ULANG tiap ada bola masuk, karena penetapan grup bisa
 *     terjadi di tengah luncuran) menerima delta kecepatan homing
 *     Magnet.steerDelta satu kali per tick sebelum langkah fisika
 *     frame itu. Giliran lawan tidak pernah diarahkan.
 */
public final class Features {
    private static final Class<?>[] SIG_INT = {int.class};
    private static final Class<?>[] SIG_BOOL = {boolean.class};

    // --- C: ingatan restore blocker ---
    private static Object[] hiddenBlockers;
    private static boolean blockersHidden;

    // --- D: keadaan luncuran aktif ---
    private static boolean rollActive;
    private static boolean rollShooterHuman;
    private static boolean[] rollTargets;
    private static Sim.Hole[] rollHoles;
    private static int rollAvailCount = -1;
    private static long lastTickMs;

    private Features() {
    }

    public static void tick(Object scene, Object table, Object rule, Object roundstate,
                            int state, int ptype) {
        long now = System.currentTimeMillis();
        float dt = lastTickMs == 0L ? Sim.STEP
                : Math.min(0.05f, Math.max(0.004f, (now - lastTickMs) / 1000.0f));
        lastTickMs = now;
        try {
            // gerbang PvP satu jalur: latch Hooks (ModState.pvpLatched)
            if (ModState.pvpLatched) {
                restoreBlockers();
                rollActive = false;
                return;
            }
            antiScratchTick(table, rule, roundstate);
            prankTick(table, rule, state, ptype);
            magnetTick(table, rule, roundstate, state, ptype, dt);
        } catch (Throwable ignored) {
            // fitur tidak boleh menjatuhkan game; pemulihan blocker
            // dicoba lagi di tick berikut / jalur keluar
        }
    }

    /** Dipanggil Hooks saat scene/meja hilang: pulihkan blocker. */
    public static void onSceneGone() {
        restoreBlockers();
        rollActive = false;
        lastTickMs = 0L;
    }

    // ---------------------------------------------------------------
    // B — Anti-Scratch: kosongkan inHole bola putih yang telanjur
    // tertangkap zona lubang (patch smali mencegah masuknya).
    private static void antiScratchTick(Object table, Object rule, Object roundstate) {
        if (!ModState.antiScratch) {
            return;
        }
        Object[] ballsArr = (Object[]) Ref.get(table, "balls");
        if (ballsArr == null) {
            return;
        }
        int cue = Ref.invokeI(rule, "getClickTarget",
                new Class<?>[]{roundstate.getClass()}, roundstate);
        if (cue < 0 || cue >= ballsArr.length || ballsArr[cue] == null) {
            return;
        }
        if (Ref.get(ballsArr[cue], "inHole") != null) {
            Ref.setObj(ballsArr[cue], "inHole", null);
        }
    }

    // ---------------------------------------------------------------
    // C — Prank: sembunyikan blocker selama giliran komputer (Vsai).
    private static void prankTick(Object table, Object rule, int state, int ptype) {
        String rc = rule.getClass().getSimpleName();
        boolean vsMode = rc.startsWith("Vsai");
        boolean aiTurn = ptype != 0 && (state == 1 || state == 2);
        boolean want = ModState.prankOn && vsMode && aiTurn;
        if (want) {
            Object[] blockersArr = (Object[]) Ref.get(table, "blockers");
            if (blockersArr != null && (!blockersHidden || hiddenBlockers != blockersArr)) {
                setBlockersVisible(blockersArr, false);
                hiddenBlockers = blockersArr;
                blockersHidden = true;
            }
        } else {
            restoreBlockers();
        }
    }

    private static void setBlockersVisible(Object[] blockersArr, boolean vis) {
        for (int i = 0; i < blockersArr.length; i++) {
            if (blockersArr[i] != null) {
                Ref.invoke(blockersArr[i], "setVisible", SIG_BOOL, vis);
            }
        }
    }

    private static void restoreBlockers() {
        if (blockersHidden && hiddenBlockers != null) {
            try {
                setBlockersVisible(hiddenBlockers, true);
            } catch (Throwable ignored) {
            }
        }
        blockersHidden = false;
        hiddenBlockers = null;
    }

    // ---------------------------------------------------------------
    // D — Magnet: arahkan bola target selama luncuran user.
    private static void magnetTick(Object table, Object rule, Object roundstate,
                                   int state, int ptype, float dt) {
        if (state != 2) {
            rollActive = false;
            rollTargets = null;
            return;
        }
        if (!rollActive) {
            // awal luncuran: yang menembak adalah currentPlayer saat
            // ini (pergantian pemain terjadi di akhir ronde)
            rollActive = true;
            rollShooterHuman = ptype == 0;
            rollTargets = null;
            rollAvailCount = -1;
            if (ModState.magnetOn && rollShooterHuman) {
                captureTargets(table, rule, roundstate);
            }
        }
        if (!ModState.magnetOn || !rollShooterHuman || rollTargets == null) {
            return;
        }
        Object[] ballsArr = (Object[]) Ref.get(table, "balls");
        if (ballsArr == null) {
            return;
        }
        // penetapan grup bisa terjadi di tengah luncuran (bola
        // pertama masuk) — jumlah bola tersedia berubah -> baca
        // ulang kelayakan dari oracle saat itu juga.
        int avail = 0;
        for (int i = 0; i < ballsArr.length; i++) {
            if (ballsArr[i] != null && Ref.getB(ballsArr[i], "isAvailable")) {
                avail++;
            }
        }
        if (rollAvailCount >= 0 && avail != rollAvailCount) {
            captureTargets(table, rule, roundstate);
            if (rollTargets == null) {
                return;
            }
        }
        rollAvailCount = avail;

        for (int i = 0; i < ballsArr.length && i < rollTargets.length; i++) {
            if (!rollTargets[i] || ballsArr[i] == null) {
                continue;
            }
            Object b = ballsArr[i];
            Sim.Ball probe = new Sim.Ball();
            probe.x = Ref.getF(b, "x");
            probe.y = Ref.getF(b, "y");
            probe.vx = Ref.getF(b, "vx");
            probe.vy = Ref.getF(b, "vy");
            probe.avail = Ref.getB(b, "isAvailable");
            probe.inHole = Ref.get(b, "inHole") != null ? 0 : -1;
            float[] dv = Magnet.steerDelta(probe, rollHoles, dt);
            if (dv != null) {
                Ref.setF(b, "vx", probe.vx + dv[0]);
                Ref.setF(b, "vy", probe.vy + dv[1]);
            }
        }
    }

    /** Baca kelayakan magnet dari oracle aturan (snapshot penuh). */
    private static void captureTargets(Object table, Object rule, Object roundstate) {
        Snap snap = Snap.capture(table, rule, roundstate);
        if (snap == null) {
            rollTargets = null;
            return;
        }
        rollTargets = Magnet.targets(snap);
        rollHoles = snap.holes;
        if (rollTargets == null) {
            // fail-closed + alasan terlihat di menu (permintaan user:
            // grup tak terbaca -> magnet diam + readout menulisnya)
            ModState.magnetStatus = "Magnet: grup belum terbaca — magnet diam";
        } else {
            int n = 0;
            for (int i = 0; i < rollTargets.length; i++) {
                if (rollTargets[i]) {
                    n++;
                }
            }
            ModState.magnetStatus = "Magnet: " + n + " bola target (grupmu)";
        }
    }
}
