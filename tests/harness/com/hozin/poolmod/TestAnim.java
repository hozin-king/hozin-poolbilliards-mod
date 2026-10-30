package com.hozin.poolmod;

import com.forthblue.pool.engine.*;
import com.forthblue.pool.rules.*;
import com.forthblue.pool.scene.GameScene;

/**
 * Harness JVM untuk Anim (Perintah 1-4): stub GameScene/PoolTable
 * bersemantik smali — shoot(Z) merekam click dari field state lalu
 * me-resetnya, putFreeball mengubah state 3 -> 1.
 */
public class TestAnim {
    static int failures = 0;

    static void check(boolean ok, String label) {
        System.out.println((ok ? "  ok: " : "  GAGAL: ") + label);
        if (!ok) {
            failures++;
        }
    }

    static class World {
        GameScene scene;
        PoolTable table;
        Vsai8Ball rule;
        PoolRule.RoundState rs;
    }

    static World world(int state) {
        World w = new World();
        w.rule = new Vsai8Ball();
        w.rs = new PoolRule.RoundState();
        w.rs.state = state;
        w.rs.currentPlayer = 0;
        w.table = new PoolTable();
        w.table.roundstate = w.rs;
        PoolBall[] balls = new PoolBall[16];
        for (int i = 0; i < 16; i++) {
            balls[i] = new PoolBall();
            balls[i].isAvailable = false;
        }
        balls[0].isAvailable = true;
        balls[0].x = 0f;
        balls[0].y = 400f;
        balls[1].isAvailable = true;
        balls[1].x = 0f;
        balls[1].y = -100f;
        w.table.balls = balls;
        Blocker[] bl = new Blocker[TestSim.BLOCKERS.length / 4];
        for (int i = 0; i < bl.length; i++) {
            Blocker b = new Blocker();
            b.x0 = TestSim.BLOCKERS[i * 4];
            b.y0 = TestSim.BLOCKERS[i * 4 + 1];
            b.x1 = TestSim.BLOCKERS[i * 4 + 2];
            b.y1 = TestSim.BLOCKERS[i * 4 + 3];
            bl[i] = b;
        }
        w.table.blockers = bl;
        PoolHole[] hs = new PoolHole[TestSim.HOLES.length / 3];
        for (int i = 0; i < hs.length; i++) {
            PoolHole h = new PoolHole();
            h.x = TestSim.HOLES[i * 3];
            h.y = TestSim.HOLES[i * 3 + 1];
            h.w = TestSim.HOLES[i * 3 + 2] * 2.0f;
            hs[i] = h;
        }
        w.table.holes = hs;
        w.scene = new GameScene();
        w.scene.table = w.table;
        return w;
    }

    static void runCycle(World w, long maxMs) throws Exception {
        long t0 = System.currentTimeMillis();
        while (Anim.isRunning() && System.currentTimeMillis() - t0 < maxMs) {
            Anim.tick(w.scene, w.table, w.rule, w.rs);
            Thread.sleep(15L);
        }
    }

    public static void main(String[] args) throws Exception {
        // --- skenario 1: tembakan normal teranimasi ---
        System.out.println("[anim-1] siklus tembakan normal");
        World w = world(1);
        Snap snap = Snap.capture(w.table, w.rule, w.rs);
        check(snap != null && snap.legalKnown, "snapshot terbaca dari stub");
        Auto.Shot shot = new Auto.Shot();
        shot.angle = -90f;
        shot.strength = 900f;
        shot.spinX = 0.3f;
        shot.spinY = -0.45f;
        shot.targetBall = 1;
        shot.level = Auto.LV_POT;
        Anim.begin(shot, snap);
        // pantau aimStrength di tengah fase PULL: harus di antara 0 dan target
        float midStrength = -1f;
        long t0 = System.currentTimeMillis();
        while (Anim.isRunning() && System.currentTimeMillis() - t0 < 9000L) {
            Anim.tick(w.scene, w.table, w.rule, w.rs);
            long el = System.currentTimeMillis() - t0;
            if (el > 600L && el < 1100L && midStrength < 0f) {
                midStrength = w.scene.peekAimStrength();
            }
            Thread.sleep(15L);
        }
        check(!Anim.isRunning(), "siklus selesai (tidak macet)");
        check(w.scene.shootCalls == 1, "shoot() terpanggil tepat 1x (dapat " + w.scene.shootCalls + ")");
        check(w.table.clickCalls == 1, "click() terjadi tepat 1x");
        check(w.table.lastClick != null
                && Math.abs(w.table.lastClick[0] - 900f) < 0.01f
                && Math.abs(w.table.lastClick[1] - 0.3f) < 0.001f
                && Math.abs(w.table.lastClick[2] - -0.45f) < 0.001f
                && Math.abs(w.table.lastClick[3] - -90f) < 0.01f,
                "click membawa nilai terpilih PERSIS (strength/spin/sudut)");
        check(w.scene.bigPPosition.setCalls >= 1
                && Math.abs(w.scene.bigPPosition.lastX - 30f) < 0.01f
                && Math.abs(w.scene.bigPPosition.lastY - -45f) < 0.01f,
                "titik merah spin ditulis ke (spinX*100, spinY*100)");
        check(w.scene.bigPPosition.lastSetMs > 0
                && w.scene.bigPPosition.lastSetMs < w.scene.shootMs,
                "spin diterapkan SEBELUM tembakan");
        check(midStrength > 0f && midStrength < 900f,
                "tarikan stik bertahap teramati di tengah siklus (aimStrength=" + midStrength + ")");

        // --- skenario 2: free ball (geser -> putFreeball -> tembak) ---
        System.out.println("[anim-2] siklus free ball");
        World w2 = world(3);
        w2.table.balls[0].x = 500f;
        w2.table.balls[0].y = 500f;
        Snap snap2 = Snap.capture(w2.table, w2.rule, w2.rs);
        Auto.Shot fb = new Auto.Shot();
        fb.freeball = true;
        fb.spotX = -300f;
        fb.spotY = 100f;
        fb.angle = -90f;
        fb.strength = 800f;
        fb.spinX = 0f;
        fb.spinY = 0f;
        fb.targetBall = 1;
        fb.level = Auto.LV_POT;
        Anim.begin(fb, snap2);
        float midX = Float.NaN;
        long t1 = System.currentTimeMillis();
        while (Anim.isRunning() && System.currentTimeMillis() - t1 < 9000L) {
            Anim.tick(w2.scene, w2.table, w2.rule, w2.rs);
            long el = System.currentTimeMillis() - t1;
            if (el > 60L && el < 300L && Float.isNaN(midX)) {
                midX = w2.table.balls[0].x;
            }
            Thread.sleep(15L);
        }
        check(w2.table.putFreeballCalls == 1, "putFreeball terpanggil 1x");
        check(Math.abs(w2.table.balls[0].x - -300f) < 0.01f
                && Math.abs(w2.table.balls[0].y - 100f) < 0.01f,
                "bola putih berakhir di titik terpilih");
        check(!Float.isNaN(midX) && midX < 490f && midX > -290f,
                "bola DIGESER bertahap, bukan teleport (x tengah=" + midX + ")");
        check(w2.scene.shootCalls == 1, "free ball lanjut menembak 1x");

        // --- skenario 3: PvP muncul di tengah siklus -> batal tanpa tembak ---
        System.out.println("[anim-3] gerbang PvP di momen eksekusi");
        World w3 = world(1);
        Snap snap3 = Snap.capture(w3.table, w3.rule, w3.rs);
        Auto.Shot s3 = new Auto.Shot();
        s3.angle = -90f;
        s3.strength = 900f;
        s3.targetBall = 1;
        s3.level = Auto.LV_POT;
        Anim.begin(s3, snap3);
        w3.rule.pvp = true; // lawan online terdeteksi sebelum FIRE
        runCycle(w3, 9000L);
        check(w3.scene.shootCalls == 0, "PvP: TIDAK ada tembakan");
        check(!Anim.isRunning(), "PvP: siklus batal bersih");

        if (failures > 0) {
            throw new AssertionError(failures + " pemeriksaan anim GAGAL");
        }
        System.out.println("SEMUA UJI ANIM LULUS");
    }
}
