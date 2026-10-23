#!/usr/bin/env python3
"""Verifikasi APK mod final Pool Billiards Pro.

Pemeriksaan:
  1. zip utuh (testzip) + ada classes5.dex (dex mod).
  2. apksigner verify lulus.
  3. badging (aapt2) identik dengan APK asli: package, versionCode,
     versionName.
  4. Decompile-balik: kelas mod ada di smali_classes5, hook
     onSceneTick/onActivityResume/onActivityPause tertanam di smali
     GameScene & Main, dan gerbang PvP SATU JALUR: isPVPMode hanya
     dirujuk Keamanan, modul lain membaca bool latch pvpLatched
     (bukti statis fitur + gerbang PvP).

Pemakaian: verify.py <apk-final> <apk-asli>
Env: ANDROID_HOME, APKTOOL_JAR.
"""
import glob
import os
import re
import subprocess
import sys
import tempfile
import zipfile

MOD_CLASSES = ["Hooks", "ModMenu", "ModState", "Predict", "Auto", "Snap", "Ref", "Sim",
               "AutoTrigger", "Magnet", "Features", "Keamanan"]


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def tool(name):
    ah = os.environ.get("ANDROID_HOME", "")
    hits = sorted(glob.glob(os.path.join(ah, "build-tools", "*", name)))
    assert hits, f"{name} tidak ditemukan di ANDROID_HOME"
    return hits[-1]


def badging(apk, aapt2):
    out = run([aapt2, "dump", "badging", apk]).stdout
    m = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", out)
    assert m, f"badging gagal untuk {apk}"
    return m.groups()


def main() -> int:
    final_apk, orig_apk = sys.argv[1], sys.argv[2]
    aapt2 = tool("aapt2")
    apksigner = tool("apksigner")
    apktool = os.environ["APKTOOL_JAR"]

    z = zipfile.ZipFile(final_apk)
    assert z.testzip() is None, "zip korup"
    names = z.namelist()
    assert "classes5.dex" in names, "classes5.dex (dex mod) tidak ada"
    print(f"1. zip utuh, {len(names)} entri, classes5.dex ada")

    r = run([apksigner, "verify", "--verbose", final_apk])
    assert r.returncode == 0 and "Verifies" in r.stdout, "apksigner verify GAGAL:\n" + r.stdout + r.stderr
    print("2. apksigner verify: Verifies")

    b_fin = badging(final_apk, aapt2)
    b_org = badging(orig_apk, aapt2)
    assert b_fin == b_org, f"badging beda: final={b_fin} asli={b_org}"
    print(f"3. badging identik: {b_fin[0]} versionCode={b_fin[1]} versionName={b_fin[2]}")

    with tempfile.TemporaryDirectory() as td:
        out = os.path.join(td, "dec")
        r = run(["java", "-jar", apktool, "d", "-f", "-o", out, final_apk])
        assert r.returncode == 0, "decompile-balik gagal:\n" + r.stdout + r.stderr
        moddir = os.path.join(out, "smali_classes5", "com", "hozin", "poolmod")
        for cname in MOD_CLASSES:
            p = os.path.join(moddir, cname + ".smali")
            assert os.path.isfile(p), f"kelas mod hilang dari APK final: {cname}"
        print("4a. semua kelas mod tertanam di APK final:", ", ".join(MOD_CLASSES))

        gs = open(os.path.join(out, "smali", "com", "forthblue", "pool", "scene", "GameScene.smali"),
                  encoding="utf-8").read()
        assert "Lcom/hozin/poolmod/Hooks;->onSceneTick" in gs, "hook tick tidak tertanam"
        mn = open(os.path.join(out, "smali", "com", "forthblue", "pool", "Main.smali"),
                  encoding="utf-8").read()
        assert "Lcom/hozin/poolmod/Hooks;->onActivityResume" in mn, "hook onResume tidak tertanam"
        assert "Lcom/hozin/poolmod/Hooks;->onActivityPause" in mn, "hook onPause tidak tertanam"
        print("4b. hook GameScene.tick + Main.onResume/onPause tertanam")

        hooks = open(os.path.join(moddir, "Hooks.smali"), encoding="utf-8").read()
        auto = open(os.path.join(moddir, "Auto.smali"), encoding="utf-8").read()
        # v1.4.2 SATU JALUR: isPVPMode hanya boleh dirujuk Keamanan
        # (kelas gerbang); semua modul lain — termasuk Hooks — membaca
        # bool latch ModState.pvpLatched saja (bukti di smali final).
        keamanan = open(os.path.join(moddir, "Keamanan.smali"), encoding="utf-8").read()
        assert "isPVPMode" in keamanan, "gerbang PvP tidak ditemukan di Keamanan"
        assert "Keamanan" in hooks, "Hooks tidak memanggil Keamanan (jalur tunggal putus)"
        for other in ("Hooks", "Auto", "Features", "Anim", "ModMenu", "Predict", "Magnet"):
            body = open(os.path.join(moddir, other + ".smali"), encoding="utf-8").read()
            assert "isPVPMode" not in body, \
                f"gerbang PvP bocor keluar jalur tunggal: isPVPMode ada di {other}"
        for reader in ("Auto", "Features", "Anim"):
            body = open(os.path.join(moddir, reader + ".smali"), encoding="utf-8").read()
            assert "pvpLatched" in body, f"pembaca latch PvP hilang di {reader}"
        sim = open(os.path.join(moddir, "Sim.smali"), encoding="utf-8").read()
        assert "BLOCK_HIT_LOST" in sim and "ROLL_K" in sim, "konstanta fisika Sim tidak ditemukan"
        print("4c. gerbang PvP SATU JALUR: isPVPMode hanya di Keamanan; "
              "Auto/Features/Anim membaca bool pvpLatched; konstanta Sim ada")

        snap = open(os.path.join(moddir, "Snap.smali"), encoding="utf-8").read()
        assert "isAvailableTarget" in snap, "oracle target legal tidak ditemukan di Snap"
        assert "passesFilter" in auto, "saringan keras autoplay tidak ditemukan di Auto"
        mm = open(os.path.join(moddir, "ModMenu.smali"), encoding="utf-8").read()
        assert "Auto Play" in mm, "saklar Auto Play tidak ditemukan di ModMenu"
        print("4d. v1.1: oracle isAvailableTarget (Snap), saringan keras (Auto), "
              "saklar Auto Play (ModMenu) tertanam")

        predict = open(os.path.join(moddir, "Predict.smali"), encoding="utf-8").read()
        assert "ensureAttached" in predict, "penegakan kehadiran container v1.2 tidak ditemukan di Predict"
        assert "filterReason" in auto, "alasan penolakan berkode v1.2 tidak ditemukan di Auto"
        assert "lastReport" in auto, "readout keputusan v1.2 tidak ditemukan di Auto"
        assert "predictDiag" in hooks, "readout diagnosis prediksi v1.2 tidak ditemukan di Hooks"
        assert "autoReport" in mm, "baris readout v1.2 tidak ditemukan di ModMenu"
        print("4e. v1.2: ensureAttached (Predict), filterReason + lastReport (Auto), "
              "predictDiag (Hooks), autoReport (ModMenu) tertanam")

        anim = open(os.path.join(moddir, "Anim.smali"), encoding="utf-8").read()
        assert "shoot" in anim and "putFreeball" in anim, \
            "mesin animasi eksekusi v1.3 tidak ditemukan di Anim"
        assert "setAimAngle" in anim, "animasi rotasi stik v1.3 tidak ditemukan di Anim"
        assert "planRunout" in auto, "perencana run-out Mustahil v1.3 tidak ditemukan di Auto"
        assert "searchBank" in auto and "searchKick" in auto, \
            "tangga expert (bank/kick) v1.3 tidak ditemukan di Auto"
        assert "arcadeFilterReason" in auto, "saringan Arcade v1.3 tidak ditemukan di Auto"
        assert "LevelRule" in snap, "deteksi Arcade (LevelRule) v1.3 tidak ditemukan di Snap"
        assert "effect_type" in snap, "lubang spesial Arcade v1.3 tidak ditemukan di Snap"
        assert "Kesulitan" in mm and "MUSTAHIL" in mm, \
            "pemilih kesulitan v1.3 tidak ditemukan di ModMenu"
        assert "autoFailCount" in open(os.path.join(moddir, "ModState.smali"),
               encoding="utf-8").read(), "pengaman 3x gagal v1.3 tidak ditemukan di ModState"
        print("4f. v1.3: Anim (shoot/putFreeball/setAimAngle), tangga expert + "
              "run-out + saringan Arcade (Auto), LevelRule + effect_type (Snap), "
              "pemilih kesulitan (ModMenu), pengaman 3x gagal (ModState) tertanam")

        # --- v1.4 ---
        trig = open(os.path.join(moddir, "AutoTrigger.smali"), encoding="utf-8").read()
        assert "PH_WAIT" in trig or "mesin status" in trig or "STABLE" in trig, \
            "mesin status pemicu v1.4 tidak ditemukan di AutoTrigger"
        assert "AutoTrigger" in hooks, "Hooks belum memakai AutoTrigger v1.4"
        ms = open(os.path.join(moddir, "ModState.smali"), encoding="utf-8").read()
        for f in ("antiScratch", "magnetOn", "prankOn", "autoStatus", "magnetStatus"):
            assert f in ms, f"field v1.4 hilang dari ModState: {f}"
        assert "planBreak" in auto and "isBreakPosition" in auto, \
            "logika break v1.4 tidak ditemukan di Auto"
        magnet = open(os.path.join(moddir, "Magnet.smali"), encoding="utf-8").read()
        assert "targets" in magnet, "kelayakan magnet v1.4 tidak ditemukan di Magnet"
        feats = open(os.path.join(moddir, "Features.smali"), encoding="utf-8").read()
        assert "prankTick" in feats and "magnetTick" in feats, \
            "mesin fitur v1.4 tidak ditemukan di Features"
        for label in ("Anti-Scratch", "Magnet", "Prank"):
            assert label in mm, f"saklar v1.4 tidak ditemukan di ModMenu: {label}"
        assert "panelIntent" in mm, "minimize/persistensi panel v1.4 tidak ditemukan di ModMenu"
        print("4g. v1.4: AutoTrigger dipakai Hooks, planBreak (Auto), Magnet.targets, "
              "Features (prank/magnet tick), saklar Anti-Scratch/Magnet/Prank + "
              "panelIntent (ModMenu), field fitur (ModState) tertanam")

        pt = open(os.path.join(out, "smali", "com", "forthblue", "pool", "engine",
                               "PoolTable.smali"), encoding="utf-8").read()
        assert "antiScratch" in pt, "penjaga Anti-Scratch tidak tertanam di PoolTable"
        ls = open(os.path.join(out, "smali", "com", "forthblue", "pool", "scene",
                               "LevelScene.smali"), encoding="utf-8").read()
        assert "MAX_TABLE:I = 0xa" in ls, "MAX_TABLE belum 10 di LevelScene"
        lr = open(os.path.join(out, "smali", "com", "forthblue", "pool", "rules",
                               "LevelRule.smali"), encoding="utf-8").read()
        # label payload tidak bertahan decompile-balik; buktinya =
        # arraycopy perluasan + 10 blok array-data (7 bawaan + 3 baru)
        # + start_angle ditulis dua kali (asli lalu hasil perluasan)
        assert "arraycopy" in lr and lr.count(".array-data") >= 10, \
            "perluasan start_angle tidak tertanam di LevelRule"
        assert lr.count("sput-object") >= 2 and "start_angle" in lr, \
            "penulisan ulang start_angle tidak ditemukan di LevelRule"
        print("4h. v1.4: penjaga Anti-Scratch (PoolTable), MAX_TABLE=10 "
              "(LevelScene), start_angle 10 baris (LevelRule) tertanam")

    # aset level baru + region judul stage di dalam zip final
    for stage in (7, 8, 9):
        name = f"assets/data/level_stage_{stage}.txt"
        assert name in names, f"aset level hilang dari APK final: {name}"
        body = z.read(name).decode("utf-8").strip().split("\n")
        assert len(body) == 30, f"{name}: {len(body)} baris (diharapkan 30)"
    pack = z.read("assets/menu_button.pack").decode("utf-8")
    for region in ("stagetitle7", "stagetitle8", "stagetitle9"):
        assert ("\n" + region + "\n") in ("\n" + pack), f"region {region} hilang dari pack"
    print("5. aset v1.4: level_stage_7/8/9 masing-masing 30 level + "
          "region stagetitle7/8/9 ada di APK final")

    print("VERIFY OK:", final_apk, os.path.getsize(final_apk), "byte")
    return 0


if __name__ == "__main__":
    sys.exit(main())
