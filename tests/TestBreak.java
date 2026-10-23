package com.hozin.poolmod;

/**
 * Uji fitur A v1.4 (Break Shot) di JVM atas Sim:
 * rack 8-ball standar (segitiga 15 bola, apex menghadap bola putih)
 * HARUS buyar signifikan oleh rencana break — dan rencana itu yang
 * dipilih Auto Play di tembakan pertama (isBreakPosition ->
 * planBreak, tingkat LV_BREAK, kekuatan nyaris maksimum).
 */
public final class TestBreak {
    static int checks = 0;

    static void check(boolean cond, String msg) {
        checks++;
        if (!cond) {
            throw new AssertionError("GAGAL: " + msg);
        }
        System.out.println("  ok: " + msg);
    }

    /** Rack standar: cue di (-705,0); segitiga apex di (705,0)
     *  membesar ke +x; indeks bola 1..15 (8 di tengah baris ke-3). */
    static Snap rackSnap() {
        Snap s = TestAuto.baseSnap(16, 8);
        s.firstShoot = true;
        for (int i = 1; i <= 15; i++) {
            s.legal[i] = true; // meja terbuka pada break
        }
        TestAuto.put(s, 0, -705f, 0f);
        float gap = 95.5f;
        float rowDx = gap * 0.866f;
        int idx = 1;
        int[][] order = {{1}, {2, 3}, {4, 8, 5}, {6, 7, 9, 10}, {11, 12, 13, 14, 15}};
        for (int row = 0; row < 5; row++) {
            for (int j = 0; j < order[row].length; j++) {
                float x = 705f + row * rowDx;
                float y = (j - row / 2.0f) * gap;
                TestAuto.put(s, order[row][j], x, y);
            }
        }
        return s;
    }

    static int movedCount(Snap s, Sim.Result r, float thresh) {
        int n = 0;
        for (int i = 1; i < s.balls.length; i++) {
            if (!s.balls[i].avail) {
                continue;
            }
            if (r.potted[i]) {
                n++;
                continue;
            }
            float dx = r.stopX[i] - s.balls[i].x;
            float dy = r.stopY[i] - s.balls[i].y;
            if (dx * dx + dy * dy > thresh * thresh) {
                n++;
            }
        }
        return n;
    }

    static int pottedCount(Sim.Result r) {
        int n = 0;
        for (int i = 1; i < r.potted.length; i++) {
            if (r.potted[i]) {
                n++;
            }
        }
        return n;
    }

    public static void main(String[] args) {
        Snap s = rackSnap();
        System.out.println("[A] posisi break dikenali + rencana break");
        check(Auto.isBreakPosition(s), "isBreakPosition: rack penuh + firstShoot dikenali");
        Auto.Shot shot = Auto.searchShot(s);
        check(shot != null, "Auto Play menemukan tembakan di posisi break");
        check(shot.level == Auto.LV_BREAK, "yang dipilih adalah rencana BREAK (bukan tangga biasa)");
        check(shot.strength >= s.maxStrength * 0.9f,
                "kekuatan break nyaris maksimum (" + (int) shot.strength + ")");
        check(!shot.safety, "break bukan tembakan pengaman");

        Sim.Result r = TestAuto.resim(s, shot);
        int moved = movedCount(s, r, 120f);
        int potted = pottedCount(r);
        System.out.println("  hasil break: " + potted + " bola jatuh, " + moved
                + "/15 bola bergeser >120 satuan, scratch=" + r.cuePotted);
        check(!r.cuePotted, "break tidak men-scratch bola putih");
        check(moved >= 10, "rack buyar signifikan: >=10 dari 15 bola bergeser jauh");

        System.out.println("[A] ketahanan: variasi sudut kecil tetap buyar");
        int worst = 15;
        for (float da : new float[]{-0.4f, -0.2f, 0.2f, 0.4f}) {
            Sim sim = s.newSim();
            Sim.Result rr = sim.simulate(shot.strength, 0f, 0f, shot.angle + da, false);
            worst = Math.min(worst, movedCount(s, rr, 120f));
        }
        System.out.println("  variasi sudut: pergeseran terburuk " + worst + "/15");
        check(worst >= 8, "rack tetap buyar pada variasi sudut +-0,4 derajat");

        System.out.println("[A] bukan break -> tangga biasa");
        Snap mid = rackSnap();
        mid.firstShoot = false;
        check(!Auto.isBreakPosition(mid), "firstShoot mati -> bukan posisi break");

        System.out.println("TestBreak: SEMUA " + checks + " pemeriksaan lulus");
    }
}
