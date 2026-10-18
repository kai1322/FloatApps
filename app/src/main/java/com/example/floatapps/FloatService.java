package com.example.floatapps;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FloatService extends Service {

    static final String EXTRA_TYPE = "type";
    static FloatService instance;
    private static boolean crashLogSet;

    final List<FloatWindow> windows = new ArrayList<>();
    WindowManager wm;
    private int counter = 0;

    // open windows are remembered so they come back if the system kills the app
    private final Handler hs = new Handler(Looper.getMainLooper());
    private final Runnable saveTask = this::saveSession;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        installCrashLog();
        startFg();
    }

    /** Writes the last uncaught error to files/crash.txt (then lets Android handle it as usual). */
    private void installCrashLog() {
        if (crashLogSet) return;
        crashLogSet = true;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        final File f = new File(getFilesDir(), "crash.txt");
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                Files.write(f.toPath(), (System.currentTimeMillis() + "\n" + sw)
                        .getBytes(StandardCharsets.UTF_8));
            } catch (Throwable ignored) {
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    private void startFg() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(
                new NotificationChannel("float", "Float Apps", NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "float")
                .setContentTitle("Float Apps")
                .setContentText("แตะเพื่อเปิดเมนู")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null) {
            // restarted by the system after the app was killed: bring the windows back,
            // but give up if that keeps failing (a page that crashes the app every time)
            long now = System.currentTimeMillis();
            SharedPreferences pr = prefs();
            int n = (now - pr.getLong("restore_t", 0) < 120000) ? pr.getInt("restore_n", 0) + 1 : 1;
            pr.edit().putLong("restore_t", now).putInt("restore_n", n).apply();
            if (n > 3) {
                pr.edit().remove("session").apply();
                stopSelf();
                return START_NOT_STICKY;
            }
            if (windows.isEmpty()) restoreSession();
            if (windows.isEmpty()) {
                stopSelf();
                return START_NOT_STICKY;
            }
            return START_STICKY;
        }
        if (intent.getStringExtra(EXTRA_TYPE) != null) {
            open(intent.getStringExtra(EXTRA_TYPE));
        }
        return START_STICKY;
    }

    private String titleFor(String type) {
        switch (type) {
            case FloatWindow.T_FB: return "Facebook Lite";
            case FloatWindow.T_YT: return "YouTube";
            case FloatWindow.T_WEB: return "เบราว์เซอร์";
            case FloatWindow.T_TR: return "แปล";
            case FloatWindow.T_EDIT: return "โปรแกรมแก้ไข";
            default: return "หน้าต่างที่เปิดอยู่";
        }
    }

    void open(String type) {
        open(type, null);
    }

    /** sess != null when a window is being restored from the saved session. */
    void open(String type, JSONObject sess) {
        // Only the browser can have several windows; others reuse the existing one.
        if (!FloatWindow.T_WEB.equals(type)) {
            for (FloatWindow w : windows) {
                if (w.type.equals(type)) {
                    if (sess == null) focusWindow(w);
                    return;
                }
            }
        }
        int same = 0;
        for (FloatWindow w : windows) if (w.type.equals(type)) same++;
        String title = titleFor(type) + (same > 0 ? " " + (same + 1) : "");
        try {
            FloatWindow w = new FloatWindow(this, type, title, counter++, sess);
            w.show();
            windows.add(w);
            if (sess != null && sess.optBoolean("minimized", false)) w.minimizeToBubble();
            w.refreshList();
            notifyChanged();
            updatePower();
            scheduleSave();
        } catch (Exception e) {
            Toast.makeText(this, "เปิดหน้าต่างไม่ได้: " + e.getMessage(), Toast.LENGTH_LONG).show();
            if (windows.isEmpty()) stopSelf();
        }
    }

    void focusWindow(FloatWindow w) {
        w.bringToFront();
        notifyChanged();
    }

    void closeWindow(FloatWindow w) {
        w.close();
        windows.remove(w);
        notifyChanged();
        updatePower();
        if (windows.isEmpty()) stopSelf();
        else scheduleSave();
    }

    void closeAll() {
        for (FloatWindow w : new ArrayList<>(windows)) w.close();
        windows.clear();
        stopSelf();
    }

    // ---- used by the settings screen ----
    void applyStyleAll() {
        for (FloatWindow w : windows) {
            w.applyStyle();
            w.applyBubbleSize();
        }
    }

    int saveAllGeoms() {
        int n = 0;
        for (FloatWindow w : windows) if (w.saveGeomQuiet()) n++;
        return n;
    }

    int saveAllBubbles() {
        int n = 0;
        for (FloatWindow w : windows) {
            if (w.minimized) {
                w.saveBubbleQuiet();
                n++;
            }
        }
        return n;
    }

    void notifyChanged() {
        for (FloatWindow w : windows) w.refreshList();
    }

    // ---- saving the open windows so they survive the app being killed ----
    void scheduleSave() {
        hs.removeCallbacks(saveTask);
        hs.postDelayed(saveTask, 1500);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("float_prefs", MODE_PRIVATE);
    }

    void saveSession() {
        if (windows.isEmpty()) return;
        JSONArray a = new JSONArray();
        for (FloatWindow w : windows) {
            if (w.type.equals(FloatWindow.T_LIST)) continue;
            try {
                a.put(w.toJson());
            } catch (Exception ignored) {
            }
        }
        prefs().edit().putString("session", a.toString()).apply();
    }

    private void restoreSession() {
        String s = prefs().getString("session", null);
        if (s == null) return;
        try {
            JSONArray a = new JSONArray(s);
            List<String> ok = Arrays.asList(FloatWindow.ALL_TYPES);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String t = o.optString("type", "");
                if (ok.contains(t)) open(t, o);
            }
        } catch (Exception ignored) {
        }
    }

    // ---- saving battery / CPU / RAM ----

    /**
     * When every web window is minimized (and none of them is YouTube, which must keep
     * playing), stop all page timers/JavaScript. They start again as soon as a window is opened.
     */
    void updatePower() {
        boolean any = false;
        boolean idle = true;
        for (FloatWindow w : windows) {
            if (!w.hasWeb()) continue;
            any = true;
            if (!w.minimized || w.type.equals(FloatWindow.T_YT)) {
                idle = false;
                break;
            }
        }
        try {
            if (any && idle) WebView.pauseTimers();
            else WebView.resumeTimers();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // 10/15 = running low/critical on memory; 60/80 = system is reclaiming
        boolean pressure = (level >= 10 && level < 20) || level >= 60;
        boolean critical = (level >= 15 && level < 20) || level >= 80;
        if (!pressure) return;
        for (FloatWindow w : new ArrayList<>(windows)) {
            try {
                w.trimMemory(critical);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onDestroy() {
        for (FloatWindow w : new ArrayList<>(windows)) w.close();
        windows.clear();
        hs.removeCallbacks(saveTask);
        // a normal stop (not a kill) means the user is done: forget the saved windows
        prefs().edit().remove("session").apply();
        try {
            WebView.resumeTimers();
        } catch (Exception ignored) {
        }
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
