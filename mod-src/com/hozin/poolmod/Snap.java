package com.hozin.poolmod;

/**
 * Snapshot state meja dari objek game yang hidup (PoolTable + PoolRule)
 * ke struktur Sim. Semua field game dibaca via refleksi; snapshot ini
 * salinan nilai (bukan referensi bola), jadi aman dipakai thread
 * pencarian autoplay. canPut() sengaja memanggil rule asli dengan array
 * bola asli (metode itu murni membaca posisi).
 */
public final class Snap {
    public Sim.Ball[] balls;
    public Sim.Blocker[] blockers;
    public Sim.Hole[] holes;
    public float force, rollForce, holeGravity, ballRadius;
    public float tableW, tableH, minStrength, maxStrength;
    public boolean firstShoot;
    public int clickTarget;
    public String ruleClass = "";
    public int currentPlayer = -1;

    /**
     * Target legal untuk pemain yang sedang giliran, ditanyakan langsung
     * ke rule game yang hidup lewat PoolRule.isAvailableTarget(
     * RoundState, PoolBall[], int) — oracle yang sama yang dipakai AI
     * bawaan game (VsaiRuleBase$1.think hanya mempertimbangkan bola yang
     * lolos metode ini):
     *   Vsai8Ball : campstate 0 = meja terbuka (semua bola non-cue),
     *               campstate 2/3 = hanya grup pemain yang giliran
     *               (1..7 atau 9..15), bola 8 hanya bila grup itu habis.
     *   Vsai9Ball : hanya bola bernomor terendah yang masih di meja.
     *   RuleImplBase (Time/Arcade/Tutorial): semua bola non-cue.
     * legalKnown=false berarti oracle tidak bisa ditanyakan — autoplay
     * WAJIB menolak menembak (tidak menebak target).
     */
    public boolean[] legal;
    public boolean legalKnown;
    /** Bola penentu menang: 8 untuk Vsai8Ball, 9 untuk Vsai9Ball, -1 lain. */
    public int moneyBall = -1;

    /** Mode Arcade (rule LevelRule): objektif & saringan khusus —
     * semua bola target sah, mata uang restTime, lubang spesial. */
    public boolean arcade;
    /** Sisa tembakan Arcade dari LevelRule$GameRoundState.restTime
     * (dibaca hidup tiap snapshot; -1 di mode lain / tak terbaca). */
    public int restTime = -1;

    // referensi hidup — hanya untuk canPut (baca-saja) & info kelas rule
    private Object ruleObj;
    private Object ballsArrObj;

    private static Class<?> roundStateCls;
    private static boolean roundStateClsTried;

    public static Snap capture(Object table, Object rule, Object roundstate) {
        try {
            if (table == null || rule == null || roundstate == null) {
                return null;
            }
            Object[] ballsArr = (Object[]) Ref.get(table, "balls");
            Object[] blockersArr = (Object[]) Ref.get(table, "blockers");
            Object[] holesArr = (Object[]) Ref.get(table, "holes");
            if (ballsArr == null || blockersArr == null || holesArr == null) {
                return null;
            }
            Snap s = new Snap();
            s.ruleObj = rule;
            s.ballsArrObj = ballsArr;
            s.ruleClass = rule.getClass().getSimpleName();
            s.force = Ref.invokeF(rule, "getForce", 1500.0f);
            s.rollForce = Ref.invokeF(rule, "getRollForce", 100.0f);
            s.holeGravity = Ref.invokeF(rule, "getHoleGravity", 3600.0f);
            s.ballRadius = Ref.invokeF(rule, "getBallRadius", 47.15f);
            s.tableW = Ref.invokeF(rule, "getTableWidth", 2810.0f);
            s.tableH = Ref.invokeF(rule, "getTableHeight", 1530.0f);
            s.minStrength = Ref.invokeF(rule, "getMinStrength", 200.0f);
            s.maxStrength = Ref.invokeF(rule, "getMaxStrength", 4000.0f);
            s.firstShoot = Ref.getB(table, "isFirstShoot");
            s.clickTarget = Ref.invokeI(rule, "getClickTarget",
                    new Class<?>[]{roundstate.getClass()}, roundstate);
            s.currentPlayer = Ref.getI(roundstate, "currentPlayer");
            s.moneyBall = s.ruleClass.startsWith("Vsai8") ? 8
                    : (s.ruleClass.startsWith("Vsai9") ? 9 : -1);
            s.arcade = s.ruleClass.equals("LevelRule");
            if (s.arcade) {
                s.restTime = Ref.getI(roundstate, "restTime");
            }
            captureLegal(s, rule, roundstate, ballsArr);

            s.balls = new Sim.Ball[ballsArr.length];
            for (int i = 0; i < ballsArr.length; i++) {
                Object b = ballsArr[i];
                Sim.Ball sb = new Sim.Ball();
                if (b != null) {
                    sb.x = Ref.getF(b, "x");
                    sb.y = Ref.getF(b, "y");
                    sb.vx = Ref.getF(b, "vx");
                    sb.vy = Ref.getF(b, "vy");
                    sb.rvx = Ref.getF(b, "rvx");
                    sb.rvy = Ref.getF(b, "rvy");
                    sb.w = Ref.getF(b, "w");
                    sb.scale = Ref.getF(b, "scale", s.ballRadius);
                    sb.avail = Ref.getB(b, "isAvailable") && Ref.invokeB(b, "isVisible");
                }
                s.balls[i] = sb;
            }
            s.blockers = new Sim.Blocker[blockersArr.length];
            for (int i = 0; i < blockersArr.length; i++) {
                Object bl = blockersArr[i];
                Sim.Blocker sbl = new Sim.Blocker();
                if (bl != null) {
                    sbl.x0 = Ref.invokeF(bl, "getX0");
                    sbl.y0 = Ref.invokeF(bl, "getY0");
                    sbl.x1 = Ref.invokeF(bl, "getX1");
                    sbl.y1 = Ref.invokeF(bl, "getY1");
                    sbl.force = Ref.getF(bl, "force", 1200.0f);
                    sbl.vis = Ref.invokeB(bl, "isVisible");
                }
                s.blockers[i] = sbl;
            }
            s.holes = new Sim.Hole[holesArr.length];
            for (int i = 0; i < holesArr.length; i++) {
                Object h = holesArr[i];
                Sim.Hole sh = new Sim.Hole();
                if (h != null) {
                    sh.x = Ref.getF(h, "x");
                    sh.y = Ref.getF(h, "y");
                    sh.w = Ref.invokeF(h, "getWidth", 140.0f);
                    sh.vis = Ref.invokeB(h, "isVisible");
                    sh.effect = Ref.getI(h, "effect_type");
                }
                s.holes[i] = sh;
            }
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Snapshot turunan dengan bola/legal diganti (untuk langkah rantai
     * run-out): semua parameter meja & referensi rule diwarisi; hanya
     * daftar bola dan daftar legal yang baru. canPut turunan tetap
     * memakai rule hidup (posisi bola lain diabaikan canPut bawaan
     * selain array asli — turunan TIDAK dipakai untuk canPut).
     */
    public Snap derive(Sim.Ball[] newBalls, boolean[] newLegal) {
        Snap d = new Snap();
        d.balls = newBalls;
        d.blockers = blockers;
        d.holes = holes;
        d.force = force;
        d.rollForce = rollForce;
        d.holeGravity = holeGravity;
        d.ballRadius = ballRadius;
        d.tableW = tableW;
        d.tableH = tableH;
        d.minStrength = minStrength;
        d.maxStrength = maxStrength;
        d.firstShoot = firstShoot;
        d.clickTarget = clickTarget;
        d.ruleClass = ruleClass;
        d.currentPlayer = currentPlayer;
        d.legal = newLegal;
        d.legalKnown = legalKnown;
        d.moneyBall = moneyBall;
        d.arcade = arcade;
        d.restTime = restTime;
        d.ruleObj = ruleObj;
        d.ballsArrObj = ballsArrObj;
        return d;
    }

    /** Sim baru yang sudah diisi snapshot ini (proto = state snapshot). */
    public Sim newSim() {
        Sim sim = new Sim();
        sim.force = force;
        sim.rollForce = rollForce;
        sim.holeGravity = holeGravity;
        sim.ballRadius = ballRadius;
        sim.tableW = tableW;
        sim.tableH = tableH;
        sim.firstShoot = firstShoot;
        sim.clickTarget = clickTarget;
        sim.loadSnapshot(balls, blockers, holes);
        return sim;
    }

    /**
     * Tanyakan target legal ke rule hidup: isAvailableTarget untuk tiap
     * bola. Dipanggil di thread game saat snapshot dibuat (state meja
     * beku selama state bidik). Gagal total -> legalKnown=false.
     */
    private static void captureLegal(Snap s, Object rule, Object roundstate, Object[] ballsArr) {
        s.legal = new boolean[ballsArr.length];
        s.legalKnown = false;
        try {
            if (!roundStateClsTried) {
                roundStateClsTried = true;
                roundStateCls = Ref.cls("com.forthblue.pool.rules.PoolRule$RoundState");
            }
            if (roundStateCls == null) {
                return;
            }
            Class<?>[] sig = new Class<?>[]{roundStateCls, ballsArr.getClass(), int.class};
            boolean any = false;
            for (int i = 0; i < ballsArr.length; i++) {
                if (i == s.clickTarget) {
                    s.legal[i] = false;
                    continue;
                }
                Object v = Ref.invoke(rule, "isAvailableTarget", sig, roundstate, ballsArr, i);
                if (!(v instanceof Boolean)) {
                    return; // oracle tidak menjawab — jangan menebak
                }
                s.legal[i] = (Boolean) v;
                any = true;
            }
            s.legalKnown = any || ballsArr.length <= 1;
        } catch (Throwable t) {
            s.legalKnown = false;
        }
    }

    /** Validasi penempatan freeball memakai rule asli (canPut). */
    public boolean canPut(float x, float y) {
        if (ruleObj == null || ballsArrObj == null) {
            return false;
        }
        Object v = Ref.invoke(ruleObj, "canPut",
                new Class<?>[]{ballsArrObj.getClass(), int.class, float.class, float.class},
                ballsArrObj, clickTarget, x, y);
        return v instanceof Boolean && (Boolean) v;
    }

    /** Sidik jari posisi bola (untuk deteksi perubahan sebelum eksekusi). */
    public long positionKey() {
        long k = 1125899906842597L;
        for (int i = 0; i < balls.length; i++) {
            k = k * 31L + (int) (balls[i].x * 2.0f);
            k = k * 31L + (int) (balls[i].y * 2.0f);
            k = k * 31L + (balls[i].avail ? 1 : 0);
        }
        return k;
    }

    /** Bandingkan posisi bola snapshot dengan meja hidup saat ini. */
    public boolean stillMatches(Object table) {
        try {
            Object[] ballsArr = (Object[]) Ref.get(table, "balls");
            if (ballsArr == null || ballsArr.length != balls.length) {
                return false;
            }
            for (int i = 0; i < ballsArr.length; i++) {
                Object b = ballsArr[i];
                if (b == null) {
                    return false;
                }
                float dx = Ref.getF(b, "x") - balls[i].x;
                float dy = Ref.getF(b, "y") - balls[i].y;
                boolean av = Ref.getB(b, "isAvailable");
                if (dx * dx + dy * dy > 1.0f || av != balls[i].avail) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
