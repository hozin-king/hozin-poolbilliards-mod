package com.hozin.poolmod;

/**
 * Fitur D v1.4 — "Bola Ngejar Lubang" (magnet lubang), logika murni.
 *
 * Saat bola bergulir sesudah tembakan USER, bola objek yang layak
 * mendapat dorongan arah (homing) ke lubang terdekat tiap langkah
 * fisika 16 ms. Kelayakan dikunci dari state aturan game lewat
 * oracle yang sama dengan Auto Play (Snap.legal dari
 * PoolRule.isAvailableTarget, dibaca SEGAR tiap giliran oleh
 * Features):
 *
 *  - bola putih (clickTarget): TIDAK PERNAH target, dalam mode apa
 *    pun, tanpa kecuali;
 *  - bola penentu (moneyBall: 8 di Vsai8Ball, 9 di Vsai9Ball):
 *    TIDAK PERNAH target, bahkan ketika aturan menandainya legal
 *    (grup sudah habis) — mod tidak meng-homing bola penenang;
 *  - mode berkelompok (Vsai8Ball): oracle hanya menandai grup milik
 *    pemain yang giliran (user) sesudah grup terbentuk, dan semua
 *    bola non-cue sebelumnya (meja terbuka) — persis state aturan;
 *    bola lawan tidak pernah legal -> tidak pernah diarahkan;
 *  - mode tanpa grup (Arcade/Time/Tutorial): oracle menandai semua
 *    bola non-cue -> semuanya target;
 *  - oracle tidak terbaca (legalKnown=false) -> targets() null:
 *    magnet DIAM (fail-closed); Features menulis alasannya ke
 *    readout ("grup belum terbaca").
 *
 * Kelas ini murni (tanpa Android/game) — dipakai Features di
 * perangkat dan TestMagnet di host atas Sim yang sama.
 */
public final class Magnet {
    /** Percepatan homing (satuan meja/detik^2) — sebanding gravitasi
     *  lubang game (3600), cukup untuk membelokkan bola bergulir
     *  tanpa terasa seperti teleport. */
    public static final float ACCEL = 2400.0f;
    /** Bola di bawah kecepatan ini dianggap berhenti: tidak didorong
     *  (magnet hanya bekerja SAAT BERGULIR). */
    public static final float MIN_SPEED = 40.0f;

    private Magnet() {
    }

    /**
     * Peta target magnet dari snapshot. null = oracle tidak terbaca
     * (magnet wajib diam). Bola yang sudah tidak tersedia (avail
     * false) tidak pernah target.
     */
    public static boolean[] targets(Snap s) {
        if (s == null || !s.legalKnown || s.legal == null || s.balls == null) {
            return null;
        }
        boolean[] t = new boolean[s.balls.length];
        for (int i = 0; i < t.length; i++) {
            if (i == s.clickTarget) {
                continue; // bola putih: tidak pernah
            }
            if (s.moneyBall > 0 && i == s.moneyBall) {
                continue; // bola penentu: tidak pernah
            }
            t[i] = s.balls[i].avail && s.legal[i];
        }
        return t;
    }

    /**
     * Delta kecepatan homing untuk satu bola selama dt detik menuju
     * lubang terdekat. Mengembalikan {dvx, dvy} atau null bila bola
     * tidak perlu diarahkan (berhenti / tidak ada lubang terlihat).
     * Bola yang sudah di dalam zona lubang (inHole) tidak diarahkan —
     * gravitasi lubang game mengambil alih di sana.
     */
    public static float[] steerDelta(Sim.Ball b, Sim.Hole[] holes, float dt) {
        if (b == null || !b.avail || b.potted || b.inHole >= 0 || holes == null) {
            return null;
        }
        float sp2 = b.vx * b.vx + b.vy * b.vy;
        if (sp2 < MIN_SPEED * MIN_SPEED) {
            return null;
        }
        int best = -1;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < holes.length; i++) {
            if (!holes[i].vis) {
                continue;
            }
            float dx = holes[i].x - b.x;
            float dy = holes[i].y - b.y;
            float d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        if (best < 0 || bd <= 1.0f) {
            return null;
        }
        float dist = (float) Math.sqrt(bd);
        float a = ACCEL * dt;
        return new float[]{(holes[best].x - b.x) / dist * a, (holes[best].y - b.y) / dist * a};
    }
}
