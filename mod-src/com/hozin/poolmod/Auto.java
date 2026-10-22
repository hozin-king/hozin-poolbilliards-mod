package com.hozin.poolmod;

import java.util.ArrayList;

/**
 * Auto play v1.1 — pencarian tembakan di atas klon Sim dengan tiga lapis
 * disiplin yang TIDAK ada di v1 (dan menjadi akar kegagalannya di HP):
 *
 *  1. TARGET LEGAL DARI GAME, BUKAN DARI SKOR. Kandidat hanya dibuat
 *     untuk bola yang dinyatakan legal oleh rule yang hidup lewat
 *     PoolRule.isAvailableTarget (tertanam di Snap.legal): grup
 *     solids/stripes pemain yang giliran di 8-ball, meja terbuka,
 *     bola 8 hanya bila grup habis, bola terendah di 9-ball. Bila
 *     oracle tidak bisa ditanyakan (Snap.legalKnown=false) autoplay
 *     MENOLAK menembak — tidak ada tebakan.
 *
 *  2. SARINGAN KERAS (bukan penalti skor). Setiap kandidat disimulasikan
 *     dengan parameter PERSIS yang akan dikirim ke PoolTable.click
 *     (strength, spinX, spinY, angle derajat — konvensi terverifikasi
 *     identik dengan GameScene: table.click(aimStrength, aimPx,
 *     aimPy, aimAngle)). Kandidat DIBUANG TOTAL bila hasil klonnya:
 *       - bola putih masuk lubang (scratch), ATAU
 *       - kontak pertama bukan bola target legal (foul menurut
 *         onHitBall game sendiri), ATAU
 *       - tidak menyentuh bola sama sekali, ATAU
 *       - bola non-target masuk TANPA satu pun bola target legal
 *         masuk, ATAU
 *       - bola 8 masuk padahal belum legal (kalah langsung di
 *         Vsai8Ball: fouled + pooledball8 -> setLose).
 *     Pengecualian satu-satunya: di 9-ball, bola 9 yang masuk setelah
 *     kontak pertama legal adalah KEMENANGAN (pooledball9 && !fouled
 *     -> setWin di Vsai9Ball.onRoundOver), jadi diperlakukan sebagai
 *     pot legal bernilai tertinggi.
 *
 *  3. PILIH YANG PALING "ENAK", BUKAN SEKADAR MASUK. Kandidat pot yang
 *     lolos saringan diuji ketahanan: sudut digeser ±0,15°/±0,3° dan
 *     strength ±5%; yang tetap memasukkan bola target yang sama
 *     (dan tetap lolos saringan) pada rentang perturbasi terlebar
 *     menang. Sudut potong kecil & jarak pendek jadi penyeimbang.
 *     Tembakan pisau-di-tepi-lubang — yang di v1 scratch di perangkat
 *     walau klon bilang aman — otomatis tersingkir di sini.
 *
 *  Bila tidak ada pot yang lolos: TEMBAKAN PENGAMAN — sentuh bola
 *  target legal selembut mungkin (kontak pertama legal, tanpa scratch,
 *  tanpa bola liar masuk, bola putih berakhir jauh dari lubang).
 *  Bila itu pun tidak ada: TIDAK MENEMBAK (null) — pemain memutuskan
 *  sendiri. Tidak ada lagi fallback buta v1.
 *
 *  Catatan kejujuran: Sim.Result tidak merekam URUTAN bola masuk,
 *  jadi aturan "non-target masuk" dinilai sebagai himpunan (boleh
 *  bila ada target legal yang ikut masuk), kecuali bola 8 yang selalu
 *  ditolak ketat. Pencarian berjalan di thread pekerja dari snapshot;
 *  eksekusi diserahkan ke Hooks (periksa ulang gerbang PvP + posisi).
 */
public final class Auto {
    public static final class Shot {
        public float strength, spinX, spinY, angle, score;
        public int potted;
        public boolean freeball;
        public boolean safety;
        public int targetBall = -1;
        public int robust;
        public float spotX, spotY;
        public long posKey;
    }

    public static volatile Shot pendingExec;
    public static volatile Snap pendingSnap;
    /** Hasil pencarian terakhir: "pot" | "aman" | "tidak ada tembakan aman" | "" */
    public static volatile String lastOutcome = "";
    /** Readout keputusan terakhir untuk menu (grup legal, jumlah
     * kandidat, penolakan per alasan, tembakan terpilih / alasan diam). */
    public static volatile String lastReport = "";

    // kode alasan filterReason()
    static final int PASS = 0;
    static final int REJ_SCRATCH = 1;   // bola putih masuk
    static final int REJ_CONTACT = 2;   // tanpa kontak / kontak pertama ilegal
    static final int REJ_ILLEGAL = 3;   // hanya bola non-target yang masuk
    static final int REJ_MONEY = 4;     // bola penentu masuk sebelum legal

    // penghitung selama satu pencarian (diisi evalPot saat `counting`)
    static int candCount;
    static final int[] rejectCounts = new int[5];
    static boolean counting;

    private Auto() {
    }

    public static void startSearch(Object table, Object rule, Object roundstate, int state) {
        // GERBANG PvP di titik masuk pencarian
        if (Ref.invokeB(rule, "isPVPMode")) {
            return;
        }
        final Snap snap = Snap.capture(table, rule, roundstate);
        if (snap == null) {
            return;
        }
        final boolean free = state == 3;
        ModState.autoBusy = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Shot s = free ? searchFreeball(snap) : searchShot(snap);
                    if (s != null) {
                        s.freeball = free;
                        s.posKey = snap.positionKey();
                        pendingSnap = snap;
                        pendingExec = s;
                        lastOutcome = s.safety ? "aman" : "pot";
                        ModState.statusText = "";
                    } else {
                        lastOutcome = "tidak ada tembakan aman";
                        ModState.statusText =
                                "Auto: tidak ada tembakan aman — main manual";
                        ModState.statusMs = System.currentTimeMillis();
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

    // ----------------------------------------------------------------
    // SARINGAN KERAS — dinilai dari hasil klon satu tembakan.
    // filterReason mengembalikan PASS atau kode alasan penolakan;
    // mengisi out[0]=jumlah pot legal, out[1]=pot money legal (0/1).
    // ----------------------------------------------------------------
    static int filterReason(Snap snap, Sim.Result r, int[] out) {
        int cue = snap.clickTarget;
        if (r.cuePotted) {
            return REJ_SCRATCH; // scratch — aib v1, sekarang mustahil lolos
        }
        if (r.firstContact < 0 || !isLegal(snap, r.firstContact)) {
            return REJ_CONTACT; // tak menyentuh bola / kontak pertama foul
        }
        int legalPots = 0;
        boolean illegalPot = false;
        boolean moneyWin = false;
        for (int i = 0; i < snap.balls.length; i++) {
            if (i == cue || !r.potted[i]) {
                continue;
            }
            if (i == snap.moneyBall && !isLegal(snap, i)) {
                if (snap.moneyBall == 9) {
                    // 9-ball: bola 9 masuk setelah kontak pertama legal
                    // = menang (foul sudah disaring di atas).
                    moneyWin = true;
                } else {
                    // 8-ball: bola 8 masuk sebelum legal = kalah.
                    return REJ_MONEY;
                }
            } else if (isLegal(snap, i)) {
                legalPots++;
            } else {
                illegalPot = true;
            }
        }
        if (illegalPot && legalPots == 0 && !moneyWin) {
            return REJ_ILLEGAL; // memasukkan bola lawan tanpa target sendiri
        }
        if (out != null) {
            out[0] = legalPots;
            out[1] = moneyWin ? 1 : 0;
        }
        return PASS;
    }

    static boolean passesFilter(Snap snap, Sim.Result r, int[] out) {
        return filterReason(snap, r, out) == PASS;
    }

    /** Skor dasar kandidat yang LOLOS saringan (pot). */
    private static float baseScore(Snap snap, Sim.Result r, int legalPots, boolean moneyWin,
                                   float strength, float cutDeg, float travel) {
        float sc = legalPots * 1000.0f;
        if (moneyWin || (snap.moneyBall > 0 && r.potted[snap.moneyBall]
                && isLegal(snap, snap.moneyBall))) {
            sc += 5000.0f;
        }
        // posisi akhir bola putih: dekat target legal berikutnya, jauh
        // dari lubang (nilai kecil — penyeimbang, bukan penentu)
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
        if (nearest < Float.MAX_VALUE) {
            sc += Math.max(0.0f, 400.0f - nearest) * 0.3f;
        }
        for (int h = 0; h < snap.holes.length; h++) {
            float d = dist(r.stopX[cue], r.stopY[cue], snap.holes[h].x, snap.holes[h].y);
            if (d < 130.0f) {
                sc -= (130.0f - d) * 1.2f;
            }
        }
        // v1.2: bobot "enak" diperkuat — sudut potong kecil, jarak
        // pendek, dan kekuatan kecil jauh lebih menentukan daripada
        // v1.1 (di sana skor didominasi uji perturbasi sehingga
        // tembakan jauh yang "kokoh" bisa mengalahkan pot dekat yang
        // jelas-jelas paling enak di mata pemain).
        sc -= cutDeg * 10.0f;             // potong lurus lebih enak
        sc -= travel * 0.08f;             // dekat lebih enak
        sc -= strength * 0.01f;           // lembut lebih enak
        return sc;
    }

    /** Satu kandidat pot yang sudah lolos saringan dasar. */
    private static final class Cand {
        float angle, strength;
        int target;
        int hole = -1;
        int legalPots;
        boolean moneyWin;
        float cutDeg, travel, score;
        int robust;
        float spotX, spotY;
        boolean hasSpot;
    }

    private static Cand evalPot(Sim sim, Snap snap, float angle, float strength, int target,
                                float cutDeg, float travel) {
        Sim.Result r = sim.simulate(strength, 0.0f, 0.0f, angle, false);
        int[] out = new int[2];
        int reason = filterReason(snap, r, out);
        if (counting) {
            candCount++;
            if (reason != PASS) {
                rejectCounts[reason]++;
            }
        }
        if (reason != PASS) {
            return null;
        }
        if (out[0] == 0 && out[1] == 0) {
            return null; // lolos saringan tapi tidak memasukkan apa pun
        }
        if (!r.potted[target] && out[1] == 0) {
            return null; // target yang dibidik tidak masuk — bukan pot ini
        }
        Cand c = new Cand();
        c.angle = angle;
        c.strength = strength;
        c.target = target;
        c.hole = r.potHole[target];
        if (c.hole < 0 && out[1] != 0 && snap.moneyBall > 0) {
            c.hole = r.potHole[snap.moneyBall];
        }
        c.legalPots = out[0];
        c.moneyWin = out[1] != 0;
        c.cutDeg = cutDeg;
        c.travel = travel;
        c.score = baseScore(snap, r, out[0], c.moneyWin, strength, cutDeg, travel);
        return c;
    }

    /**
     * Uji ketahanan: berapa perturbasi (sudut ±0,15/±0,3°, strength
     * ±5%) yang tetap memasukkan target yang sama & lolos saringan.
     */
    private static int robustness(Sim sim, Snap snap, Cand c) {
        float[] dAng = {-0.3f, -0.15f, 0.15f, 0.3f};
        int ok = 0;
        for (int i = 0; i < dAng.length; i++) {
            Sim.Result r = sim.simulate(c.strength, 0.0f, 0.0f, c.angle + dAng[i], false);
            int[] out = new int[2];
            if (passesFilter(snap, r, out) && (r.potted[c.target] || out[1] != 0)) {
                ok++;
            }
        }
        float[] mStr = {0.95f, 1.05f};
        for (int i = 0; i < mStr.length; i++) {
            float st = clamp(c.strength * mStr[i], snap.minStrength, snap.maxStrength);
            Sim.Result r = sim.simulate(st, 0.0f, 0.0f, c.angle, false);
            int[] out = new int[2];
            if (passesFilter(snap, r, out) && (r.potted[c.target] || out[1] != 0)) {
                ok++;
            }
        }
        return ok; // 0..6
    }

    private static Shot toShot(Cand c, int robust) {
        Shot s = new Shot();
        s.angle = c.angle;
        s.strength = c.strength;
        s.spinX = 0.0f;
        s.spinY = 0.0f;
        s.targetBall = c.target;
        s.potted = c.legalPots;
        s.robust = robust;
        s.score = c.score + robust * 250.0f;
        s.safety = false;
        if (c.hasSpot) {
            s.spotX = c.spotX;
            s.spotY = c.spotY;
        }
        return s;
    }

    // ----------------------------------------------------------------
    public static Shot searchShot(Snap snap) {
        resetCounters();
        if (!snap.legalKnown) {
            counting = false;
            setReport("Auto: oracle target tidak terbaca — tidak menembak");
            return null; // oracle target tidak terbaca — jangan menembak
        }
        Sim sim = snap.newSim();
        Sim.Ball cue = sim.protoBall(snap.clickTarget);

        ArrayList<Cand> cands = new ArrayList<Cand>();
        for (int b = 0; b < snap.balls.length; b++) {
            if (b == snap.clickTarget || !snap.balls[b].avail || !isLegal(snap, b)) {
                continue; // HANYA bola target legal milik pemain
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
                float vx = gx - cue.x;
                float vy = gy - cue.y;
                float d1 = (float) Math.sqrt(vx * vx + vy * vy);
                if (d1 < 1.0f) {
                    continue;
                }
                float ang = (float) Math.toDegrees(Math.atan2(vy, vx));
                // sudut potong: arah cue->ghost vs arah bola->lubang
                float dot = (vx / d1) * ux + (vy / d1) * uy;
                float cut = (float) Math.toDegrees(Math.acos(
                        Math.max(-1.0f, Math.min(1.0f, dot))));
                float est = estStrength(snap, d1, len);
                float[] sts = {clamp(est * 0.75f, snap.minStrength, snap.maxStrength),
                        est,
                        clamp(est * 1.3f, snap.minStrength, snap.maxStrength)};
                for (int si = 0; si < sts.length; si++) {
                    Cand c = evalPot(sim, snap, ang, sts[si], b, cut, d1 + len);
                    if (c != null) {
                        cands.add(c);
                    }
                }
            }
        }

        counting = false;
        Cand best = pickMostRobust(sim, snap, cands);
        if (best != null) {
            trimStrength(sim, snap, best);
            setReport(reportHead(snap) + "dipilih bola " + best.target + " → "
                    + holeName(snap, best.hole) + ", kekuatan " + (int) best.strength
                    + ", kokoh " + best.robust + "/6");
            return toShot(best, best.robust);
        }
        Shot safety = searchSafety(sim, snap);
        if (safety != null) {
            setReport(reportHead(snap) + "pengaman ke bola " + safety.targetBall
                    + ", kekuatan " + (int) safety.strength);
        } else {
            setReport(reportHead(snap) + "tidak menembak (tak ada pot/pengaman aman)");
        }
        return safety;
    }

    /**
     * Pemilihan v1.2 — "paling enak" seperti mata pemain, bukan sekadar
     * paling kokoh: ketahanan perturbasi menjadi SYARAT (bila ada
     * kandidat dengan robust >= 3, yang di bawah itu disisihkan),
     * lalu skor kemudahan (potong kecil, jarak pendek, kekuatan kecil)
     * yang memutuskan; robust hanya penimbang tambahan kecil.
     * Di v1.1 robust mendominasi (x250) sehingga tembakan jauh yang
     * lebar jendela pertubasinya mengalahkan pot dekat yang jelas.
     */
    private static Cand pickMostRobust(Sim sim, Snap snap, ArrayList<Cand> cands) {
        if (cands.isEmpty()) {
            return null;
        }
        // ambil 8 terbaik berdasar skor dasar untuk uji ketahanan
        for (int i = 0; i < cands.size(); i++) {
            for (int j = i + 1; j < cands.size(); j++) {
                if (cands.get(j).score > cands.get(i).score) {
                    Cand tmp = cands.get(i);
                    cands.set(i, cands.get(j));
                    cands.set(j, tmp);
                }
            }
        }
        int lim = Math.min(8, cands.size());
        int bestRobustSeen = -1;
        for (int i = 0; i < lim; i++) {
            Cand c = cands.get(i);
            c.robust = robustness(sim, snap, c);
            if (c.robust > bestRobustSeen) {
                bestRobustSeen = c.robust;
            }
        }
        Cand best = null;
        float bestFinal = -Float.MAX_VALUE;
        for (int i = 0; i < lim; i++) {
            Cand c = cands.get(i);
            if (bestRobustSeen >= 3 && c.robust < 3) {
                continue; // terlalu rapuh — pisau di tepi lubang
            }
            float fin = c.score + c.robust * 60.0f;
            if (fin > bestFinal) {
                bestFinal = fin;
                best = c;
            }
        }
        if (best == null) { // semua kandidat rapuh: robust tertinggi dulu
            for (int i = 0; i < lim; i++) {
                Cand c = cands.get(i);
                if (best == null || c.robust > best.robust
                        || (c.robust == best.robust && c.score > best.score)) {
                    best = c;
                }
            }
        }
        return best;
    }

    /**
     * Pelunakan kekuatan pasca-pilih: turunkan strength bertahap
     * (x0,88, maks 3 langkah) selama tembakan tetap memasukkan target,
     * lolos saringan, DAN tetap masuk pada geser sudut ±0,15°.
     * Tembakan selembut yang masih kokoh = bola tidak berhamburan —
     * salah satu sumber kesan "tolol" v1.1 (strength selalu besar).
     */
    private static void trimStrength(Sim sim, Snap snap, Cand c) {
        float st = c.strength;
        for (int i = 0; i < 3; i++) {
            float lower = clamp(st * 0.88f, snap.minStrength, snap.maxStrength);
            if (lower >= st - 1.0f) {
                break;
            }
            if (!potsAt(sim, snap, c, lower, c.angle)
                    || !potsAt(sim, snap, c, lower, c.angle + 0.15f)
                    || !potsAt(sim, snap, c, lower, c.angle - 0.15f)) {
                break;
            }
            st = lower;
        }
        c.strength = st;
    }

    private static boolean potsAt(Sim sim, Snap snap, Cand c, float strength, float angle) {
        Sim.Result r = sim.simulate(strength, 0.0f, 0.0f, angle, false);
        int[] out = new int[2];
        return passesFilter(snap, r, out) && (r.potted[c.target] || out[1] != 0);
    }

    // ----------------------------------------------------------------
    // READOUT KEPUTUSAN (menu) — grup legal, kandidat, penolakan, pilihan
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
            sb.append(" bolalawan ").append(rejectCounts[REJ_ILLEGAL]);
        }
        if (rejectCounts[REJ_MONEY] > 0) {
            sb.append(" bola8dini ").append(rejectCounts[REJ_MONEY]);
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

    private static String reportHead(Snap snap) {
        return "Auto: " + groupLabel(snap) + " • kandidat " + candCount
                + " • ditolak:" + rejectSummary() + " • ";
    }

    private static void setReport(String s) {
        lastReport = s;
        ModState.autoReport = s;
    }

    // ----------------------------------------------------------------
    // TEMBAKAN PENGAMAN: sentuh target legal selembut mungkin.
    // ----------------------------------------------------------------
    private static Shot searchSafety(Sim sim, Snap snap) {
        Sim.Ball cue = sim.protoBall(snap.clickTarget);
        Shot best = null;
        float bestScore = -Float.MAX_VALUE;
        float[] strengths = {260.0f, 340.0f, 450.0f, 600.0f};
        for (int b = 0; b < snap.balls.length; b++) {
            if (b == snap.clickTarget || !snap.balls[b].avail || !isLegal(snap, b)) {
                continue;
            }
            Sim.Ball ball = snap.balls[b];
            float dc = dist(cue.x, cue.y, ball.x, ball.y);
            // bidik titik kontak: tengah + geser setengah bola dua sisi
            float perpX = -(ball.y - cue.y) / Math.max(dc, 1.0f);
            float perpY = (ball.x - cue.x) / Math.max(dc, 1.0f);
            float[] offs = {0.0f, -0.45f, 0.45f};
            for (int oi = 0; oi < offs.length; oi++) {
                float tx = ball.x + perpX * ball.scale * offs[oi];
                float ty = ball.y + perpY * ball.scale * offs[oi];
                float ang = (float) Math.toDegrees(Math.atan2(ty - cue.y, tx - cue.x));
                for (int si = 0; si < strengths.length; si++) {
                    float st = clamp(strengths[si] + dc * 0.35f,
                            snap.minStrength, snap.maxStrength);
                    Sim.Result r = sim.simulate(st, 0.0f, 0.0f, ang, false);
                    int[] out = new int[2];
                    if (!passesFilter(snap, r, out)) {
                        continue;
                    }
                    if (out[0] > 0 || out[1] > 0) {
                        continue; // ini pot, bukan pengaman (tak terjangkau
                        // karena pot sudah dicari lebih dulu)
                    }
                    // nilai pengaman: bola putih berakhir jauh dari
                    // lubang + strength kecil + target bergeser cukup
                    float sc = -st * 0.01f;
                    for (int h = 0; h < snap.holes.length; h++) {
                        float d = dist(r.stopX[snap.clickTarget], r.stopY[snap.clickTarget],
                                snap.holes[h].x, snap.holes[h].y);
                        sc += Math.min(d, 500.0f) * 0.02f;
                    }
                    float moved = dist(ball.x, ball.y,
                            r.stopX[b], r.stopY[b]);
                    sc += Math.min(moved, 300.0f) * 0.05f;
                    if (sc > bestScore) {
                        bestScore = sc;
                        Shot s = new Shot();
                        s.angle = ang;
                        s.strength = st;
                        s.targetBall = b;
                        s.safety = true;
                        s.score = sc;
                        best = s;
                    }
                }
            }
        }
        return best; // boleh null -> benar-benar tidak menembak
    }

    // ----------------------------------------------------------------
    public static Shot searchFreeball(Snap snap) {
        resetCounters();
        if (!snap.legalKnown) {
            counting = false;
            setReport("Auto: oracle target tidak terbaca — tidak menembak");
            return null;
        }
        Sim sim = snap.newSim();
        Sim.Ball cueProto = sim.protoBall(snap.clickTarget);
        float origX = cueProto.x;
        float origY = cueProto.y;

        ArrayList<Cand> cands = new ArrayList<Cand>();
        float[] depths = {300.0f, 600.0f, 1000.0f};
        for (int b = 0; b < snap.balls.length; b++) {
            if (b == snap.clickTarget || !snap.balls[b].avail || !isLegal(snap, b)) {
                continue; // hanya target legal
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
                float ghostDist = cueProto.scale + ball.scale;
                float gx = ball.x - ux * ghostDist;
                float gy = ball.y - uy * ghostDist;
                for (int di = 0; di < depths.length; di++) {
                    float sx = gx - ux * depths[di];
                    float sy = gy - uy * depths[di];
                    if (!snap.canPut(sx, sy)) {
                        continue;
                    }
                    sim.setProtoPosition(snap.clickTarget, sx, sy);
                    float ang = (float) Math.toDegrees(Math.atan2(gy - sy, gx - sx));
                    float est = estStrength(snap, depths[di], len);
                    Cand c = evalPot(sim, snap, ang, est, b, 0.0f, depths[di] + len);
                    if (c != null) {
                        c.spotX = sx;
                        c.spotY = sy;
                        c.hasSpot = true;
                        cands.add(c);
                    }
                }
            }
        }
        sim.setProtoPosition(snap.clickTarget, origX, origY);
        counting = false;

        // Pemenang dipilih dari skor dasar; ketahanannya diuji dengan
        // proto cue diparkir di spot pemenang (perturbasi hanya valid
        // pada titik penempatan itu).
        Cand best = null;
        for (int i = 0; i < cands.size(); i++) {
            Cand c = cands.get(i);
            if (best == null || c.score > best.score) {
                best = c;
            }
        }
        if (best == null) {
            setReport(reportHead(snap) + "freeball: tidak ada penempatan pot aman");
            return null; // tidak ada penempatan pot yang aman & legal
        }
        sim.setProtoPosition(snap.clickTarget, best.spotX, best.spotY);
        int rob = robustness(sim, snap, best);
        best.robust = rob;
        trimStrength(sim, snap, best);
        sim.setProtoPosition(snap.clickTarget, origX, origY);
        setReport(reportHead(snap) + "freeball bola " + best.target + " → "
                + holeName(snap, best.hole) + ", kekuatan " + (int) best.strength
                + ", kokoh " + rob + "/6");
        return toShot(best, rob);
    }
}
