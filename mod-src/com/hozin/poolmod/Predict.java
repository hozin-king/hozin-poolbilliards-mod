package com.hozin.poolmod;

import java.util.ArrayList;

/**
 * Pengelola gambar garis prediksi & long line di DALAM scene game.
 *
 * Garis adalah LineSprite asli game (com.fruitsmobile.basket.sprites.
 * LineSprite) yang dikumpulkan dalam satu SimpleContainer anak
 * PoolTable — jadi koordinatnya satuan meja (sama seperti bola), ikut
 * transformasi & scissor jendela meja, dan tergambar di atas bola
 * (urutan anak terakhir). Tidak ada overlay Android untuk garis.
 *
 * Long line memakai PoolTable.calcAimTarget(angle) yang sudah public:
 * garis lurus dari bola putih ke titik kontak pertama persis hitungan
 * game sendiri. Prediksi penuh menggambar polyline hasil klon Sim per
 * bola dengan warna bola itu + tanda X di titik berhenti.
 */
public final class Predict {
    private static final int POOL = 896;
    private static final int MAXSEG_PER_BALL = 52;
    private static final Class<?>[] SIG_FFFF = {float.class, float.class, float.class, float.class};
    private static final Class<?>[] SIG_Z = {boolean.class};
    private static final Class<?>[] SIG_F = {float.class};

    // warna bola standar (r,g,b) per id; 9-15 = strip, warna sama dgn 1-7
    private static final float[][] BALL_COLORS = {
            {1.00f, 1.00f, 1.00f}, // 0 putih (cue)
            {1.00f, 0.84f, 0.00f}, // 1 kuning
            {0.16f, 0.36f, 1.00f}, // 2 biru
            {1.00f, 0.16f, 0.20f}, // 3 merah
            {0.62f, 0.32f, 0.95f}, // 4 ungu
            {1.00f, 0.46f, 0.06f}, // 5 oranye
            {0.10f, 0.76f, 0.36f}, // 6 hijau
            {0.66f, 0.16f, 0.20f}, // 7 marun
            {0.22f, 0.22f, 0.25f}, // 8 hitam
            {1.00f, 0.84f, 0.00f}, {0.16f, 0.36f, 1.00f}, {1.00f, 0.16f, 0.20f},
            {0.62f, 0.32f, 0.95f}, {1.00f, 0.46f, 0.06f}, {0.10f, 0.76f, 0.36f},
            {0.66f, 0.16f, 0.20f},
    };

    private static Object container;      // SimpleContainer anak PoolTable
    private static Object[] lines;        // pool LineSprite
    private static int used = 0;          // sprite yang sedang terlihat
    private static Object attachedTable;  // table tempat container menempel

    // diagnostik runtime (dibaca Hooks -> ModState.predictDiag -> menu)
    public static volatile boolean poolReady;
    public static volatile boolean containerAttached;
    public static volatile boolean reattachedOnce;
    public static volatile int lastSegCount;

    private Predict() {
    }

    /**
     * Penegakan kehadiran per tick (dipanggil Hooks sebelum cek kunci
     * gambar): bila game menghapus container dari table (reset/setRule),
     * pasang ulang SEKARANG — jangan menunggu bidik berubah, karena
     * kunci cache gambar bisa menekan gambar ulang tanpa batas.
     * Murah: iterasi <= kapasitas anak table.
     */
    public static void ensureAttached(Object table) {
        if (lines == null || container == null) {
            return;
        }
        try {
            if (!isPresent(table)) {
                Ref.invoke(table, "addChild",
                        new Class<?>[]{Ref.cls("com.fruitsmobile.basket.DrawableObject")}, container);
                if (attachedTable == table) {
                    reattachedOnce = true;
                }
                attachedTable = table;
                containerAttached = isPresent(table);
                poolReady = containerAttached;
            } else {
                containerAttached = true;
                poolReady = true;
            }
        } catch (Throwable ignored) {
        }
    }

    /** Apakah container saat ini benar-benar anak dari table ini? */
    private static boolean isPresent(Object table) {
        try {
            int count = Ref.invokeI(table, "getCount", new Class<?>[0]);
            for (int i = 0; i < count; i++) {
                Object child = Ref.invoke(table, "getChild", new Class<?>[]{int.class}, i);
                if (child == container) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean ensurePool(Object table) {
        try {
            if (lines == null) {
                Class<?> lineCls = Ref.cls("com.fruitsmobile.basket.sprites.LineSprite");
                Class<?> contCls = Ref.cls("com.fruitsmobile.basket.SimpleContainer");
                if (lineCls == null || contCls == null) {
                    poolReady = false;
                    return false;
                }
                container = Ref.newInstanceInt(contCls, POOL + 8);
                if (container == null) {
                    poolReady = false;
                    return false;
                }
                lines = new Object[POOL];
                for (int i = 0; i < POOL; i++) {
                    lines[i] = Ref.newInstance(lineCls);
                    if (lines[i] == null) {
                        lines = null;
                        container = null;
                        poolReady = false;
                        return false;
                    }
                    Ref.invoke(lines[i], "setVisible", SIG_Z, false);
                    Ref.invoke(container, "addChild",
                            new Class<?>[]{Ref.cls("com.fruitsmobile.basket.DrawableObject")}, lines[i]);
                }
                attachedTable = null; // paksa pemeriksaan kehadiran di bawah
            }
            // PERBAIKAN v1.2 — akar garis hilang di HP: game MEMBANGUN
            // ULANG anak-anak PoolTable pada tiap pergantian game/ronde
            // (GameScene.setRule/showGameEnd -> PoolTable.reset() ->
            // ContainerBase.clear(), arrUsed=0). Container mod ikut
            // terhapus dari instance table yang SAMA, dan pemeriksaan
            // lama hanya berjalan saat instance table berganti — jadi
            // garis mati permanen tanpa gejala lain. Sekarang kehadiran
            // container diverifikasi pada SETIAP panggilan gambar dan
            // dipasang ulang bila hilang.
            if (!isPresent(table)) {
                Ref.invoke(table, "addChild",
                        new Class<?>[]{Ref.cls("com.fruitsmobile.basket.DrawableObject")}, container);
                if (attachedTable == table) {
                    reattachedOnce = true; // terhapus oleh reset game
                }
                attachedTable = table;
                containerAttached = isPresent(table);
            } else {
                attachedTable = table;
                containerAttached = true;
            }
            poolReady = containerAttached;
            return poolReady;
        } catch (Throwable t) {
            poolReady = false;
            return false;
        }
    }

    private static void setSeg(int idx, float x0, float y0, float x1, float y1,
                               float r, float g, float b, float a) {
        Object s = lines[idx];
        Ref.invoke(s, "setColor", SIG_FFFF, r, g, b, a);
        Ref.invoke(s, "setLinePosition", SIG_FFFF, x0, y0, x1, y1);
        Ref.invoke(s, "setVisible", SIG_Z, true);
    }

    public static void hideAll() {
        if (lines == null) {
            return;
        }
        for (int i = 0; i < used; i++) {
            Ref.invoke(lines[i], "setVisible", SIG_Z, false);
        }
        used = 0;
    }

    private static void finishFrom(int newUsed) {
        for (int i = newUsed; i < used; i++) {
            Ref.invoke(lines[i], "setVisible", SIG_Z, false);
        }
        used = newUsed;
    }

    /**
     * Gambar long line saja: segmen lurus bola putih -> kontak pertama
     * memakai PoolTable.calcAimTarget (hitungan game sendiri).
     */
    public static void drawLongLine(Object table, Object rule, Object roundstate,
                                    Snap snap, float angleDeg) {
        if (!ensurePool(table)) {
            return;
        }
        Object distObj = Ref.invoke(table, "calcAimTarget", SIG_F, angleDeg);
        if (!(distObj instanceof Number)) {
            hideAll();
            return;
        }
        float dist = ((Number) distObj).floatValue();
        Sim.Ball cue = snap.balls[snap.clickTarget];
        float ex = cue.x + Sim.cosDeg(angleDeg) * dist;
        float ey = cue.y + Sim.sinDeg(angleDeg) * dist;
        setSeg(0, cue.x, cue.y, ex, ey, 1.0f, 1.0f, 1.0f, 0.9f);
        finishFrom(1);
        lastSegCount = 1;
    }

    /** Gambar prediksi penuh dari hasil klon Sim. */
    public static void drawPrediction(Object table, Snap snap, Sim.Result r) {
        if (!ensurePool(table)) {
            return;
        }
        int idx = 0;
        int n = snap.balls.length;
        for (int i = 0; i < n && idx < POOL - 4; i++) {
            ArrayList<float[]> pts = r.paths[i];
            if (pts == null || pts.size() < 2) {
                continue;
            }
            float[] col = BALL_COLORS[i % BALL_COLORS.length];
            float alpha = i == snap.clickTarget ? 0.95f : 0.85f;
            int segs = pts.size() - 1;
            int stride = 1;
            if (segs > MAXSEG_PER_BALL) {
                stride = (segs + MAXSEG_PER_BALL - 1) / MAXSEG_PER_BALL;
            }
            int prev = 0;
            for (int k = stride; k < pts.size() && idx < POOL - 4; k += stride) {
                float[] a = pts.get(prev);
                float[] b = pts.get(k);
                setSeg(idx++, a[0], a[1], b[0], b[1], col[0], col[1], col[2], alpha);
                prev = k;
            }
            if (prev != pts.size() - 1 && idx < POOL - 4) {
                float[] a = pts.get(prev);
                float[] b = pts.get(pts.size() - 1);
                setSeg(idx++, a[0], a[1], b[0], b[1], col[0], col[1], col[2], alpha);
            }
            // tanda X titik berhenti
            float[] last = pts.get(pts.size() - 1);
            float s = snap.ballRadius * 0.8f;
            if (idx < POOL - 2) {
                setSeg(idx++, last[0] - s, last[1] - s, last[0] + s, last[1] + s,
                        col[0], col[1], col[2], alpha);
                setSeg(idx++, last[0] - s, last[1] + s, last[0] + s, last[1] - s,
                        col[0], col[1], col[2], alpha);
            }
        }
        finishFrom(idx);
        lastSegCount = idx;
    }
}
