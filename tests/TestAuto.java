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
        check(shot == null || shot.safety || shot.targetBall != 9,
                "autoplay tidak memilih bola lawan (hasil: "
                        + (shot == null ? "tidak menembak" : shot.safety ? "pengaman" : "bola " + shot.targetBall) + ")");
        if (shot != null && !shot.safety) {
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
        if (!shot.safety) {
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
        check(shot != null && !shot.safety, "ada pot legal untuk solids");
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
        check(shot2 != null && !shot2.safety && shot2.targetBall == 8,
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
        check(shot != null && !shot.safety, "meja terbuka: ada pot");
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

    public static void main(String[] args) {
        testOnlyOpponentPottable();
        testScratchTrap();
        testGroups();
        testOpenTable();
        testNineBall();
        testMoneyBallFilter();
        testNoOracleNoShot();
        System.out.println("SEMUA UJI AUTO LULUS");
    }
}
