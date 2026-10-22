package com.hozin.poolmod;

import java.lang.reflect.Method;
import java.util.Random;

/**
 * Uji konsistensi klon fisika Sim di JVM (tanpa Android):
 *  1. audit konstanta vs REPORT/RuleImplBase,
 *  2. workout() analitik vs brute-force numerik (acak, seeded),
 *  3. pot lurus: bola masuk, cue tidak ikut masuk, kontak pertama benar,
 *  4. break shot: kontak pertama = bola apex, semua berhenti di meja,
 *  5. pantulan cushion satu bola: berhenti di dalam meja.
 *
 * Geometri disalin dari RuleImplBase (blockersPosition/holePosition,
 * blocker.force=1200, lebar lubang = 2*jari-jari lubang).
 */
public final class TestSim {
    static final float[] BLOCKERS = {
            -1305f, -665f, 1305f, -665f, -1305f, -665f, -1305f, 665f,
            1305f, -665f, 1305f, 665f, -1305f, 665f, 1305f, 665f,
            -1225f, 520f, -1225f, -490f, -1120f, -605f, -100f, -605f,
            100f, -605f, 1120f, -605f, 1217f, 520f, 1217f, -500f,
            -1120f, 625f, -100f, 625f, 100f, 625f, 1120f, 625f};
    static final float[] HOLES = {
            0f, -628f, 80f, -1220f, -600f, 70f, 1220f, -600f, 70f,
            0f, 650f, 80f, -1230f, 630f, 70f, 1220f, 630f, 70f};
    static final float R = 47.15f;

    static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("GAGAL: " + msg);
        }
        System.out.println("  ok: " + msg);
    }

    static Sim baseSim(int nballs) {
        Sim sim = new Sim();
        sim.force = 1500.0f;
        sim.rollForce = 100.0f;
        sim.holeGravity = 3600.0f;
        sim.ballRadius = R;
        sim.tableW = 2810.0f;
        sim.tableH = 1530.0f;
        sim.firstShoot = false;
        sim.clickTarget = 0;
        Sim.Blocker[] bl = new Sim.Blocker[BLOCKERS.length / 4];
        for (int i = 0; i < bl.length; i++) {
            Sim.Blocker b = new Sim.Blocker();
            b.x0 = BLOCKERS[i * 4];
            b.y0 = BLOCKERS[i * 4 + 1];
            b.x1 = BLOCKERS[i * 4 + 2];
            b.y1 = BLOCKERS[i * 4 + 3];
            b.force = 1200.0f;
            b.vis = true;
            bl[i] = b;
        }
        Sim.Hole[] hs = new Sim.Hole[HOLES.length / 3];
        for (int i = 0; i < hs.length; i++) {
            Sim.Hole h = new Sim.Hole();
            h.x = HOLES[i * 3];
            h.y = HOLES[i * 3 + 1];
            h.w = HOLES[i * 3 + 2] * 2.0f;
            h.vis = true;
            hs[i] = h;
        }
        Sim.Ball[] balls = new Sim.Ball[nballs];
        for (int i = 0; i < nballs; i++) {
            balls[i] = new Sim.Ball();
            balls[i].scale = R;
            balls[i].avail = true;
        }
        sim.loadSnapshot(balls, bl, hs);
        return sim;
    }

    static void testConstants() {
        System.out.println("[1] konstanta");
        check(Sim.ROLL_K == 1.5811388f, "ROLL_K = 1.5811388");
        check(Sim.BLOCK_HIT_LOST == 0.8f, "BLOCK_HIT_LOST = 0.8");
        check(Sim.MAX_TIME == 3600.0f, "MAX_TIME = 3600");
        check(Sim.STEP == 0.016f, "STEP = 0.016 (16 ms)");
        check(Sim.ROLLW_FORCE_K == 0.15f, "ROLLW_FORCE_K = 0.15");
    }

    static void testWorkout() throws Exception {
        System.out.println("[2] workout analitik vs brute-force");
        Sim sim = baseSim(1);
        Method w = Sim.class.getDeclaredMethod("workout",
                float.class, float.class, float.class, float.class, float.class);
        w.setAccessible(true);
        Random rnd = new Random(42L);
        int checked = 0;
        for (int it = 0; it < 4000 && checked < 300; it++) {
            float dx = (rnd.nextFloat() * 2 - 1) * 600f;
            float dy = (rnd.nextFloat() * 2 - 1) * 600f;
            float dvx = (rnd.nextFloat() * 2 - 1) * 800f;
            float dvy = (rnd.nextFloat() * 2 - 1) * 800f;
            float r = 94.3f;
            float d0 = (float) Math.sqrt(dx * dx + dy * dy);
            if (d0 < r + 30f) {
                continue; // mulai sudah tumpang tindih/dekat — di luar domain uji
            }
            float t = (Float) w.invoke(sim, dx, dy, dvx, dvy, r);
            if (t < Sim.MAX_TIME && t > 7.5f) {
                continue; // di luar cakrawala brute-force
            }
            // brute force: langkah 0,2 ms sampai 8 s
            float tc = -1f;
            float px = dx, py = dy;
            float dt = 0.0002f;
            for (float tt = 0f; tt < 8.0f; tt += dt) {
                px += dvx * dt;
                py += dvy * dt;
                if (px * px + py * py <= r * r) {
                    tc = tt + dt;
                    break;
                }
            }
            if (t >= Sim.MAX_TIME) {
                check(tc < 0f, "analitik MAX & brute tak menemukan tabrakan");
            } else {
                check(tc > 0f && Math.abs(tc - t) < 0.004f,
                        "t analitik=" + t + " brute=" + tc);
            }
            checked++;
        }
        check(checked >= 200, "sampel cukup: " + checked);
    }

    static void testStraightPot() {
        System.out.println("[3] pot lurus");
        Sim sim = baseSim(2);
        sim.setProtoPosition(0, 0f, 100f);
        sim.protoBall(1).x = 0f;
        sim.protoBall(1).y = -400f;
        sim.protoBall(1).avail = true;
        // strength 700 terverifikasi empiris: pada 1500 bola putih ikut
        // masuk karena follow-through kopling rv khas mesin ini (bola
        // sudah rolling penuh saat kontak; rv yang tersimpan mendorong
        // bola putih maju setelah v-nya berhenti) — perilaku asli game,
        // bukan bug klon.
        Sim.Result r = sim.simulate(700f, 0f, 0f, -90f, true);
        check(r.firstContact == 1, "kontak pertama bola 1 (dapat " + r.firstContact + ")");
        check(r.potted[1], "bola 1 masuk lubang tengah");
        check(!r.cuePotted, "bola putih TIDAK ikut masuk");
        check(r.steps > 5 && r.steps < 6000, "langkah wajar: " + r.steps);
    }

    static void testBreak() {
        System.out.println("[4] break shot");
        Sim sim = baseSim(16);
        sim.setProtoPosition(0, -700f, 0f);
        int idx = 1;
        for (int row = 0; row < 5; row++) {
            for (int k = 0; k <= row; k++) {
                Sim.Ball b = sim.protoBall(idx++);
                b.x = 700f + row * 81.68f;
                b.y = (k - row / 2.0f) * 94.3f;
                b.avail = true;
            }
        }
        sim.firstShoot = true;
        Sim.Result r = sim.simulate(3200f, 0f, 0f, 0f, false);
        check(r.firstContact == 1, "kontak pertama = apex (dapat " + r.firstContact + ")");
        int moved = 0;
        for (int i = 1; i < 16; i++) {
            float dx = r.stopX[i] - sim.protoBall(i).x;
            float dy = r.stopY[i] - sim.protoBall(i).y;
            if (dx * dx + dy * dy > 100f) {
                moved++;
            }
            check(Math.abs(r.stopX[i]) < 1600f && Math.abs(r.stopY[i]) < 1000f,
                    "bola " + i + " berhenti di area meja");
            check(!Float.isNaN(r.stopX[i]) && !Float.isNaN(r.stopY[i]), "bola " + i + " bukan NaN");
        }
        check(moved >= 8, "rack pecah: " + moved + " bola bergerak jauh");
        check(r.steps < 6000, "simulasi berhenti sendiri: " + r.steps + " langkah");
    }

    static void testCushion() {
        System.out.println("[5] pantulan cushion");
        Sim sim = baseSim(1);
        sim.setProtoPosition(0, 0f, 0f);
        Sim.Result r = sim.simulate(1200f, 0f, 0f, 0f, false);
        check(!r.cuePotted, "bola tidak masuk lubang");
        check(Math.abs(r.stopX[0]) <= 1305f && Math.abs(r.stopY[0]) <= 665f,
                "berhenti di dalam meja: (" + r.stopX[0] + "," + r.stopY[0] + ")");
        check(r.stopX[0] < 1169.85f + 47.15f, "bola memantul kembali dari cushion kanan");
    }

    public static void main(String[] args) throws Exception {
        testConstants();
        testWorkout();
        testStraightPot();
        testBreak();
        testCushion();
        System.out.println("SEMUA UJI SIM LULUS");
    }
}
