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
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;

/**
 * Menu melayang mod: jendela WindowManager terpisah bertipe
 * TYPE_APPLICATION_PANEL pada token jendela activity (tanpa izin
 * overlay sistem), di atas permukaan GL game — pola yang sama dengan
 * mod gabung user sebelumnya.
 *
 * Tombol "MOD" bisa digeser; ketuk membuka panel berisi saklar
 * Long Line / Prediksi Penuh dan tombol AUTO PLAY. Watchdog 300 ms
 * menyembunyikan seluruh menu saat tidak di dalam permainan (tidak ada
 * tick GameScene) dan — mutlak — selama mode PvP (gerbang juga
 * ditegakkan di Hooks/Auto/Predict, menu hanya lapisan UI).
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
    private static boolean panelOpen;
    private static TextView statusView;
    private static TextView diagView;
    private static Switch swAutoState;
    private static Button diffBtn;
    private static boolean watchdogOn;

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

        // --- tombol MOD ---
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
        btnMod.setOnTouchListener(new DragTapListener());

        // --- panel ---
        panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(act, 16), dp(act, 14), dp(act, 16), dp(act, 14));
        panel.setBackground(roundBg(PANEL_BG, dp(act, 16), ACCENT));

        TextView title = new TextView(act);
        title.setText("POOL MOD");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15.0f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        panel.addView(title);

        TextView sub = new TextView(act);
        sub.setText("Khusus mode offline (VS CPU, Time, Arcade, Tutorial).");
        sub.setTextColor(Color.rgb(170, 185, 195));
        sub.setTextSize(11.0f);
        panel.addView(sub);

        statusView = new TextView(act);
        statusView.setTextColor(Color.rgb(140, 230, 175));
        statusView.setTextSize(12.0f);
        statusView.setPadding(0, dp(act, 8), 0, dp(act, 4));
        panel.addView(statusView);

        // baris diagnosis: alasan prediksi tidak menggambar + keputusan
        // autoplay terakhir (grup legal, kandidat, penolakan, pilihan)
        diagView = new TextView(act);
        diagView.setTextColor(Color.rgb(165, 180, 190));
        diagView.setTextSize(10.5f);
        diagView.setPadding(0, 0, 0, dp(act, 4));
        panel.addView(diagView);

        Switch swPredict = new Switch(act);
        swPredict.setText("Prediksi penuh");
        swPredict.setTextColor(Color.WHITE);
        swPredict.setChecked(ModState.predictOn);
        swPredict.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                ModState.predictOn = checked;
                savePrefs(act);
            }
        });
        panel.addView(swPredict);

        Switch swLong = new Switch(act);
        swLong.setText("Long line");
        swLong.setTextColor(Color.WHITE);
        swLong.setChecked(ModState.longLineOn);
        swLong.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                ModState.longLineOn = checked;
                savePrefs(act);
            }
        });
        panel.addView(swLong);

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
                // posisi meja saat ini tidak boleh langsung ditembak:
                // lupakan probe & tahan sampai meja berubah/stabil lagi
                ModState.autoProbeKey = Long.MIN_VALUE;
                ModState.autoHoldKey = Long.MIN_VALUE;
                ModState.autoPair = -1;
                savePrefs(act);
            }
        });
        panel.addView(swAuto);

        // pemilih kesulitan Auto Play (Perintah 6): ketuk = putar
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
        panel.addView(diffBtn);

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
        panel.addView(auto);

        TextView note = new TextView(act);
        note.setText("Di mode PvP online semua fitur mod mati total.");
        note.setTextColor(Color.rgb(150, 160, 168));
        note.setTextSize(10.5f);
        note.setPadding(0, dp(act, 8), 0, 0);
        panel.addView(note);

        panelLp = new WindowManager.LayoutParams(
                dp(act, 264),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        panelLp.token = act.getWindow().getDecorView().getWindowToken();
        panelLp.gravity = Gravity.TOP | Gravity.RIGHT;
        panelLp.x = dp(act, 10);
        panelLp.y = dp(act, 160);
    }

    private static void savePrefs(Activity act) {
        try {
            act.getSharedPreferences("poolmod", Context.MODE_PRIVATE).edit()
                    .putBoolean("predictOn", ModState.predictOn)
                    .putBoolean("longLineOn", ModState.longLineOn)
                    .putBoolean("autoOn", ModState.autoOn)
                    .putInt("diff", ModState.difficulty)
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
    private static void showBtnIfWanted() {
        Activity act = actRef == null ? null : actRef.get();
        boolean want = act != null && !act.isFinishing()
                && ModState.inGame
                && System.currentTimeMillis() - ModState.lastTickMs < 1200L
                && !ModState.pvpLatched;
        if (want && !btnAdded && wm != null && btnMod != null) {
            try {
                wm.addView(btnMod, btnLp);
                btnAdded = true;
            } catch (Throwable ignored) {
            }
        } else if (!want && btnAdded) {
            closePanel();
            try {
                wm.removeView(btnMod);
            } catch (Throwable ignored) {
            }
            btnAdded = false;
        }
        if (panelOpen) {
            updateStatus();
        }
    }

    private static void togglePanel() {
        if (panelOpen) {
            closePanel();
            return;
        }
        Activity act = actRef == null ? null : actRef.get();
        if (act == null || wm == null || panel == null) {
            return;
        }
        try {
            wm.addView(panel, panelLp);
            panelAdded = true;
            panelOpen = true;
            updateStatus();
        } catch (Throwable ignored) {
        }
    }

    private static void closePanel() {
        if (panelAdded && wm != null && panel != null) {
            try {
                wm.removeView(panel);
            } catch (Throwable ignored) {
            }
        }
        panelAdded = false;
        panelOpen = false;
    }

    private static void removeViews() {
        closePanel();
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

    /** Seret untuk memindah tombol; ketuk (gerakan kecil) buka/tutup panel. */
    private static final class DragTapListener implements View.OnTouchListener {
        private int startX, startY;
        private float downX, downY;
        private boolean moved;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = btnLp.x;
                    startY = btnLp.y;
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
                    btnLp.x = Math.max(0, startX - (int) dx);
                    btnLp.y = Math.max(0, startY + (int) dy);
                    if (btnAdded && wm != null) {
                        try {
                            wm.updateViewLayout(btnMod, btnLp);
                        } catch (Throwable ignored) {
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved) {
                        togglePanel();
                    }
                    return true;
                default:
                    return false;
            }
        }
    }
}
