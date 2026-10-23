package com.hozin.poolmod;

/**
 * Uji fitur C v1.4 (Prank Giliran Lawan) di JVM atas Sim:
 * mekanisme perangkatnya = PoolBlocker dibuat tidak terlihat
 * (Sim melewati tabrakan cushion untuk blocker !vis, sama seperti
 * PoolTable asli). Yang dibuktikan di sini:
 *  - kontrol (blocker terlihat): bola memantul di dalam meja;
 *  - prank (blocker tak terlihat): bola meluncur menembus garis
 *    cushion, keluar area, lalu kode game sendiri (blok wrap di
 *    updateRunning/frictionStep) membawanya ke sisi berlawanan —
 *    bola tidak hilang, tidak NaN, simulasi berakhir normal;
 *  - sesudah visibilitas dipulihkan, pantulan normal lagi.
 */
public final class TestPrank {
    static int checks = 0;

    static void check(boolean cond, String msg) {
        checks++;
        if (!cond) {
            throw new AssertionError("GAGAL: " + msg);
        }
        System.out.println("  ok: " + msg);
    }

    static Snap shotSnap(boolean cushionsVisible) {
        Snap s = TestAuto.baseSnap(2, -1);
        TestAuto.put(s, 0, 0f, 0f);
        for (int i = 0; i < s.blockers.length; i++) {
            s.blockers[i].vis = cushionsVisible;
        }
        return s;
    }

    static float maxAbsXPath(Sim.Result r, int ball) {
        float m = 0f;
        for (float[] p : r.paths[ball]) {
            m = Math.max(m, Math.abs(p[0]));
        }
        return m;
    }

    public static void main(String[] args) {
        System.out.println("[C] kontrol: cushion aktif, bola memantul di dalam meja");
        Sim.Result ctrl = shotSnap(true).newSim().simulate(2600f, 0f, 0f, 0f, true);
        check(maxAbsXPath(ctrl, 0) < 1330f,
                "kontrol: bola putih tidak pernah melewati garis cushion (maks |x|="
                        + (int) maxAbsXPath(ctrl, 0) + ")");

        System.out.println("[C] prank: cushion mati, bola meluncur keluar & di-wrap game");
        Sim.Result prank = shotSnap(false).newSim().simulate(2600f, 0f, 0f, 0f, true);
        boolean crossed = false;
        boolean wrapped = false;
        for (float[] p : prank.paths[0]) {
            if (p[0] > 1330f) {
                crossed = true;
            }
            if (p[0] < -1000f) {
                wrapped = true;
            }
        }
        check(crossed, "prank: bola menembus garis cushion kanan");
        check(wrapped, "prank: bola muncul di sisi berlawanan (wrap updateRunning game)");
        check(Float.isFinite(prank.stopX[0]) && Float.isFinite(prank.stopY[0]),
                "prank: posisi akhir valid (tidak NaN)");
        Sim.Result after = shotSnap(false).newSim().simulate(2600f, 0f, 0f, 0f, false);
        check(after.steps < 6000, "prank: simulasi berakhir normal (bola berhenti oleh friksi)");

        System.out.println("[C] pulih: visibilitas kembali, pantulan normal");
        Sim.Result back = shotSnap(true).newSim().simulate(2600f, 0f, 0f, 0f, true);
        check(maxAbsXPath(back, 0) < 1330f, "sesudah pulih bola kembali memantul normal");

        System.out.println("TestPrank: SEMUA " + checks + " pemeriksaan lulus");
    }
}
