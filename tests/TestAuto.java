package com.hozin.poolmod;

/**
 * Uji logika Auto Play v1.1 di JVM (tanpa Android): target legal,
 * saringan keras anti-scratch/anti-bola-lawan, dan pemilihan tembakan.
 *
 * Skenario wajib dari kegagalan v1 di HP user:
 *  (a) hanya bola LAWAN yang bisa dipot -> tidak boleh dipilih;
 *      jatuh ke tembakan pengaman / tidak menembak.
 *  (b) pot menggoda yang men-scratch bola putih (follow-through khas
 *      mesin ini, terverifikasi di TestSim pada strength 1500) ->
 *      ditolak saringan; yang dipilih terbukti tidak scratch.
 *  (c) grup solids terdaftar -> hanya solids ditargetkan; bola 8
 *      hanya sesudah grup habis.
 *  (d) meja terbuka -> bola legal mana pun boleh (kecuali tidak ada
 *      yang ilegal), tembakan termudah yang dipilih.
 *  (e) 9-ball -> kontak pertama wajib bola terendah; pot lurus bola 2
 *      yang menggoda ditolak saringan.
 *  (f) unit saringan: bola 8 masuk sebelum legal = ditolak mutlak;
 *      bola 9 masuk sesudah kontak legal di 9-ball = kemenangan sah.
 *
 * Geometri meja memakai konstanta TestSim (RuleImplBase).
 */
public final class TestAuto {
    static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("GAGAL: " + msg);
        }
        System.out.println("  ok: " + msg);
    }

    static Snap baseSnap(int nballs, int moneyBall) {
        Snap s = new Snap();
        s.force = 1500.0f;
        s.rollForce = 100.0f;
        s.holeGravity = 3600.0f;
        s.ballRadius = TestSim.R;
        s.tableW = 2810.0f;
        s.tableH = 1530.0f;
        s.minStrength = 200.0f;
        s.maxStrength = 4000.0f;
        s.firstShoot = false;
        s.clickTarget = 0;
        s.moneyBall = moneyBall;
        s.legalKnown = true;
        s.legal = new boolean[nballs];
        Sim.Blocker[] bl = new Sim.Blocker[TestSim.BLOCKERS.length / 4];
        for (int i = 0; i < bl.length; i++) {
            Sim.Blocker b = new Sim.Blocker();
            b.x0 = TestSim.BLOCKERS[i * 4];
            b.y0 = TestSim.BLOCKERS[i * 4 + 1];
            b.x1 = TestSim.BLOCKERS[i * 4 + 2];
            b.y1 = TestSim.BLOCKERS[i * 4 + 3];
            b.force = 1200.0f;
            b.vis = true;
            bl[i] = b;
        }
        s.blockers = bl;
        Sim.Hole[] hs = new Sim.Hole[TestSim.HOLES.length / 3];
        for (int i = 0; i < hs.length; i++) {
            Sim.Hole h = new Sim.Hole();
            h.x = TestSim.HOLES[i * 3];
            h.y = TestSim.HOLES[i * 3 + 1];
            h.w = TestSim.HOLES[i * 3 + 2] * 2.0f;
            h.vis = true;
            hs[i] = h;
        }
        s.holes = hs;
        s.balls = new Sim.Ball[nballs];
        for (int i = 0; i < nballs; i++) {
            s.balls[i] = new Sim.Ball();
            s.balls[i].scale = TestSim.R;
            s.balls[i].avail = false;
        }
        return s;
    }

    static void put(Snap s, int i, float x, float y) {
        s.balls[i].x = x;
        s.balls[i].y = y;
        s.balls[i].avail = true;
    }

    /** Simulasikan ulang tembakan terpilih dari snapshot yang sama. */
    static Sim.Result resim(Snap s, Auto.Shot shot) {
        Sim sim = s.newSim();
        return sim.simulate(shot.strength, shot.spinX, shot.spinY, shot.angle, false);
    }

    // (a) hanya bola lawan yang bisa dipot
    static void testOnlyOpponentPottable() {
        System.out.println("[a] hanya bola lawan yang bisa dipot");
        Snap s = baseSnap(16, 8);
        put(s, 0, 0f, 100f);          // cue
        put(s, 1, -1200f, -100f);     // solid kita: jauh, tanpa pot bersih
        put(s, 9, 0f, -400f);         // stripe lawan: pot lurus menggoda
        for (int i = 1; i <= 7; i++) {
            s.legal[i] = true;        // grup kita = solids
        }
        // jebakan memang nyata: tembakan lurus memasukkan bola 9
        Sim sim = s.newSim();
        Sim.Result trap = sim.simulate(700f, 0f, 0f, -90f, false);
        check(trap.potted[9], "jebakan terverifikasi: bola 9 bisa dipot lurus");
        check(!Auto.passesFilter(s, trap, null),
                "saringan menolak pot bola lawan tanpa target sendiri");
        Auto.Shot shot = Auto.searchShot(s);
        check(shot == null || shot.level != Auto.LV_POT || shot.targetBall != 9,
                "autoplay tidak memilih bola lawan (hasil: "
                        + (shot == null ? "tidak menembak" : "tingkat " + shot.level + " bola " + shot.targetBall) + ")");
        if (shot != null && shot.level == Auto.LV_POT) {
            check(shot.targetBall == 1, "pot yang dipilih adalah bola kita");
        }
    }

    // (b) pot menggoda yang men-scratch bola putih
    static void testScratchTrap() {
        System.out.println("[b] jebakan scratch follow-through");
        Snap s = baseSnap(16, 8);
        put(s, 0, 0f, 100f);
        put(s, 1, 0f, -400f);
        for (int i = 1; i <= 15; i++) {
            s.legal[i] = true;        // meja terbuka
        }
        Sim sim = s.newSim();
        Sim.Result trap = sim.simulate(1500f, 0f, 0f, -90f, false);
        check(trap.potted[1] && trap.cuePotted,
                "jebakan terverifikasi: strength 1500 memasukkan bola 1 DAN bola putih");
        check(!Auto.passesFilter(s, trap, null), "saringan menolak tembakan scratch");
        Auto.Shot shot = Auto.searchShot(s);
        check(shot != null, "autoplay tetap menemukan jalan (pot bersih/pengaman)");
        Sim.Result r = resim(s, shot);
        check(!r.cuePotted, "tembakan terpilih TIDAK men-scratch bola putih");
        if (shot.level <= Auto.LV_COMBO) {
            check(r.potted[shot.targetBall], "pot terpilih benar-benar masuk di simulasi ulang");
        }
    }

    // (c) grup solids terdaftar + bola 8 terakhir
    static void testGroups() {
        System.out.println("[c] grup solids terdaftar");
        Snap s = baseSnap(16, 8);
        put(s, 0, 0f, 100f);
        put(s, 2, 300f, -300f);       // solid kita: pot miring wajar
        put(s, 10, 0f, -400f);        // stripe lawan: pot lurus TERMUDAH
        for (int i = 1; i <= 7; i++) {
            s.legal[i] = true;
        }
        Auto.Shot shot = Auto.searchShot(s);
        check(shot != null && shot.level == Auto.LV_POT, "ada pot legal untuk solids");
        check(shot.targetBall == 2, "yang ditargetkan solid kita, bukan stripe lawan (dapat "
                + shot.targetBall + ")");
        Sim.Result r = resim(s, shot);
        check(r.potted[2] && !r.cuePotted, "solid masuk, tanpa scratch");

        System.out.println("[c2] grup habis -> bola 8 legal");
        Snap s2 = baseSnap(16, 8);
        put(s2, 0, 0f, 100f);
        put(s2, 8, 0f, -400f);
        s2.legal[8] = true;
        Auto.Shot shot2 = Auto.searchShot(s2);
        check(shot2 != null && shot2.level == Auto.LV_POT && shot2.targetBall == 8,
                "bola 8 ditargetkan sesudah grup habis");
        Sim.Result r2 = resim(s2, shot2);
        check(r2.potted[8] && !r2.cuePotted, "bola 8 masuk bersih");
    }

    // (d) meja terbuka
    static void testOpenTable() {
        System.out.println("[d] meja terbuka");
        Snap s = baseSnap(16, 8);
        put(s, 0, 0f, 100f);
        put(s, 12, 0f, -400f);        // stripe: pot lurus mudah
        put(s, 3, -600f, 300f);       // solid: posisi lebih sulit
        for (int i = 1; i <= 15; i++) {
            s.legal[i] = true;
        }
        Auto.Shot shot = Auto.searchShot(s);
        check(shot != null && shot.level == Auto.LV_POT, "meja terbuka: ada pot");
        Sim.Result r = resim(s, shot);
        int[] out = new int[2];
        check(Auto.passesFilter(s, r, out) && out[0] > 0,
                "tembakan meja terbuka lolos saringan & memasukkan target legal");
        check(shot.targetBall == 12, "yang dipilih pot termudah (bola 12), dapat "
                + shot.targetBall);
    }

    // (e) 9-ball: kontak pertama wajib bola terendah
    static void testNineBall() {
        System.out.println("[e] 9-ball bola terendah");
        Snap s = baseSnap(10, 9);
        put(s, 0, 0f, 100f);
        put(s, 1, 500f, 500f);        // bola terendah: posisi sulit
        put(s, 2, 0f, -400f);         // bola 2: pot lurus menggoda
        s.legal[1] = true;            // hanya bola 1 legal (oracle 9-ball)
        Sim sim = s.newSim();
        Sim.Result trap = sim.simulate(700f, 0f, 0f, -90f, false);
        check(trap.potted[2], "jebakan terverifikasi: bola 2 bisa dipot lurus");
        check(!Auto.passesFilter(s, trap, null),
                "saringan menolak kontak pertama bukan bola terendah");
        Auto.Shot shot = Auto.searchShot(s);
        check(shot == null || shot.targetBall == 1,
                "autoplay hanya menargetkan bola 1 / pengaman ke bola 1 / diam");
    }

    // (f) unit saringan money ball
    static void testMoneyBallFilter() {
        System.out.println("[f] saringan bola penentu");
        Snap s = baseSnap(16, 8);
        s.legal[1] = true;            // solids, bola 8 BELUM legal
        Sim.Result r = new Sim.Result(16, false);
        r.firstContact = 1;
        r.potted[8] = true;
        check(!Auto.passesFilter(s, r, null), "bola 8 masuk sebelum legal -> ditolak mutlak");

        Snap s9 = baseSnap(10, 9);
        s9.legal[1] = true;
        Sim.Result r9 = new Sim.Result(10, false);
        r9.firstContact = 1;
        r9.potted[9] = true;
        int[] out = new int[2];
        check(Auto.passesFilter(s9, r9, out) && out[1] == 1,
                "bola 9 masuk sesudah kontak legal -> kemenangan sah");

        Sim.Result r9b = new Sim.Result(10, false);
        r9b.firstContact = 3;         // kontak pertama salah di 9-ball
        r9b.potted[9] = true;
        check(!Auto.passesFilter(s9, r9b, null),
                "bola 9 masuk tapi kontak pertama foul -> ditolak");
    }

    // (g) oracle tidak terbaca -> tidak menembak sama sekali
    static void testNoOracleNoShot() {
        System.out.println("[g] tanpa oracle tidak menembak");
        Snap s = baseSnap(16, 8);
        put(s, 0, 0f, 100f);
        put(s, 1, 0f, -400f);
        s.legalKnown = false;
        s.legal = null;
        check(Auto.searchShot(s) == null, "legalKnown=false -> autoplay diam");
    }

    /** Layout meja dari screenshot uji HP user 22 Okt 20:08 (koordinat meja). */
    static Snap screenshotSnap() {
        Snap s = baseSnap(16, 8);
        put(s, 0, -700f, -60f);     // cue, kiri-tengah
        put(s, 1, -620f, 190f);     // solids tersebar
        put(s, 2, 140f, 90f);
        put(s, 3, 690f, 300f);      // gugus kanan-bawah: 3,5,6,13
        put(s, 4, -470f, 430f);
        put(s, 5, 770f, 330f);
        put(s, 6, 830f, 210f);
        put(s, 7, 1060f, 110f);     // marun kanan
        put(s, 8, 490f, 40f);       // 8 hitam kanan-tengah
        put(s, 9, -160f, -540f);    // stripes
        put(s, 10, 120f, -430f);    // stripe biru dekat lubang atas-tengah
        put(s, 11, 330f, -520f);
        put(s, 12, 500f, -270f);
        put(s, 13, 880f, 280f);
        put(s, 14, 890f, 420f);
        put(s, 15, 1090f, -40f);
        return s;
    }

    // (h) layout screenshot, grup user = stripes
    static void testScreenshotStripes() {
        System.out.println("[h] layout screenshot — stripes legal");
        Snap s = screenshotSnap();
        for (int i = 9; i <= 15; i++) {
            s.legal[i] = true;
        }
        Auto.Shot shot = Auto.searchShot(s);
        check(shot != null, "ada tembakan pada layout screenshot");
        check(shot.level == Auto.LV_POT, "yang dipilih pot langsung (ada pot bersih)");
        check(shot.targetBall >= 9 && shot.targetBall <= 15,
                "target adalah stripe milik pemain (dapat " + shot.targetBall + ")");
        Sim.Result r = resim(s, shot);
        check(r.potted[shot.targetBall] && !r.cuePotted,
                "pot terpilih masuk bersih tanpa scratch pada simulasi ulang");
        check(Auto.lastReport.contains("stripes"),
                "readout menyebut grup stripes: " + Auto.lastReport);
        System.out.println("  readout: " + Auto.lastReport);
    }

    // (h2) preferensi "paling enak": pot lurus pendek harus mengalahkan
    // pot jauh yang sama-sama lolos — inti keluhan v1.1 ("tidak memilih
    // bola yang paling enak"). Bola 10 lurus ke lubang atas-tengah
    // (potongan ~4°, travel pendek) vs bola 12 jauh (potongan ~16°,
    // travel ~3x) pada layout screenshot yang sama.
    static void testPreferenceStraightBeatsFar() {
        System.out.println("[h2] preferensi pot lurus pendek vs pot jauh");
        Snap s = screenshotSnap();
        // reposisi: cue hampir segaris di belakang bola 10
        s.balls[0].x = 30f;
        s.balls[0].y = -60f;
        s.balls[10].x = 0f;
        s.balls[10].y = -350f;
        // bola 12 dipindah ke pot yang BENAR-BENAR lebih sulit tapi
        // tetap bisa masuk: ke lubang kiri-atas, potongan ~35 derajat,
        // travel bola ~800 (posisi aslinya di layout ternyata pot
        // lurus ~0 derajat — premis lama keliru secara geometri)
        s.balls[12].x = -500f;
        s.balls[12].y = -500f;
        for (int i = 9; i <= 15; i++) {
            s.legal[i] = true;
        }
        // buktikan pot 12 memang ada (ghost lurus ke kiri-atas masuk)
        {
            Sim sim0 = s.newSim();
            float hx = -1290f, hy = -628f;
            float dx = hx - -500f, dy = hy - -500f;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            float gx = -500f - dx / len * 94.3f;
            float gy = -500f - dy / len * 94.3f;
            float ang = (float) Math.toDegrees(Math.atan2(gy - -60f, gx - 30f));
            Sim.Result rp = sim0.simulate(1500f, 0f, 0f, ang, false);
            check(rp.potted[12], "pot jauh bola 12 terverifikasi bisa masuk");
        }
        Auto.Shot shot = Auto.searchShot(s);
        check(shot != null && shot.level == Auto.LV_POT, "ada pot pada skenario preferensi");
        check(shot.targetBall == 10,
                "pot lurus pendek (bola 10) yang dipilih, bukan pot jauh (dapat "
                        + shot.targetBall + ")");
        Sim.Result r = resim(s, shot);
        check(r.potted[10] && !r.cuePotted, "pot 10 masuk bersih tanpa scratch");
        System.out.println("  readout: " + Auto.lastReport);
    }

    // (i) layout screenshot yang sama, grup legal = solids
    static void testScreenshotSolids() {
        System.out.println("[i] layout screenshot — solids legal");
        Snap s = screenshotSnap();
        for (int i = 1; i <= 7; i++) {
            s.legal[i] = true;
        }
        Auto.Shot shot = Auto.searchShot(s);
        check(shot == null || (shot.targetBall >= 1 && shot.targetBall <= 7),
                "stripe tidak pernah ditargetkan saat solids yang legal (dapat "
                        + (shot == null ? "diam" : shot.targetBall) + ")");
        if (shot != null && shot.level <= Auto.LV_COMBO) {
            Sim.Result r = resim(s, shot);
            check(r.potted[shot.targetBall] && !r.cuePotted,
                    "pot solid terpilih masuk bersih tanpa scratch");
        }
        System.out.println("  readout: " + Auto.lastReport);
    }

    // (j) reproduksi screenshot 21:26 — bola putih terjepit di kanan,
    // kontak langsung ke solids terhalang stripes/8. Perencana expert
    // TIDAK BOLEH menyerah: wajib keputusan tangga (kick/safety/dst)
    // yang bersih pada simulasi ulang.
    static void testSnookered() {
        System.out.println("[j] posisi terjepit (screenshot 21:26)");
        Snap s = baseSnap(16, 8);
        put(s, 0, 1200f, 0f);        // cue menempel sisi kanan
        put(s, 6, 1200f, 420f);      // solid kita, segaris di bawah cue
        put(s, 14, 1200f, 200f);     // stripe menghalangi garis ke 6
        put(s, 1, -1000f, 0f);       // solid bebas jauh di kiri
        put(s, 8, 300f, 10f);        // bola 8 menghalangi garis ke 1
        put(s, 9, -300f, -15f);      // stripe menghalangi pantulan garis
        put(s, 3, -1200f, -500f);    // solid dekat lubang kiri-bawah
        put(s, 13, -1050f, -380f);   // stripe menghalangi garis ke 3
        for (int i = 1; i <= 7; i++) {
            s.legal[i] = true;
        }
        Auto.Shot shot = Auto.searchShot(s, Auto.DIFF_EXPERT);
        check(shot != null,
                "perencana TIDAK menyerah pada posisi terjepit (tangga expert)");
        Sim.Result r = resim(s, shot);
        int[] out = new int[2];
        check(Auto.passesFilter(s, r, out),
                "keputusan tangga bersih: kontak legal, tanpa bola salah/scratch");
        System.out.println("  tingkat=" + shot.level + " readout: " + Auto.lastReport);
    }

    // (k) unit saringan penuh Perintah 5A: pot legal yang DALAM
    // SIMULASI PENUH ikut menjatuhkan bola lawan (tabrakan sekunder)
    // wajib ditolak; pot legal bersih wajib lolos.
    static void testFullSimTrap() {
        System.out.println("[k] saringan penuh: bola salah ikut masuk = tolak");
        Snap s = baseSnap(16, 8);
        s.legal[1] = true;
        Sim.Result bad = new Sim.Result(16, false);
        bad.firstContact = 1;
        bad.potted[1] = true;   // target legal masuk
        bad.potted[9] = true;   // TAPI bola lawan ikut masuk (sekunder)
        check(!Auto.passesFilter(s, bad, null),
                "pot legal + bola lawan ikut masuk -> DITOLAK (5A)");
        Sim.Result bad8 = new Sim.Result(16, false);
        bad8.firstContact = 1;
        bad8.potted[1] = true;
        bad8.potted[8] = true;  // bola 8 dini ikut masuk
        check(!Auto.passesFilter(s, bad8, null),
                "pot legal + bola 8 dini -> DITOLAK (5A)");
        Sim.Result good = new Sim.Result(16, false);
        good.firstContact = 1;
        good.potted[1] = true;
        int[] out = new int[2];
        check(Auto.passesFilter(s, good, out) && out[0] == 1,
                "pot legal bersih -> lolos");
    }

    // (l) tingkat kesulitan pada posisi yang sama: NORMAL = pot
    // langsung sederhana; MUSTAHIL = rencana run-out >= 3 bola.
    static void testDifficultyLevels() {
        System.out.println("[l] kesulitan: Normal vs Mustahil");
        Snap s = baseSnap(16, 8);
        put(s, 0, 0f, 400f);
        put(s, 1, 0f, -100f);
        put(s, 2, -450f, -350f);
        put(s, 3, 450f, -350f);
        s.legal[1] = true;
        s.legal[2] = true;
        s.legal[3] = true;
        Auto.Shot n = Auto.searchShot(s, Auto.DIFF_NORMAL);
        check(n != null && n.level == Auto.LV_POT && n.chainLen == 0,
                "NORMAL: pot langsung sederhana tanpa rantai (dapat tingkat "
                        + (n == null ? "null" : n.level) + ")");
        Auto.Shot m = Auto.searchShot(s, Auto.DIFF_MUSTAHIL);
        check(m != null, "MUSTAHIL: ada keputusan");
        check(m != null && m.chainLen >= 3,
                "MUSTAHIL: rencana run-out >= 3 bola (dapat chain "
                        + (m == null ? "-" : m.chainLen) + ")");
        System.out.println("  readout mustahil: " + Auto.lastReport);
    }

    static Snap arcadeSnap() {
        Snap s = baseSnap(16, -1);
        s.arcade = true;
        s.restTime = 6;
        s.ruleClass = "LevelRule";
        for (int i = 1; i < s.legal.length; i++) {
            s.legal[i] = true; // LevelRule: semua bola non-cue sah
        }
        return s;
    }

    // (m) Arcade: lubang atas-tengah = lubang hitam (effect 1).
    // Bola 1 lurus ke sana menggoda; perencana wajib memot bola lain /
    // jalur lain — tidak ada bola boleh masuk lubang hitam.
    static void testArcadeBlackHole() {
        System.out.println("[m] arcade: hindari lubang hitam");
        Snap s = arcadeSnap();
        s.holes[0].effect = 1; // TM (0,-628) lubang hitam
        put(s, 0, 0f, 100f);
        put(s, 1, 0f, -400f);      // lurus ke lubang hitam
        put(s, 2, -700f, -300f);   // pot bersih ke TL (normal)
        Auto.Shot shot = Auto.searchShot(s, Auto.DIFF_EXPERT);
        check(shot != null, "arcade: ada tembakan");
        Sim.Result r = resim(s, shot);
        int[] out = new int[2];
        check(Auto.passesFilter(s, r, out) && out[0] >= 1,
                "arcade: tembakan terpilih memasukkan bola & lolos saringan");
        for (int i = 1; i < s.balls.length; i++) {
            if (r.potted[i]) {
                check(s.holes[r.potHole[i]].effect != 1,
                        "tidak ada bola masuk lubang hitam");
            }
        }
        System.out.println("  readout: " + Auto.lastReport);
    }

    // (n) Arcade: multi-pot (bola 1 masuk + bola putih menyusul
    // mengetuk bola 2 yang menggantung) harus mengalahkan pot tunggal.
    static void testArcadeMultiPot() {
        System.out.println("[n] arcade: multi-pot di atas pot tunggal");
        Snap s = arcadeSnap();
        put(s, 0, 0f, 300f);
        put(s, 1, 0f, -300f);      // lurus ke lubang atas-tengah
        put(s, 2, -15f, -560f);    // segaris di depan lubang: kena
                                   // sundul bola 1 dalam perjalanan
        put(s, 3, -900f, 400f);    // pot tunggal alternatif jauh
        Auto.Shot shot = Auto.searchShot(s, Auto.DIFF_EXPERT);
        check(shot != null, "arcade multi: ada tembakan");
        Sim.Result r = resim(s, shot);
        int pots = 0;
        for (int i = 1; i < s.balls.length; i++) {
            if (r.potted[i]) {
                pots++;
            }
        }
        check(pots >= 2, "multi-pot dipilih: " + pots + " bola masuk dalam 1 tembakan");
        System.out.println("  readout: " + Auto.lastReport);
    }

    // (o) Arcade: jebakan scratch yang sama seperti (b) — kandidat
    // scratch wajib ditolak selama ada garis berkontak.
    static void testArcadeScratch() {
        System.out.println("[o] arcade: scratch tetap ditolak");
        Snap s = arcadeSnap();
        put(s, 0, 0f, 100f);
        put(s, 1, 0f, -400f);
        Sim sim = s.newSim();
        Sim.Result trap = sim.simulate(1500f, 0f, 0f, -90f, false);
        check(trap.potted[1] && trap.cuePotted, "jebakan scratch arcade nyata");
        check(!Auto.passesFilter(s, trap, null), "saringan arcade menolak scratch");
        Auto.Shot shot = Auto.searchShot(s, Auto.DIFF_EXPERT);
        check(shot != null, "arcade: tetap ada tembakan bersih");
        Sim.Result r = resim(s, shot);
        check(!r.cuePotted, "tembakan arcade terpilih tidak scratch");
    }

    public static void main(String[] args) {
        testOnlyOpponentPottable();
        testScratchTrap();
        testGroups();
        testOpenTable();
        testNineBall();
        testMoneyBallFilter();
        testNoOracleNoShot();
        testScreenshotStripes();
        testPreferenceStraightBeatsFar();
        testScreenshotSolids();
        testSnookered();
        testFullSimTrap();
        testDifficultyLevels();
        testArcadeBlackHole();
        testArcadeMultiPot();
        testArcadeScratch();
        System.out.println("SEMUA UJI AUTO LULUS");
    }
}
