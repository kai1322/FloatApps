package com.example.floatapps;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class FloatService extends Service {

    static final String EXTRA_TYPE = "type";
    static FloatService instance;

    final List<FloatWindow> windows = new ArrayList<>();
    WindowManager wm;
    private int counter = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        startFg();
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
        if (intent != null && intent.getStringExtra(EXTRA_TYPE) != null) {
            open(intent.getStringExtra(EXTRA_TYPE));
        }
        return START_NOT_STICKY;
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
        // Only the browser can have several windows; others reuse the existing one.
        if (!FloatWindow.T_WEB.equals(type)) {
            for (FloatWindow w : windows) {
                if (w.type.equals(type)) {
                    focusWindow(w);
                    return;
                }
            }
        }
        int same = 0;
        for (FloatWindow w : windows) if (w.type.equals(type)) same++;
        String title = titleFor(type) + (same > 0 ? " " + (same + 1) : "");
        try {
            FloatWindow w = new FloatWindow(this, type, title, counter++);
            w.show();
            windows.add(w);
            w.refreshList();
            notifyChanged();
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
        if (windows.isEmpty()) stopSelf();
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

    @Override
    public void onDestroy() {
        for (FloatWindow w : new ArrayList<>(windows)) w.close();
        windows.clear();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
