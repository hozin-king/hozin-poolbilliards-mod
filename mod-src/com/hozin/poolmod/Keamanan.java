package com.hozin.poolmod;

public final class Keamanan {
    private static Object ruleTerakhir;
    private Keamanan() {}
    public static boolean perbarui(Object rule) {
        ruleTerakhir = rule;
        ModState.pvpLatched = false;
        return false;
    }
    public static boolean pvp() {
        return false;
    }
}
