package com.example.floatapps;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    private SharedPreferences sp;
    private SeekBar sbWin, sbBar;

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String t, int size, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(size);
        if (bold) tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(0, dp(8), 0, dp(4));
        return tv;
    }

    private Button button(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setOnClickListener(l);
        return b;
    }

    private void applyLive() {
        FloatService s = FloatService.instance;
        if (s != null) s.applyStyleAll();
    }

    private SeekBar addSlider(LinearLayout box, final String name, final String key) {
        final TextView lab = text(name + ": " + sp.getInt(key, 0) + "%", 14, false);
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(sp.getInt(key, 0));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                lab.setText(name + ": " + p + "%");
                if (fromUser) {
                    sp.edit().putInt(key, p).apply();
                    applyLive();
                }
            }

            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        box.addView(lab);
        box.addView(sb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return sb;
    }

    private void clearKeys(String suffix1, String suffix2, String suffix3, String suffix4) {
        SharedPreferences.Editor ed = sp.edit();
        for (String t : FloatWindow.ALL_TYPES) {
            ed.remove(t + suffix1).remove(t + suffix2);
            if (suffix3 != null) ed.remove(t + suffix3);
            if (suffix4 != null) ed.remove(t + suffix4);
        }
        ed.apply();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("ตั้งค่า");
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        sp = getSharedPreferences("float_prefs", MODE_PRIVATE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        box.setPadding(pad, pad, pad, pad);

        box.addView(text("จัดหน้าต่างให้ถูกใจก่อน (หน้าต่างลอยอยู่เหนือหน้านี้) แล้วกดบันทึกด้านล่าง",
                13, false));

        // ---- transparency ----
        box.addView(text("ความโปร่งใส (0 = ทึบ, 100 = ใสมาก) บันทึกอัตโนมัติ", 16, true));
        sbWin = addSlider(box, "เนื้อหาหน้าต่าง", "alpha_win");
        sbBar = addSlider(box, "แถบดำด้านบน (ปุ่มไม่จางตาม)", "alpha_bar");
        box.addView(button("รีเซ็ตความโปร่งใส", v -> {
            sp.edit().remove("alpha_win").remove("alpha_bar").apply();
            sbWin.setProgress(0);
            sbBar.setProgress(0);
            applyLive();
        }));

        // ---- minimized icon ----
        box.addView(text("ไอคอนตอนย่อ", 16, true));
        box.addView(text("ขนาด (บันทึกอัตโนมัติ)", 14, false));
        final RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        String[] names = {"เล็ก", "กลาง", "ใหญ่"};
        final int[] ids = new int[3];
        for (int i = 0; i < 3; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(names[i]);
            rb.setId(View.generateViewId());
            ids[i] = rb.getId();
            rg.addView(rb);
        }
        int cur = sp.getInt("bubsize", 1);
        rg.check(ids[(cur < 0 || cur > 2) ? 1 : cur]);
        rg.setOnCheckedChangeListener((g, id) -> {
            for (int i = 0; i < 3; i++) {
                if (ids[i] == id) {
                    sp.edit().putInt("bubsize", i).apply();
                    applyLive();
                }
            }
        });
        box.addView(rg);
        box.addView(text("ย่อหน้าต่างเป็นไอคอน ลากไปตำแหน่งที่ต้องการ แล้วกดปุ่มนี้", 13, false));
        box.addView(button("บันทึกตำแหน่งไอคอนที่ย่ออยู่", v -> {
            FloatService s = FloatService.instance;
            int n = (s == null) ? 0 : s.saveAllBubbles();
            Toast.makeText(this, n > 0 ? "บันทึกไอคอน " + n + " อันแล้ว"
                    : "ยังไม่มีไอคอนที่ย่ออยู่", Toast.LENGTH_SHORT).show();
        }));
        box.addView(button("รีเซ็ตตำแหน่งไอคอนทั้งหมด", v -> {
            clearKeys(".bx", ".by", null, null);
            Toast.makeText(this, "รีเซ็ตแล้ว มีผลตอนเปิดหน้าต่างครั้งถัดไป",
                    Toast.LENGTH_SHORT).show();
        }));

        // ---- YouTube mini player ----
        box.addView(text("มินิเพลเยอร์ YouTube", 16, true));
        box.addView(text("กดปุ่ม — ตอนมีวิดีโอเล่นอยู่ จะหดเป็นช่องเล็ก ลากย้ายได้ (จำตำแหน่งให้อัตโนมัติ)",
                13, false));
        box.addView(text("ขนาดช่อง (บันทึกอัตโนมัติ)", 14, false));
        final RadioGroup rg2 = new RadioGroup(this);
        rg2.setOrientation(RadioGroup.HORIZONTAL);
        final int[] ids2 = new int[3];
        for (int i = 0; i < 3; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(names[i]);
            rb.setId(View.generateViewId());
            ids2[i] = rb.getId();
            rg2.addView(rb);
        }
        int cur2 = sp.getInt("minisize", 1);
        rg2.check(ids2[(cur2 < 0 || cur2 > 2) ? 1 : cur2]);
        rg2.setOnCheckedChangeListener((g, id) -> {
            for (int i = 0; i < 3; i++) {
                if (ids2[i] == id) {
                    sp.edit().putInt("minisize", i).apply();
                    applyLive();
                }
            }
        });
        box.addView(rg2);
        box.addView(button("รีเซ็ตตำแหน่งมินิเพลเยอร์", v -> {
            sp.edit().remove("mini.x").remove("mini.y").apply();
            Toast.makeText(this, "รีเซ็ตแล้ว มีผลตอนย่อครั้งถัดไป", Toast.LENGTH_SHORT).show();
        }));

        // ---- window position / size ----
        box.addView(text("ตำแหน่งและขนาดหน้าต่าง", 16, true));
        box.addView(text("บันทึกเฉพาะหน้าต่างที่เปิดอยู่ (ไม่ได้ย่อ)", 13, false));
        box.addView(button("บันทึกตำแหน่ง/ขนาดหน้าต่างที่เปิดอยู่", v -> {
            FloatService s = FloatService.instance;
            int n = (s == null) ? 0 : s.saveAllGeoms();
            Toast.makeText(this, n > 0 ? "บันทึก " + n + " หน้าต่างแล้ว"
                    : "ยังไม่มีหน้าต่างที่เปิดอยู่", Toast.LENGTH_SHORT).show();
        }));
        box.addView(button("รีเซ็ตตำแหน่ง/ขนาดทั้งหมด", v -> {
            clearKeys(".x", ".y", ".w", ".h");
            Toast.makeText(this, "รีเซ็ตแล้ว มีผลตอนเปิดหน้าต่างครั้งถัดไป",
                    Toast.LENGTH_SHORT).show();
        }));

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        setContentView(sv);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
