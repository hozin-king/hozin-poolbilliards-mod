package com.hozin.poolmod;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generator + verifikator level Arcade baru (Stage 8-15 =
 * level_stage_7..14.txt) untuk Pool Mod v1.4/v1.5.0.
 *
 * Setiap kandidat layout diverifikasi BISA TAMAT oleh klon Sim +
 * perencana Auto yang sama dengan yang dipakai Auto Play di HP
 * (DIFF_EXPERT = default), dengan aturan LevelRule asli:
 * restTime mulai 6; tembakan tanpa pot -1; scratch -2 (+ timpa
 * bola putih); tanpa kontak pertama -2; bola ke black hole -2
 * per bola; menang = semua bola objek habis saat restTime > 0.
 * Hanya level yang lulus verifikasi yang ditulis ke mod-assets.
 */
public final class GenLevels {

    // ---- parse & bangun Snap dari satu baris level ----------------
    static int[] parse(String line) {
        String[] p = line.trim().split("\\s+");
        int[] v = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            v[i] = Integer.parseInt(p[i]);
        }
        return v;
    }

    static Snap snapFromLine(int[] v) {
        Snap s = new Snap();
        s.ballRadius = TestSim.R;
        s.tableW = 2810f;
        s.tableH = 1530f;
        s.force = 1500f;
        s.rollForce = 100f;
        s.holeGravity = 3600f;
        s.minStrength = 200f;
        s.maxStrength = 4000f;
        s.arcade = true;
        s.legalKnown = true;
        s.moneyBall = -1;
        s.clickTarget = 0;
        int bc = v[0];
        int bhc = v[1];
        s.holes = new Sim.Hole[6];
        for (int h = 0; h < 6; h++) {
            Sim.Hole hole = new Sim.Hole();
            hole.x = TestSim.HOLES[h * 3];
            hole.y = TestSim.HOLES[h * 3 + 1];
            hole.w = TestSim.HOLES[h * 3 + 2] * 2.0f;
            hole.effect = 0;
            hole.vis = true;
            s.holes[h] = hole;
        }
        for (int k = 0; k < bhc; k++) {
            s.holes[v[2 + k]].effect = 1;
        }
        s.blockers = new Sim.Blocker[TestSim.BLOCKERS.length / 4];
        for (int i = 0; i < s.blockers.length; i++) {
            Sim.Blocker b = new Sim.Blocker();
            b.x0 = TestSim.BLOCKERS[i * 4];
            b.y0 = TestSim.BLOCKERS[i * 4 + 1];
            b.x1 = TestSim.BLOCKERS[i * 4 + 2];
            b.y1 = TestSim.BLOCKERS[i * 4 + 3];
            b.force = 1200.0f;
            b.vis = true;
            s.blockers[i] = b;
        }
        s.balls = new Sim.Ball[bc];
        s.legal = new boolean[bc];
        for (int i = 0; i < bc; i++) {
            Sim.Ball b = new Sim.Ball();
            // tekstur hanya di berkas level; fisika tidak memakainya
            b.x = v[2 + bhc + i * 3 + 1];
            b.y = v[2 + bhc + i * 3 + 2];
            b.avail = true;
            s.balls[i] = b;
            s.legal[i] = i != 0;
        }
        return s;
    }

    /** Verifikasi satu level bisa tamat. stats[0]=tembakan,
     *  stats[1]=sisa restTime, stats[2]=bola jatuh. */
    static boolean verifyLevel(int[] v, int[] stats) {
        Snap s = snapFromLine(v);
        int rest = 6;
        int shots = 0;
        int totalPotted = 0;
        while (true) {
            int remain = 0;
            for (int i = 1; i < s.balls.length; i++) {
                if (s.balls[i].avail) {
                    remain++;
                }
            }
            if (remain == 0) {
                stats[0] = shots;
                stats[1] = rest;
                stats[2] = totalPotted;
                return rest > 0;
            }
            if (shots >= 40 || rest <= 0) {
                return false;
            }
            Auto.Shot sh = Auto.searchShot(s, Auto.DIFF_EXPERT);
            if (sh == null) {
                return false;
            }
            Sim.Result r = s.newSim().simulate(sh.strength, sh.spinX, sh.spinY, sh.angle, false);
            shots++;
            int potted = 0;
            int black = 0;
            for (int i = 1; i < s.balls.length; i++) {
                if (r.potted[i]) {
                    potted++;
                }
                if (r.potHole[i] >= 0 && s.holes[r.potHole[i]].effect == 1) {
                    black++;
                }
            }
            if (r.potHole[0] >= 0 && s.holes[r.potHole[0]].effect == 1) {
                black++;
            }
            if (r.firstContact == -1) {
                rest -= 2;
            }
            if (potted == 0) {
                rest -= 1;
            }
            rest -= 2 * black;
            if (r.cuePotted) {
                rest -= 2;
            }
            totalPotted += potted;
            for (int i = 1; i < s.balls.length; i++) {
                if (r.potted[i]) {
                    s.balls[i].avail = false;
                } else {
                    s.balls[i].x = r.stopX[i];
                    s.balls[i].y = r.stopY[i];
                }
            }
            if (r.cuePotted) {
                float[] rs = respotCue(s);
                s.balls[0].x = rs[0];
                s.balls[0].y = rs[1];
            } else {
                s.balls[0].x = r.stopX[0];
                s.balls[0].y = r.stopY[0];
            }
        }
    }

    /** Posisi respawn bola putih ala game (dekat -705,0, digeser
     *  sampai tidak bertumpuk bola lain). */
    static float[] respotCue(Snap s) {
        for (int dx = 0; dx <= 500; dx += 25) {
            for (int dy = 0; dy <= 400; dy += 25) {
                float[][] cand = {
                        {-705f + dx, dy}, {-705f + dx, -dy},
                        {-705f - dx, dy}, {-705f - dx, -dy}};
                for (float[] c : cand) {
                    if (Math.abs(c[0]) > 1200f || Math.abs(c[1]) > 600f) {
                        continue;
                    }
                    boolean free = true;
                    for (int i = 1; i < s.balls.length; i++) {
                        if (!s.balls[i].avail) {
                            continue;
                        }
                        float ddx = c[0] - s.balls[i].x;
                        float ddy = c[1] - s.balls[i].y;
                        if (ddx * ddx + ddy * ddy < 97f * 97f) {
                            free = false;
                            break;
                        }
                    }
                    if (free) {
                        return c;
                    }
                }
            }
        }
        return new float[]{-705f, 0f};
    }

    // ---- generator layout ----------------------------------------
    static int[] genCandidate(Random rnd, int bc, int bhc, int[] texPool) {
        List<Integer> blackHoles = new ArrayList<Integer>();
        while (blackHoles.size() < bhc) {
            int h = rnd.nextInt(6);
            if (!blackHoles.contains(h)) {
                blackHoles.add(h);
            }
        }
        float cueX = -705f + rnd.nextInt(81) - 40;
        float cueY = rnd.nextInt(201) - 100;
        float[][] pos = new float[bc][2];
        pos[0][0] = cueX;
        pos[0][1] = cueY;
        int placed = 1;
        int guard = 0;
        while (placed < bc && guard++ < 4000) {
            // pilih lubang target normal + jarak + arah ke dalam meja
            int h = rnd.nextInt(6);
            if (blackHoles.contains(h)) {
                continue;
            }
            float hx = TestSim.HOLES[h * 3];
            float hy = TestSim.HOLES[h * 3 + 1];
            double base = Math.atan2(-hy, -hx);
            double ang = base + (rnd.nextDouble() - 0.5) * 1.9;
            float dist = 300f + rnd.nextFloat() * 620f;
            float x = hx + (float) Math.cos(ang) * dist;
            float y = hy + (float) Math.sin(ang) * dist;
            if (Math.abs(x) > 1120f || Math.abs(y) > 545f) {
                continue;
            }
            boolean ok = true;
            for (int i = 0; i < placed; i++) {
                float dx = x - pos[i][0];
                float dy = y - pos[i][1];
                if (dx * dx + dy * dy < 100f * 100f) {
                    ok = false;
                    break;
                }
            }
            if (!ok) {
                continue;
            }
            for (int bh : blackHoles) {
                float dx = x - TestSim.HOLES[bh * 3];
                float dy = y - TestSim.HOLES[bh * 3 + 1];
                if (dx * dx + dy * dy < 255f * 255f) {
                    ok = false;
                    break;
                }
            }
            if (!ok) {
                continue;
            }
            for (int hh = 0; hh < 6; hh++) {
                float dx = x - TestSim.HOLES[hh * 3];
                float dy = y - TestSim.HOLES[hh * 3 + 1];
                if (dx * dx + dy * dy < 135f * 135f) {
                    ok = false;
                    break;
                }
            }
            if (!ok) {
                continue;
            }
            pos[placed][0] = x;
            pos[placed][1] = y;
            placed++;
        }
        if (placed < bc) {
            return null;
        }
        int[] v = new int[2 + bhc + bc * 3];
        v[0] = bc;
        v[1] = bhc;
        for (int k = 0; k < bhc; k++) {
            v[2 + k] = blackHoles.get(k);
        }
        for (int i = 0; i < bc; i++) {
            v[2 + bhc + i * 3] = i == 0 ? 0 : texPool[rnd.nextInt(texPool.length)];
            v[2 + bhc + i * 3 + 1] = Math.round(pos[i][0]);
            v[2 + bhc + i * 3 + 2] = Math.round(pos[i][1]);
        }
        return v;
    }

    static String toLine(int[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(v[i]);
        }
        return sb.toString();
    }

    /** Pra-filter murah (v1.5.0): adakah POT LANGSUNG dari posisi
     *  awal? Geometri murni (tanpa simulasi): untuk satu bola
     *  objek + satu lubang normal, titik hantu (ghost ball) 2R di
     *  belakang bola pada garis bola->lubang; segmen putih->hantu
     *  dan segmen bola->lubang harus bersih dari bola lain
     *  (jarak > 96). Kandidat yang lolos filter ini hampir pasti
     *  dibuka perencana dengan pot langsung — bukan jalur
     *  bank/kick yang membuat satu verifyLevel makan puluhan
     *  menit. Filter ini HANYA mempercepat sampling generator;
     *  penerimaan level tetap diputuskan verifyLevel penuh
     *  (DIFF_EXPERT) di bawah, dan TestLevels memverifikasi
     *  ulang semua level dari berkas final. */
    static boolean segClear(float x0, float y0, float x1, float y1,
                            int[] v, int bhc, int skipA, int skipB) {
        int bc = v[0];
        float vx = x1 - x0;
        float vy = y1 - y0;
        float len2 = vx * vx + vy * vy;
        for (int j = 0; j < bc; j++) {
            if (j == skipA || j == skipB) {
                continue;
            }
            float px = v[2 + bhc + j * 3 + 1];
            float py = v[2 + bhc + j * 3 + 2];
            float t = len2 == 0 ? 0 : ((px - x0) * vx + (py - y0) * vy) / len2;
            t = Math.max(0, Math.min(1, t));
            float qx = x0 + t * vx;
            float qy = y0 + t * vy;
            float ddx = px - qx;
            float ddy = py - qy;
            if (ddx * ddx + ddy * ddy < 96f * 96f) {
                return false;
            }
        }
        return true;
    }

    static boolean hasInitialPot(int[] v) {
        int bc = v[0];
        int bhc = v[1];
        boolean[] black = new boolean[6];
        for (int k = 0; k < bhc; k++) {
            black[v[2 + k]] = true;
        }
        float cx = v[2 + bhc + 1];
        float cy = v[2 + bhc + 2];
        for (int i = 1; i < bc; i++) {
            float bx = v[2 + bhc + i * 3 + 1];
            float by = v[2 + bhc + i * 3 + 2];
            for (int h = 0; h < 6; h++) {
                if (black[h]) {
                    continue;
                }
                float hx = TestSim.HOLES[h * 3];
                float hy = TestSim.HOLES[h * 3 + 1];
                float dx = hx - bx;
                float dy = hy - by;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d < 1) {
                    continue;
                }
                float ux = dx / d;
                float uy = dy / d;
                float gx = bx - ux * 2 * TestSim.R;
                float gy = by - uy * 2 * TestSim.R;
                if (!segClear(cx, cy, gx, gy, v, bhc, 0, i)) {
                    continue;
                }
                if (!segClear(bx, by, hx, hy, v, bhc, -1, i)) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    /** Nilai start_angle[stage][level] kasar dari arah bola objek
     *  terdekat dari posisi awal bola putih (kuadran 0..3). */
    static int startAngleOf(int[] v) {
        int bhc = v[1];
        float cx = v[2 + bhc + 1];
        float cy = v[2 + bhc + 2];
        float bd = Float.MAX_VALUE;
        float bx = 1f;
        float by = 0f;
        for (int i = 1; i < v[0]; i++) {
            float x = v[2 + bhc + i * 3 + 1];
            float y = v[2 + bhc + i * 3 + 2];
            float dx = x - cx;
            float dy = y - cy;
            float d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                bx = dx;
                by = dy;
            }
        }
        double deg = Math.toDegrees(Math.atan2(by, bx));
        if (deg < 0) {
            deg += 360.0;
        }
        if (deg >= 315.0 || deg < 45.0) {
            return 0;
        }
        if (deg < 135.0) {
            return 1;
        }
        if (deg < 225.0) {
            return 2;
        }
        return 3;
    }

    public static void main(String[] args) throws IOException {
        Path outDir = Paths.get(args.length > 0 ? args[0] : "mod-assets/data");
        Files.createDirectories(outDir);
        // arg ke-2 opsional: hanya kerjakan stage file ini (7/8/9) —
        // untuk menjalankan stage secara paralel di proses terpisah;
        // baris start_angle + laporan ditulis ke berkas fragmen
        // per-stage dan digabung di akhir.
        final int onlyStage = args.length > 1 ? Integer.parseInt(args[1]) : -1;
        // stage file index 7..14 = Stage 8..15 (v1.5.0: +Stage 11-15)
        int[][] ballRange = {{16, 20}, {19, 23}, {22, 25}, {21, 23},
                {20, 22}, {21, 23}, {20, 22}, {20, 22}};
        int[][] blackRange = {{0, 2}, {1, 3}, {2, 3}, {2, 2},
                {2, 2}, {2, 2}, {2, 2}, {2, 2}};
        int[] texPool = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
        StringBuilder report = new StringBuilder();
        List<int[]> allRows = new ArrayList<int[]>();
        for (int st = 0; st < 8; st++) {
            int fileStage = 7 + st;
            if (onlyStage >= 0 && fileStage != onlyStage) {
                continue;
            }
            // RESUME: level yang sudah tertulis di berkas (terverifikasi
            // saat dibuat) tidak dibuat ulang — generator aman diinterupsi.
            Path stageFile = outDir.resolve("level_stage_" + fileStage + ".txt");
            List<String> lines = new ArrayList<String>();
            if (Files.exists(stageFile)) {
                lines.addAll(Files.readAllLines(stageFile, StandardCharsets.UTF_8));
                System.out.println("resume stage " + (fileStage + 1)
                        + ": " + lines.size() + " level sudah ada");
            }
            int[] row = new int[30];
            for (int i = 0; i < lines.size(); i++) {
                row[i] = startAngleOf(parse(lines.get(i)));
            }
            for (int lv = lines.size() + 1; lv <= 30; lv++) {
                int bc = ballRange[st][0]
                        + (int) Math.round((ballRange[st][1] - ballRange[st][0])
                                * (lv - 1) / 29.0);
                int bhc = blackRange[st][0]
                        + (int) Math.round((blackRange[st][1] - blackRange[st][0])
                                * (lv - 1) / 29.0);
                int[] accepted = null;
                int[] stats = new int[3];
                int usedBc = bc;
                int usedBhc = bhc;
                for (int attempt = 0; attempt < 60 && accepted == null; attempt++) {
                    Random rnd = new Random(900100L + fileStage * 1000L + lv * 37L + attempt * 7919L);
                    int tryBc = usedBc;
                    int tryBhc = usedBhc;
                    if (attempt == 25) {
                        tryBc = Math.max(14, usedBc - 3);
                        tryBhc = Math.max(0, usedBhc - 1);
                    }
                    if (attempt == 45) {
                        tryBc = Math.max(12, usedBc - 6);
                        tryBhc = 0;
                    }
                    int[] cand = genCandidate(rnd, tryBc, tryBhc, texPool);
                    if (cand == null) {
                        continue;
                    }
                    if (!hasInitialPot(cand)) {
                        continue;
                    }
                    int[] st2 = new int[3];
                    if (verifyLevel(cand, st2)) {
                        accepted = cand;
                        stats = st2;
                        usedBc = tryBc;
                        usedBhc = tryBhc;
                    }
                }
                if (accepted == null) {
                    throw new AssertionError("level_stage_" + fileStage + " level " + lv
                            + ": tidak ada kandidat lulus verifikasi");
                }
                lines.add(toLine(accepted));
                row[lv - 1] = startAngleOf(accepted);
                String msg = "stage " + (fileStage + 1) + " level " + lv
                        + ": bola=" + usedBc + " blackHole=" + usedBhc
                        + " tembakan=" + stats[0] + " sisaCue=" + stats[1];
                System.out.println(msg);
                report.append(msg).append('\n');
                // tulis per level supaya progres tidak hilang
                Files.write(stageFile, lines, StandardCharsets.UTF_8);
            }
            allRows.add(row);
        }
        Path rowsPath = onlyStage >= 0
                ? outDir.getParent().resolve("start_angle_rows_" + onlyStage + ".txt")
                : outDir.getParent().resolve("start_angle_rows.txt");
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(rowsPath, StandardCharsets.UTF_8))) {
            for (int[] row : allRows) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < row.length; i++) {
                    if (i > 0) {
                        sb.append(' ');
                    }
                    sb.append(row[i]);
                }
                pw.println(sb.toString());
            }
        }
        Path reportPath = onlyStage >= 0
                ? outDir.getParent().resolve("levels_report_" + onlyStage + ".txt")
                : outDir.getParent().resolve("levels_report.txt");
        String prev = Files.exists(reportPath)
                ? new String(Files.readAllBytes(reportPath), StandardCharsets.UTF_8) : "";
        Files.write(reportPath, (prev + report.toString()).getBytes(StandardCharsets.UTF_8));
        System.out.println("GenLevels: level terverifikasi ditulis ke " + outDir);
    }
}
