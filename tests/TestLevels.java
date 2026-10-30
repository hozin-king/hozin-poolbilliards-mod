package com.hozin.poolmod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Verifikasi independen 240 level baru dari BERKAS FINAL di
 * mod-assets/data (bukan dari generator): struktur baris sah
 * (rumus jumlah int, jarak bola, rentang koordinat ala bawaan)
 * dan setiap level terbukti TAMAT oleh klon Sim + perencana
 * (aturan restTime LevelRule asli) lewat GenLevels.verifyLevel.
 */
public final class TestLevels {
    static int checks = 0;

    static void check(boolean cond, String msg) {
        checks++;
        if (!cond) {
            throw new AssertionError("GAGAL: " + msg);
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args.length > 0 ? args[0] : "mod-assets/data");
        int totalLevels = 0;
        for (int stage = 7; stage <= 14; stage++) {
            Path f = dir.resolve("level_stage_" + stage + ".txt");
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            check(lines.size() == 30, f.getFileName() + ": tepat 30 level");
            for (int li = 0; li < lines.size(); li++) {
                int[] v = GenLevels.parse(lines.get(li));
                String tag = "stage " + (stage + 1) + " level " + (li + 1);
                int bc = v[0];
                int bhc = v[1];
                check(v.length == 2 + bhc + 3 * bc, tag + ": rumus panjang baris");
                check(bc >= 12 && bc <= 36, tag + ": jumlah bola dalam batas mesin");
                check(bhc >= 0 && bhc <= 3, tag + ": black hole wajar");
                for (int k = 0; k < bhc; k++) {
                    check(v[2 + k] >= 0 && v[2 + k] <= 5, tag + ": indeks black hole sah");
                }
                // jarak antar bola & rentang koordinat
                for (int i = 0; i < bc; i++) {
                    int x = v[2 + bhc + i * 3 + 1];
                    int y = v[2 + bhc + i * 3 + 2];
                    check(Math.abs(x) <= 1230 && Math.abs(y) <= 660,
                            tag + ": koordinat bola " + i + " dalam meja");
                    for (int j = i + 1; j < bc; j++) {
                        int x2 = v[2 + bhc + j * 3 + 1];
                        int y2 = v[2 + bhc + j * 3 + 2];
                        int dx = x - x2;
                        int dy = y - y2;
                        check(dx * dx + dy * dy >= 95 * 95,
                                tag + ": bola " + i + "/" + j + " tidak bertumpuk");
                    }
                }
                int[] stats = new int[3];
                boolean win = GenLevels.verifyLevel(v, stats);
                check(win, tag + ": terbukti tamat oleh perencana");
                System.out.println("  ok: " + tag + " (bola=" + bc + ", blackHole=" + bhc
                        + ", tembakan=" + stats[0] + ", sisaCue=" + stats[1] + ")");
                totalLevels++;
            }
        }
        check(totalLevels == 240, "total 240 level baru");
        System.out.println("TestLevels: SEMUA " + checks + " pemeriksaan lulus (240 level)");
    }
}
