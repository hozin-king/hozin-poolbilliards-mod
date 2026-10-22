package com.hozin.poolmod;

import java.util.ArrayList;

/**
 * Auto play pintar: pencarian tembakan memakai klon Sim.
 *
 * Kandidat sudut TIDAK disapu membabi buta: untuk tiap pasangan
 * (bola objek, lubang) dihitung sudut ghost-ball geometris, lalu 3
 * tingkat strength di sekitar perkiraan jarak. Tiap kandidat
 * disimulasikan penuh oleh klon dan diberi skor:
 *   bola objek masuk +1000/ball (9-ball: bola target terendah +1000,
 *   bola lain +250 dan kontak pertama wajib bola target, -900),
 *   bola putih ikut masuk -100000, tanpa kontak bola -700,
 *   posisi akhir bola putih dekat bola berikutnya & jauh dari lubang.
 * Bila tak ada yang memasukkan bola, fase spin mencoba varian
 * spinY/spinX pada kandidat terbaik; terakhir sapuan halus di sekitar
 * arah tiap bola objek. Freeball: titik penempatan dicari di garis
 * ghost-ball (divalidasi rule.canPut asli), lalu tembakan terbaik dari
 * titik itu.
 *
 * Pencarian berjalan di thread pekerja dari snapshot; eksekusi
 * diserahkan ke thread game lewat pendingExec (Hooks) yang memeriksa
 * ulang gerbang PvP + kesesuaian posisi sebelum PoolTable.click.
 */
public final class Auto {
    public static final class Shot {
        public float strength, spinX, spinY, angle, score;
        public int potted;
        public boolean freeball;
        public float spotX, spotY;
        public long posKey;
    }

    public static volatile Shot pendingExec;
    public static volatile Snap pendingSnap;

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

    private static int lowestObject(Snap snap) {
        int best = -1;
        for (int i = 0; i < snap.balls.length; i++) {
            if (i != snap.clickTarget && snap.balls[i].avail) {
                if (best < 0 || i < best) {
                    best = i;
                }
            }
        }
        return best;
    }

    private static float score(Snap snap, Sim.Result r, float strength, boolean nine, int lowestObj) {
        float sc = 0.0f;
        int cue = snap.clickTarget;
        for (int i = 0; i < snap.balls.length; i++) {
            if (i != cue && r.potted[i]) {
                if (nine) {
                    sc += i == lowestObj ? 1000.0f : 250.0f;
                } else {
                    sc += 1000.0f;
                }
            }
        }
        if (r.firstContact < 0) {
            sc -= 700.0f;
        }
        if (nine && lowestObj >= 0 && r.firstContact != lowestObj) {
            sc -= 900.0f;
        }
        if (r.cuePotted) {
            sc -= 100000.0f;
        }
        float nearest = Float.MAX_VALUE;
        for (int i = 0; i < snap.balls.length; i++) {
            if (i != cue && snap.balls[i].avail && !r.potted[i]) {
                float d = dist(r.stopX[cue], r.stopY[cue], snap.balls[i].x, snap.balls[i].y);
                if (d < nearest) {
                    nearest = d;
                }
            }
        }
        if (nearest < Float.MAX_VALUE) {
            sc += Math.max(0.0f, 400.0f - nearest) * 0.35f;
        }
        for (int h = 0; h < snap.holes.length; h++) {
            float d = dist(r.stopX[cue], r.stopY[cue], snap.holes[h].x, snap.holes[h].y);
            if (d < 130.0f) {
                sc -= (130.0f - d) * 1.2f;
            }
        }
        sc -= strength * 0.002f;
        return sc;
    }

    private static int countPotted(Snap snap, Sim.Result r) {
        int c = 0;
        for (int i = 0; i < snap.balls.length; i++) {
            if (i != snap.clickTarget && r.potted[i]) {
                c++;
            }
        }
        return c;
    }

    private static Shot eval(Sim sim, Snap snap, float angle, float strength,
                             float spinX, float spinY, boolean nine, int lowestObj) {
        Sim.Result r = sim.simulate(strength, spinX, spinY, angle, false);
        Shot s = new Shot();
        s.angle = angle;
        s.strength = strength;
        s.spinX = spinX;
        s.spinY = spinY;
        s.potted = countPotted(snap, r);
        s.score = score(snap, r, strength, nine, lowestObj);
        return s;
    }

    // ----------------------------------------------------------------
    public static Shot searchShot(Snap snap) {
        Sim sim = snap.newSim();
        boolean nine = snap.ruleClass.contains("9");
        int lowestObj = lowestObject(snap);
        Sim.Ball cue = sim.protoBall(snap.clickTarget);

        ArrayList<float[]> cand = new ArrayList<float[]>();
        for (int b = 0; b < snap.balls.length; b++) {
            if (b == snap.clickTarget || !snap.balls[b].avail) {
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
                float ang = (float) Math.toDegrees(Math.atan2(gy - cue.y, gx - cue.x));
                float d1 = dist(cue.x, cue.y, gx, gy);
                float est = estStrength(snap, d1, len);
                cand.add(new float[]{ang, clamp(est * 0.75f, snap.minStrength, snap.maxStrength)});
                cand.add(new float[]{ang, est});
                cand.add(new float[]{ang, clamp(est * 1.3f, snap.minStrength, snap.maxStrength)});
            }
        }

        Shot best = null;
        ArrayList<Shot> top = new ArrayList<Shot>();
        for (int i = 0; i < cand.size(); i++) {
            float[] c = cand.get(i);
            Shot s = eval(sim, snap, c[0], c[1], 0.0f, 0.0f, nine, lowestObj);
            if (best == null || s.score > best.score) {
                best = s;
            }
            top.add(s);
        }

        if (best == null) {
            // tak ada bola objek sama sekali: tembak lurus seperlunya
            Shot s = new Shot();
            s.angle = 0.0f;
            s.strength = snap.minStrength;
            s.score = -Float.MAX_VALUE;
            return s;
        }

        // fase spin: hanya bila belum ada yang memasukkan bola
        if (best.potted == 0) {
            // urutkan top menurun berdasarkan skor (seleksi sederhana)
            for (int i = 0; i < top.size(); i++) {
                for (int j = i + 1; j < top.size(); j++) {
                    if (top.get(j).score > top.get(i).score) {
                        Shot tmp = top.get(i);
                        top.set(i, top.get(j));
                        top.set(j, tmp);
                    }
                }
            }
            float[][] spins = {{0.0f, 0.45f}, {0.0f, -0.45f}, {0.35f, 0.0f}, {-0.35f, 0.0f}};
            int lim = Math.min(6, top.size());
            for (int i = 0; i < lim; i++) {
                Shot base = top.get(i);
                for (int k = 0; k < spins.length; k++) {
                    Shot s = eval(sim, snap, base.angle, base.strength,
                            spins[k][0], spins[k][1], nine, lowestObj);
                    if (s.score > best.score) {
                        best = s;
                    }
                }
            }
        }

        // fallback: sapuan halus arah tiap bola objek bila belum kontak/pot
        if (best.potted == 0 && best.score < -600.0f) {
            for (int b = 0; b < snap.balls.length; b++) {
                if (b == snap.clickTarget || !snap.balls[b].avail) {
                    continue;
                }
                Sim.Ball ball = snap.balls[b];
                float baseAng = (float) Math.toDegrees(Math.atan2(ball.y - cue.y, ball.x - cue.x));
                float dc = dist(cue.x, cue.y, ball.x, ball.y);
                float spread = (float) Math.toDegrees(Math.atan2(cue.scale + ball.scale + 6.0f, Math.max(dc, 1.0f)));
                float est = estStrength(snap, dc, 600.0f);
                for (int k = -12; k <= 12; k++) {
                    float ang = baseAng + spread * (k / 12.0f);
                    Shot s = eval(sim, snap, ang, est, 0.0f, 0.0f, nine, lowestObj);
                    if (s.score > best.score) {
                        best = s;
                    }
                }
            }
        }
        return best;
    }

    // ----------------------------------------------------------------
    public static Shot searchFreeball(Snap snap) {
        Sim sim = snap.newSim();
        boolean nine = snap.ruleClass.contains("9");
        int lowestObj = lowestObject(snap);
        Sim.Ball cueProto = sim.protoBall(snap.clickTarget);
        float origX = cueProto.x;
        float origY = cueProto.y;

        Shot best = null;
        float[] depths = {300.0f, 600.0f, 1000.0f};
        for (int b = 0; b < snap.balls.length; b++) {
            if (b == snap.clickTarget || !snap.balls[b].avail) {
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
                    Shot s = eval(sim, snap, ang, est, 0.0f, 0.0f, nine, lowestObj);
                    s.spotX = sx;
                    s.spotY = sy;
                    if (best == null || s.score > best.score) {
                        best = s;
                    }
                }
            }
        }
        sim.setProtoPosition(snap.clickTarget, origX, origY);
        if (best != null) {
            // perhalus strength di titik terbaik
            sim.setProtoPosition(snap.clickTarget, best.spotX, best.spotY);
            float[] mults = {0.8f, 1.25f};
            for (int i = 0; i < mults.length; i++) {
                float st = clamp(best.strength * mults[i], snap.minStrength, snap.maxStrength);
                Shot s = eval(sim, snap, best.angle, st, 0.0f, 0.0f, nine, lowestObj);
                s.spotX = best.spotX;
                s.spotY = best.spotY;
                if (s.score > best.score) {
                    best = s;
                }
            }
            sim.setProtoPosition(snap.clickTarget, origX, origY);
        }
        return best;
    }
}
