package com.hozin.poolmod;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;

/**
 * Menu melayang mod: jendela WindowManager terpisah bertipe
 * TYPE_APPLICATION_PANEL pada token jendela activity (tanpa izin
 * overlay sistem), di atas permukaan GL game.
 *
 * v1.4 (permintaan user): panel bisa DIGESER dari header-nya,
 * bisa DIMINIMIZE jadi tombol/ball kecil "MOD" (ketuk untuk
 * membuka lagi), dan posisi panel + tombol + keadaan
 * tampil/sembunyi DIPERTAHANKAN selama sesi game (field statis
 * proses; watchdog hanya mencocokkan visibilitas dengan gerbang:
 * di dalam game & bukan PvP). Di PvP menu hilang TOTAL termasuk
 * ikonnya (gerbang juga ditegakkan di Hooks/Auto/Predict/Features
 * — menu hanya lapisan UI).
 */
public final class ModMenu {
    private static final int ACCENT = Color.rgb(64, 199, 120);
    private static final int PANEL_BG = Color.argb(235, 13, 20, 25);
    private static final int BTN_BG = Color.argb(205, 13, 20, 25);

    private static WeakReference<Activity> actRef;
    private static WindowManager wm;
    private static Handler ui;
    private static TextView btnMod;
    private static WindowManager.LayoutParams btnLp;
    private static boolean btnAdded;
    private static LinearLayout panel;
    private static WindowManager.LayoutParams panelLp;
    private static boolean panelAdded;
    /** maksud user: panel ingin tampil (dipertahankan per sesi) */
    private static boolean panelIntent;
    private static TextView statusView;
    private static TextView diagView;
    private static Switch swAutoState;
    private static Button diffBtn;
    private static boolean watchdogOn;

    private interface BoolSetter {
        void accept(boolean v);
    }

    static String diffLabel() {
        int d = ModState.difficulty;
        return "Kesulitan: " + (d == 0 ? "NORMAL" : (d == 2 ? "MUSTAHIL" : "EXPERT"));
    }

    private ModMenu() {
    }

    // ----------------------------------------------------------------
    public static void attach(Object activityObj) {
        if (!(activityObj instanceof Activity)) {
            return;
        }
        final Activity act = (Activity) activityObj;
        actRef = new WeakReference<Activity>(act);
        if (ui == null) {
            ui = new Handler(Looper.getMainLooper());
        }
        ui.post(new Runnable() {
            @Override
            public void run() {
                buildIfNeeded(act);
                showBtnIfWanted();
                startWatchdog();
            }
        });
    }

    public static void detach(Object activityObj) {
        if (ui == null) {
            return;
        }
        ui.post(new Runnable() {
            @Override
            public void run() {
                removeViews();
            }
        });
    }

    // ----------------------------------------------------------------
    private static GradientDrawable roundBg(int color, int radiusPx, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        if (strokeColor != 0) {
            d.setStroke(2, strokeColor);
        }
        return d;
    }

    private static int dp(Activity act, float v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static Switch addSwitch(final Activity act, LinearLayout parent,
                                    String label, boolean initial,
                                    final BoolSetter setter) {
        Switch sw = new Switch(act);
        sw.setText(label);
        sw.setTextColor(Color.WHITE);
        sw.setChecked(initial);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                setter.accept(checked);
                savePrefs(act);
            }
        });
        parent.addView(sw);
        return sw;
    }

    private static void buildIfNeeded(final Activity act) {
        if (btnMod != null) {
            return;
        }
        wm = (WindowManager) act.getSystemService(Context.WINDOW_SERVICE);
        SharedPreferences prefs = act.getSharedPreferences("poolmod", Context.MODE_PRIVATE);
        ModState.predictOn = prefs.getBoolean("predictOn", true);
        ModState.longLineOn = prefs.getBoolean("longLineOn", true);
        ModState.autoOn = prefs.getBoolean("autoOn", false);
        ModState.difficulty = prefs.getInt("diff", 1);
        ModState.antiScratch = prefs.getBoolean("antiScratch", false);
        ModState.magnetOn = prefs.getBoolean("magnetOn", false);
        ModState.prankOn = prefs.getBoolean("prankOn", false);

        // --- tombol MOD (ikon kecil; juga pegangan saat panel minimize) ---
        btnMod = new TextView(act);
        btnMod.setText("MOD");
        btnMod.setTextColor(Color.WHITE);
        btnMod.setTextSize(13.0f);
        btnMod.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        btnMod.setGravity(Gravity.CENTER);
        btnMod.setPadding(dp(act, 14), dp(act, 8), dp(act, 14), dp(act, 8));
        btnMod.setBackground(roundBg(BTN_BG, dp(act, 18), ACCENT));
        btnLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        btnLp.token = act.getWindow().getDecorView().getWindowToken();
        btnLp.gravity = Gravity.TOP | Gravity.RIGHT;
        btnLp.x = dp(act, 10);
        btnLp.y = dp(act, 110);

        // --- panel ---
        panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(roundBg(PANEL_BG, dp(act, 16), ACCENT));

        // header: seret untuk memindah panel; tombol — untuk minimize
        LinearLayout header = new LinearLayout(act);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(act, 16), dp(act, 10), dp(act, 8), dp(act, 2));
        TextView title = new TextView(act);
        title.setText("POOL MOD  ≡");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15.0f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        header.addView(title);
        TextView minBtn = new TextView(act);
        minBtn.setText(" — ");
        minBtn.setTextColor(Color.WHITE);
        minBtn.setTextSize(17.0f);
        minBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        minBtn.setPadding(dp(act, 10), dp(act, 2), dp(act, 10), dp(act, 2));
        minBtn.setBackground(roundBg(Color.rgb(35, 55, 70), dp(act, 10), 0));
        minBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                panelIntent = false;
                hidePanelView();
            }
        });
        header.addView(minBtn);
        panel.addView(header);

        LinearLayout content = new LinearLayout(act);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(act, 16), dp(act, 4), dp(act, 16), dp(act, 14));

        TextView sub = new TextView(act);
        sub.setText("Khusus mode offline (VS CPU, Time, Arcade, Tutorial)."
                + " Seret header untuk memindah panel.");
        sub.setTextColor(Color.rgb(170, 185, 195));
        sub.setTextSize(11.0f);
        content.addView(sub);

        statusView = new TextView(act);
        statusView.setTextColor(Color.rgb(140, 230, 175));
        statusView.setTextSize(12.0f);
        statusView.setPadding(0, dp(act, 8), 0, dp(act, 4));
        content.addView(statusView);

        // baris diagnosis: alasan prediksi tidak menggambar, keputusan
        // autoplay terakhir, status pemicu Auto, status magnet
        diagView = new TextView(act);
        diagView.setTextColor(Color.rgb(165, 180, 190));
        diagView.setTextSize(10.5f);
        diagView.setPadding(0, 0, 0, dp(act, 4));
        content.addView(diagView);

        addSwitch(act, content, "Prediksi penuh", ModState.predictOn,
                new BoolSetter() {
                    @Override
                    public void accept(boolean v) {
                        ModState.predictOn = v;
                    }
                });
        addSwitch(act, content, "Long line", ModState.longLineOn,
                new BoolSetter() {
                    @Override
                    public void accept(boolean v) {
                        ModState.longLineOn = v;
                    }
                });
        Switch swAuto = new Switch(act);
        swAuto.setText("Auto Play");
        swAutoState = swAuto;
        swAuto.setTextColor(Color.WHITE);
        swAuto.setChecked(ModState.autoOn);
        swAuto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                ModState.autoOn = checked;
                if (checked) {
                    ModState.autoFailCount = 0;
                }
                // mesin status pemicu mulai bersih setiap saklar ON
                Hooks.resetAutoTrigger();
                savePrefs(act);
            }
        });
        content.addView(swAuto);

        addSwitch(act, content, "Anti-Scratch Total (bola putih kebal lubang)",
                ModState.antiScratch,
                new BoolSetter() {
                    @Override
                    public void accept(boolean v) {
                        ModState.antiScratch = v;
                    }
                });
        addSwitch(act, content, "Magnet Lubang (bola ngejar lubang)",
                ModState.magnetOn,
                new BoolSetter() {
                    @Override
                    public void accept(boolean v) {
                        ModState.magnetOn = v;
                        if (v) {
                            ModState.magnetStatus = "aktif — menunggu tembakanmu";
                        }
                    }
                });
        addSwitch(act, content, "Prank: rel mati saat giliran AI (VS CPU)",
                ModState.prankOn,
                new BoolSetter() {
                    @Override
                    public void accept(boolean v) {
                        ModState.prankOn = v;
                    }
                });

        // pemilih kesulitan Auto Play: ketuk = putar
        // Normal -> Expert -> Mustahil; bawaan Expert.
        diffBtn = new Button(act);
        diffBtn.setText(diffLabel());
        diffBtn.setTextColor(Color.WHITE);
        diffBtn.setBackground(roundBg(Color.rgb(35, 55, 70), dp(act, 10), 0));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(act, 8);
        diffBtn.setLayoutParams(dlp);
        diffBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ModState.difficulty = (ModState.difficulty + 1) % 3;
                diffBtn.setText(diffLabel());
                savePrefs(act);
            }
        });
        content.addView(diffBtn);

        Button auto = new Button(act);
        auto.setText("AUTO PLAY");
        auto.setTextColor(Color.WHITE);
        auto.setBackground(roundBg(Color.rgb(22, 110, 62), dp(act, 10), 0));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(act, 10);
        auto.setLayoutParams(alp);
        auto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onAutoPlay(act);
            }
        });
        content.addView(auto);

        TextView note = new TextView(act);
        note.setText("Di mode PvP online semua fitur mod mati total.");
        note.setTextColor(Color.rgb(150, 160, 168));
        note.setTextSize(10.5f);
        note.setPadding(0, dp(act, 8), 0, 0);
        content.addView(note);

        ScrollView scroll = new ScrollView(act);
        scroll.addView(content);
        panel.addView(scroll);

        panelLp = new WindowManager.LayoutParams(
                dp(act, 264),
                dp(act, 330),
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        panelLp.token = act.getWindow().getDecorView().getWindowToken();
        panelLp.gravity = Gravity.TOP | Gravity.RIGHT;
        panelLp.x = dp(act, 10);
        panelLp.y = dp(act, 160);

        // listener seret: tombol MOD (ketuk = buka/tutup panel),
        // header panel (seret saja; root jendelanya = panel)
        btnMod.setOnTouchListener(new DragListener(btnMod, btnLp, new Runnable() {
            @Override
            public void run() {
                panelIntent = !panelIntent;
                applyPanelVisibility();
            }
        }));
        header.setOnTouchListener(new DragListener(panel, panelLp, null));
    }

    private static void savePrefs(Activity act) {
        try {
            act.getSharedPreferences("poolmod", Context.MODE_PRIVATE).edit()
                    .putBoolean("predictOn", ModState.predictOn)
                    .putBoolean("longLineOn", ModState.longLineOn)
                    .putBoolean("autoOn", ModState.autoOn)
                    .putInt("diff", ModState.difficulty)
                    .putBoolean("antiScratch", ModState.antiScratch)
                    .putBoolean("magnetOn", ModState.magnetOn)
                    .putBoolean("prankOn", ModState.prankOn)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private static void onAutoPlay(Activity act) {
        if (!Hooks.gateAllows() || ModState.pvpLatched) {
            Toast.makeText(act, "Mod nonaktif di mode ini (PvP / belum di meja).", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ModState.autoBusy) {
            Toast.makeText(act, "Sedang menghitung tembakan…", Toast.LENGTH_SHORT).show();
            return;
        }
        int st = ModState.stateNow;
        if (st == 2) {
            Toast.makeText(act, "Bola masih bergerak.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (st == 3 || (st == 1 && ModState.humanTurn)) {
            ModState.autoRequested = true;
            Toast.makeText(act, "Menghitung tembakan terbaik…", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(act, "Belum giliranmu.", Toast.LENGTH_SHORT).show();
        }
    }

    // ----------------------------------------------------------------
    private static boolean gateWantsMenu() {
        Activity act = actRef == null ? null : actRef.get();
        return act != null && !act.isFinishing()
                && ModState.inGame
                && System.currentTimeMillis() - ModState.lastTickMs < 1200L
                && !ModState.pvpLatched;
    }

    private static void showBtnIfWanted() {
        boolean want = gateWantsMenu();
        if (want && !btnAdded && wm != null && btnMod != null) {
            try {
                wm.addView(btnMod, btnLp);
                btnAdded = true;
            } catch (Throwable ignored) {
            }
        } else if (!want && btnAdded) {
            hidePanelView();
            try {
                wm.removeView(btnMod);
            } catch (Throwable ignored) {
            }
            btnAdded = false;
        }
        applyPanelVisibility();
        if (panelAdded) {
            updateStatus();
        }
    }

    /** Visibilitas panel = maksud user (intent) DAN gerbang sesi. */
    private static void applyPanelVisibility() {
        if (panelIntent && gateWantsMenu() && !panelAdded && wm != null && panel != null) {
            try {
                wm.addView(panel, panelLp);
                panelAdded = true;
                updateStatus();
            } catch (Throwable ignored) {
            }
        } else if ((!panelIntent || !gateWantsMenu()) && panelAdded) {
            hidePanelView();
        }
    }

    private static void hidePanelView() {
        if (panelAdded && wm != null && panel != null) {
            try {
                wm.removeView(panel);
            } catch (Throwable ignored) {
            }
        }
        panelAdded = false;
    }

    private static void removeViews() {
        hidePanelView();
        if (btnAdded && wm != null && btnMod != null) {
            try {
                wm.removeView(btnMod);
            } catch (Throwable ignored) {
            }
        }
        btnAdded = false;
    }

    private static void updateStatus() {
        if (statusView == null) {
            return;
        }
        // sinkronkan saklar Auto Play dengan keadaan sebenarnya —
        // saklar bisa mati sendiri (3x gagal / masuk PvP) dan itu
        // harus terlihat di menu, bukan diam-diam.
        if (swAutoState != null && swAutoState.isChecked() != ModState.autoOn) {
            swAutoState.setChecked(ModState.autoOn);
        }
        if (diffBtn != null) {
            diffBtn.setText(diffLabel());
        }
        String mode = ModState.modeLabel;
        String m;
        if (mode.contains("Vsai8")) {
            m = "VS CPU 8-Ball";
        } else if (mode.contains("Vsai9")) {
            m = "VS CPU 9-Ball";
        } else if (mode.contains("Time")) {
            m = "Time";
        } else if (mode.contains("Level")) {
            m = "Arcade";
        } else if (mode.contains("Tutorial")) {
            m = "Tutorial";
        } else {
            m = mode;
        }
        String st;
        if (ModState.statusText != null && !ModState.statusText.isEmpty()
                && System.currentTimeMillis() - ModState.statusMs < 6000L) {
            st = ModState.statusText;
        } else if (ModState.autoBusy) {
            st = "menghitung tembakan…";
        } else if (ModState.stateNow == 1) {
            st = ModState.humanTurn ? "giliranmu — silakan bidik" : "giliran lawan / AI";
        } else if (ModState.stateNow == 2) {
            st = "bola bergerak";
        } else if (ModState.stateNow == 3) {
            st = "freeball — AUTO PLAY menempatkan bola";
        } else if (ModState.stateNow == 4) {
            st = "ronde selesai";
        } else {
            st = "-";
        }
        statusView.setText(m + " • " + st);
        if (diagView != null) {
            StringBuilder sb = new StringBuilder();
            if (ModState.predictDiag != null && !ModState.predictDiag.isEmpty()) {
                sb.append(ModState.predictDiag);
            }
            if (ModState.autoReport != null && !ModState.autoReport.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(ModState.autoReport);
            }
            if (ModState.autoOn && ModState.autoStatus != null
                    && !ModState.autoStatus.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append("Auto: ").append(ModState.autoStatus);
            }
            if (ModState.magnetOn && ModState.magnetStatus != null
                    && !ModState.magnetStatus.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append("Magnet: ").append(ModState.magnetStatus);
            }
            diagView.setText(sb.toString());
        }
    }

    private static void startWatchdog() {
        if (watchdogOn || ui == null) {
            return;
        }
        watchdogOn = true;
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                showBtnIfWanted();
                ui.postDelayed(this, 300L);
            }
        }, 300L);
    }

    /**
     * Seret untuk memindah jendela (gravitasi TOP|RIGHT: x menyusut
     * saat jari ke kanan); ketuk (gerakan kecil) menjalankan onTap
     * bila ada. root = view yang didaftarkan ke WindowManager
     * (tombol MOD / panel), karena updateViewLayout hanya menerima
     * view root jendela.
     */
    private static final class DragListener implements View.OnTouchListener {
        private final View root;
        private final WindowManager.LayoutParams lp;
        private final Runnable onTap;
        private int startX, startY;
        private float downX, downY;
        private boolean moved;

        DragListener(View root, WindowManager.LayoutParams lp, Runnable onTap) {
            this.root = root;
            this.lp = lp;
            this.onTap = onTap;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = lp.x;
                    startY = lp.y;
                    downX = e.getRawX();
                    downY = e.getRawY();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    if (Math.abs(dx) + Math.abs(dy) > 12.0f) {
                        moved = true;
                    }
                    lp.x = Math.max(0, startX - (int) dx);
                    lp.y = Math.max(0, startY + (int) dy);
                    if (wm != null) {
                        try {
                            wm.updateViewLayout(root, lp);
                        } catch (Throwable ignored) {
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved && onTap != null) {
                        onTap.run();
                    }
                    return true;
                default:
                    return false;
            }
        }
    }
}
