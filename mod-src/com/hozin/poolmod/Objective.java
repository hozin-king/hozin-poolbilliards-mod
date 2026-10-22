package com.hozin.poolmod;

/**
 * Fungsi objektif perencana — dipisahkan dari mesin pencarian supaya
 * sasaran per mode bisa disisipkan tanpa menyentuh tangga keputusan.
 *
 * Implementasi saat ini = objektif STANDAR (8-ball/9-ball/umum):
 * nilai pot seragam, bonus bola penentu, posisi lanjutan dinilai dari
 * kemudahan tembakan berikutnya. Audit mode Arcade berjalan paralel;
 * bila level Arcade punya sasaran berbeda (target per level), buat
 * subclass/factory di sini dan pilih dari ruleClass/mode di Snap —
 * JANGAN mengunci logika mode ke dalam Auto.
 *
 * Yang TIDAK termasuk objektif (dan tidak boleh diubah kesulitan/
 * objektif): aturan keras legalitas — oracle isAvailableTarget +
 * saringan simulasi penuh (Perintah 5A). Itu hukum, bukan selera.
 */
public class Objective {
    public final String name = "standar";

    /** Nilai dasar satu pot legal. */
    public float potValue(Snap s, int ball) {
        return 1000.0f;
    }

    /** Nilai memasukkan bola penentu secara legal (menang ronde). */
    public float winValue(Snap s) {
        return 5000.0f;
    }

    /** Bobot nilai posisi lanjutan (2-ply) pada skor akhir kandidat. */
    public float positionWeight() {
        return 1.0f;
    }

    /** Penalti bila sesudah tembakan tidak ada tembakan lanjutan legal
     * yang layak dari posisi bola putih akhir (posisi mati). */
    public float deadPositionPenalty() {
        return 350.0f;
    }

    /** Penalti per bola lawan yang berakhir sangat dekat mulut lubang. */
    public float opponentNearHolePenalty(float distToHole) {
        return Math.max(0.0f, 170.0f - distToHole) * 1.5f;
    }
}
