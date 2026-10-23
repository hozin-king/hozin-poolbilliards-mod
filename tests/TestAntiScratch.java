package com.hozin.poolmod;

/**
 * Uji fitur B v1.4 (Anti-Scratch Total) di JVM atas Sim:
 * saklar ON -> waktu tabrakan lubang bola putih selalu MAX_TIME
 * (cermin patch smali PoolTable) -> bola putih digelindingkan
 * lurus ke keenam lubang dari berbagai kecepatan TIDAK PERNAH
 * tertangkap, termasuk berhenti tepat di atas lubang.
 * Saklar OFF (kontrol) -> tembakan yang sama men-scratch.
 */
public final class TestAntiScratch {
    static int checks = 0;

    static void check(boolean cond, String msg) {
        checks++;
        if (!cond) {
            throw new AssertionError("GAGAL: " + msg);
        }
        System.out.println("  ok: " + msg);
    }

    /** Tembak bola putih lurus ke pusat lubang h dari arah tengah
     *  meja (titik mulai = 45% garis tengah->lubang, selalu di dalam
     *  area bermain). */
    static Sim.Result rollToHole(int h, float distIgnored, float strength, boolean immune) {
        Snap s = TestAuto.baseSnap(2, -1);
        float hx = TestSim.HOLES[h * 3];
        float hy = TestSim.HOLES[h * 3 + 1];
        float sx = hx * 0.45f;
        float sy = hy * 0.45f;
        TestAuto.put(s, 0, sx, sy);
        Sim sim = s.newSim();
        sim.cueHoleImmune = immune;
        float ang = (float) Math.toDegrees(Math.atan2(hy - sy, hx - sx));
        return sim.simulate(strength, 0f, 0f, ang, false);
    }

    /** Gulir pelan ke lubang dari jarak dekat (berhenti di atasnya). */
    static Sim.Result creepToHole(int h, boolean immune) {
        Snap s = TestAuto.baseSnap(2, -1);
        float hx = TestSim.HOLES[h * 3];
        float hy = TestSim.HOLES[h * 3 + 1];
        float len = (float) Math.sqrt(hx * hx + hy * hy);
        float sx = hx * (1.0f - 200.0f / len);
        float sy = hy * (1.0f - 200.0f / len);
        TestAuto.put(s, 0, sx, sy);
        Sim sim = s.newSim();
        sim.cueHoleImmune = immune;
        float ang = (float) Math.toDegrees(Math.atan2(hy - sy, hx - sx));
        return sim.simulate(230f, 0f, 0f, ang, false);
    }

    public static void main(String[] args) {
        float[] speeds = {500f, 1500f, 3000f};
        System.out.println("[B] saklar ON: bola putih tidak pernah tertangkap");
        for (int h = 0; h < 6; h++) {
            for (int si = 0; si < speeds.length; si++) {
                Sim.Result r = rollToHole(h, 380f, speeds[si], true);
                check(!r.cuePotted, "lubang " + h + " kecepatan " + (int) speeds[si]
                        + ": bola putih lolos (tidak potted)");
            }
        }
        System.out.println("[B] berhenti tepat di atas lubang");
        for (int h = 0; h < 6; h++) {
            Sim.Result r = creepToHole(h, true);
            check(!r.cuePotted, "lubang " + h + ": berhenti di atas lubang tanpa tertangkap");
        }
        System.out.println("[B] kontrol OFF: tembakan lurus men-scratch");
        for (int h = 0; h < 6; h++) {
            Sim.Result r = rollToHole(h, 380f, 900f, false);
            check(r.cuePotted, "lubang " + h + ": kontrol OFF memang scratch");
        }
        System.out.println("TestAntiScratch: SEMUA " + checks + " pemeriksaan lulus");
    }
}
