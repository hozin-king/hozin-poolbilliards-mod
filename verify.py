#!/usr/bin/env python3
"""Verifikasi APK mod final Pool Billiards Pro.

Pemeriksaan:
  1. zip utuh (testzip) + ada classes5.dex (dex mod).
  2. apksigner verify lulus.
  3. badging (aapt2) identik dengan APK asli: package, versionCode,
     versionName.
  4. Decompile-balik: kelas mod ada di smali_classes5, hook
     onSceneTick/onActivityResume/onActivityPause tertanam di smali
     GameScene & Main, dan gerbang isPVPMode dirujuk oleh Hooks & Auto
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

MOD_CLASSES = ["Hooks", "ModMenu", "ModState", "Predict", "Auto", "Snap", "Ref", "Sim"]


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
        assert "isPVPMode" in hooks, "gerbang PvP tidak ditemukan di Hooks"
        assert "isPVPMode" in auto, "gerbang PvP tidak ditemukan di Auto"
        sim = open(os.path.join(moddir, "Sim.smali"), encoding="utf-8").read()
        assert "BLOCK_HIT_LOST" in sim and "ROLL_K" in sim, "konstanta fisika Sim tidak ditemukan"
        print("4c. gerbang isPVPMode dirujuk Hooks & Auto; konstanta Sim ada")

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

    print("VERIFY OK:", final_apk, os.path.getsize(final_apk), "byte")
    return 0


if __name__ == "__main__":
    sys.exit(main())
