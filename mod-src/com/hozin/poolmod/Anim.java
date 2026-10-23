package com.hozin.poolmod;

/**
 * Animasi eksekusi tembakan (Perintah 1) — BUKAN teleport.
 *
 * Yang digerakkan adalah STIK ASLI GAME lewat state bidik resmi
 * GameScene, terbukti dari smali game:
 *  - GameScene.calcAimTarget() memanggil stick.setPosition(posisi
 *    bola putih di layar) + stick.setAngle(aimAngle) — dan
 *    setAimAngle() memanggil calcAimTarget().
 *  - Update per frame GameScene menghitung ulang
 *    stick.distance = (ballRadius + aimStrength * 0,1) * table.scalex
 *    untuk stick & stickShadow; PoolStick.commitTo merender stik
 *    sebagai translate(posisi) -> rotasi(180-angle) ->
 *    translate(distance, 0): distance adalah jarak tarik stik dari
 *    bola. Jadi tarikan stik adalah fungsi murni aimStrength.
 *  - GameScene.shoot(false) — jalur yang sama dengan lepasnya
 *    StrengthBar pemain — memanggil
 *    table.click(aimStrength, aimPx, aimPy, aimAngle) TEPAT SATU KALI
 *    lalu me-reset ketiga field ke 0.
 *  - Spin: GameScene$6 (handler bola spin game) menulis
 *    aimPx/aimPy (dijepit ke lingkaran satuan) lalu
 *    bigPPosition.setPosition(aimPx*100, aimPy*100) — titik merah.
 *
 * Fase siklus (digerakkan Hooks per tick thread game):
 *  PLACE  (khusus free ball) geser bola putih ke titik terpilih
 *         dengan interpolasi ±0,35 dtk menulis field x/y bola yang
 *         sama dengan yang ditulis drag manual (GameScene$8);
 *         di akhir: canPut dicek ulang, lalu table.putFreeball()
 *         (konfirmasi resmi game, state 3 -> 1).
 *  SPIN   tulis aimPx/aimPy + titik merah (instan; jeda ±0,2 dtk).
 *  ROTATE interpolasi aimAngle (jalur sudut terpendek) ±0,3 dtk
 *         lewat setAimAngle() — stik berputar halus, garis bidik &
 *         prediksi mod mengikuti.
 *  PULL   naikkan aimStrength 0 -> target selama 0,5–0,8 dtk
 *         (proporsional kekuatan) — stik tertarik mundur oleh
 *         perhitungan distance game sendiri.
 *  STRIKE turunkan aimStrength ke ±12% selama ±0,08 dtk — stik
 *         menghentak maju ke bola.
 *  FIRE   tulis ulang keempat field PERSIS nilai terpilih lalu
 *         panggil scene.shoot(false) satu kali (click terjadi di
 *         dalam shoot dengan nilai state itu juga).
 *  SETTLE tunggu meja berhenti (dua probe posisi sama) + jeda
 *         natural hingga ±0,7–1,2 dtk sesudah tembakan; siklus
 *         selesai. Saklar Auto Play boleh dimatikan di tengah
 *         siklus: tembakan yang sedang berjalan TETAP diselesaikan,
 *         siklus baru tidak dimulai (dijaga Hooks).
 *
 * Sebelum FIRE semua gerbang diperiksa ulang (PvP segar, giliran
 * manusia, state, posisi meja tidak berubah sejak pencarian) —
 * gagal = siklus batal tanpa menembak.
 */
public final class Anim {
    static final int IDLE = 0;
    static final int PLACE = 1;
    static final int SPIN = 2;
    static final int ROTATE = 3;
    static final int PULL = 4;
    static final int STRIKE = 5;
    static final int SETTLE = 6;

    private static final Class<?>[] SIG_F = {float.class};
    private static final Class<?>[] SIG_Z = {boolean.class};

    static int phase = IDLE;
    private static Auto.Shot shot;
    private static Snap snap;
    private static long phaseStartMs;
    private static float fromAngle;
    private static float fromX, fromY;
    private static long fireMs;
    private static long settleProbeKey = Long.MIN_VALUE;
    private static long settleProbeMs;
    private static boolean placeInit;

    private Anim() {
    }

    public static boolean isRunning() {
        return phase != IDLE;
    }

    /** Mulai satu siklus untuk tembakan terpilih. Dipanggil Hooks. */
    public static void begin(Auto.Shot s, Snap sn) {
        shot = s;
        snap = sn;
        phaseStartMs = System.currentTimeMillis();
        phase = (s != null && s.freeball) ? PLACE : SPIN;
        ModState.animBusy = true;
    }

    public static void cancel() {
        phase = IDLE;
        shot = null;
        snap = null;
        placeInit = false;
        ModState.animBusy = false;
    }

    private static void done() {
        cancel();
    }

    /** Dipanggil tiap tick GameScene dari Hooks (sesudah gerbang PvP). */
    public static void tick(Object scene, Object table, Object rule, Object roundstate) {
        if (phase == IDLE || shot == null) {
            return;
        }
        try {
            step(scene, table, rule, roundstate);
        } catch (Throwable t) {
            cancel(); // animasi tidak boleh menjatuhkan game / macet
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Interpolasi sudut jalur terpendek (derajat). */
    private static float lerpAngle(float a, float b, float t) {
        float d = (b - a) % 360.0f;
        if (d > 180.0f) {
            d -= 360.0f;
        } else if (d < -180.0f) {
            d += 360.0f;
        }
        return a + d * t;
    }

    private static void step(Object scene, Object table, Object rule, Object roundstate) {
        long t = now();
        int state = Ref.getI(roundstate, "state");
        switch (phase) {
            case PLACE: {
                if (state != 3) {
                    cancel();
                    return;
                }
                Object[] ballsArr = (Object[]) Ref.get(table, "balls");
                if (ballsArr == null || snap == null || snap.clickTarget >= ballsArr.length) {
                    cancel();
                    return;
                }
                Object cue = ballsArr[snap.clickTarget];
                if (cue == null) {
                    cancel();
                    return;
                }
                if (!placeInit) {
                    fromX = Ref.getF(cue, "x");
                    fromY = Ref.getF(cue, "y");
                    placeInit = true;
                }
                float k = Math.min(1.0f, (t - phaseStartMs) / 350.0f);
                float nx = lerp(fromX, shot.spotX, k);
                float ny = lerp(fromY, shot.spotY, k);
                Ref.setF(cue, "x", nx);
                Ref.setF(cue, "y", ny);
                if (k >= 1.0f) {
                    // posisi akhir WAJIB lolos canPut lagi sebelum lanjut
                    if (snap == null || !snap.canPut(shot.spotX, shot.spotY)) {
                        cancel();
                        return;
                    }
                    Ref.setF(cue, "x", shot.spotX);
                    Ref.setF(cue, "y", shot.spotY);
                    Ref.invoke(table, "putFreeball", new Class<?>[0]);
                    placeInit = false;
                    toPhase(SPIN);
                }
                return;
            }
            case SPIN: {
                if (state != 1) {
                    // free ball: putFreeball baru saja mengubah state ke 1;
                    // state lain berarti siklus tidak valid lagi
                    if (!shot.freeball) {
                        cancel();
                        return;
                    }
                }
                // tulis spin PERSIS seperti handler bola spin game:
                // field aimPx/aimPy + titik merah di (px*100, py*100)
                float px = shot.spinX;
                float py = shot.spinY;
                float rr = (float) Math.sqrt(px * px + py * py);
                if (rr > 1.0f) {
                    px /= rr;
                    py /= rr;
                }
                Ref.setF(scene, "aimPx", px);
                Ref.setF(scene, "aimPy", py);
                Object dot = Ref.get(scene, "bigPPosition");
                if (dot != null) {
                    Ref.invoke(dot, "setPosition",
                            new Class<?>[]{float.class, float.class}, px * 100.0f, py * 100.0f);
                }
                if (t - phaseStartMs >= 200L) {
                    fromAngle = Ref.invokeF(scene, "getAimAngle");
                    toPhase(ROTATE);
                }
                return;
            }
            case ROTATE: {
                float k = Math.min(1.0f, (t - phaseStartMs) / 300.0f);
                float ang = lerpAngle(fromAngle, shot.angle, easeOut(k));
                Ref.invoke(scene, "setAimAngle", SIG_F, ang);
                if (k >= 1.0f) {
                    Ref.invoke(scene, "setAimAngle", SIG_F, shot.angle);
                    toPhase(PULL);
                }
                return;
            }
            case PULL: {
                float frac = shot.strength / Math.max(1.0f, shotStrengthMax(scene, rule));
                long dur = (long) (500.0f + 300.0f * Math.min(1.0f, frac));
                float k = Math.min(1.0f, (t - phaseStartMs) / (float) dur);
                Ref.setF(scene, "aimStrength", lerp(0.0f, shot.strength, easeOut(k)));
                if (k >= 1.0f) {
                    toPhase(STRIKE);
                }
                return;
            }
            case STRIKE: {
                float k = Math.min(1.0f, (t - phaseStartMs) / 80.0f);
                Ref.setF(scene, "aimStrength", lerp(shot.strength, shot.strength * 0.12f, k));
                if (k >= 1.0f) {
                    fire(scene, table, rule, roundstate);
                }
                return;
            }
            case SETTLE: {
                if (t - fireMs < 700L) {
                    return;
                }
                Snap probe = Snap.capture(table, rule, roundstate);
                if (probe == null) {
                    if (t - fireMs > 4000L) {
                        done();
                    }
                    return;
                }
                long key = probe.positionKey();
                if (key == settleProbeKey && t - settleProbeMs >= 250L) {
                    // meja diam pada dua probe; lengkapi jeda natural
                    if (t - fireMs >= 900L) {
                        done();
                    }
                    return;
                }
                if (key != settleProbeKey) {
                    settleProbeKey = key;
                    settleProbeMs = t;
                }
                if (t - fireMs > 6000L) {
                    done(); // pengaman anti-macet
                }
                return;
            }
            default:
                cancel();
        }
    }

    private static float easeOut(float k) {
        return 1.0f - (1.0f - k) * (1.0f - k);
    }

    private static void toPhase(int p) {
        phase = p;
        phaseStartMs = now();
    }

    private static float shotStrengthMax(Object scene, Object rule) {
        float m = Ref.invokeF(rule, "getMaxStrength", 4000.0f);
        return m > 0.0f ? m : 4000.0f;
    }

    /**
     * Momen kontak: periksa ulang semua gerbang, tulis nilai terpilih
     * persis ke state scene, lalu panggil jalur resmi game
     * scene.shoot(false) — click() terjadi tepat satu kali di dalamnya
     * dengan nilai state tersebut.
     */
    private static void fire(Object scene, Object table, Object rule, Object roundstate) {
        // GERBANG PvP satu jalur di momen eksekusi: latch Hooks
        if (ModState.pvpLatched) {
            cancel();
            return;
        }
        int state = Ref.getI(roundstate, "state");
        if (state != 1) {
            cancel();
            return;
        }
        int current = Ref.getI(roundstate, "currentPlayer");
        if (Ref.invokeI(rule, "getPlayerType", new Class<?>[]{int.class}, current) != 0) {
            cancel();
            return;
        }
        if (!shot.freeball && snap != null && !snap.stillMatches(table)) {
            cancel(); // meja berubah sejak pencarian — jangan tembak buta
            return;
        }
        Ref.setF(scene, "aimAngle", shot.angle);
        Ref.setF(scene, "aimStrength", shot.strength);
        Ref.setF(scene, "aimPx", shot.spinX);
        Ref.setF(scene, "aimPy", shot.spinY);
        Ref.invoke(scene, "calcAimTarget", new Class<?>[0]);
        Predict.hideAll();
        Ref.invoke(scene, "shoot", SIG_Z, false);
        fireMs = now();
        settleProbeKey = Long.MIN_VALUE;
        toPhase(SETTLE);
    }
}
