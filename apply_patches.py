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
import os
import re
import shutil
import sys

REPO = os.path.dirname(os.path.abspath(__file__))


def insert_after_locals(smali: str, method_sig: str, invoke_line: str) -> str:
    pat = re.compile(
        r"(\.method public " + re.escape(method_sig) + r"\n(?:\s*\.locals \d+|\s*\.registers \d+)\n)"
    )
    matches = pat.findall(smali)
    assert len(matches) == 1, f"{method_sig}: {len(matches)} kecocokan (diharapkan 1)"
    return pat.sub(lambda m: m.group(1) + "\n    " + invoke_line + "\n", smali, count=1)


def method_span(smali: str, sig_part: str):
    """Kembalikan (start, end) rentang teks satu method (termasuk
    baris .method s/d .end method) yang headernya mengandung sig_part."""
    starts = [m.start() for m in re.finditer(r"(?m)^\.method [^\n]*" + re.escape(sig_part), smali)]
    assert len(starts) == 1, f"method {sig_part}: {len(starts)} kecocokan"
    start = starts[0]
    end = smali.index("\n.end method", start) + len("\n.end method")
    return start, end


def patch_in_method(smali: str, sig_part: str, old: str, new: str, count: int) -> str:
    start, end = method_span(smali, sig_part)
    body = smali[start:end]
    found = body.count(old)
    assert found == count, f"{sig_part}: '{old}' ditemukan {found}x (diharapkan {count})"
    body = body.replace(old, new)
    return smali[:start] + body + smali[end:]


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

    # --- PoolTable.getNextHitTime(PoolBall, PoolHole): Anti-Scratch ---
    # Bila saklar ModState.antiScratch menyala dan bola yang diuji
    # adalah bola bidik (putih), kembalikan MAX_TIME (3600.0f) supaya
    # mesin game tidak pernah menjadwalkan penangkapan lubang untuk
    # bola putih. Cermin persis flag Sim.cueHoleImmune di uji host.
    path = find_smali(root, "com/forthblue/pool/engine/PoolTable.smali")
    smali = open(path, encoding="utf-8").read()
    sig = "getNextHitTime(Lcom/forthblue/pool/engine/PoolBall;Lcom/forthblue/pool/engine/PoolHole;)F"
    start, end = method_span(smali, sig)
    body = smali[start:end]
    anchor = ".locals 8\n"
    assert body.count(anchor) == 1, "PoolTable: jangkar .locals tidak unik"
    guard = anchor + """
    sget-boolean v0, Lcom/hozin/poolmod/ModState;->antiScratch:Z

    if-eqz v0, :mod_antiscratch_cont

    iget-object v0, p0, Lcom/forthblue/pool/engine/PoolTable;->rule:Lcom/forthblue/pool/rules/PoolRule;

    iget-object v1, p0, Lcom/forthblue/pool/engine/PoolTable;->roundstate:Lcom/forthblue/pool/rules/PoolRule$RoundState;

    invoke-interface {v0, v1}, Lcom/forthblue/pool/rules/PoolRule;->getClickTarget(Lcom/forthblue/pool/rules/PoolRule$RoundState;)I

    move-result v0

    iget-object v1, p0, Lcom/forthblue/pool/engine/PoolTable;->balls:[Lcom/forthblue/pool/engine/PoolBall;

    aget-object v0, v1, v0

    if-ne p1, v0, :mod_antiscratch_cont

    const v0, 0x46500000    # 3600.0f

    return v0

    :mod_antiscratch_cont
"""
    body = body.replace(anchor, guard, 1)
    smali = smali[:start] + body + smali[end:]
    open(path, "w", encoding="utf-8").write(smali)
    print("PoolTable.getNextHitTime(ball,hole): penjaga Anti-Scratch disisipkan")

    # --- LevelScene: 7 stage -> 15 stage (+1 kartu teaser) ---
    path = find_smali(root, "com/forthblue/pool/scene/LevelScene.smali")
    smali = open(path, encoding="utf-8").read()
    assert smali.count(".field public static final MAX_TABLE:I = 0x7") == 1
    smali = smali.replace(".field public static final MAX_TABLE:I = 0x7",
                          ".field public static final MAX_TABLE:I = 0xf")
    # konstruktor: dimensi skor [7][30]->[15][30] + sentinel tablebx
    # 7->15; kapasitas ScrollHContainer & batas loop kartu 8->16;
    # batas gulir 6000->13000 (16 kartu x 800 + slack)
    smali = patch_in_method(smali, "constructor <init>(Lcom/forthblue/pool/Main;)V",
                            "const/4 v11, 0x7", "const/16 v11, 0xf", 2)
    smali = patch_in_method(smali, "constructor <init>(Lcom/forthblue/pool/Main;)V",
                            "const/16 v1, 0x8", "const/16 v1, 0x10", 2)
    smali = patch_in_method(smali, "constructor <init>(Lcom/forthblue/pool/Main;)V",
                            "0x45bb8000", "0x464b2000", 1)
    smali = patch_in_method(smali, "initLevelPanel(I)V",
                            "const/4 v0, 0x7", "const/16 v0, 0xf", 1)
    smali = patch_in_method(smali, "initTable()V",
                            "const/4 v2, 0x7", "const/16 v2, 0xf", 1)
    smali = patch_in_method(smali, "loadPreference()V",
                            "const/4 v3, 0x7", "const/16 v3, 0xf", 1)
    smali = patch_in_method(smali, "onButtonClick(I)V",
                            "const/16 v1, 0x11", "const/16 v1, 0x19", 1)
    smali = patch_in_method(smali, "pageIndexChange(Lcom/forthblue/pool/scene/ScrollHContainer;I)V",
                            "const/4 p1, 0x7", "const/16 p1, 0xf", 1)
    open(path, "w", encoding="utf-8").write(smali)
    print("LevelScene: gerbang 7 stage dibuka ke 15 stage + teaser")

    # --- LevelRule.<clinit>: start_angle 7 -> 15 baris ---
    rows_path = os.path.join(REPO, "mod-assets", "start_angle_rows.txt")
    rows = [ln.split() for ln in open(rows_path, encoding="utf-8").read().strip().split("\n")]
    assert len(rows) == 8 and all(len(r) == 30 for r in rows), "start_angle_rows harus 8x30"
    assert all(v in ("0", "1", "2", "3") for r in rows for v in r), "start_angle harus 0..3"
    path = find_smali(root, "com/forthblue/pool/rules/LevelRule.smali")
    smali = open(path, encoding="utf-8").read()
    start, end = method_span(smali, "static constructor <clinit>()V")
    body = smali[start:end]
    anchor = ("    sput-object v7, Lcom/forthblue/pool/rules/LevelRule;->start_angle:[[I\n"
              "\n    return-void")
    assert body.count(anchor) == 1, "LevelRule clinit: jangkar sput tidak unik"
    ext_lines = [
        "    sput-object v7, Lcom/forthblue/pool/rules/LevelRule;->start_angle:[[I",
        "",
        "    const/16 v3, 0xf",
        "",
        "    new-array v3, v3, [[I",
        "",
        "    sget-object v1, Lcom/forthblue/pool/rules/LevelRule;->start_angle:[[I",
        "",
        "    const/4 v2, 0x0",
        "",
        "    const/4 v4, 0x0",
        "",
        "    const/4 v5, 0x7",
        "",
        "    invoke-static/range {v1 .. v5}, Ljava/lang/System;->arraycopy(Ljava/lang/Object;ILjava/lang/Object;II)V",
        "",
        "    const/16 v1, 0x1e",
        "",
    ]
    for idx in range(7, 15):
        ext_lines += [
            "    new-array v2, v1, [I",
            "",
            f"    fill-array-data v2, :mod_array_{idx}",
            "",
            f"    const/16 v4, 0x{idx:x}",
            "",
            "    aput-object v2, v3, v4",
            "",
        ]
    ext_lines += [
        "    sput-object v3, Lcom/forthblue/pool/rules/LevelRule;->start_angle:[[I",
        "",
        "    return-void",
    ]
    ext = "\n".join(ext_lines)
    body = body.replace(anchor, ext, 1)
    data = ""
    for i, row in enumerate(rows):
        data += f"\n    :mod_array_{7 + i}\n    .array-data 4\n"
        for v in row:
            data += f"        0x{v}\n"
        data += "    .end array-data\n"
    # payload array-data tinggal di dalam method, sesudah return-void
    assert body.endswith("\n.end method")
    body = body[: -len("\n.end method")] + data + "\n.end method"
    smali = smali[:start] + body + smali[end:]
    open(path, "w", encoding="utf-8").write(smali)
    print("LevelRule.<clinit>: start_angle diperluas ke 15 baris")

    # --- Aset mod: level baru + atlas judul stage ---
    assets = os.path.join(REPO, "mod-assets")
    for stage in range(7, 15):
        src = os.path.join(assets, "data", f"level_stage_{stage}.txt")
        dst = os.path.join(root, "assets", "data", f"level_stage_{stage}.txt")
        shutil.copyfile(src, dst)
        lines = open(dst, encoding="utf-8").read().strip().split("\n")
        assert len(lines) == 30, f"level_stage_{stage}: {len(lines)} baris"
    shutil.copyfile(os.path.join(assets, "menu_button.png"),
                    os.path.join(root, "assets", "menu_button.png"))
    shutil.copyfile(os.path.join(assets, "menu_button.pack"),
                    os.path.join(root, "assets", "menu_button.pack"))
    print("aset: level_stage_7..14 + atlas menu_button terpasang")

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
