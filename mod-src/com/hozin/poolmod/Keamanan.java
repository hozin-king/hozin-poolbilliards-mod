package com.hozin.poolmod;

/**
 * Keamanan — gerbang PvP SATU JALUR (v1.4.2).
 *
 * Seluruh keputusan PvP hidup di kelas ini saja, berbentuk bool
 * sederhana: perbarui() dipanggil Hooks.tick() sekali per tick,
 * membaca rule.isPVPMode() (satu-satunya panggilan refleksi untuk
 * ini di seluruh mod) lalu MEN-SET bool ModState.pvpLatched:
 *
 *   true  -> mode PvP: semua fitur mod mati total, menu disembunyikan.
 *   false -> mode offline: fitur boleh jalan (sesuai saklar user).
 *
 * Bool menempel per instance rule (rule baru = dievaluasi ulang) dan
 * lengket: sekali true untuk instance itu, tetap true. Semua modul
 * lain (Auto, Features, Anim, ModMenu, Hooks) cukup membaca bool-nya
 * — tanpa refleksi, tanpa cek ulang — supaya tidak membebani frame.
 *
 * Bool ini di-set OTOMATIS dari mode game yang sedang berjalan,
 * bukan saklar manual pengguna.
 */
public final class Keamanan {

    private static Object ruleTerakhir;

    private Keamanan() {
    }

    /**
     * Set bool gerbang dari rule yang sedang aktif. Dipanggil tepat
     * satu kali per tick oleh Hooks. Mengembalikan nilai bool-nya.
     */
    public static boolean perbarui(Object rule) {
        boolean pvp = Ref.invokeB(rule, "isPVPMode");
        if (rule != ruleTerakhir) {
            ruleTerakhir = rule;
            ModState.pvpLatched = pvp;
        }
        if (pvp) {
            ModState.pvpLatched = true;
        }
        return ModState.pvpLatched;
    }

    /** Baca bool gerbang (true = PvP, fitur mati total). */
    public static boolean pvp() {
        return ModState.pvpLatched;
    }
}
