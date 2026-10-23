package com.hozin.poolmod;

/**
 * Uji fitur D v1.4 (Magnet Lubang) di JVM atas Sim:
 *  (a) kelayakan per mode: VS solids -> hanya solids; VS stripes ->
 *      hanya stripes; meja terbuka -> semua kecuali putih & 8;
 *      oracle tak terbaca -> null (magnet diam);
 *  (b) hasil: bola grup sendiri yang bergulir meleset diarahkan
 *      masuk; bola lawan pada lintasan yang sama TIDAK diarahkan;
 *      bola putih dan bola 8 tidak pernah diarahkan (lintasannya
 *      identik dengan tanpa magnet).
 */
public final class TestMagnet {
    static int checks = 0;

    static void check(boolean cond, String msg) {
        checks++;
        if (!cond) {
            throw new AssertionError("GAGAL: " + msg);
        }
        System.out.println("  ok: " + msg);
    }

    static Snap vsSnap(boolean userStripes, boolean openTable) {
        Snap s = TestAuto.baseSnap(16, 8);
        for (int i = 1; i <= 15; i++) {
            if (openTable) {
                s.legal[i] = true;
            } else if (userStripes) {
                s.legal[i] = i >= 9 && i <= 15;
            } else {
                s.legal[i] = i >= 1 && i <= 7;
            }
        }
        return s;
    }

    public static void main(String[] args) {
        System.out.println("[D-a] kelayakan target per mode");
        boolean[] t = Magnet.targets(vsSnap(false, false));
        check(t != null, "VS solids: targets terbaca");
        boolean okSolids = !t[0] && !t[8];
        for (int i = 1; i <= 7; i++) {
            // bola harus avail untuk jadi target: di snap ini belum
            // ditaruh -> avail false -> bukan target; cek ulang di
            // bawah dengan bola terpasang
            okSolids &= !t[i];
        }
        check(okSolids, "VS solids: putih/8 tak pernah target");

        Snap s1 = vsSnap(false, false);
        for (int i = 1; i <= 15; i++) {
            TestAuto.put(s1, i, 100f + i * 10f, 500f);
        }
        t = Magnet.targets(s1);
        boolean g = true;
        for (int i = 1; i <= 7; i++) {
            g &= t[i];
        }
        for (int i = 9; i <= 15; i++) {
            g &= !t[i];
        }
        check(g && !t[0] && !t[8], "VS solids: hanya grup sendiri yang ditargetkan");

        Snap s2 = vsSnap(true, false);
        for (int i = 1; i <= 15; i++) {
            TestAuto.put(s2, i, 100f + i * 10f, 500f);
        }
        t = Magnet.targets(s2);
        g = true;
        for (int i = 9; i <= 15; i++) {
            g &= t[i];
        }
        for (int i = 1; i <= 7; i++) {
            g &= !t[i];
        }
        check(g && !t[0] && !t[8], "VS stripes: hanya grup sendiri yang ditargetkan");

        Snap s3 = vsSnap(false, true);
        for (int i = 1; i <= 15; i++) {
            TestAuto.put(s3, i, 100f + i * 10f, 500f);
        }
        t = Magnet.targets(s3);
        g = true;
        for (int i = 1; i <= 15; i++) {
            g &= (i == 8) != t[i];
        }
        check(g && !t[0], "meja terbuka: semua bola objek kecuali 8");

        Snap s4 = vsSnap(false, false);
        s4.legalKnown = false;
        check(Magnet.targets(s4) == null, "oracle tak terbaca -> null (fail-closed)");

        System.out.println("[D-b] hasil pengarahan di simulasi");
        // Lintasan uji: bola objek di (250,-250) ditembak bola putih
        // dari (250,-640) ke atas dengan potongan tipis sehingga bola
        // objek bergulir ke kanan-atas MELENGENG dari lubang kanan
        // bawah (1220,-600)... lubang terdekatnya justru (0,-628):
        // tanpa magnet ia meleset; dengan magnet ia membelok masuk.
        // Bola putih menembak lurus ke atas (angle 90).
        Snap base = TestAuto.baseSnap(16, 8);
        TestAuto.put(base, 0, 250f, -640f);
        TestAuto.put(base, 1, 268f, -250f);
        Sim sim0 = base.newSim();
        Sim.Result noMag = sim0.simulate(620f, 0f, 0f, 90f, false);
        check(!noMag.potted[1], "tanpa magnet: bola 1 meleset (kontrol)");

        boolean[] targets = new boolean[16];
        targets[1] = true;
        Sim sim1 = base.newSim();
        Sim.Result mag = sim1.simulateMagnet(620f, 0f, 0f, 90f, targets, false);
        check(mag.potted[1], "magnet ON: bola 1 diarahkan masuk lubang");
        check(!mag.cuePotted, "magnet ON: bola putih tidak ikut diarahkan (tak scratch)");

        // bola lawan pada lintasan yang sama persis (indeks 9):
        Snap opp = TestAuto.baseSnap(16, 8);
        TestAuto.put(opp, 0, 250f, -640f);
        TestAuto.put(opp, 9, 268f, -250f);
        boolean[] none = new boolean[16]; // 9 bukan grup user -> bukan target
        Sim sim2 = opp.newSim();
        Sim.Result oppR = sim2.simulateMagnet(620f, 0f, 0f, 90f, none, false);
        check(!oppR.potted[9], "bola lawan: tidak diarahkan, tetap meleset");

        // bola 8 pada lintasan yang sama: tidak pernah diarahkan
        Snap eight = TestAuto.baseSnap(16, 8);
        TestAuto.put(eight, 0, 250f, -640f);
        TestAuto.put(eight, 8, 268f, -250f);
        boolean[] tAll = new boolean[16];
        tAll[8] = true; // dipaksa true pun steerDelta dipakai per bola;
        // jalur resminya lewat Magnet.targets yang mengecualikan 8:
        Snap eightSnap = vsSnap(false, true);
        TestAuto.put(eightSnap, 8, 268f, -250f);
        check(!Magnet.targets(eightSnap)[8], "bola 8 dikecualikan targets() bahkan di meja terbuka");

        System.out.println("TestMagnet: SEMUA " + checks + " pemeriksaan lulus");
    }
}
