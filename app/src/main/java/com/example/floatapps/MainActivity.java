package com.example.floatapps;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView status;
    private Button permBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Float Apps");
        title.setTextSize(26);
        box.addView(title);

        status = new TextView(this);
        status.setTextSize(14);
        status.setPadding(0, pad / 2, 0, pad / 2);
        box.addView(status);

        permBtn = new Button(this);
        permBtn.setText("ให้สิทธิ์แสดงทับแอปอื่น");
        permBtn.setOnClickListener(v -> askOverlay());
        box.addView(permBtn);

        String[][] items = {
                {FloatWindow.T_FB, "Facebook Lite"},
                {FloatWindow.T_YT, "YouTube"},
                {FloatWindow.T_WEB, "เบราว์เซอร์"},
                {FloatWindow.T_TR, "แปล"},
                {FloatWindow.T_EDIT, "โปรแกรมแก้ไข"},
                {FloatWindow.T_LIST, "หน้าต่างที่เปิดอยู่"},
        };
        for (String[] it : items) {
            final String type = it[0];
            Button b = new Button(this);
            b.setText(it[1]);
            b.setTextSize(17);
            b.setOnClickListener(v -> launch(type));
            box.addView(b, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        Button stop = new Button(this);
        stop.setText("ปิดหน้าต่างทั้งหมด");
        stop.setOnClickListener(v -> stopService(new Intent(this, FloatService.class)));
        box.addView(stop);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean ok = Settings.canDrawOverlays(this);
        status.setText(ok ? "พร้อมใช้งาน แตะเพื่อเปิดหน้าต่างลอย"
                : "ต้องให้สิทธิ์ \"แสดงทับแอปอื่น\" ก่อน");
        permBtn.setVisibility(ok ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    private void askOverlay() {
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
    }

    private void launch(String type) {
        if (!Settings.canDrawOverlays(this)) {
            askOverlay();
            return;
        }
        Intent i = new Intent(this, FloatService.class);
        i.putExtra(FloatService.EXTRA_TYPE, type);
        startForegroundService(i);
        moveTaskToBack(true);
    }
}
