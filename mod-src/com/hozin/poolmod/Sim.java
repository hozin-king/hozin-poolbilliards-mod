package com.hozin.poolmod;

import java.util.ArrayList;

/**
 * Klon fisika PoolTable (com.forthblue.pool.engine.PoolTable) — porting
 * setia fungsi-fungsi murni game ke kelas mandiri:
 *
 *   workout(5) / workout(7)   : penyelesaian waktu tabrakan analitik
 *   getNextHitTime x3 + scan  : event tabrakan berikutnya (matriks penuh
 *                               dihitung ulang tiap iterasi, sama seperti
 *                               getNextHitTime(forceAll,...) asli)
 *   doRunning                 : gerak lurus + gravitasi lubang
 *   doHit(ball,ball)          : tumbukan elastik (java.lang.Math, verbatim)
 *   doHit(ball,blocker)       : pantulan cushion restitusi 0,8 + kopling
 *                               spin blocker.force (verbatim)
 *   processRunning            : loop event dalam satu langkah 16 ms
 *                               (termasuk pemotongan waktu
 *                               ((int)(t*100000))/100000 khas asli)
 *   frictionStep              : blok friksi updateRunning(16) tanpa bagian
 *                               visual (quaternion/shadow/onRoundOver)
 *
 * Yang SENGAJA dibuang dari asli: SoundManager (bunyi klik), callback
 * aturan (onHitBall/onPotBall dsb. — tidak dibutuhkan untuk menggambar /
 * menilai tembakan), dan animasi hilang bola masuk lubang (bola yang
 * masuk langsung ditandai potted & nonaktif; lintasan bola lain tidak
 * terpengaruh karena bola potted memang berhenti bertabrakan juga di
 * game asli).
 *
 * MathUtil game adalah native dan berDERAJAT; di sini dipakai
 * (float)Math.cos/sin(Math.toRadians(...)) dan (float)Math.sqrt —
 * setara secara semantik dengan perbedaan dibawah presisi float kasat
 * mata. doHit bola-bola di game asli memang memakai java.lang.Math dan
 * diporting apa adanya.
 *
 * Konstanta acuan (RuleImplBase): jari-jari 47,15; getForce 1500;
 * getRollForce 100; getHoleGravity 3600; BLOCK_HIT_LOST 0,8;
 * ROLL_K 1,5811388; ROLLW_FORCE_K 0,15; langkah tetap 16 ms.
 * Nilai force/rollForce/holeGravity/ballRadius/ukuran meja TIDAK
 * di-hardcode: diambil dari rule yang hidup saat snapshot dibuat.
 */
public final class Sim {
    public static final float MAX_TIME = 3600.0f;
    public static final float ROLL_K = 1.5811388f;
    public static final float ROLLW_FORCE_K = 0.15f;
    public static final float BLOCK_HIT_LOST = 0.8f;
    public static final float STEP = 0.016f;
    private static final int MAX_STEPS = 6000;
    private static final int MAX_EVENTS_PER_STEP = 100000;

    public static final class Ball {
        public float x, y, vx, vy, rvx, rvy, w, scale;
        public boolean avail;
        public int inHole = -1;
        public boolean potted;

        void copyFrom(Ball o) {
            this.x = o.x; this.y = o.y;
            this.vx = o.vx; this.vy = o.vy;
            this.rvx = o.rvx; this.rvy = o.rvy;
            this.w = o.w; this.scale = o.scale;
            this.avail = o.avail; this.inHole = o.inHole; this.potted = o.potted;
        }
    }

    public static final class Blocker {
        public float x0, y0, x1, y1, force;
        public boolean vis;
    }

    public static final class Hole {
        public float x, y, w;
        public boolean vis;
        /** PoolHole.effect_type (Arcade): 0 normal, 1 lubang hitam,
         * 4 lubang -bola, 5 lubang +cue. Mode lain selalu 0. */
        public int effect;
    }

    public static final class Result {
        public ArrayList<float[]>[] paths; // null bila record=false
        public boolean[] potted;
        public int[] potHole;
        public float[] stopX;
        public float[] stopY;
        public int firstContact = -1;
        public boolean cuePotted;
        public int steps;
        // v1.3 — pelacakan cushion untuk tangga expert (bank/kick/safety):
        /** bola i menyentuh cushion setidaknya sekali selama simulasi */
        public boolean[] cushionHit;
        /** bola i masuk lubang SESUDAH menyentuh cushion (bank murni) */
        public boolean[] potAfterRail;
        /** jumlah sentuhan cushion per bola (bank multi-rel Mustahil) */
        public int[] cushionHitCount;
        /** bola putih menyentuh cushion SEBELUM kontak bola pertama */
        public boolean cueRailFirst;
        /** ada cushion tersentuh SESUDAH kontak pertama (syarat anti-foul safety) */
        public boolean railAfterContact;
        /** bola putih menyentuh cushion sesudah kontak pertama */
        public boolean cueRailAfterContact;

        @SuppressWarnings("unchecked")
        Result(int n, boolean record) {
            this.potted = new boolean[n];
            this.potHole = new int[n];
            this.stopX = new float[n];
            this.stopY = new float[n];
            this.cushionHit = new boolean[n];
            this.potAfterRail = new boolean[n];
            this.cushionHitCount = new int[n];
            for (int i = 0; i < n; i++) {
                this.potHole[i] = -1;
            }
            if (record) {
                this.paths = new ArrayList[n];
                for (int i = 0; i < n; i++) {
                    this.paths[i] = new ArrayList<float[]>();
                }
            }
        }
    }

    // parameter dari rule (diisi saat snapshot)
    public float force, rollForce, holeGravity, ballRadius, tableW, tableH;
    public boolean firstShoot;
    public int clickTarget;

    // Fitur B v1.4 (Anti-Scratch): bila true, bola clickTarget tidak
    // pernah dihitung waktu tabrakannya dengan lubang — cermin persis
    // patch smali PoolTable.getNextHitTime(PoolBall, PoolHole) yang
    // mengembalikan MAX_TIME untuk bola putih saat saklar ON (bola
    // tidak pernah masuk zona lubang, gravitasi lubang tidak pernah
    // bekerja padanya, ia meluncur melewati mulut lubang).
    public boolean cueHoleImmune;

    private Ball[] proto;   // snapshot asal (tidak dimutasi)
    private Ball[] balls;   // salinan kerja
    public Blocker[] blockers;
    public Hole[] holes;

    // state kerja peniru statics asli (hittype/hita/hitb/hitx/hity)
    private int hittype, hita, hitb;
    private float hitx, hity;
    private float[][] tBB;
    private float[][] tBL;
    private float[][] tHole;
    private float[][] cBLx;
    private float[][] cBLy;

    static float sqrtF(float v) {
        return (float) Math.sqrt(v);
    }

    static float cosDeg(float deg) {
        return (float) Math.cos(Math.toRadians(deg));
    }

    static float sinDeg(float deg) {
        return (float) Math.sin(Math.toRadians(deg));
    }

    /** Isi snapshot: proto = salinan state bola; geometri meja. */
    public void loadSnapshot(Ball[] snapshotBalls, Blocker[] snapshotBlockers, Hole[] snapshotHoles) {
        int n = snapshotBalls.length;
        this.proto = new Ball[n];
        this.balls = new Ball[n];
        for (int i = 0; i < n; i++) {
            this.proto[i] = new Ball();
            this.proto[i].copyFrom(snapshotBalls[i]);
            this.balls[i] = new Ball();
        }
        this.blockers = snapshotBlockers;
        this.holes = snapshotHoles;
        this.tBB = new float[n][n];
        this.tBL = new float[n][snapshotBlockers.length];
        this.tHole = new float[n][snapshotHoles.length];
        this.cBLx = new float[n][snapshotBlockers.length];
        this.cBLy = new float[n][snapshotBlockers.length];
    }

    public Ball protoBall(int i) {
        return proto[i];
    }

    /**
     * Majukan proto ke keadaan sesudah satu tembakan (untuk perencanaan
     * rantai run-out Mustahil): posisi = posisi berhenti hasil, bola
     * yang masuk menjadi non-aktif, semua kecepatan/spin dinolkan.
     */
    public void advanceProto(Result r) {
        for (int i = 0; i < proto.length; i++) {
            Ball p = proto[i];
            p.x = r.stopX[i];
            p.y = r.stopY[i];
            p.vx = 0.0f;
            p.vy = 0.0f;
            p.rvx = 0.0f;
            p.rvy = 0.0f;
            p.w = 0.0f;
            p.inHole = -1;
            boolean nowPotted = p.potted || r.potted[i];
            p.potted = nowPotted;
            p.avail = !nowPotted;
        }
    }

    /** Geser posisi bola pada proto (untuk pencarian titik freeball). */
    public void setProtoPosition(int i, float x, float y) {
        Ball b = proto[i];
        b.x = x;
        b.y = y;
        b.vx = 0.0f;
        b.vy = 0.0f;
        b.rvx = 0.0f;
        b.rvy = 0.0f;
        b.w = 0.0f;
        b.inHole = -1;
        b.potted = false;
        b.avail = true;
    }

    public int ballCount() {
        return proto == null ? 0 : proto.length;
    }

    /** Terapkan rumus click() asli ke salinan bola target. */
    public void applyClick(float strength, float spinX, float spinY, float angleDeg) {
        Ball cue = balls[clickTarget];
        float cos = cosDeg(angleDeg);
        float sin = sinDeg(angleDeg);
        cue.vx = strength * cos;
        cue.vy = strength * sin;
        float f5 = spinY * strength;
        cue.rvx = cos * f5;
        cue.rvy = f5 * sin;
        cue.w = ((-strength) * spinX) / ballRadius;
    }

    /**
     * Jalankan satu tembakan penuh pada salinan kerja (proto dipulihkan
     * lebih dulu). record=true merekam polyline tiap bola.
     */
    public Result simulate(float strength, float spinX, float spinY, float angleDeg, boolean record) {
        for (int i = 0; i < balls.length; i++) {
            balls[i].copyFrom(proto[i]);
        }
        // Bola yang SUDAH potted di snapshot (keadaan turunan rantai
        // run-out / evaluasi posisi) tidak boleh dilaporkan sebagai
        // "masuk oleh tembakan ini" — kalau tidak, saringan akan
        // menolaknya sebagai bola ilegal dan rantai Mustahil mustahil
        // terbentuk (bug terbukti di uji l).
        boolean[] wasPotted = new boolean[balls.length];
        for (int i = 0; i < balls.length; i++) {
            wasPotted[i] = balls[i].potted;
        }
        Result r = new Result(balls.length, record);
        applyClick(strength, spinX, spinY, angleDeg);
        if (record) {
            recordAll(r);
        }
        int steps = 0;
        while (steps < MAX_STEPS) {
            processRunning(STEP, r);
            boolean active = frictionStep(STEP);
            if (record) {
                recordAll(r);
            }
            steps++;
            if (!active) {
                break;
            }
        }
        r.steps = steps;
        for (int i = 0; i < balls.length; i++) {
            Ball b = balls[i];
            r.potted[i] = b.potted && !wasPotted[i];
            r.stopX[i] = b.x;
            r.stopY[i] = b.y;
            if (r.potted[i] && r.potHole[i] < 0) {
                r.potHole[i] = nearestHole(b.x, b.y);
            }
            if (record) {
                ArrayList<float[]> p = r.paths[i];
                if (p.size() == 0 || dist2(p.get(p.size() - 1), b.x, b.y) > 0.01f) {
                    p.add(new float[]{b.x, b.y});
                }
            }
        }
        r.cuePotted = r.potted[clickTarget];
        return r;
    }

    /**
     * Fitur D v1.4: seperti simulate(), tetapi sebelum tiap langkah
     * fisika 16 ms bola-bola target magnet menerima delta kecepatan
     * homing dari Magnet.steerDelta — cermin persis yang dilakukan
     * Features di perangkat satu kali per tick GameScene (sebelum
     * langkah fisika frame itu). targets==null = tanpa magnet.
     */
    public Result simulateMagnet(float strength, float spinX, float spinY, float angleDeg,
                                   boolean[] targets, boolean record) {
        for (int i = 0; i < balls.length; i++) {
            balls[i].copyFrom(proto[i]);
        }
        boolean[] wasPotted = new boolean[balls.length];
        for (int i = 0; i < balls.length; i++) {
            wasPotted[i] = balls[i].potted;
        }
        Result r = new Result(balls.length, record);
        applyClick(strength, spinX, spinY, angleDeg);
        if (record) {
            recordAll(r);
        }
        int steps = 0;
        while (steps < MAX_STEPS) {
            if (targets != null) {
                for (int i = 0; i < balls.length; i++) {
                    if (i < targets.length && targets[i]) {
                        float[] dv = Magnet.steerDelta(balls[i], holes, STEP);
                        if (dv != null) {
                            balls[i].vx += dv[0];
                            balls[i].vy += dv[1];
                        }
                    }
                }
            }
            processRunning(STEP, r);
            boolean active = frictionStep(STEP);
            if (record) {
                recordAll(r);
            }
            steps++;
            if (!active) {
                break;
            }
        }
        r.steps = steps;
        for (int i = 0; i < balls.length; i++) {
            Ball b = balls[i];
            r.potted[i] = b.potted && !wasPotted[i];
            r.stopX[i] = b.x;
            r.stopY[i] = b.y;
            if (r.potted[i] && r.potHole[i] < 0) {
                r.potHole[i] = nearestHole(b.x, b.y);
            }
        }
        r.cuePotted = r.potted[clickTarget];
        return r;
    }

    private int nearestHole(float x, float y) {
        int best = -1;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < holes.length; i++) {
            float dx = holes[i].x - x;
            float dy = holes[i].y - y;
            float d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    private static float dist2(float[] p, float x, float y) {
        float dx = p[0] - x;
        float dy = p[1] - y;
        return dx * dx + dy * dy;
    }

    private void recordAll(Result r) {
        for (int i = 0; i < balls.length; i++) {
            Ball b = balls[i];
            if (!b.avail && !b.potted) {
                continue;
            }
            ArrayList<float[]> p = r.paths[i];
            if (p.size() == 0 || dist2(p.get(p.size() - 1), b.x, b.y) > 0.5625f) {
                p.add(new float[]{b.x, b.y});
            }
        }
    }

    // ----------------------------------------------------------------
    // Porting PoolTable.processRunning
    // ----------------------------------------------------------------
    private void processRunning(float f, Result r) {
        hittype = -1;
        int guard = 0;
        while (true) {
            float nextHitTime = scanNextHit();
            if (nextHitTime >= f) {
                doRunning(f);
                return;
            }
            float f2 = ((int) (nextHitTime * 100000.0f)) / 100000.0f;
            if (f2 >= 1.0E-5d) {
                f -= f2;
                doRunning(f2);
            }
            processHit(r);
            if (++guard > MAX_EVENTS_PER_STEP) {
                return; // pengaman anti-gantung (asli: tanpa batas)
            }
        }
    }

    private void processHit(Result r) {
        if (hittype == 0) {
            doHitBall(balls[hita], balls[hitb]);
            if (r.firstContact < 0) {
                if (hita == clickTarget) {
                    r.firstContact = hitb;
                } else if (hitb == clickTarget) {
                    r.firstContact = hita;
                }
            }
        } else if (hittype == 1) {
            r.cushionHit[hita] = true;
            r.cushionHitCount[hita]++;
            if (r.firstContact < 0) {
                if (hita == clickTarget) {
                    r.cueRailFirst = true;
                }
            } else {
                r.railAfterContact = true;
                if (hita == clickTarget) {
                    r.cueRailAfterContact = true;
                }
            }
            doHitBlocker(balls[hita], blockers[hitb]);
        } else if (hittype == 2) {
            balls[hita].inHole = hitb;
        } else if (hittype == 3) {
            Ball b = balls[hita];
            Hole h = holes[b.inHole];
            r.potHole[hita] = b.inHole;
            r.potAfterRail[hita] = r.cushionHit[hita];
            b.x = h.x;
            b.y = h.y;
            b.avail = false;
            b.potted = true;
            b.inHole = -1;
        }
    }

    // ----------------------------------------------------------------
    // Porting PoolTable.getNextHitTime(boolean, float): matriks penuh
    // dihitung ulang lalu dipindai minimumnya dengan urutan yang sama.
    // ----------------------------------------------------------------
    private float scanNextHit() {
        int n = balls.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                tBB[i][j] = MAX_TIME;
            }
            for (int k = 0; k < blockers.length; k++) {
                tBL[i][k] = MAX_TIME;
            }
            for (int h = 0; h < holes.length; h++) {
                tHole[i][h] = MAX_TIME;
            }
        }
        float[] contact = new float[2];
        for (int i = 0; i < n; i++) {
            Ball bi = balls[i];
            if (!bi.avail) {
                continue;
            }
            for (int j = 0; j < i; j++) {
                Ball bj = balls[j];
                if (!bj.avail) {
                    continue;
                }
                float t = workout(bi.x - bj.x, bi.y - bj.y, bi.vx - bj.vx, bi.vy - bj.vy,
                        bi.scale + bj.scale);
                tBB[i][j] = t;
                tBB[j][i] = t;
            }
            for (int k = 0; k < blockers.length; k++) {
                if (!blockers[k].vis) {
                    continue;
                }
                tBL[i][k] = nextHitTimeBlocker(bi, blockers[k], contact);
                cBLx[i][k] = contact[0];
                cBLy[i][k] = contact[1];
            }
            if (cueHoleImmune && i == clickTarget) {
                // Fitur B: waktu tabrakan lubang untuk bola putih
                // selalu MAX_TIME (tidak pernah masuk zona lubang)
            } else if (bi.inHole >= 0) {
                tHole[i][0] = nextHitTimeHole(bi, holes[bi.inHole]);
            } else {
                for (int h = 0; h < holes.length; h++) {
                    if (!holes[h].vis) {
                        continue;
                    }
                    tHole[i][h] = nextHitTimeHole(bi, holes[h]);
                }
            }
        }
        float best = MAX_TIME;
        for (int i8 = 0; i8 < n; i8++) {
            for (int i9 = 0; i9 < i8; i9++) {
                float f3 = tBB[i8][i9];
                if (f3 < best) {
                    hittype = 0;
                    hita = i8;
                    hitb = i9;
                    best = f3;
                }
            }
            for (int i10 = 0; i10 < blockers.length; i10++) {
                float f4 = tBL[i8][i10];
                if (f4 < best) {
                    hittype = 1;
                    hita = i8;
                    hitb = i10;
                    hitx = cBLx[i8][i10];
                    hity = cBLy[i8][i10];
                    best = f4;
                }
            }
            if (balls[i8].inHole >= 0) {
                float f5 = tHole[i8][0];
                if (f5 < best) {
                    hittype = 3;
                    hita = i8;
                    hitb = 0;
                    best = f5;
                }
            } else {
                for (int i11 = 0; i11 < holes.length; i11++) {
                    float f6 = tHole[i8][i11];
                    if (f6 < best) {
                        hittype = 2;
                        hita = i8;
                        hitb = i11;
                        best = f6;
                    }
                }
            }
        }
        return best;
    }

    // Porting getNextHitTime(PoolBall, PoolHole)
    private float nextHitTimeHole(Ball b, Hole h) {
        float x = b.x - h.x;
        float y = b.y - h.y;
        float width = h.w * 0.5f;
        boolean isInThisHole = b.inHole >= 0 && holes[b.inHole] == h;
        if (!isInThisHole) {
            return workout(x, y, b.vx, b.vy, width);
        }
        if ((x * x) + (y * y) <= width * width * 4.0E-4f) {
            return 0.0f;
        }
        return workout(x, y, b.vx, b.vy, width * 0.02f);
    }

    // Porting getNextHitTime(PoolBall, PoolBlocker); contact = titik kena
    private float nextHitTimeBlocker(Ball b, Blocker bl, float[] contact) {
        float workout = workout(b.x - bl.x0, b.y - bl.y0, b.vx, b.vy, b.scale);
        float cx = bl.x0;
        float cy = bl.y0;
        float workout2 = workout(b.x - bl.x1, b.y - bl.y1, b.vx, b.vy, b.scale);
        if (workout2 < workout) {
            cx = bl.x1;
            cy = bl.y1;
            workout = workout2;
        }
        float f = bl.x1 - bl.x0;
        float f2 = bl.y1 - bl.y0;
        float sqrt = sqrtF((f * f) + (f2 * f2));
        float f3 = f / sqrt;
        float f4 = f2 / sqrt;
        float f5 = b.scale * f4;
        float f6 = (-f3) * b.scale;
        if ((b.vx * f5) + (b.vy * f6) < 0.0f) {
            f5 = -f5;
            f6 = -f6;
        }
        float f7 = b.x + f5;
        float f8 = b.y + f6;
        float workout3 = workout(bl.x0 - f7, bl.y0 - f8, f3, f4, b.vx, b.vy, sqrt);
        if (workout3 >= workout) {
            contact[0] = cx;
            contact[1] = cy;
            return workout;
        }
        contact[0] = f7 + (b.vx * workout3);
        contact[1] = f8 + (b.vy * workout3);
        return workout3;
    }

    // Porting workout 7-argumen (segmen garis)
    private static float workout(float f, float f2, float f3, float f4, float f5, float f6, float f7) {
        float f8 = (f4 * f5) - (f3 * f6);
        float f9 = (f * f4) - (f2 * f3);
        if (Math.abs(f8) < 0.001d) {
            return MAX_TIME;
        }
        float f10 = f9 / f8;
        if (f10 > 0.0f) {
            float f11 = (((-f) + (f5 * f10)) * f3) + (((-f2) + (f6 * f10)) * f4);
            if (0.0f <= f11 && f11 <= f7) {
                return f10;
            }
        }
        return MAX_TIME;
    }

    // Porting workout 5-argumen (lingkaran), termasuk gerbang isFirstShoot
    private float workout(float f, float f2, float f3, float f4, float f5) {
        float f6 = (f3 * f3) + (f4 * f4);
        float f7 = ((f3 * f) + (f4 * f2)) * (-2.0f);
        float f8 = ((f * f) + (f2 * f2)) - (f5 * f5);
        if (f6 < 1.0E-4f) {
            return MAX_TIME;
        }
        float f9 = (f7 * f7) - ((4.0f * f6) * f8);
        if (f9 <= 0.001f) {
            return MAX_TIME;
        }
        if (f8 <= 0.001f && f7 > 0.0f) {
            return 0.0f;
        }
        float sqrt = (f7 - sqrtF(f9)) / (f6 * 2.0f);
        if (this.firstShoot) {
            if (sqrt <= 0.001f) {
                return MAX_TIME;
            }
        } else if (sqrt < 1.0E-7d) {
            return MAX_TIME;
        }
        return sqrt;
    }

    // Porting doRunning (gerak lurus + gravitasi lubang)
    private void doRunning(float f) {
        for (int i = 0; i < balls.length; i++) {
            Ball b = balls[i];
            if (!b.avail) {
                continue;
            }
            if (b.inHole >= 0) {
                Hole hole = holes[b.inHole];
                float f2 = hole.x - b.x;
                float f3 = hole.y - b.y;
                float f4 = (f2 * f2) + (f3 * f3);
                float sqrt = sqrtF(f4);
                float f5 = f2 / sqrt;
                float f6 = f3 / sqrt;
                float holeGravity = 0.5f * f * this.holeGravity;
                float f7 = (b.vx * f5) + (b.vy * f6);
                if (f7 < 0.0f) {
                    b.vx -= f7 * f5;
                    b.vy -= f7 * f6;
                }
                float f8 = 1.0f - (5.0f * f);
                b.vx *= f8;
                b.vy *= f8;
                b.vx += f5 * holeGravity;
                b.vy += f6 * holeGravity;
                float f9 = ((b.vx * b.vx) + (b.vy * b.vy)) * f * f;
                if (f9 > f4) {
                    float f10 = f4 / f9;
                    b.vx *= f10;
                    b.vy *= f10;
                }
            }
            b.x += b.vx * f;
            b.y += b.vy * f;
        }
    }

    // Porting doHit(PoolBall, PoolBall) — java.lang.Math, verbatim
    private void doHitBall(Ball a, Ball b) {
        float f = b.x - a.x;
        float f2 = b.y - a.y;
        float f3 = (f * f) + (f2 * f2);
        double atan2 = (float) Math.atan2(a.vx, a.vy);
        float sin = (float) Math.sin(atan2);
        float cos = (float) Math.cos(atan2);
        float sqrt = (a.scale * 2.0f) - ((float) Math.sqrt(f3));
        if (sqrt > 0.0f) {
            a.x -= sin * sqrt;
            a.y -= sqrt * cos;
            f = b.x - a.x;
            f2 = b.y - a.y;
            f3 = (f * f) + (f2 * f2);
        }
        float sqrt2 = sqrtF(f3);
        float f4 = f / sqrt2;
        float f5 = f2 / sqrt2;
        float f6 = ((a.vx * f4) + (a.vy * f5)) - ((b.vx * f4) + (b.vy * f5));
        float f7 = f4 * f6;
        float f8 = f6 * f5;
        a.vx -= f7;
        a.vy -= f8;
        b.vx += f7;
        b.vy += f8;
    }

    // Porting doHit(PoolBall, PoolBlocker) — tanpa SoundManager
    private void doHitBlocker(Ball b, Blocker bl) {
        float f = b.x - hitx;
        float f2 = b.y - hity;
        float sqrt = sqrtF((f * f) + (f2 * f2));
        float f3 = f / sqrt;
        float f4 = f2 / sqrt;
        float f5 = (b.vx * f3) + (b.vy * f4);
        if (f5 < 0.0f) {
            float f6 = f5 * (-2.0f);
            b.vx += f3 * f6;
            b.vy += f6 * f4;
            float spin = b.w * this.ballRadius;
            if (Math.abs(spin) >= bl.force) {
                float f7 = bl.force;
                if (spin <= 0.0f) {
                    f7 = -f7;
                }
                b.vx -= f4 * f7;
                b.vy += f3 * f7;
                b.w -= (f7 * ROLL_K) / this.ballRadius;
            }
            b.vx *= BLOCK_HIT_LOST;
            b.vy *= BLOCK_HIT_LOST;
            b.w /= 2.0f;
            b.rvx = (b.rvx - b.vx) / 2.0f;
            b.rvy = (b.rvy - b.vy) / 2.0f;
        }
    }

    /**
     * Porting blok friksi PoolTable.updateRunning(16) — mengembalikan
     * flag "masih ada aktivitas" (z pada asli; asli memanggil
     * onRoundOver saat z==false). Bagian visual (quaternion, shadow)
     * dan klausul bola potted-masih-terlihat SENGAJA tidak diporting:
     * di klon, bola potted langsung nonaktif.
     */
    private boolean frictionStep(float dt) {
        boolean z = false;
        for (int i = 0; i < balls.length; i++) {
            Ball b = balls[i];
            if (!b.avail) {
                continue;
            }
            if (b.inHole >= 0) {
                z = true;
            }
            if (b.x < tableW * (-0.5d)) {
                b.x += tableW;
            }
            if (b.y < tableH * (-0.5d)) {
                b.y += tableH;
            }
            if (b.x > tableW * 0.5d) {
                b.x -= tableW;
            }
            if (b.y > tableH * 0.5d) {
                b.y -= tableH;
            }
            float f2 = b.rvx + b.vx;
            float f3 = b.rvy + b.vy;
            float sqrt2 = sqrtF((f2 * f2) + (f3 * f3));
            if (sqrt2 > force * ROLL_K * dt) {
                float fc = force / sqrt2;
                float f4 = f2 * fc * dt;
                b.vx -= f4;
                float f5 = f3 * fc * dt;
                b.vy -= f5;
                b.rvx -= f4 * ROLL_K;
                b.rvy -= f5 * ROLL_K;
                z = true;
            } else {
                b.rvx = -b.vx;
                b.rvy = -b.vy;
            }
            if (Math.abs(b.w) > force * ROLLW_FORCE_K * dt) {
                float fw = force * ROLLW_FORCE_K;
                if (b.w < 0.0f) {
                    b.w += fw * dt;
                } else {
                    b.w -= fw * dt;
                }
                z = true;
            } else {
                b.w = 0.0f;
            }
            float sqrt3 = sqrtF((b.vx * b.vx) + (b.vy * b.vy));
            if (sqrt3 > rollForce * dt) {
                float rf = rollForce / sqrt3;
                float f6 = b.vx * rf * dt;
                float f7 = b.vy * rf * dt;
                b.vx -= f6;
                b.vy -= f7;
                b.rvx += f6;
                b.rvy += f7;
                z = true;
            } else {
                b.rvx += b.vx;
                b.rvy += b.vy;
                b.vx = 0.0f;
                b.vy = 0.0f;
            }
        }
        return z;
    }
}
