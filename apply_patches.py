#!/usr/bin/env python3
"""Patch smali pada pohon apktool Pool Billiards Pro 5.3.

Tiga sisipan invoke-static, semua dengan asersi pola persis (gagal keras
bila pola tidak ditemukan — tidak ada patch diam-diam setengah jadi):

1. GameScene.tick(J)V — sisipkan di awal method:
     invoke-static/range {p0 .. p0}, Lcom/hozin/poolmod/Hooks;->onSceneTick(Ljava/lang/Object;)V
   (tick adalah method per-frame scene permainan; semua pekerjaan mod
   berjalan dari sini di thread game.)

2. Main.onResume()V — sisipkan di awal method:
     Hooks.onActivityResume(this)   (memasang menu WindowManager)

3. Main.onPause()V — sisipkan di awal method:
     Hooks.onActivityPause(this)    (melepas menu)

Manifest: APK ini berkas tunggal (bukan split) sehingga tidak ada
atribut split yang perlu dibuang; hanya extractNativeLibs=false yang
bila ada diubah ke true agar lib native terekstrak saat pemasangan.

Pemakaian: apply_patches.py <dir hasil apktool d>
"""
import glob
import re
import sys


def insert_after_locals(smali: str, method_sig: str, invoke_line: str) -> str:
    pat = re.compile(
        r"(\.method public " + re.escape(method_sig) + r"\n(?:\s*\.locals \d+|\s*\.registers \d+)\n)"
    )
    matches = pat.findall(smali)
    assert len(matches) == 1, f"{method_sig}: {len(matches)} kecocokan (diharapkan 1)"
    return pat.sub(lambda m: m.group(1) + "\n    " + invoke_line + "\n", smali, count=1)


def find_smali(root: str, relpath: str) -> str:
    hits = sorted(glob.glob(root + "/smali*/" + relpath))
    assert len(hits) == 1, f"{relpath}: {len(hits)} berkas ditemukan"
    return hits[0]


def main() -> int:
    root = sys.argv[1]

    # --- GameScene.tick ---
    path = find_smali(root, "com/forthblue/pool/scene/GameScene.smali")
    smali = open(path, encoding="utf-8").read()
    smali = insert_after_locals(
        smali,
        "tick(J)V",
        "invoke-static/range {p0 .. p0}, Lcom/hozin/poolmod/Hooks;->onSceneTick(Ljava/lang/Object;)V",
    )
    open(path, "w", encoding="utf-8").write(smali)
    print("GameScene.tick: hook onSceneTick disisipkan")

    # --- Main.onResume / onPause ---
    path = find_smali(root, "com/forthblue/pool/Main.smali")
    smali = open(path, encoding="utf-8").read()
    smali = insert_after_locals(
        smali,
        "onResume()V",
        "invoke-static/range {p0 .. p0}, Lcom/hozin/poolmod/Hooks;->onActivityResume(Ljava/lang/Object;)V",
    )
    smali = insert_after_locals(
        smali,
        "onPause()V",
        "invoke-static/range {p0 .. p0}, Lcom/hozin/poolmod/Hooks;->onActivityPause(Ljava/lang/Object;)V",
    )
    open(path, "w", encoding="utf-8").write(smali)
    print("Main.onResume/onPause: hook menu disisipkan")

    # --- Manifest ---
    mpath = root + "/AndroidManifest.xml"
    xml = open(mpath, encoding="utf-8").read()
    if 'android:extractNativeLibs="false"' in xml:
        xml = xml.replace('android:extractNativeLibs="false"',
                          'android:extractNativeLibs="true"')
        open(mpath, "w", encoding="utf-8").write(xml)
        print("manifest: extractNativeLibs -> true")
    else:
        print("manifest: extractNativeLibs sudah aman, tidak diubah")
    return 0


if __name__ == "__main__":
    sys.exit(main())
