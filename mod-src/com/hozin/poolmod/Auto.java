package com.hozin.poolmod;

import java.util.ArrayList;

/**
 * Auto play v1.3 — perencana expert berjenjang di atas klon Sim.
 *
 * HUKUM (tidak berubah oleh tingkat kesulitan / objektif):
 *  1. Target legal dari oracle game (PoolRule.isAvailableTarget,
 *     tertanam di Snap.legal). Oracle tak terbaca -> tidak menembak.
 *  2. SARINGAN KERAS SIMULASI PENUH (Perintah 5A): kandidat dibuang
 *     bila hasil simulasi sampai semua bola berhenti menunjukkan
 *     bola putih masuk, bola NON-legal apa pun masuk (termasuk lewat
 *     pantulan & tabrakan sekunder), bola 8 masuk sebelum legal,
 *     atau kontak pertama bukan bola legal. Pengecualian: bola 9 yang
 *     masuk sesudah kontak legal di 9-ball = kemenangan.
 *
 * TANGGA KEPUTUSAN (Expert/Mustahil; perencana tidak menyerah selama
 * masih ada suapan yang lolos hukum di atas):
 *  1 Pot langsung - 2 Bank (target memantul cushion ke lubang) -
 *  3 Kick/rail-first (cue memantul cushion dulu, lalu kontak legal) -
 *  4 Combination (bola sendiri menabrak bola sendiri lain masuk) -
 *  5 Safety expert (kontak legal + ada bola mencapai cushion sesudah
 *    kontak; dioptimalkan menyembunyikan bola putih) - 6 Breakout
 *    (membuka cluster sendiri) - 7 Upaya paling tidak buruk (garis
 *    terdekat ke kontak legal; hukum 5A tetap berlaku).
 *
 * TINGKAT KESULITAN (Perintah 6) — mengubah kedalaman & gaya, BUKAN
 * legalitas:
 *  NORMAL : tangga 1 + safety sederhana; sampling kasar; tanpa 2-ply;
 *           spin hanya sebagai penyelamat pot.
 *  EXPERT : tangga penuh, 2-ply position play, grid spin penuh.
 *  MUSTAHIL: Expert + pencarian rapat (refine sudut/power), bank &
 *           kick multi-cushion sebagai kandidat kelas satu, dan
 *           PERENCANAAN RUN-OUT: rantai menghabiskan sisa grup (+8)
 *           dinilai global; langkah pertama rantai terbaik yang
 *           dieksekusi (perencanaan ulang terjadi alami karena tiap
 *           tembakan nyata memicu pencarian baru dari state nyata).
 *           Anggaran ±2,8 dtk; habis -> hasil terbaik sejauh ini
 *           (turun anggun ke kualitas Expert, dicatat di readout).
 *
 * Skor memakai Objective (fungsi objektif terpisah — sasaran per mode
 * lain, mis. Arcade, disisipkan di sana, bukan di kelas ini).
 *
 * Catatan kejujuran (sejak v1): klon memakai (float)Math.* vs MathUtil
 * native game — selisih di bawah presisi float mungkin ada; rantai
 * panjang & multi-pantulan adalah yang paling peka. Yang diklaim:
 * parameter dihitung eksak dari simulasi klon, bukan "pasti masuk".
 */
public final class Auto {
    // tingkat kesulitan
    public static final int DIFF_NORMAL = 0;
    public static final int DIFF_EXPERT = 1;
    public static final int DIFF_MUSTAHIL = 2;

    // tingkat tangga keputusan
    public static final int LV_POT = 1;
    public static final int LV_BANK = 2;
    public static final int LV_KICK = 3;
    public static final int LV_COMBO = 4;
    public static final int LV_SAFETY = 5;
    public static final int LV_BREAKOUT = 6;
    public static final int LV_LAST = 7;
    public static final int LV_BREAK = 8;

    static final Objective GOAL = new Objective();
    static final long MUSTAHIL_BUDGET_MS = 2800L;

    public static final class Shot {
        public float strength, spinX, spinY, angle, score;
        public int potted;
        public boolean freeball;
        public boolean safety;
        public int targetBall = -1;
        public int robust;
        public int level = LV_POT;
        public int chainLen;
        public float spotX, spotY;
        public long posKey;
    }

    public static volatile Shot pendingExec;
    public static volatile Snap pendingSnap;
    public static volatile String lastOutcome = "";
    public static volatile String lastReport = "";

    // kode alasan filterReason()
    static final int PASS = 0;
    static final int REJ_SCRATCH = 1;
    static final int REJ_CONTACT = 2;
    static final int REJ_ILLEGAL = 3;
    static final int REJ_MONEY = 4;
    static final int REJ_BLACKHOLE = 5; // Arcade: bola masuk lubang hitam

    static int candCount;
    static final int[] rejectCounts = new int[6];
    static boolean counting;

    private Auto() {
    }

    public static String diffName(int d) {
        return d == DIFF_NORMAL ? "Normal" : (d == DIFF_MUSTAHIL ? "Mustahil" : "Expert");
    }

    public static String levelName(int lv) {
        switch (lv) {
            case LV_POT: return "Pot";
            case LV_BANK: return "Bank";
            case LV_KICK: return "Kick";
            case LV_COMBO: return "Combo";
            case LV_SAFETY: return "Safety";
            case LV_BREAKOUT: return "Breakout";
            case LV_BREAK: return "Break";
            default: return "Upaya terakhir";
        }
    }

    public static void startSearch(Object table, Object rule, Object roundstate,
                                   int state, final boolean fromAuto) {
        // GERBANG PvP di titik masuk pencarian
        if (Ref.invokeB(rule, "isPVPMode")) {
            return;
        }
        final Snap snap = Snap.capture(table, rule, roundstate);
        if (snap == null) {
            return;
        }
        final boolean free = state == 3;
        final int diff = ModState.difficulty;
        final long deadline = diff == DIFF_MUSTAHIL
                ? System.currentTimeMillis() + MUSTAHIL_BUDGET_MS : 0L;
        if (diff == DIFF_MUSTAHIL) {
            setReport("Auto (Mustahil): berpikir…");
        }
        ModState.autoBusy = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Shot s = free ? searchFreeball(snap, diff, deadline)
                            : searchShot(snap, diff, deadline);
                    if (s != null) {
                        s.freeball = free;
                        s.posKey = snap.positionKey();
                        pendingSnap = snap;
                        pendingExec = s;
                        lastOutcome = s.safety ? "aman" : "pot";
                        ModState.statusText = "";
                        if (fromAuto) {
                            ModState.autoFailCount = 0;
                        }
                    } else {
                        lastOutcome = "tidak ada tembakan aman";
                        ModState.statusText =
                                "Auto: tidak ada tembakan aman — main manual";
                        ModState.statusMs = System.currentTimeMillis();
                        if (fromAuto && ModState.autoOn) {
                            // pengaman Perintah 2: 3x gagal beruntun ->
                            // saklar mati sendiri + alasan di readout
                            ModState.autoFailCount++;
                            if (ModState.autoFailCount >= 3) {
                                ModState.autoOn = false;
                                ModState.autoFailCount = 0;
                                setReport("Auto: MATI SENDIRI — 3x berturut-turut "
                                        + "gagal menemukan suapan legal. " + lastReport);
                                ModState.statusText = "Auto Play mati: 3x gagal merencanakan";
                                ModState.statusMs = System.currentTimeMillis();
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                ModState.autoBusy = false;
            }
        }, "poolmod-autoplay");
        t.setDaemon(true);
        t.start();
    }

    // ----------------------------------------------------------------
    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float dist(float x0, float y0, float x1, float y1) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float estStrength(Snap snap, float d1, float d2) {
        return clamp(650.0f + (d1 + d2) * 0.95f, snap.minStrength, snap.maxStrength);
    }

    private static boolean isLegal(Snap snap, int i) {
        return snap.legal != null && i >= 0 && i < snap.legal.length && snap.legal[i];
    }

    private static boolean isLegal(boolean[] legal, int i) {
        return legal != null && i >= 0 && i < legal.length && legal[i];
    }

    private static boolean expired(long deadline) {
        return deadline > 0L && System.currentTimeMillis() > deadline;
    }

    // ----------------------------------------------------------------
    // SARINGAN KERAS SIMULASI PENUH (Perintah 5A).
    // out[0]=jumlah pot legal, out[1]=kemenangan bola penentu (0/1).
    // ----------------------------------------------------------------
    static int filterReason(Snap snap, boolean[] legal, Sim.Result r, int[] out) {
        if (snap.arcade) {
            return arcadeFilterReason(snap, r, out);
        }
        int cue = snap.clickTarget;
        if (r.cuePotted) {
            return REJ_SCRATCH;
        }
        if (r.firstContact < 0 || !isLegal(legal, r.firstContact)) {
            return REJ_CONTACT;
        }
        int legalPots = 0;
        boolean moneyWin = false;
        for (int i = 0; i < snap.balls.length; i++) {
            if (i == cue || !r.potted[i]) {
                continue;
            }
            if (i == snap.moneyBall && !isLegal(legal, i)) {
                if (snap.moneyBall == 9) {
                    moneyWin = true; // 9 masuk sesudah kontak legal = menang
                } else {
                    return REJ_MONEY; // bola 8 dini = kalah
                }
            } else if (isLegal(legal, i)) {
                legalPots++;
            } else {
                // 5A: bola salah masuk — dari jalur mana pun (pantulan,
                // tabrakan sekunder) — DIBUANG tanpa kecuali.
                return REJ_ILLEGAL;
            }
        }
        if (out != null) {
            out[0] = legalPots;
            out[1] = moneyWin ? 1 : 0;
        }
        return PASS;
    }

    static int filterReason(Snap snap, Sim.Result r, int[] out) {
        return filterReason(snap, snap.legal, r, out);
    }

    /**
     * Saringan Arcade (audit AUDIT-ARCADE §4): tidak ada bola salah —
     * semua bola objek boleh masuk (multi-pot untung). Yang keras:
     * scratch (-2), tanpa kontak sama sekali (-2), dan bola apa pun
     * masuk LUBANG HITAM (effect_type 1, -2 per bola) — lubang terlarang
     * untuk pot yang disengaja di semua tingkat kesulitan.
     * out[0] = jumlah bola masuk (semua bernilai), out[1] = 0.
     */
    static int arcadeFilterReason(Snap snap, Sim.Result r, int[] out) {
        if (r.cuePotted) {
            return REJ_SCRATCH;
        }
        if (r.firstContact < 0) {
            return REJ_CONTACT;
        }
        int pots = 0;
        for (int i = 0; i < snap.balls.length; i++) {
            if (i == snap.clickTarget || !r.potted[i]) {
                continue;
            }
            int h = r.potHole[i];
            if (h >= 0 && h < snap.holes.length && snap.holes[h].effect == 1) {
                return REJ_BLACKHOLE;
            }
            pots++;
        }
        if (out != null) {
            out[0] = pots;
            out[1] = 0;
        }
        return PASS;
    }

    static boolean passesFilter(Snap snap, Sim.Result r, int[] out) {
        return filterReason(snap, r, out) == PASS;
    }

    static boolean passesFilter(Snap snap, boolean[] legal, Sim.Result r, int[] out) {
        return filterReason(snap, legal, r, out) == PASS;
    }

    private static void counted(int reason) {
        if (counting) {
            candCount++;
            if (reason != PASS) {
                rejectCounts[reason]++;
            }
        }
    }

    // ----------------------------------------------------------------
    // SKOR
    // ----------------------------------------------------------------
    /** Skor dasar kandidat pot yang LOLOS saringan. */
    private static float baseScore(Snap snap, Sim.Result r, int legalPots, boolean moneyWin,
                                   float strength, float cutDeg, float travel) {
        if (snap.arcade) {
            return arcadeBaseScore(snap, r, legalPots, strength, cutDeg, travel);
        }
        float sc = legalPots * GOAL.potValue(snap, -1);
        if (moneyWin || (snap.moneyBall > 0 && r.potted[snap.moneyBall]
                && isLegal(snap, snap.moneyBall))) {
            sc += GOAL.winValue(snap);
        }
        int cue = snap.clickTarget;
        // bola putih berakhir menempel lubang = rawan
        for (int h = 0; h < snap.holes.length; h++) {
            float d = dist(r.stopX[cue], r.stopY[cue], snap.holes[h].x, snap.holes[h].y);
            if (d < 130.0f) {
                sc -= (130.0f - d) * 1.2f;
            }
        }
        // 5A penalti: bola lawan berakhir sangat dekat mulut lubang
        // (posisi akhir menguntungkan lawan) — penalti, bukan buang.
        for (int i = 0; i < snap.balls.length; i++) {
            if (i == cue || !snap.balls[i].avail || r.potted[i] || isLegal(snap, i)) {
                continue;
            }
            float nd = Float.MAX_VALUE;
            for (int h = 0; h < snap.holes.length; h++) {
                nd = Math.min(nd, dist(r.stopX[i], r.stopY[i],
                        snap.holes[h].x, snap.holes[h].y));
            }
            sc -= GOAL.opponentNearHolePenalty(nd);
        }
        sc -= cutDeg * 10.0f;
        sc -= travel * 0.08f;
        sc -= strength * 0.01f;
        return sc;
    }

    /**
     * Skor Arcade: maksimalkan bola masuk per tembakan (multi-pot =
     * combo & skor), bonus lubang spesial (+cue effect 5 = +1 restTime;
     * -bola effect 4 = bola sisa lain ikut terhapus + skor), dan
     * urgensi sisa restTime: makin sedikit stik tersisa relatif ke
     * bola, makin agresif mengejar multi-pot. Lubang hitam tak pernah
     * sampai sini (sudah ditolak saringan).
     */
    private static float arcadeBaseScore(Snap snap, Sim.Result r, int pots,
                                         float strength, float cutDeg, float travel) {
        int remaining = 0;
        for (int i = 0; i < snap.balls.length; i++) {
            if (i != snap.clickTarget && snap.balls[i].avail) {
                remaining++;
            }
        }
        float urgency = snap.restTime > 0
                ? Math.min(2.0f, (float) remaining / (float) snap.restTime)
                : 1.0f;
        float sc = pots * 1000.0f * (1.0f + 0.25f * urgency);
        if (pots > 1) {
            sc += (pots - 1) * 500.0f; // nilai combo
        }
        for (int i = 0; i < snap.balls.length; i++) {
            if (i == snap.clickTarget || !r.potted[i]) {
                continue;
            }
            int h = r.potHole[i];
            if (h >= 0 && h < snap.holes.length) {
                if (snap.holes[h].effect == 5) {
                    sc += 900.0f; // +1 restTime
                } else if (snap.holes[h].effect == 4) {
                    sc += 700.0f; // bola sisa lain ikut terhapus
                }
            }
        }
        int cue = snap.clickTarget;
        for (int h = 0; h < snap.holes.length; h++) {
            float d = dist(r.stopX[cue], r.stopY[cue], snap.holes[h].x, snap.holes[h].y);
            if (d < 130.0f) {
                sc -= (130.0f - d) * 1.2f;
            }
        }
        sc -= cutDeg * 6.0f;
        sc -= travel * 0.05f;
        sc -= strength * 0.008f;
        return sc;
    }

    /** Pruning geometri: jalur bola->lubang atau cue->ghost terhalang
     * bola lain (selain bola target b). */
    private static boolean pathBlocked(Snap snap, int target, float bx, float by,
                                       float hx, float hy, float cx, float cy,
                                       float gx, float gy) {
        float rad = snap.ballRadius * 2.0f - 8.0f;
        for (int o = 0; o < snap.balls.length; o++) {
            if (o == snap.clickTarget || o == target || !snap.balls[o].avail) {
                continue;
            }
            if (segBlocked(bx, by, hx, hy, snap.balls[o].x, snap.balls[o].y, rad)
                    || segBlocked(cx, cy, gx, gy, snap.balls[o].x, snap.balls[o].y, rad)) {
                return true;
            }
        }
        return false;
    }

    /** Apakah segmen (x0,y0)-(x1,y1) terhalang bola di (bx,by) radius rad? */
    private static boolean segBlocked(float x0, float y0, float x1, float y1,
                                      float bx, float by, float rad) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len2 = dx * dx + dy * dy;
        if (len2 < 1.0f) {
            return false;
        }
        float t = ((bx - x0) * dx + (by - y0) * dy) / len2;
        t = Math.max(0.0f, Math.min(1.0f, t));
        float px = x0 + dx * t;
        float py = y0 + dy * t;
        float d = dist(px, py, bx, by);
        return d < rad;
    }

    /**
     * NILAI POSISI LANJUTAN 2-PLY (Perintah 5D): dari posisi bola putih
     * akhir hasil r, nilai tembakan legal berikutnya yang paling mudah
     * (geometris: garis ghost bersih, lubang bersih, potong <= 78°).
     * Tidak ada lanjutan layak -> penalti posisi mati (kecuali meja
     * habis = menang). Bola penentu yang menjadi satu-satunya sisa
     * ikut dinilai sebagai lanjutan.
     */
    static float nextShotValue(Snap snap, boolean[] legalNow, Sim.Result r) {
        int cue = snap.clickTarget;
        boolean[] potted = r.potted;
        // legal pada giliran berikutnya (grup habis -> bola penentu)
        boolean[] lg = chainLegal(snap, legalNow, potted);
        float cx = r.stopX[cue];
        float cy = r.stopY[cue];
        float rad = snap.ballRadius;
        boolean anyRemaining = false;
        float best = -Float.MAX_VALUE;
        for (int b = 0; b < snap.balls.length; b++) {
            if (b == cue || !snap.balls[b].avail || potted[b]) {
                continue;
            }
            anyRemaining = true;
            if (!isLegal(lg, b)) {
                continue;
            }
            float bx = r.stopX[b];
            float by = r.stopY[b];
            for (int h = 0; h < snap.holes.length; h++) {
                float hx = snap.holes[h].x;
                float hy = snap.holes[h].y;
                float bl = dist(bx, by, hx, hy);
                if (bl < 1.0f) {
                    continue;
                }
                float ux = (hx - bx) / bl;
                float uy = (hy - by) / bl;
                float gx = bx - ux * rad * 2.0f;
                float gy = by - uy * rad * 2.0f;
                float d1 = dist(cx, cy, gx, gy);
                if (d1 < 1.0f) {
                    continue;
                }
                float dot = ((gx - cx) / d1) * ux + ((gy - cy) / d1) * uy;
                float cut = (float) Math.toDegrees(Math.acos(
                        Math.max(-1.0f, Math.min(1.0f, dot))));
                if (cut > 78.0f) {
                    continue;
                }
                boolean blocked = false;
                for (int o = 0; o < snap.balls.length && !blocked; o++) {
                    if (o == cue || o == b || !snap.balls[o].avail || potted[o]) {
                        continue;
                    }
                    if (segBlocked(cx, cy, gx, gy, r.stopX[o], r.stopY[o], rad * 2.0f - 6.0f)
                            || segBlocked(bx, by, hx, hy, r.stopX[o], r.stopY[o], rad * 2.0f - 6.0f)) {
                        blocked = true;
                    }
                }
                if (blocked) {
                    continue;
                }
                float ease = 420.0f - cut * 3.2f - (d1 + bl) * 0.12f;
                if (ease > best) {
                    best = ease;
                }
            }
        }
        if (!anyRemaining) {
            return 600.0f; // meja habis — ronde selesai
        }
        if (best == -Float.MAX_VALUE) {
            return -GOAL.deadPositionPenalty();
        }
        return best;
    }

    /** Legalitas giliran berikutnya sesudah himpunan bola masuk. */
    static boolean[] chainLegal(Snap snap, boolean[] legalNow, boolean[] potted) {
        boolean[] lg = new boolean[snap.balls.length];
        if (snap.moneyBall == 9) {
            for (int i = 1; i < snap.balls.length; i++) {
                if (snap.balls[i].avail && !potted[i]) {
                    lg[i] = true;
                    return lg;
                }
            }
            return lg;
        }
        boolean any = false;
        for (int i = 0; i < snap.balls.length; i++) {
            lg[i] = isLegal(legalNow, i) && !potted[i];
            any |= lg[i];
        }
        if (!any && snap.moneyBall > 0 && snap.balls.length > snap.moneyBall
                && snap.balls[snap.moneyBall].avail && !potted[snap.moneyBall]) {
            lg[snap.moneyBall] = true;
        }
        return lg;
    }

    // ----------------------------------------------------------------
    // KANDIDAT POT
    // ----------------------------------------------------------------
    private static final class Cand {
        float angle, strength, spinX, spinY;
        int target;
        int hole = -1;
        int legalPots;
        boolean moneyWin;
        float cutDeg, travel, score;
        int robust;
        int level = LV_POT;
        Sim.Result res;
        float spotX, spotY;
        boolean hasSpot;
    }

    private static Cand evalPot(Sim sim, Snap snap, boolean[] legal, float angle,
                                float strength, int target, float cutDeg, float travel) {
        Sim.Result r = sim.simulate(strength, 0.0f, 0.0f, angle, false);
        int[] out = new int[2];
        int reason = filterReason(snap, legal, r, out);
        counted(reason);
        if (reason != PASS) {
            return null;
        }
        if (out[0] == 0 && out[1] == 0) {
            return null;
        }
        if (!r.potted[target] && out[1] == 0 && !snap.arcade) {
            return null;
        }
        Cand c = new Cand();
        c.angle = angle;
        c.strength = strength;
        c.target = target;
        c.hole = r.potHole[target];
        if (c.hole < 0 && out[1] != 0 && snap.moneyBall > 0) {
            c.hole = r.potHole[snap.moneyBall];
        }
        if (c.hole < 0 && snap.arcade) {
            // target bidik tidak masuk tapi bola lain masuk (multi-pot
            // liar yang sah di Arcade) — catat lubang pot pertama
            for (int i = 0; i < snap.balls.length; i++) {
                if (i != snap.clickTarget && r.potted[i]) {
                    c.hole = r.potHole[i];
                    if (!r.potted[target]) {
                        c.target = i;
                    }
                    break;
                }
            }
        }
        c.legalPots = out[0];
        c.moneyWin = out[1] != 0;
        c.cutDeg = cutDeg;
        c.travel = travel;
        c.res = r;
        c.score = baseScore(snap, r, out[0], c.moneyWin, strength, cutDeg, travel);
        return c;
    }

    /**
     * Pembangkit kandidat pot langsung dari geometri ghost-ball.
     * strengthsMode: 0 = kasar (1 kekuatan), 1 = standar (3 kekuatan).
     * Proto sim HARUS sudah sejalan dengan posisi snap.balls.
     */
    private static ArrayList<Cand> genPots(Sim sim, Snap snap, boolean[] legal,
                                           int strengthsMode) {
        ArrayList<Cand> cands = new ArrayList<Cand>();
        Sim.Ball cue = sim.protoBall(snap.clickTarget);
        // pruning level besar (Arcade s/d 25 bola): pasangan (bola,
        // lubang) yang jalurnya jelas terhalang bola lain dibuang
        // sebelum simulasi — anggaran berpikir tetap ±3 dtk.
        boolean prune = snap.balls.length > 15;
        for (int b = 0; b < snap.balls.length; b++) {
            if (b == snap.clickTarget || !snap.balls[b].avail || !isLegal(legal, b)) {
                continue;
            }
            Sim.Ball ball = snap.balls[b];
            for (int h = 0; h < snap.holes.length; h++) {
                Sim.Hole hole = snap.holes[h];
                float dx = hole.x - ball.x;
                float dy = hole.y - ball.y;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 1.0f) {
                    continue;
                }
                float ux = dx / len;
                float uy = dy / len;
                float ghostDist = cue.scale + ball.scale;
                float gx = ball.x - ux * ghostDist;
                float gy = ball.y - uy * ghostDist;
                if (prune && pathBlocked(snap, b, ball.x, ball.y, hole.x, hole.y,
                        cue.x, cue.y, gx, gy)) {
                    continue;
                }
                float vx = gx - cue.x;
                float vy = gy - cue.y;
                float d1 = (float) Math.sqrt(vx * vx + vy * vy);
                if (d1 < 1.0f) {
                    continue;
                }
                float ang = (float) Math.toDegrees(Math.atan2(vy, vx));
                float dot = (vx / d1) * ux + (vy / d1) * uy;
                float cut = (float) Math.toDegrees(Math.acos(
                        Math.max(-1.0f, Math.min(1.0f, dot))));
                float est = estStrength(snap, d1, len);
                float[] sts = strengthsMode == 0
                        ? new float[]{est}
                        : new float[]{clamp(est * 0.75f, snap.minStrength, snap.maxStrength),
                                est,
                                clamp(est * 1.3f, snap.minStrength, snap.maxStrength)};
                for (int si = 0; si < sts.length; si++) {
                    Cand c = evalPot(sim, snap, legal, ang, sts[si], b, cut, d1 + len);
                    if (c != null) {
                        cands.add(c);
                    }
                }
            }
        }
        return cands;
    }

    /** Uji ketahanan perturbasi (sudut ±0,15/±0,3°, strength ±5%). */
    private static int robustness(Sim sim, Snap snap, boolean[] legal, Cand c) {
        float[] dAng = {-0.3f, -0.15f, 0.15f, 0.3f};
        int ok = 0;
        for (int i = 0; i < dAng.length; i++) {
            Sim.Result r = sim.simulate(c.strength, c.spinX, c.spinY,
                    c.angle + dAng[i], false);
            int[] out = new int[2];
            if (passesFilter(snap, legal, r, out) && (r.potted[c.target] || out[1] != 0)) {
                ok++;
            }
        }
        float[] mStr = {0.95f, 1.05f};
        for (int i = 0; i < mStr.length; i++) {
            float st = clamp(c.strength * mStr[i], snap.minStrength, snap.maxStrength);
            Sim.Result r = sim.simulate(st, c.spinX, c.spinY, c.angle, false);
            int[] out = new int[2];
            if (passesFilter(snap, legal, r, out) && (r.potted[c.target] || out[1] != 0)) {
                ok++;
            }
        }
        return ok;
    }

    /**
     * Pilih kandidat terbaik dari sekumpulan kandidat pot: robust >= 3
     * menjadi syarat bila ada yang mencapainya; skor akhir = skor
     * dasar + robust*60 + (2-ply position value bila usePosition).
     * Mengembalikan skor akhir di c.score.
     */
    private static Cand pickBest(Sim sim, Snap snap, boolean[] legal,
                                 ArrayList<Cand> cands, int robustTopK,
                                 boolean usePosition) {
        if (cands == null || cands.isEmpty()) {
            return null;
        }
        for (int i = 0; i < cands.size(); i++) {
            for (int j = i + 1; j < cands.size(); j++) {
                if (cands.get(j).score > cands.get(i).score) {
                    Cand tmp = cands.get(i);
                    cands.set(i, cands.get(j));
                    cands.set(j, tmp);
                }
            }
        }
        int lim = Math.min(robustTopK, cands.size());
        int bestRobustSeen = -1;
        for (int i = 0; i < lim; i++) {
            Cand c = cands.get(i);
            c.robust = robustness(sim, snap, legal, c);
            if (c.robust > bestRobustSeen) {
                bestRobustSeen = c.robust;
            }
        }
        Cand best = null;
        float bestFinal = -Float.MAX_VALUE;
        for (int i = 0; i < lim; i++) {
            Cand c = cands.get(i);
            if (bestRobustSeen >= 3 && c.robust < 3) {
                continue;
            }
            // Nilai posisi lanjutan (2-ply) adalah penentu SEKUNDER:
            // dijepit & diskala kecil supaya tidak pernah mengalahkan
            // pot yang jauh lebih mudah/kokoh — inti keluhan user
            // "tidak memilih bola yang paling enak" (uji h2).
            float posRaw = usePosition
                    ? nextShotValue(snap, legal, c.res)
                    : simpleLeaveBonus(snap, c.res);
            float pos = Math.max(-400.0f, Math.min(400.0f, posRaw)) * 0.3f;
            float fin = c.score + c.robust * 60.0f + pos;
            if (fin > bestFinal) {
                bestFinal = fin;
                best = c;
            }
        }
        if (best == null) {
            for (int i = 0; i < lim; i++) {
                Cand c = cands.get(i);
                if (best == null || c.robust > best.robust
                        || (c.robust == best.robust && c.score > best.score)) {
                    best = c;
                }
            }
            if (best != null) {
                best.score = best.score + best.robust * 60.0f;
                return best;
            }
        }
        if (best != null) {
            best.score = bestFinal;
        }
        return best;
    }

    /** Bonus leave sederhana (Normal): bola putih dekat target legal
     * berikutnya. Tanpa penilaian kelayakan penuh (itu 2-ply Expert). */
    private static float simpleLeaveBonus(Snap snap, Sim.Result r) {
        int cue = snap.clickTarget;
        float nearest = Float.MAX_VALUE;
        for (int i = 0; i < snap.balls.length; i++) {
            if (i != cue && snap.balls[i].avail && !r.potted[i] && isLegal(snap, i)) {
                float d = dist(r.stopX[cue], r.stopY[cue], snap.balls[i].x, snap.balls[i].y);
                if (d < nearest) {
                    nearest = d;
                }
            }
        }
        if (nearest == Float.MAX_VALUE) {
            return 0.0f;
        }
        return Math.max(0.0f, 400.0f - nearest) * 0.3f;
    }

    /**
     * PILIH SPIN (Perintah 3): untuk kandidat terpilih, nilai grid spin
     * pada klon (spin memengaruhi defleksi & posisi akhir bola putih
     * lewat rvx/rvy/w — Sim.applyClick meniru click() asli). Spin hanya
     * diganti dari (0,0) bila varian spin tetap memasukkan target,
     * lolos saringan, dan memberi posisi lanjutan lebih baik.
     * Mengembalikan true bila spin non-nol terpilih.
     */
    private static boolean chooseSpin(Sim sim, Snap snap, boolean[] legal, Cand c,
                                      int gridLevel) {
        float[][] grid = gridLevel >= 2
                ? new float[][]{{0.45f, 0.0f}, {-0.45f, 0.0f}, {0.0f, 0.45f}, {0.0f, -0.45f},
                        {0.3f, 0.3f}, {-0.3f, 0.3f}, {0.3f, -0.3f}, {-0.3f, -0.3f},
                        {0.7f, 0.0f}, {-0.7f, 0.0f}, {0.0f, 0.7f}, {0.0f, -0.7f}}
                : new float[][]{{0.45f, 0.0f}, {-0.45f, 0.0f}, {0.0f, 0.45f}, {0.0f, -0.45f},
                        {0.3f, 0.3f}, {-0.3f, 0.3f}, {0.3f, -0.3f}, {-0.3f, -0.3f}};
        int basePots = c.legalPots + (c.moneyWin ? 1 : 0);
        float bestVal = basePots * 2000.0f + nextShotValue(snap, legal, c.res) * 0.3f;
        float bsx = 0.0f, bsy = 0.0f;
        Sim.Result bestRes = c.res;
        int bestPots = basePots;
        int[] bestOut = null;
        for (int i = 0; i < grid.length; i++) {
            Sim.Result r = sim.simulate(c.strength, grid[i][0], grid[i][1], c.angle, false);
            int[] out = new int[2];
            if (!passesFilter(snap, legal, r, out)) {
                continue;
            }
            if (!r.potted[c.target] && out[1] == 0 && !snap.arcade) {
                continue;
            }
            // Spin yang memasukkan LEBIH banyak bola sah (mis. follow
            // menyusul bola yang menggantung — multi-pot Arcade)
            // mengalahkan spin yang hanya memperbaiki posisi.
            int pots = out[0] + (out[1] != 0 ? 1 : 0);
            float v = pots * 2000.0f + nextShotValue(snap, legal, r) * 0.3f
                    - (Math.abs(grid[i][0]) + Math.abs(grid[i][1])) * 12.0f;
            if (v > bestVal + 18.0f) { // harus jelas lebih baik
                bestVal = v;
                bsx = grid[i][0];
                bsy = grid[i][1];
                bestRes = r;
                bestPots = pots;
                bestOut = out;
            }
        }
        if (bestOut != null && bestPots > basePots) {
            c.legalPots = bestOut[0];
            c.moneyWin = bestOut[1] != 0;
            if (!bestRes.potted[c.target]) {
                for (int i = 0; i < snap.balls.length; i++) {
                    if (i != snap.clickTarget && bestRes.potted[i]) {
                        c.target = i;
                        c.hole = bestRes.potHole[i];
                        break;
                    }
                }
            }
        }
        c.spinX = bsx;
        c.spinY = bsy;
        c.res = bestRes;
        return bsx != 0.0f || bsy != 0.0f;
    }

    /** Pelunakan kekuatan pasca-pilih (tetap pot + lolos + kokoh ±0,15°). */
    private static void trimStrength(Sim sim, Snap snap, boolean[] legal, Cand c) {
        float st = c.strength;
        for (int i = 0; i < 3; i++) {
            float lower = clamp(st * 0.88f, snap.minStrength, snap.maxStrength);
            if (lower >= st - 1.0f) {
                break;
            }
            if (!potsAt(sim, snap, legal, c, lower, c.angle)
                    || !potsAt(sim, snap, legal, c, lower, c.angle + 0.15f)
                    || !potsAt(sim, snap, legal, c, lower, c.angle - 0.15f)) {
                break;
            }
            st = lower;
        }
        c.strength = st;
    }

    private static boolean potsAt(Sim sim, Snap snap, boolean[] legal, Cand c,
                                  float strength, float angle) {
        Sim.Result r = sim.simulate(strength, c.spinX, c.spinY, angle, false);
        int[] out = new int[2];
        return passesFilter(snap, legal, r, out) && (r.potted[c.target] || out[1] != 0);
    }

    private static Shot toShot(Cand c) {
        Shot s = new Shot();
        s.angle = c.angle;
        s.strength = c.strength;
        s.spinX = c.spinX;
        s.spinY = c.spinY;
        s.targetBall = c.target;
        s.potted = c.legalPots;
        s.robust = c.robust;
        s.score = c.score;
        s.level = c.level;
        s.safety = c.level == LV_SAFETY || c.level == LV_BREAKOUT || c.level == LV_LAST;
        if (c.hasSpot) {
            s.spotX = c.spotX;
            s.spotY = c.spotY;
        }
        return s;
    }

    // ----------------------------------------------------------------
    // GEOMETRI PANTULAN (bank / kick)
    // ----------------------------------------------------------------
    /** Cerminkan titik (px,py) terhadap garis (x0,y0)-(x1,y1). */
    private static float[] reflect(float px, float py, float x0, float y0, float x1, float y1) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len2 = dx * dx + dy * dy;
        if (len2 < 1.0f) {
            return new float[]{px, py};
        }
        float t = ((px - x0) * dx + (py - y0) * dy) / len2;
        float cx = x0 + dx * t;
        float cy = y0 + dy * t;
        return new float[]{2.0f * cx - px, 2.0f * cy - py};
    }

    /** Segmen cushion panjang (rel) dari daftar blocker snapshot. */
    private static ArrayList<Sim.Blocker> railLines(Snap snap) {
        ArrayList<Sim.Blocker> rails = new ArrayList<Sim.Blocker>();
        for (int i = 0; i < snap.blockers.length; i++) {
            Sim.Blocker bl = snap.blockers[i];
            if (bl.vis && dist(bl.x0, bl.y0, bl.x1, bl.y1) >= 900.0f) {
                rails.add(bl);
            }
        }
        return rails;
    }

    /** Nilai satu tembakan non-pot murni dari hasil sim-nya. */
    private static Cand evalCustom(Sim sim, Snap snap, boolean[] legal, float angle,
                                   float strength, int target, int level) {
        Sim.Result r = sim.simulate(strength, 0.0f, 0.0f, angle, false);
        int[] out = new int[2];
        int reason = filterReason(snap, legal, r, out);
        counted(reason);
        if (reason != PASS) {
            return null;
        }
        Cand c = new Cand();
        c.angle = angle;
        c.strength = strength;
        c.target = target;
        c.legalPots = out[0];
        c.moneyWin = out[1] != 0;
        c.level = level;
        c.res = r;
        c.hole = (target >= 0 && r.potted[target]) ? r.potHole[target] : -1;
        c.score = baseScore(snap, r, out[0], c.moneyWin, strength, 45.0f, 1200.0f);
        return c;
    }

    // ----------------------------------------------------------------
    // TANGGA 2 — BANK: bola target memantul cushion lalu masuk lubang.
    // Geometri: cerminkan lubang terhadap garis rel; bola dibidik ke
    // bayangan lubang. Validasi: sim penuh + potAfterRail target.
    // multi=true (Mustahil): pantulan dua rel (cermin berurutan) dan
    // kewajiban cushionHitCount target >= 2.
    // ----------------------------------------------------------------
    private static Cand searchBank(Sim sim, Snap snap, boolean[] legal,
                                   boolean multi, long deadline) {
        Sim.Ball cue = sim.protoBall(snap.clickTarget);
        ArrayList<Sim.Blocker> rails = railLines(snap);
        Cand best = null;
        for (int b = 0; b < snap.balls.length; b++) {
            if (b == snap.clickTarget || !snap.balls[b].avail || !isLegal(legal, b)) {
                continue;
            }
            Sim.Ball ball = snap.balls[b];
            for (int h = 0; h < snap.holes.length; h++) {
                Sim.Hole hole = snap.holes[h];
                for (int ri = 0; ri < rails.size(); ri++) {
                    Sim.Blocker rl = rails.get(ri);
                    float[] rp = reflect(hole.x, hole.y, rl.x0, rl.y0, rl.x1, rl.y1);
                    Cand c = tryBankLine(sim, snap, legal, cue, ball, b, rp[0], rp[1], 1);
                    if (c != null && (best == null || c.score > best.score)) {
                        best = c;
                    }
                    if (multi) {
                        for (int rj = 0; rj < rails.size(); rj++) {
                            if (rj == ri) {
                                continue;
                            }
                            if (expired(deadline)) {
                                return best;
                            }
                            Sim.Blocker rl2 = rails.get(rj);
                            float[] rp2 = reflect(rp[0], rp[1], rl2.x0, rl2.y0, rl2.x1, rl2.y1);
                            Cand c2 = tryBankLine(sim, snap, legal, cue, ball, b,
                                    rp2[0], rp2[1], 2);
                            if (c2 != null && (best == null || c2.score > best.score)) {
                                best = c2;
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    private static Cand tryBankLine(Sim sim, Snap snap, boolean[] legal, Sim.Ball cue,
                                    Sim.Ball ball, int b, float aimX, float aimY, int railsNeeded) {
        float dx = aimX - ball.x;
        float dy = aimY - ball.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1.0f) {
            return null;
        }
        float ux = dx / len;
        float uy = dy / len;
        float gd = cue.scale + ball.scale;
        float gx = ball.x - ux * gd;
        float gy = ball.y - uy * gd;
        float vx = gx - cue.x;
        float vy = gy - cue.y;
        float d1 = (float) Math.sqrt(vx * vx + vy * vy);
        if (d1 < 1.0f) {
            return null;
        }
        float ang = (float) Math.toDegrees(Math.atan2(vy, vx));
        float est = estStrength(snap, d1, len + 500.0f);
        float[] sts = {est, clamp(est * 1.25f, snap.minStrength, snap.maxStrength)};
        for (int si = 0; si < sts.length; si++) {
            Sim.Result r = sim.simulate(sts[si], 0.0f, 0.0f, ang, false);
            int[] out = new int[2];
            int reason = filterReason(snap, legal, r, out);
            counted(reason);
            if (reason != PASS || !r.potted[b]) {
                continue;
            }
            if (!r.potAfterRail[b] || r.cushionHitCount[b] < railsNeeded) {
                continue;
            }
            Cand c = new Cand();
            c.angle = ang;
            c.strength = sts[si];
            c.target = b;
            c.hole = r.potHole[b];
            c.legalPots = out[0];
            c.moneyWin = out[1] != 0;
            c.level = LV_BANK;
            c.res = r;
            c.score = baseScore(snap, r, out[0], c.moneyWin, sts[si], 60.0f, d1 + len)
                    - 250.0f; // bank lebih berisiko dari pot langsung
            return c;
        }
        return null;
    }

    // ----------------------------------------------------------------
    // TANGGA 3 — KICK / rail-first: bola putih memantul cushion DULU
    // lalu kontak bola legal (pelarian posisi terjepit). Geometri:
    // cerminkan titik ghost bola target terhadap garis rel; cue
    // dibidik ke bayangannya. Validasi: cueRailFirst + kontak legal.
    // ----------------------------------------------------------------
    private static Cand searchKick(Sim sim, Snap snap, boolean[] legal, long deadline) {
        Sim.Ball cue = sim.protoBall(snap.clickTarget);
        ArrayList<Sim.Blocker> rails = railLines(snap);
        Cand best = null;
        for (int b = 0; b < snap.balls.length; b++) {
            if (b == snap.clickTarget || !snap.balls[b].avail || !isLegal(legal, b)) {
                continue;
            }
            Sim.Ball ball = snap.balls[b];
            for (int h = 0; h < snap.holes.length; h++) {
                Sim.Hole hole = snap.holes[h];
                float dx = hole.x - ball.x;
                float dy = hole.y - ball.y;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 1.0f) {
                    continue;
                }
                float ux = dx / len;
                float uy = dy / len;
                float gd = cue.scale + ball.scale;
                float gx = ball.x - ux * gd;
                float gy = ball.y - uy * gd;
                for (int ri = 0; ri < rails.size(); ri++) {
                    if (expired(deadline)) {
                        return best;
                    }
                    Sim.Blocker rl = rails.get(ri);
                    float[] rp = reflect(gx, gy, rl.x0, rl.y0, rl.x1, rl.y1);
                    float vx = rp[0] - cue.x;
                    float vy = rp[1] - cue.y;
                    float d1 = (float) Math.sqrt(vx * vx + vy * vy);
                    if (d1 < 1.0f) {
                        continue;
                    }
                    float ang = (float) Math.toDegrees(Math.atan2(vy, vx));
                    float est = estStrength(snap, d1, len + 400.0f);
                    float[] sts = {est, clamp(est * 1.3f, snap.minStrength, snap.maxStrength)};
                    for (int si = 0; si < sts.length; si++) {
                        Sim.Result r = sim.simulate(sts[si], 0.0f, 0.0f, ang, false);
                        int[] out = new int[2];
                        int reason = filterReason(snap, legal, r, out);
                        counted(reason);
                        if (reason != PASS || !r.cueRailFirst || r.firstContact != b) {
                            continue;
                        }
                        Cand c = new Cand();
                        c.angle = ang;
                        c.strength = sts[si];
                        c.target = b;
                        c.legalPots = out[0];
                        c.moneyWin = out[1] != 0;
                        c.level = LV_KICK;
                        c.res = r;
                        c.hole = r.potted[b] ? r.potHole[b] : -1;
                        c.score = baseScore(snap, r, out[0], c.moneyWin, sts[si], 70.0f, d1 + len)
                                - 150.0f + (r.potted[b] ? 500.0f : 0.0f);
                        if (best == null || c.score > best.score) {
                            best = c;
                        }
                    }
                }
            }
        }
        return best;
    }

    // ----------------------------------------------------------------
    // TANGGA 4 — COMBINATION: bola sendiri A ditabrakkan ke bola
    // sendiri B yang searah lubang; B yang masuk.
    // ----------------------------------------------------------------
    private static Cand searchCombo(Sim sim, Snap snap, boolean[] legal) {
        Sim.Ball cue = sim.protoBall(snap.clickTarget);
        Cand best = null;
        for (int a = 0; a < snap.balls.length; a++) {
            if (a == snap.clickTarget || !snap.balls[a].avail || !isLegal(legal, a)) {
                continue;
            }
            Sim.Ball ballA = snap.balls[a];
            for (int b = 0; b < snap.balls.length; b++) {
                if (b == snap.clickTarget || b == a || !snap.balls[b].avail
                        || !isLegal(legal, b)) {
                    continue;
                }
                Sim.Ball ballB = snap.balls[b];
                for (int h = 0; h < snap.holes.length; h++) {
                    Sim.Hole hole = snap.holes[h];
                    float dx = hole.x - ballB.x;
                    float dy = hole.y - ballB.y;
                    float len = (float) Math.sqrt(dx * dx + dy * dy);
                    if (len < 1.0f) {
                        continue;
                    }
                    float ux = dx / len;
                    float uy = dy / len;
                    // titik pusat A saat kontak agar B meluncur ke lubang
                    float px = ballB.x - ux * (ballA.scale + ballB.scale);
                    float py = ballB.y - uy * (ballA.scale + ballB.scale);
                    float ax = px - ballA.x;
                    float ay = py - ballA.y;
                    float alen = (float) Math.sqrt(ax * ax + ay * ay);
                    if (alen < 1.0f) {
                        continue;
                    }
                    float gx = ballA.x - (ax / alen) * (cue.scale + ballA.scale);
                    float gy = ballA.y - (ay / alen) * (cue.scale + ballA.scale);
                    float vx = gx - cue.x;
                    float vy = gy - cue.y;
                    float d1 = (float) Math.sqrt(vx * vx + vy * vy);
                    if (d1 < 1.0f) {
                        continue;
                    }
                    float ang = (float) Math.toDegrees(Math.atan2(vy, vx));
                    float est = estStrength(snap, d1 + alen, len);
                    Sim.Result r = sim.simulate(est, 0.0f, 0.0f, ang, false);
                    int[] out = new int[2];
                    int reason = filterReason(snap, legal, r, out);
                    counted(reason);
                    if (reason != PASS || r.firstContact != a || !r.potted[b]) {
                        continue;
                    }
                    Cand c = new Cand();
                    c.angle = ang;
                    c.strength = est;
                    c.target = b;
                    c.hole = r.potHole[b];
                    c.legalPots = out[0];
                    c.moneyWin = out[1] != 0;
                    c.level = LV_COMBO;
                    c.res = r;
                    c.score = baseScore(snap, r, out[0], c.moneyWin, est, 55.0f, d1 + alen + len)
                            - 200.0f;
                    if (best == null || c.score > best.score) {
                        best = c;
                    }
                }
            }
        }
        return best;
    }

    // ----------------------------------------------------------------
    // TANGGA 5 — SAFETY EXPERT: kontak legal + (bola mencapai cushion
    // sesudah kontak, syarat anti-foul) + bola putih bersembunyi:
    // jauh dari bola termudah lawan, ada penghalang di antaranya, dan
    // berakhir dekat cushion.
    // ----------------------------------------------------------------
    private static Cand searchSafety(Sim sim, Snap snap, boolean[] legal) {
        Sim.Ball cue = sim.protoBall(snap.clickTarget);
        Cand best = null;
        Cand bestNoRail = null;
        float[] strengths = {240.0f, 320.0f, 430.0f, 580.0f};
        float[] offs = {0.0f, -0.45f, 0.45f, -0.85f, 0.85f};
        for (int b = 0; b < snap.balls.length; b++) {
            if (b == snap.clickTarget || !snap.balls[b].avail || !isLegal(legal, b)) {
                continue;
            }
            Sim.Ball ball = snap.balls[b];
            float dc = dist(cue.x, cue.y, ball.x, ball.y);
            float perpX = -(ball.y - cue.y) / Math.max(dc, 1.0f);
            float perpY = (ball.x - cue.x) / Math.max(dc, 1.0f);
            for (int oi = 0; oi < offs.length; oi++) {
                float tx = ball.x + perpX * ball.scale * offs[oi];
                float ty = ball.y + perpY * ball.scale * offs[oi];
                float ang = (float) Math.toDegrees(Math.atan2(ty - cue.y, tx - cue.x));
                for (int si = 0; si < strengths.length; si++) {
                    float st = clamp(strengths[si] + dc * 0.35f,
                            snap.minStrength, snap.maxStrength);
                    Sim.Result r = sim.simulate(st, 0.0f, 0.0f, ang, false);
                    int[] out = new int[2];
                    int reason = filterReason(snap, legal, r, out);
                    counted(reason);
                    if (reason != PASS || out[0] > 0 || out[1] > 0) {
                        continue;
                    }
                    // Arcade: tidak ada lawan — safety diganti SETUP:
                    // nilai posisi tembakan berikutnya, bukan sembunyi.
                    float sc = snap.arcade
                            ? nextShotValue(snap, legal, r) - st * 0.01f
                            : hideScore(snap, r) - st * 0.01f;
                    boolean railOk = r.railAfterContact || r.cueRailAfterContact;
                    Cand c = new Cand();
                    c.angle = ang;
                    c.strength = st;
                    c.target = b;
                    c.level = LV_SAFETY;
                    c.res = r;
                    c.score = sc;
                    if (railOk) {
                        if (best == null || sc > best.score) {
                            best = c;
                        }
                    } else if (bestNoRail == null || sc > bestNoRail.score) {
                        bestNoRail = c;
                    }
                }
            }
        }
        return best != null ? best : bestNoRail;
    }

    /** Skor bersembunyi bola putih pada posisi akhir hasil r. */
    private static float hideScore(Snap snap, Sim.Result r) {
        int cue = snap.clickTarget;
        float cx = r.stopX[cue];
        float cy = r.stopY[cue];
        float sc = 0.0f;
        // jarak ke bola lawan terdekat (yang paling mudah diserang lawan)
        int opp = -1;
        float oppD = Float.MAX_VALUE;
        for (int i = 0; i < snap.balls.length; i++) {
            if (i == cue || !snap.balls[i].avail || r.potted[i] || isLegal(snap, i)) {
                continue;
            }
            float d = dist(cx, cy, r.stopX[i], r.stopY[i]);
            if (d < oppD) {
                oppD = d;
                opp = i;
            }
        }
        if (opp >= 0) {
            sc += Math.min(oppD, 1200.0f) * 0.05f;
            // penghalang di antara bola putih dan bola lawan itu
            int blocks = 0;
            for (int i = 0; i < snap.balls.length; i++) {
                if (i == cue || i == opp || !snap.balls[i].avail || r.potted[i]) {
                    continue;
                }
                if (segBlocked(cx, cy, r.stopX[opp], r.stopY[opp],
                        r.stopX[i], r.stopY[i], snap.ballRadius * 2.0f)) {
                    blocks++;
                }
            }
            sc += blocks * 45.0f;
        }
        // berakhir dekat cushion menyulitkan lawan
        float dc = Float.MAX_VALUE;
        dc = Math.min(dc, Math.abs(cx + snap.tableW * 0.5f));
        dc = Math.min(dc, Math.abs(cx - snap.tableW * 0.5f));
        dc = Math.min(dc, Math.abs(cy + snap.tableH * 0.5f));
        dc = Math.min(dc, Math.abs(cy - snap.tableH * 0.5f));
        sc += Math.max(0.0f, 500.0f - dc) * 0.05f;
        // jauh dari lubang
        for (int h = 0; h < snap.holes.length; h++) {
            float d = dist(cx, cy, snap.holes[h].x, snap.holes[h].y);
            sc += Math.min(d, 500.0f) * 0.02f;
        }
        return sc;
    }

    // ----------------------------------------------------------------
    // TANGGA 6 — BREAKOUT: grup sendiri terikat cluster & tak ada pot:
    // tabrak cluster dengan kecepatan terkontrol untuk membukanya,
    // bola putih tetap relatif aman (saringan keras tetap berlaku).
    // ----------------------------------------------------------------
    private static Cand searchBreakout(Sim sim, Snap snap, boolean[] legal) {
        Sim.Ball cue = sim.protoBall(snap.clickTarget);
        // cari bola legal yang berada dalam cluster bola legal lain
        ArrayList<Integer> clustered = new ArrayList<Integer>();
        for (int b = 0; b < snap.balls.length; b++) {
            if (b == snap.clickTarget || !snap.balls[b].avail || !isLegal(legal, b)) {
                continue;
            }
            for (int o = 0; o < snap.balls.length; o++) {
                if (o == snap.clickTarget || o == b || !snap.balls[o].avail
                        || !isLegal(legal, o)) {
                    continue;
                }
                if (dist(snap.balls[b].x, snap.balls[b].y,
                        snap.balls[o].x, snap.balls[o].y) < snap.ballRadius * 5.6f) {
                    clustered.add(b);
                    break;
                }
            }
        }
        if (clustered.isEmpty()) {
            return null;
        }
        float before = 0.0f;
        for (int i = 0; i < clustered.size(); i++) {
            for (int j = i + 1; j < clustered.size(); j++) {
                before += dist(snap.balls[clustered.get(i)].x, snap.balls[clustered.get(i)].y,
                        snap.balls[clustered.get(j)].x, snap.balls[clustered.get(j)].y);
            }
        }
        Cand best = null;
        float[] sts = {950.0f, 1400.0f, 1900.0f};
        for (int ci = 0; ci < clustered.size(); ci++) {
            int b = clustered.get(ci);
            Sim.Ball ball = snap.balls[b];
            float ang = (float) Math.toDegrees(Math.atan2(ball.y - cue.y, ball.x - cue.x));
            for (int si = 0; si < sts.length; si++) {
                float st = clamp(sts[si], snap.minStrength, snap.maxStrength);
                Sim.Result r = sim.simulate(st, 0.0f, 0.0f, ang, false);
                int[] out = new int[2];
                int reason = filterReason(snap, legal, r, out);
                counted(reason);
                if (reason != PASS) {
                    continue;
                }
                float after = 0.0f;
                for (int i = 0; i < clustered.size(); i++) {
                    for (int j = i + 1; j < clustered.size(); j++) {
                        after += dist(r.stopX[clustered.get(i)], r.stopY[clustered.get(i)],
                                r.stopX[clustered.get(j)], r.stopY[clustered.get(j)]);
                    }
                }
                Cand c = new Cand();
                c.angle = ang;
                c.strength = st;
                c.target = b;
                c.legalPots = out[0];
                c.moneyWin = out[1] != 0;
                c.level = LV_BREAKOUT;
                c.res = r;
                c.score = (after - before) * 0.1f - st * 0.005f
                        + (out[0] > 0 ? 400.0f : 0.0f);
                if (best == null || c.score > best.score) {
                    best = c;
                }
            }
        }
        return best;
    }

    // ----------------------------------------------------------------
    // TANGGA 7 — UPAYA PALING TIDAK BURUK: garis yang paling dekat ke
    // kontak legal. Hukum 5A tetap berlaku pada hasil sim-nya sendiri:
    // tidak boleh ada bola salah masuk / bola 8 dini / scratch.
    // (Kontak pertamanya boleh bukan yang terdekat secara geometri —
    // inilah "upaya", dan readout menyatakannya terang-terangan.)
    // ----------------------------------------------------------------
    private static Cand searchLastResort(Sim sim, Snap snap, boolean[] legal) {
        Sim.Ball cue = sim.protoBall(snap.clickTarget);
        Cand best = null;
        float bestGap = Float.MAX_VALUE;
        float[] offs = {-1.1f, -0.55f, 0.0f, 0.55f, 1.1f};
        for (int b = 0; b < snap.balls.length; b++) {
            if (b == snap.clickTarget || !snap.balls[b].avail || !isLegal(legal, b)) {
                continue;
            }
            Sim.Ball ball = snap.balls[b];
            float dc = dist(cue.x, cue.y, ball.x, ball.y);
            float perpX = -(ball.y - cue.y) / Math.max(dc, 1.0f);
            float perpY = (ball.x - cue.x) / Math.max(dc, 1.0f);
            for (int oi = 0; oi < offs.length; oi++) {
                float tx = ball.x + perpX * ball.scale * 2.0f * offs[oi];
                float ty = ball.y + perpY * ball.scale * 2.0f * offs[oi];
                float ang = (float) Math.toDegrees(Math.atan2(ty - cue.y, tx - cue.x));
                float st = estStrength(snap, dc, 300.0f);
                Sim.Result r = sim.simulate(st, 0.0f, 0.0f, ang, false);
                if (r.cuePotted) {
                    continue;
                }
                boolean bad = false;
                for (int i = 0; i < snap.balls.length; i++) {
                    if (i == snap.clickTarget || !r.potted[i]) {
                        continue;
                    }
                    if (!isLegal(legal, i)) {
                        bad = true; // bola salah / 8 dini masuk — dilarang
                        break;
                    }
                    int hh = r.potHole[i];
                    if (snap.arcade && hh >= 0 && hh < snap.holes.length
                            && snap.holes[hh].effect == 1) {
                        bad = true; // lubang hitam — dilarang di semua mode
                        break;
                    }
                }
                if (bad) {
                    continue;
                }
                // celah geometris: jarak tegak lurus pusat bola ke garis bidik
                float dirx = (float) Math.cos(Math.toRadians(ang));
                float diry = (float) Math.sin(Math.toRadians(ang));
                float relx = ball.x - cue.x;
                float rely = ball.y - cue.y;
                float along = relx * dirx + rely * diry;
                if (along <= 0.0f) {
                    continue;
                }
                float gap = Math.abs(relx * diry - rely * dirx) - cue.scale - ball.scale;
                if (gap < bestGap) {
                    bestGap = gap;
                    Cand c = new Cand();
                    c.angle = ang;
                    c.strength = st;
                    c.target = b;
                    c.level = LV_LAST;
                    c.res = r;
                    c.legalPots = 0;
                    for (int i = 0; i < snap.balls.length; i++) {
                        if (i != snap.clickTarget && r.potted[i] && isLegal(legal, i)) {
                            c.legalPots++;
                        }
                    }
                    c.score = -gap;
                    best = c;
                }
            }
        }
        return best;
    }

    // ----------------------------------------------------------------
    // READOUT
    // ----------------------------------------------------------------
    static String groupLabel(Snap snap) {
        if (!snap.legalKnown) {
            return "grup tidak terbaca";
        }
        if (snap.moneyBall == 9) {
            for (int i = 1; i < snap.balls.length; i++) {
                if (isLegal(snap, i)) {
                    return "bola terendah " + i;
                }
            }
            return "tidak ada bola legal";
        }
        if (snap.moneyBall == 8) {
            boolean solids = false, stripes = false, eight = false;
            for (int i = 1; i <= 7 && i < snap.balls.length; i++) {
                solids |= isLegal(snap, i);
            }
            for (int i = 9; i <= 15 && i < snap.balls.length; i++) {
                stripes |= isLegal(snap, i);
            }
            eight = snap.balls.length > 8 && isLegal(snap, 8);
            if (eight && !solids && !stripes) {
                return "tinggal bola 8";
            }
            if (solids && stripes) {
                return "meja terbuka";
            }
            if (solids) {
                return "solids (1-7)";
            }
            if (stripes) {
                return "stripes (9-15)";
            }
            return "tidak ada bola legal";
        }
        return "semua bola";
    }

    static String holeName(Snap snap, int h) {
        if (h < 0 || h >= snap.holes.length) {
            return "lubang ?";
        }
        Sim.Hole hole = snap.holes[h];
        String sx = Math.abs(hole.x) < snap.tableW * 0.18f ? "tengah"
                : (hole.x < 0 ? "kiri" : "kanan");
        String sy = hole.y < 0 ? "atas" : "bawah";
        return "lubang " + sx + "-" + sy;
    }

    private static String rejectSummary() {
        StringBuilder sb = new StringBuilder();
        if (rejectCounts[REJ_SCRATCH] > 0) {
            sb.append(" scratch ").append(rejectCounts[REJ_SCRATCH]);
        }
        if (rejectCounts[REJ_CONTACT] > 0) {
            sb.append(" kontak ").append(rejectCounts[REJ_CONTACT]);
        }
        if (rejectCounts[REJ_ILLEGAL] > 0) {
            sb.append(" bolasalah ").append(rejectCounts[REJ_ILLEGAL]);
        }
        if (rejectCounts[REJ_MONEY] > 0) {
            sb.append(" bola8dini ").append(rejectCounts[REJ_MONEY]);
        }
        if (rejectCounts[REJ_BLACKHOLE] > 0) {
            sb.append(" blackhole ").append(rejectCounts[REJ_BLACKHOLE]);
        }
        return sb.length() == 0 ? " -" : sb.toString();
    }

    private static void resetCounters() {
        candCount = 0;
        for (int i = 0; i < rejectCounts.length; i++) {
            rejectCounts[i] = 0;
        }
        counting = true;
    }

    private static String reportHead(Snap snap, int diff) {
        String mode = snap.arcade
                ? " [Arcade • sisa stik " + (snap.restTime >= 0 ? snap.restTime : "?") + "]"
                : "";
        return "Auto (" + diffName(diff) + ")" + mode + ": " + groupLabel(snap)
                + " • kandidat " + candCount + " • ditolak:" + rejectSummary() + " • ";
    }

    private static void setReport(String s) {
        lastReport = s;
        ModState.autoReport = s;
    }

    private static String spinNote(Cand c) {
        if (c.spinX == 0.0f && c.spinY == 0.0f) {
            return "";
        }
        return ", spin (" + trimNum(c.spinX) + "," + trimNum(c.spinY) + ")";
    }

    private static String trimNum(float v) {
        String s = String.valueOf(v);
        return s.length() > 5 ? s.substring(0, 5) : s;
    }

    private static String decisionText(Snap snap, int diff, Cand c) {
        StringBuilder sb = new StringBuilder(reportHead(snap, diff));
        sb.append("[").append(levelName(c.level)).append("] ");
        if (c.level == LV_SAFETY && snap.arcade) {
            sb.append("setup: sentuh bola ").append(c.target)
                    .append(", siapkan tembakan berikutnya, kekuatan ")
                    .append((int) c.strength);
        } else if (c.level == LV_SAFETY) {
            sb.append("pengaman: sentuh bola ").append(c.target)
                    .append(", bola putih bersembunyi, kekuatan ").append((int) c.strength);
        } else if (c.level == LV_BREAKOUT) {
            sb.append("buka cluster lewat bola ").append(c.target)
                    .append(", kekuatan ").append((int) c.strength);
        } else if (c.level == LV_LAST) {
            sb.append("upaya terdekat ke kontak legal bola ").append(c.target)
                    .append(", kekuatan ").append((int) c.strength);
        } else {
            sb.append("dipilih bola ").append(c.target).append(" → ")
                    .append(holeName(snap, c.hole))
                    .append(", kekuatan ").append((int) c.strength)
                    .append(", kokoh ").append(c.robust).append("/6")
                    .append(spinNote(c));
        }
        return sb.toString();
    }

    // ----------------------------------------------------------------
    // PENCARIAN RAPAT MUSTAHIL: di sekitar kandidat terbaik, sapu sudut
    // ±0,3° (langkah 0,15°) x kekuatan 0,85/1,0/1,2 — varian yang lolos
    // ditambahkan sebagai kandidat baru.
    // ----------------------------------------------------------------
    private static void refineInto(Sim sim, Snap snap, boolean[] legal,
                                   ArrayList<Cand> cands, long deadline) {
        if (cands.isEmpty()) {
            return;
        }
        for (int i = 0; i < cands.size(); i++) {
            for (int j = i + 1; j < cands.size(); j++) {
                if (cands.get(j).score > cands.get(i).score) {
                    Cand tmp = cands.get(i);
                    cands.set(i, cands.get(j));
                    cands.set(j, tmp);
                }
            }
        }
        int lim = Math.min(5, cands.size());
        float[] dAng = {-0.3f, -0.15f, 0.15f, 0.3f};
        float[] mStr = {0.85f, 1.2f};
        ArrayList<Cand> extra = new ArrayList<Cand>();
        for (int i = 0; i < lim; i++) {
            Cand base = cands.get(i);
            for (int a = 0; a < dAng.length; a++) {
                if (expired(deadline)) {
                    cands.addAll(extra);
                    return;
                }
                Cand v = evalPot(sim, snap, legal, base.angle + dAng[a], base.strength,
                        base.target, base.cutDeg, base.travel);
                if (v != null) {
                    extra.add(v);
                }
            }
            for (int m = 0; m < mStr.length; m++) {
                float st = clamp(base.strength * mStr[m], snap.minStrength, snap.maxStrength);
                Cand v = evalPot(sim, snap, legal, base.angle, st,
                        base.target, base.cutDeg, base.travel);
                if (v != null) {
                    extra.add(v);
                }
            }
        }
        cands.addAll(extra);
    }

    /** Penyelamat pot Normal: coba garis geometri dengan spin follow/
     * draw/samping bila pot tanpa spin tidak ditemukan sama sekali. */
    private static Cand spinRescue(Sim sim, Snap snap, boolean[] legal) {
        Sim.Ball cue = sim.protoBall(snap.clickTarget);
        float[][] spins = {{0.0f, 0.5f}, {0.0f, -0.5f}, {0.4f, 0.0f}, {-0.4f, 0.0f}};
        ArrayList<Cand> found = new ArrayList<Cand>();
        for (int b = 0; b < snap.balls.length; b++) {
            if (b == snap.clickTarget || !snap.balls[b].avail || !isLegal(legal, b)) {
                continue;
            }
            Sim.Ball ball = snap.balls[b];
            for (int h = 0; h < snap.holes.length; h++) {
                Sim.Hole hole = snap.holes[h];
                float dx = hole.x - ball.x;
                float dy = hole.y - ball.y;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 1.0f) {
                    continue;
                }
                float ux = dx / len;
                float uy = dy / len;
                float gd = cue.scale + ball.scale;
                float gx = ball.x - ux * gd;
                float gy = ball.y - uy * gd;
                float vx = gx - cue.x;
                float vy = gy - cue.y;
                float d1 = (float) Math.sqrt(vx * vx + vy * vy);
                if (d1 < 1.0f) {
                    continue;
                }
                float ang = (float) Math.toDegrees(Math.atan2(vy, vx));
                float est = estStrength(snap, d1, len);
                for (int si = 0; si < spins.length; si++) {
                    Sim.Result r = sim.simulate(est, spins[si][0], spins[si][1], ang, false);
                    int[] out = new int[2];
                    int reason = filterReason(snap, legal, r, out);
                    counted(reason);
                    if (reason != PASS || !r.potted[b]) {
                        continue;
                    }
                    Cand c = new Cand();
                    c.angle = ang;
                    c.strength = est;
                    c.spinX = spins[si][0];
                    c.spinY = spins[si][1];
                    c.target = b;
                    c.hole = r.potHole[b];
                    c.legalPots = out[0];
                    c.moneyWin = out[1] != 0;
                    c.res = r;
                    c.score = baseScore(snap, r, out[0], c.moneyWin, est, 30.0f, d1 + len);
                    found.add(c);
                }
            }
        }
        return pickBest(sim, snap, legal, found, 4, false);
    }

    // ----------------------------------------------------------------
    // PERENCANAAN RUN-OUT (Mustahil): beam search rantai pot yang
    // menghabiskan sisa grup + bola penentu. Mengembalikan kandidat
    // langkah pertama rantai terbaik; chainLenOut[0] = jumlah bola
    // yang dihabiskan rantai. Perencanaan ulang dari state nyata
    // terjadi alami: tembakan nyata berikutnya memicu pencarian baru.
    // ----------------------------------------------------------------
    private static final class ChainState {
        Snap cur;
        boolean[] potted;
        Cand firstStep;
        float score;
        int pottedCount;
        boolean complete;
    }

    private static Sim.Ball[] ballsFrom(Snap cur, Sim.Result r) {
        Sim.Ball[] nb = new Sim.Ball[cur.balls.length];
        for (int i = 0; i < nb.length; i++) {
            nb[i] = new Sim.Ball();
            nb[i].copyFrom(cur.balls[i]);
            nb[i].x = r.stopX[i];
            nb[i].y = r.stopY[i];
            nb[i].vx = 0.0f;
            nb[i].vy = 0.0f;
            nb[i].rvx = 0.0f;
            nb[i].rvy = 0.0f;
            nb[i].w = 0.0f;
            if (r.potted[i]) {
                nb[i].avail = false;
                nb[i].potted = true;
            }
        }
        return nb;
    }

    private static Cand planRunout(Snap snap, long deadline, int[] chainLenOut) {
        int n = snap.balls.length;
        int remaining = 0;
        for (int i = 0; i < n; i++) {
            if (i != snap.clickTarget && snap.balls[i].avail) {
                remaining++;
            }
        }
        if (remaining < 2) {
            return null;
        }
        ArrayList<ChainState> beams = new ArrayList<ChainState>();
        ChainState init = new ChainState();
        init.cur = snap;
        init.potted = new boolean[n];
        init.score = 0.0f;
        init.pottedCount = 0;
        beams.add(init);
        ChainState bestComplete = null;
        ChainState bestPartial = null;
        for (int depth = 0; depth <= remaining; depth++) {
            ArrayList<ChainState> next = new ArrayList<ChainState>();
            for (int bi = 0; bi < beams.size(); bi++) {
                ChainState st = beams.get(bi);
                if (st.complete) {
                    continue;
                }
                if (expired(deadline)) {
                    break;
                }
                Sim sim = st.cur.newSim();
                ArrayList<Cand> cands = genPots(sim, st.cur, st.cur.legal, 0);
                for (int i = 0; i < cands.size(); i++) {
                    for (int j = i + 1; j < cands.size(); j++) {
                        Cand a = cands.get(i);
                        Cand b2 = cands.get(j);
                        if (b2.legalPots > a.legalPots
                                || (b2.legalPots == a.legalPots && b2.score > a.score)) {
                            cands.set(i, b2);
                            cands.set(j, a);
                        }
                    }
                }
                int lim = Math.min(3, cands.size());
                for (int ci = 0; ci < lim; ci++) {
                    Cand cand = cands.get(ci);
                    boolean[] childPotted = st.potted.clone();
                    int fresh = 0;
                    for (int i = 0; i < n; i++) {
                        if (cand.res.potted[i] && !childPotted[i]) {
                            childPotted[i] = true;
                            if (i != snap.clickTarget) {
                                fresh++;
                            }
                        }
                    }
                    if (fresh == 0) {
                        continue;
                    }
                    ChainState child = new ChainState();
                    child.potted = childPotted;
                    child.pottedCount = st.pottedCount + fresh;
                    child.firstStep = st.firstStep != null ? st.firstStep : cand;
                    child.score = st.score + cand.score * 0.05f + fresh * 900.0f;
                    boolean allDown = true;
                    for (int i = 0; i < n; i++) {
                        if (i != snap.clickTarget && snap.balls[i].avail
                                && !childPotted[i]) {
                            allDown = false;
                            break;
                        }
                    }
                    child.complete = allDown;
                    child.cur = st.cur.derive(ballsFrom(st.cur, cand.res),
                            chainLegal(snap, snap.legal, childPotted));
                    next.add(child);
                    if (child.complete) {
                        if (bestComplete == null || child.score > bestComplete.score) {
                            bestComplete = child;
                        }
                    } else if (bestPartial == null
                            || child.pottedCount > bestPartial.pottedCount
                            || (child.pottedCount == bestPartial.pottedCount
                                    && child.score > bestPartial.score)) {
                        bestPartial = child;
                    }
                }
            }
            if (next.isEmpty()) {
                break;
            }
            // beam: 3 terbaik (complete > count > score)
            for (int i = 0; i < next.size(); i++) {
                for (int j = i + 1; j < next.size(); j++) {
                    ChainState a = next.get(i);
                    ChainState b2 = next.get(j);
                    boolean swap = (b2.complete && !a.complete)
                            || (b2.complete == a.complete && b2.pottedCount > a.pottedCount)
                            || (b2.complete == a.complete && b2.pottedCount == a.pottedCount
                                    && b2.score > a.score);
                    if (swap) {
                        next.set(i, b2);
                        next.set(j, a);
                    }
                }
            }
            beams.clear();
            for (int i = 0; i < Math.min(3, next.size()); i++) {
                beams.add(next.get(i));
            }
            if (bestComplete != null) {
                break; // rantai tuntas ditemukan pada kedalaman ini
            }
        }
        ChainState win = bestComplete != null ? bestComplete
                : (bestPartial != null && bestPartial.pottedCount >= 2 ? bestPartial : null);
        if (win == null || win.firstStep == null) {
            return null;
        }
        chainLenOut[0] = win.pottedCount;
        return win.firstStep;
    }

    // ----------------------------------------------------------------
    // ORKESTRASI PENCARIAN TEMBAKAN
    // ----------------------------------------------------------------
    // ---------------------------------------------------------------
    // Fitur A v1.4 — BREAK SHOT.
    //
    // Posisi break dikenali dari state game sendiri: flag
    // PoolTable.isFirstShoot (snapshot.firstShoot) masih menyala dan
    // semua bola objek masih di meja membentuk rack (9-ball: 9 bola,
    // 8-ball: 15 bola). Mode Arcade dikecualikan (level bukan rack).
    //
    // Rencana: kekuatan maksimum (atau 94%-nya) membidik titik ghost
    // tabrakan-penuh ke bola apex (bola rack terdekat ke bola putih),
    // dengan beberapa offset lateral kecil; kandidat dinilai dari
    // simulasi klon: bola jatuh x1500 + bola bergeser jauh x25,
    // scratch = ditolak mutlak. Yang dieksekusi = yang TERBUKTI
    // membuyarkan rack di simulasi, bukan tembakan asal keras.
    static boolean isBreakPosition(Snap snap) {
        if (!snap.firstShoot || snap.arcade || snap.balls == null) {
            return false;
        }
        int avail = 0;
        for (int i = 0; i < snap.balls.length; i++) {
            if (i != snap.clickTarget && snap.balls[i].avail) {
                avail++;
            }
        }
        return avail >= 9;
    }

    static Shot planBreak(Sim sim, Snap snap) {
        Sim.Ball cue = snap.balls[snap.clickTarget];
        int apex = -1;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < snap.balls.length; i++) {
            if (i == snap.clickTarget || !snap.balls[i].avail) {
                continue;
            }
            float dx = snap.balls[i].x - cue.x;
            float dy = snap.balls[i].y - cue.y;
            float d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                apex = i;
            }
        }
        if (apex < 0) {
            return null;
        }
        Sim.Ball ap = snap.balls[apex];
        float dx = ap.x - cue.x;
        float dy = ap.y - cue.y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 1.0f) {
            return null;
        }
        float ux = dx / dist;
        float uy = dy / dist;
        // titik ghost tabrakan penuh: pusat bola putih berhenti
        // tepat 2R sebelum pusat apex pada garis yang sama
        float gx = ap.x - ux * (2.0f * snap.ballRadius);
        float gy = ap.y - uy * (2.0f * snap.ballRadius);
        float[] offsets = {0.0f, -10.0f, 10.0f, -20.0f, 20.0f};
        float[] strengths = {snap.maxStrength, snap.maxStrength * 0.94f};

        Shot best = null;
        float bestScore = -1.0f;
        int bestMoved = 0;
        int bestPotted = 0;
        for (int oi = 0; oi < offsets.length; oi++) {
            // offset lateral tegak lurus arah tembak
            float tx = gx + (-uy) * offsets[oi];
            float ty = gy + ux * offsets[oi];
            float ang = (float) Math.toDegrees(Math.atan2(ty - cue.y, tx - cue.x));
            for (int si = 0; si < strengths.length; si++) {
                Sim.Result r = sim.simulate(strengths[si], 0.0f, 0.0f, ang, false);
                if (r.cuePotted) {
                    continue; // scratch pada break = ditolak mutlak
                }
                int potted = 0;
                int moved = 0;
                for (int i = 0; i < snap.balls.length; i++) {
                    if (i == snap.clickTarget || !snap.balls[i].avail) {
                        continue;
                    }
                    if (r.potted[i]) {
                        potted++;
                        moved++;
                        continue;
                    }
                    float mx = r.stopX[i] - snap.balls[i].x;
                    float my = r.stopY[i] - snap.balls[i].y;
                    if (mx * mx + my * my > 80.0f * 80.0f) {
                        moved++;
                    }
                }
                float score = potted * 1500.0f + moved * 25.0f
                        - Math.abs(offsets[oi]) * 0.5f;
                if (score > bestScore) {
                    bestScore = score;
                    bestMoved = moved;
                    bestPotted = potted;
                    Shot s = new Shot();
                    s.strength = strengths[si];
                    s.spinX = 0.0f;
                    s.spinY = 0.0f;
                    s.angle = ang;
                    s.score = score;
                    s.potted = potted;
                    s.safety = false;
                    s.targetBall = apex;
                    s.level = LV_BREAK;
                    best = s;
                }
            }
        }
        if (best != null) {
            setReport(reportHead(snap, ModState.difficulty) + "BREAK: kekuatan "
                    + (int) best.strength + " ke apex bola " + apex
                    + " — simulasi: " + bestPotted + " bola jatuh, "
                    + bestMoved + " bola bergeser jauh");
        }
        return best;
    }

    public static Shot searchShot(Snap snap) {
        return searchShot(snap, DIFF_EXPERT, 0L);
    }

    public static Shot searchShot(Snap snap, int diff) {
        long dl = System.currentTimeMillis()
                + (diff == DIFF_MUSTAHIL ? 2800L : 60000L);
        return searchShot(snap, diff, dl);
    }

    public static Shot searchShot(Snap snap, int diff, long deadline) {
        resetCounters();
        if (!snap.legalKnown) {
            counting = false;
            setReport("Auto (" + diffName(diff) + "): oracle target tidak terbaca — tidak menembak");
            return null;
        }
        Sim sim = snap.newSim();
        boolean[] legal = snap.legal;

        // Fitur A v1.4 — BREAK SHOT khusus: posisi awal rack penuh
        // ditangani logika break (kekuatan maks, bidik apex), bukan
        // tangga biasa yang hanya menyenggol rack tanpa memasukkan.
        if (isBreakPosition(snap)) {
            Shot brk = planBreak(sim, snap);
            if (brk != null) {
                counting = false;
                return brk;
            }
        }

        if (diff == DIFF_NORMAL) {
            ArrayList<Cand> cands = genPots(sim, snap, legal, 0);
            Cand best = pickBest(sim, snap, legal, cands, 4, false);
            if (best == null) {
                best = spinRescue(sim, snap, legal);
            }
            counting = false;
            if (best != null) {
                trimStrength(sim, snap, legal, best);
                setReport(decisionText(snap, diff, best));
                return toShot(best);
            }
            Cand safety = searchSafety(sim, snap, legal);
            if (safety != null) {
                setReport(decisionText(snap, diff, safety));
                return toShot(safety);
            }
            setReport(reportHead(snap, diff) + "tidak menembak (Normal: tak ada pot/safety sederhana)");
            return null;
        }

        // EXPERT / MUSTAHIL
        ArrayList<Cand> cands = genPots(sim, snap, legal, 1);
        if (diff == DIFF_MUSTAHIL) {
            refineInto(sim, snap, legal, cands, deadline);
        }
        Cand best = pickBest(sim, snap, legal, cands, 8, true);
        if (best != null) {
            chooseSpin(sim, snap, legal, best, diff == DIFF_MUSTAHIL ? 2 : 1);
            trimStrength(sim, snap, legal, best);
            Shot shot = toShot(best);
            if (diff == DIFF_MUSTAHIL) {
                // kandidat kelas satu lain + rantai run-out ikut bertanding
                Cand alt = null;
                Cand bk = searchBank(sim, snap, legal, true, deadline);
                if (bk != null) {
                    alt = bk;
                }
                Cand kk = searchKick(sim, snap, legal, deadline);
                if (kk != null && (alt == null || kk.score > alt.score)) {
                    alt = kk;
                }
                Cand cb = searchCombo(sim, snap, legal);
                if (cb != null && (alt == null || cb.score > alt.score)) {
                    alt = cb;
                }
                if (alt != null) {
                    alt.score += GOAL.positionWeight()
                            * nextShotValue(snap, legal, alt.res);
                    if (alt.score > best.score * 1.1f && !best.moneyWin) {
                        chooseSpin(sim, snap, legal, alt, 2);
                        counting = false;
                        setReport(decisionText(snap, diff, alt));
                        return toShot(alt);
                    }
                }
                int[] chainLen = new int[1];
                Cand step = planRunout(snap, deadline, chainLen);
                boolean degraded = expired(deadline);
                if (step != null && chainLen[0] >= 2 && !best.moneyWin) {
                    // nilai langkah pertama dari rantai utuhnya
                    Sim sim2 = snap.newSim();
                    Cand chk = evalPot(sim2, snap, legal, step.angle, step.strength,
                            step.target, step.cutDeg, step.travel);
                    if (chk != null) {
                        chk.score += chainLen[0] * 700.0f;
                        if (chk.score > shot.score) {
                            chooseSpin(sim2, snap, legal, chk, 2);
                            trimStrength(sim2, snap, legal, chk);
                            counting = false;
                            setReport(decisionText(snap, diff, chk)
                                    + " • rencana habiskan " + chainLen[0] + " bola"
                                    + (degraded ? " (anggaran habis — sisa rencana kualitas Expert)" : ""));
                            Shot cs = toShot(chk);
                            cs.chainLen = chainLen[0];
                            return cs;
                        }
                    }
                }
                counting = false;
                setReport(decisionText(snap, diff, best) + (degraded
                        ? " (Mustahil: anggaran habis — kualitas Expert)" : ""));
                return shot;
            }
            counting = false;
            setReport(decisionText(snap, diff, best));
            return shot;
        }

        // tangga lanjutan (Expert & Mustahil sama)
        Cand bank = searchBank(sim, snap, legal, diff == DIFF_MUSTAHIL, deadline);
        if (bank != null) {
            chooseSpin(sim, snap, legal, bank, diff == DIFF_MUSTAHIL ? 2 : 1);
            counting = false;
            setReport(decisionText(snap, diff, bank));
            return toShot(bank);
        }
        Cand kick = searchKick(sim, snap, legal, deadline);
        if (kick != null) {
            chooseSpin(sim, snap, legal, kick, diff == DIFF_MUSTAHIL ? 2 : 1);
            counting = false;
            setReport(decisionText(snap, diff, kick));
            return toShot(kick);
        }
        Cand combo = searchCombo(sim, snap, legal);
        if (combo != null) {
            chooseSpin(sim, snap, legal, combo, diff == DIFF_MUSTAHIL ? 2 : 1);
            counting = false;
            setReport(decisionText(snap, diff, combo));
            return toShot(combo);
        }
        Cand safety = searchSafety(sim, snap, legal);
        if (safety != null) {
            counting = false;
            setReport(decisionText(snap, diff, safety));
            return toShot(safety);
        }
        Cand breakout = searchBreakout(sim, snap, legal);
        if (breakout != null) {
            counting = false;
            setReport(decisionText(snap, diff, breakout));
            return toShot(breakout);
        }
        Cand last = searchLastResort(sim, snap, legal);
        counting = false;
        if (last != null) {
            setReport(decisionText(snap, diff, last));
            return toShot(last);
        }
        setReport(reportHead(snap, diff)
                + "tidak menembak (semua tangga kosong — tak ada suapan lolos aturan keras)");
        return null;
    }

    // ----------------------------------------------------------------
    // FREE BALL (Perintah 4): pilih POSISI + tembakan bersama.
    // Kandidat posisi: garis pot klasik (kedalaman di belakang ghost)
    // + grid kasar meja; semua divalidasi rule.canPut game. Tiap posisi
    // dinilai dari kualitas tembakan terbaiknya; pemenang dievaluasi
    // penuh (robust/trim/spin; Mustahil: nilai awal rantai run-out).
    // Tanpa pot di mana pun: penempatan pengaman terjauh dari lawan.
    // ----------------------------------------------------------------
    public static Shot searchFreeball(Snap snap) {
        return searchFreeball(snap, DIFF_EXPERT, 0L);
    }

    public static Shot searchFreeball(Snap snap, int diff, long deadline) {
        resetCounters();
        if (!snap.legalKnown) {
            counting = false;
            setReport("Auto (" + diffName(diff) + "): oracle target tidak terbaca — tidak menembak");
            return null;
        }
        Sim sim = snap.newSim();
        boolean[] legal = snap.legal;
        Sim.Ball cueProto = sim.protoBall(snap.clickTarget);
        float origX = cueProto.x;
        float origY = cueProto.y;

        ArrayList<float[]> positions = new ArrayList<float[]>();
        // kandidat garis pot (seperti v1): di belakang ghost tiap bola/lubang
        float[] depths = {300.0f, 600.0f, 1000.0f};
        for (int b = 0; b < snap.balls.length; b++) {
            if (b == snap.clickTarget || !snap.balls[b].avail || !isLegal(legal, b)) {
                continue;
            }
            Sim.Ball ball = snap.balls[b];
            for (int h = 0; h < snap.holes.length; h++) {
                Sim.Hole hole = snap.holes[h];
                float dx = hole.x - ball.x;
                float dy = hole.y - ball.y;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 1.0f) {
                    continue;
                }
                float ux = dx / len;
                float uy = dy / len;
                float gd = cueProto.scale + ball.scale;
                float gx = ball.x - ux * gd;
                float gy = ball.y - uy * gd;
                for (int di = 0; di < depths.length; di++) {
                    float sx = gx - ux * depths[di];
                    float sy = gy - uy * depths[di];
                    if (snap.canPut(sx, sy)) {
                        addPosition(positions, sx, sy);
                    }
                }
            }
        }
        // grid kasar seluruh meja
        float margin = snap.ballRadius + 24.0f;
        float stepG = 280.0f;
        for (float x = -snap.tableW * 0.5f + margin; x <= snap.tableW * 0.5f - margin; x += stepG) {
            for (float y = -snap.tableH * 0.5f + margin; y <= snap.tableH * 0.5f - margin; y += stepG) {
                if (snap.canPut(x, y)) {
                    addPosition(positions, x, y);
                }
            }
        }

        // nilai ringan tiap posisi: pot terbaik (kasar) dari posisi itu
        ArrayList<float[]> scored = new ArrayList<float[]>(); // {x, y, score}
        Cand[] lightBest = new Cand[positions.size()];
        for (int pi = 0; pi < positions.size(); pi++) {
            if (expired(deadline) && pi > 8) {
                break;
            }
            float[] p = positions.get(pi);
            sim.setProtoPosition(snap.clickTarget, p[0], p[1]);
            Snap ps = snapAt(snap, p[0], p[1]);
            ArrayList<Cand> lc = genPots(sim, ps, legal, 0);
            Cand lb = null;
            for (int i = 0; i < lc.size(); i++) {
                if (lb == null || lc.get(i).score > lb.score) {
                    lb = lc.get(i);
                }
            }
            lightBest[pi] = lb;
            scored.add(new float[]{p[0], p[1], lb == null ? -Float.MAX_VALUE : lb.score});
        }

        // urutkan posisi menurut skor ringan
        for (int i = 0; i < scored.size(); i++) {
            for (int j = i + 1; j < scored.size(); j++) {
                if (scored.get(j)[2] > scored.get(i)[2]) {
                    float[] tmp = scored.get(i);
                    scored.set(i, scored.get(j));
                    scored.set(j, tmp);
                }
            }
        }

        Cand winner = null;
        float[] winnerPos = null;
        int winnerChain = 0;
        int tryTop = Math.min(diff == DIFF_MUSTAHIL ? 3 : 1, scored.size());
        for (int ti = 0; ti < tryTop; ti++) {
            float[] p = scored.get(ti);
            if (p[2] == -Float.MAX_VALUE) {
                break;
            }
            sim.setProtoPosition(snap.clickTarget, p[0], p[1]);
            Snap ps = snapAt(snap, p[0], p[1]);
            ArrayList<Cand> full = genPots(sim, ps, legal, diff == DIFF_NORMAL ? 0 : 1);
            Cand b = pickBest(sim, ps, legal, full, 6, diff != DIFF_NORMAL);
            if (b == null) {
                continue;
            }
            int chain = 0;
            if (diff == DIFF_MUSTAHIL) {
                int[] cl = new int[1];
                Cand step = planRunout(ps, deadline, cl);
                if (step != null) {
                    chain = cl[0];
                    b.score += chain * 700.0f;
                }
            }
            b.spotX = p[0];
            b.spotY = p[1];
            b.hasSpot = true;
            if (winner == null || b.score > winner.score) {
                winner = b;
                winnerPos = p;
                winnerChain = chain;
            }
        }

        if (winner != null) {
            sim.setProtoPosition(snap.clickTarget, winnerPos[0], winnerPos[1]);
            Snap ps = snapAt(snap, winnerPos[0], winnerPos[1]);
            if (diff != DIFF_NORMAL) {
                chooseSpin(sim, ps, legal, winner, diff == DIFF_MUSTAHIL ? 2 : 1);
            }
            trimStrength(sim, ps, legal, winner);
            sim.setProtoPosition(snap.clickTarget, origX, origY);
            counting = false;
            String reason = winner.cutDeg < 12.0f
                    ? "garis lurus ke bola " + winner.target
                    : (winner.cutDeg < 35.0f
                            ? "sudut potong kecil ke bola " + winner.target
                            : "pot bola " + winner.target);
            setReport(reportHead(snap, diff) + "freeball: posisi dipilih — " + reason
                    + " → " + holeName(snap, winner.hole)
                    + ", kekuatan " + (int) winner.strength
                    + ", kokoh " + winner.robust + "/6" + spinNote(winner)
                    + (winnerChain >= 2 ? " • awal rantai habiskan " + winnerChain + " bola" : ""));
            Shot s = toShot(winner);
            s.chainLen = winnerChain;
            return s;
        }

        // penempatan pengaman: posisi sah terjauh dari bola lawan,
        // dengan safety shot yang benar-benar ada dari sana
        sim.setProtoPosition(snap.clickTarget, origX, origY);
        Cand safetyShot = null;
        float[] safetyPos = null;
        int lim = Math.min(6, scored.size());
        for (int ti = 0; ti < lim && safetyShot == null; ti++) {
            // urut ulang menurut jarak aman: pakai daftar positions mentah
            float[] p = safestPosition(snap, positions, ti);
            if (p == null) {
                break;
            }
            sim.setProtoPosition(snap.clickTarget, p[0], p[1]);
            Snap ps = snapAt(snap, p[0], p[1]);
            Cand sf = searchSafety(sim, ps, legal);
            if (sf != null) {
                sf.spotX = p[0];
                sf.spotY = p[1];
                sf.hasSpot = true;
                safetyShot = sf;
                safetyPos = p;
            }
        }
        sim.setProtoPosition(snap.clickTarget, origX, origY);
        counting = false;
        if (safetyShot != null) {
            setReport(reportHead(snap, diff)
                    + "freeball pengaman: posisi aman terjauh dari lawan — sentuh bola "
                    + safetyShot.target + ", kekuatan " + (int) safetyShot.strength);
            return toShot(safetyShot);
        }
        setReport(reportHead(snap, diff)
                + "freeball: tidak ada penempatan yang menghasilkan suapan legal");
        return null;
    }

    private static void addPosition(ArrayList<float[]> positions, float x, float y) {
        for (int i = 0; i < positions.size(); i++) {
            float[] p = positions.get(i);
            float dx = p[0] - x;
            float dy = p[1] - y;
            if (dx * dx + dy * dy < 70.0f * 70.0f) {
                return;
            }
        }
        positions.add(new float[]{x, y});
    }

    /** Snap turunan dengan bola putih di (x,y) (untuk penilaian posisi). */
    private static Snap snapAt(Snap snap, float x, float y) {
        Sim.Ball[] nb = new Sim.Ball[snap.balls.length];
        for (int i = 0; i < nb.length; i++) {
            nb[i] = new Sim.Ball();
            nb[i].copyFrom(snap.balls[i]);
        }
        nb[snap.clickTarget].x = x;
        nb[snap.clickTarget].y = y;
        nb[snap.clickTarget].avail = true;
        return snap.derive(nb, snap.legal);
    }

    /** Posisi sah ke-`rank` menurut keamanan: jarak minimum ke bola
     * lawan terbesar + jauh dari lubang. */
    private static float[] safestPosition(Snap snap, ArrayList<float[]> positions, int rank) {
        ArrayList<float[]> sorted = new ArrayList<float[]>(positions);
        for (int i = 0; i < sorted.size(); i++) {
            for (int j = i + 1; j < sorted.size(); j++) {
                if (safetyPosScore(snap, sorted.get(j)) > safetyPosScore(snap, sorted.get(i))) {
                    float[] tmp = sorted.get(i);
                    sorted.set(i, sorted.get(j));
                    sorted.set(j, tmp);
                }
            }
        }
        return rank < sorted.size() ? sorted.get(rank) : null;
    }

    private static float safetyPosScore(Snap snap, float[] p) {
        float minOpp = Float.MAX_VALUE;
        float minHole = Float.MAX_VALUE;
        for (int i = 0; i < snap.balls.length; i++) {
            if (i == snap.clickTarget || !snap.balls[i].avail) {
                continue;
            }
            float d = dist(p[0], p[1], snap.balls[i].x, snap.balls[i].y);
            if (!isLegal(snap, i) && d < minOpp) {
                minOpp = d;
            }
        }
        for (int h = 0; h < snap.holes.length; h++) {
            minHole = Math.min(minHole, dist(p[0], p[1], snap.holes[h].x, snap.holes[h].y));
        }
        return (minOpp == Float.MAX_VALUE ? 1000.0f : minOpp)
                + 0.3f * (minHole == Float.MAX_VALUE ? 0.0f : minHole);
    }
}
