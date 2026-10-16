package com.example.floatapps;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** One floating window (or its minimized bubble). */
@SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
public class FloatWindow {

    static final String T_FB = "facebook", T_YT = "youtube", T_WEB = "browser",
            T_TR = "translate", T_EDIT = "editor", T_LIST = "windows";

    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    static final int FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;

    // Third-party ad / tracker hosts that get blocked inside the web windows.
    static final String[] AD_HOSTS = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "amazon-adsystem.com", "adnxs.com", "taboola.com",
            "outbrain.com", "criteo.com", "criteo.net", "2mdn.net", "moatads.com",
            "adsrvr.org", "pubmatic.com", "rubiconproject.com", "openx.net",
            "scorecardresearch.com", "popads.net", "propellerads.com", "adcolony.com",
            "unity3d.com", "applovin.com", "mopub.com", "inmobi.com"
    };

    final FloatService svc;
    final WindowManager wm;
    final String type;
    final String title;
    final Handler h = new Handler(Looper.getMainLooper());

    WinRoot root;
    WindowManager.LayoutParams lp;
    TextView bubble;
    WindowManager.LayoutParams blp;
    boolean rootAttached, bubbleAttached, minimized;

    WebView web;
    EditText urlBar, editor;
    LinearLayout listBox;
    final Runnable saveTask = this::saveNow;

    FloatWindow(FloatService svc, String type, String title, int index) {
        this.svc = svc;
        this.wm = svc.wm;
        this.type = type;
        this.title = title;
        build(index);
    }

    // ---------- helpers ----------
    int dp(int v) {
        return Math.round(v * svc.getResources().getDisplayMetrics().density);
    }

    TextView label(String t, int sp, int color) {
        TextView tv = new TextView(svc);
        tv.setText(t);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        return tv;
    }

    TextView btn(String t, View.OnClickListener l) {
        TextView tv = label(t, 17, Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        tv.setOnClickListener(l);
        return tv;
    }

    static boolean isAd(String host) {
        if (host == null) return false;
        for (String a : AD_HOSTS) {
            if (host.equals(a) || host.endsWith("." + a)) return true;
        }
        return false;
    }

    void updateLayout() {
        if (rootAttached) wm.updateViewLayout(root, lp);
    }

    // ---------- build UI ----------
    void build(int index) {
        DisplayMetrics dm = svc.getResources().getDisplayMetrics();
        int w = Math.min((int) (dm.widthPixels * 0.88f), dp(520));
        int hgt = Math.min((int) (dm.heightPixels * 0.6f), dp(620));

        lp = new WindowManager.LayoutParams(w, hgt,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, FLAGS, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(16) + (index % 6) * dp(28);
        lp.y = dp(60) + (index % 6) * dp(36);
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;

        root = new WinRoot(svc);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1E1E1E);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), 0xFF666666);
        root.setBackground(bg);
        root.setPadding(dp(1), dp(1), dp(1), dp(1));

        LinearLayout col = new LinearLayout(svc);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(MATCH, MATCH));

        // title bar (drag to move)
        LinearLayout bar = new LinearLayout(svc);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF2D2D2D);
        bar.setPadding(dp(12), 0, 0, 0);
        TextView tv = label(title, 14, Color.WHITE);
        tv.setSingleLine(true);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(tv, new LinearLayout.LayoutParams(0, MATCH, 1f));
        bar.addView(btn("—", v -> minimize()), new LinearLayout.LayoutParams(dp(46), MATCH));
        bar.addView(btn("✕", v -> svc.closeWindow(this)), new LinearLayout.LayoutParams(dp(46), MATCH));
        col.addView(bar, new LinearLayout.LayoutParams(MATCH, dp(40)));

        bar.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy;
            int sx, sy;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX();
                        dy = e.getRawY();
                        sx = lp.x;
                        sy = lp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = sx + (int) (e.getRawX() - dx);
                        lp.y = sy + (int) (e.getRawY() - dy);
                        updateLayout();
                        return true;
                }
                return false;
            }
        });

        View content;
        switch (type) {
            case T_FB:   content = buildWeb("https://m.facebook.com/"); break;
            case T_YT:   content = buildWeb("https://m.youtube.com/"); break;
            case T_TR:   content = buildWeb("https://translate.google.com/?sl=auto&tl=th"); break;
            case T_WEB:  content = buildWeb("https://duckduckgo.com/"); break;
            case T_EDIT: content = buildEditor(); break;
            default:     content = buildList(); break;
        }
        col.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        // resize handle (bottom-right)
        TextView rz = label("◢", 18, 0xFFBBBBBB);
        rz.setGravity(Gravity.CENTER);
        root.addView(rz, new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM | Gravity.END));
        rz.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy;
            int sw, sh;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX();
                        dy = e.getRawY();
                        sw = lp.width;
                        sh = lp.height;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.width = Math.max(dp(200), sw + (int) (e.getRawX() - dx));
                        lp.height = Math.max(dp(160), sh + (int) (e.getRawY() - dy));
                        updateLayout();
                        return true;
                }
                return false;
            }
        });
    }

    // ---------- web window ----------
    View buildWeb(String startUrl) {
        LinearLayout box = new LinearLayout(svc);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout tb = new LinearLayout(svc);
        tb.setOrientation(LinearLayout.HORIZONTAL);
        tb.setGravity(Gravity.CENTER_VERTICAL);
        tb.setBackgroundColor(0xFF333333);

        tb.addView(btn("◀", v -> {
            if (web != null && web.canGoBack()) web.goBack();
        }), new LinearLayout.LayoutParams(dp(40), MATCH));
        tb.addView(btn("⟳", v -> {
            if (web != null) web.reload();
        }), new LinearLayout.LayoutParams(dp(40), MATCH));

        urlBar = new EditText(svc);
        urlBar.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlBar.setMaxLines(1);
        urlBar.setTextSize(13);
        urlBar.setTextColor(Color.BLACK);
        urlBar.setBackgroundColor(0xFFEEEEEE);
        urlBar.setPadding(dp(8), 0, dp(8), 0);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBar.setOnEditorActionListener((v, actionId, ev) -> {
            go(urlBar.getText().toString());
            return true;
        });
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(0, dp(32), 1f);
        up.setMargins(dp(2), 0, dp(6), 0);
        tb.addView(urlBar, up);
        box.addView(tb, new LinearLayout.LayoutParams(MATCH, dp(38)));

        web = new WebView(svc);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                if (isAd(r.getUrl().getHost())) {
                    return new WebResourceResponse("text/plain", "utf-8",
                            new ByteArrayInputStream(new byte[0]));
                }
                return super.shouldInterceptRequest(v, r);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String sch = r.getUrl().getScheme();
                return !("http".equals(sch) || "https".equals(sch));
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                if (urlBar != null && !urlBar.hasFocus()) urlBar.setText(url);
            }
        });
        box.addView(web, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        web.loadUrl(startUrl);
        return box;
    }

    void go(String in) {
        in = in.trim();
        if (in.isEmpty() || web == null) return;
        String u;
        if (in.startsWith("http://") || in.startsWith("https://")) u = in;
        else if (in.contains(".") && !in.contains(" ")) u = "https://" + in;
        else u = "https://duckduckgo.com/?q=" + Uri.encode(in);
        web.loadUrl(u);
        urlBar.clearFocus();
        InputMethodManager imm = (InputMethodManager) svc.getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
    }

    // ---------- text editor window ----------
    View buildEditor() {
        LinearLayout box = new LinearLayout(svc);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout tb = new LinearLayout(svc);
        tb.setOrientation(LinearLayout.HORIZONTAL);
        tb.setBackgroundColor(0xFF333333);
        TextView copy = btn("คัดลอก", v -> {
            ClipboardManager cm = (ClipboardManager) svc.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("note", editor.getText()));
            Toast.makeText(svc, "คัดลอกแล้ว", Toast.LENGTH_SHORT).show();
        });
        TextView clear = btn("ล้าง", v -> editor.setText(""));
        copy.setTextSize(14);
        clear.setTextSize(14);
        copy.setPadding(dp(14), 0, dp(14), 0);
        clear.setPadding(dp(14), 0, dp(14), 0);
        tb.addView(copy, new LinearLayout.LayoutParams(WRAP, dp(34)));
        tb.addView(clear, new LinearLayout.LayoutParams(WRAP, dp(34)));
        box.addView(tb, new LinearLayout.LayoutParams(MATCH, dp(34)));

        editor = new EditText(svc);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setTextColor(Color.WHITE);
        editor.setHintTextColor(0xFF888888);
        editor.setHint("พิมพ์ข้อความที่นี่ (บันทึกอัตโนมัติ)");
        editor.setBackgroundColor(0xFF1E1E1E);
        editor.setPadding(dp(10), dp(8), dp(10), dp(8));
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);

        try {
            File f = new File(svc.getFilesDir(), "note.txt");
            if (f.exists()) {
                editor.setText(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                h.removeCallbacks(saveTask);
                h.postDelayed(saveTask, 600);
            }
        });
        box.addView(editor, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        return box;
    }

    void saveNow() {
        if (editor == null) return;
        try {
            File f = new File(svc.getFilesDir(), "note.txt");
            Files.write(f.toPath(), editor.getText().toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    // ---------- "open windows" list ----------
    View buildList() {
        ScrollView sv = new ScrollView(svc);
        listBox = new LinearLayout(svc);
        listBox.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listBox, new FrameLayout.LayoutParams(MATCH, WRAP));
        // list is filled by refreshList() once this window is registered in the service
        return sv;
    }

    void refreshList() {
        if (listBox == null) return;
        listBox.removeAllViews();
        int n = 0;
        for (final FloatWindow w : svc.windows) {
            if (w == this) continue;
            n++;
            TextView row = label((w.minimized ? "◻  " : "◼  ") + w.title
                    + (w.minimized ? "   (ย่ออยู่)" : ""), 15, Color.WHITE);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setOnClickListener(v -> svc.focusWindow(w));
            listBox.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        if (n == 0) {
            TextView none = label("ไม่มีหน้าต่างอื่นที่เปิดอยู่", 14, 0xFFAAAAAA);
            none.setPadding(dp(14), dp(14), dp(14), dp(14));
            listBox.addView(none);
        }
        TextView all = label("ปิดทั้งหมด", 15, 0xFFFF6B6B);
        all.setPadding(dp(14), dp(16), dp(14), dp(16));
        all.setOnClickListener(v -> svc.closeAll());
        listBox.addView(all, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    // ---------- show / minimize / restore / close ----------
    void show() {
        lp.flags = FLAGS;
        wm.addView(root, lp);
        rootAttached = true;
    }

    void minimize() {
        if (minimized || !rootAttached) return;
        wm.removeView(root);
        rootAttached = false;
        minimized = true;
        showBubble();
        svc.notifyChanged();
    }

    void restore() {
        if (!minimized) return;
        hideBubble();
        minimized = false;
        lp.flags = FLAGS;
        wm.addView(root, lp);
        rootAttached = true;
        svc.notifyChanged();
    }

    void bringToFront() {
        if (minimized) {
            restore();
            return;
        }
        if (rootAttached) {
            wm.removeView(root);
            lp.flags = FLAGS;
            wm.addView(root, lp);
        }
    }

    void close() {
        if (type.equals(T_EDIT)) saveNow();
        h.removeCallbacksAndMessages(null);
        if (rootAttached) {
            wm.removeView(root);
            rootAttached = false;
        }
        hideBubble();
        if (web != null) {
            ViewGroup p = (ViewGroup) web.getParent();
            if (p != null) p.removeView(web);
            web.stopLoading();
            web.destroy();
            web = null;
        }
    }

    // ---------- bubble (minimized) ----------
    String bubbleIcon() {
        switch (type) {
            case T_FB: return "f";
            case T_YT: return "▶";
            case T_WEB: return "◎";
            case T_TR: return "A文";
            case T_EDIT: return "✎";
            default: return "▤";
        }
    }

    void showBubble() {
        if (bubble == null) {
            bubble = label(bubbleIcon(), 18, Color.WHITE);
            bubble.setGravity(Gravity.CENTER);
            GradientDrawable oval = new GradientDrawable();
            oval.setShape(GradientDrawable.OVAL);
            oval.setColor(0xFF0D6EFD);
            bubble.setBackground(oval);
            blp = new WindowManager.LayoutParams(dp(52), dp(52),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            blp.gravity = Gravity.TOP | Gravity.START;
            bubble.setOnTouchListener(new View.OnTouchListener() {
                float dx, dy;
                int sx, sy;
                boolean dragging;

                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            dx = e.getRawX();
                            dy = e.getRawY();
                            sx = blp.x;
                            sy = blp.y;
                            dragging = false;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            float mx = e.getRawX() - dx, my = e.getRawY() - dy;
                            if (Math.abs(mx) > dp(8) || Math.abs(my) > dp(8)) dragging = true;
                            if (dragging && bubbleAttached) {
                                blp.x = sx + (int) mx;
                                blp.y = sy + (int) my;
                                wm.updateViewLayout(bubble, blp);
                                // remember the spot so the window reopens there
                                lp.x = blp.x;
                                lp.y = blp.y;
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                            if (!dragging) h.post(FloatWindow.this::restore);
                            return true;
                    }
                    return false;
                }
            });
        }
        blp.x = lp.x;
        blp.y = lp.y;
        wm.addView(bubble, blp);
        bubbleAttached = true;
    }

    void hideBubble() {
        if (bubbleAttached) {
            wm.removeView(bubble);
            bubbleAttached = false;
        }
    }

    // ---------- root view: handles keyboard focus toggling ----------
    class WinRoot extends FrameLayout {
        WinRoot(Context c) {
            super(c);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_OUTSIDE) {
                if ((lp.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0) {
                    InputMethodManager imm = (InputMethodManager)
                            getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(getWindowToken(), 0);
                    lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                    updateLayout();
                }
                return true;
            }
            if (a == MotionEvent.ACTION_DOWN
                    && (lp.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
                lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                updateLayout();
            }
            return super.dispatchTouchEvent(e);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
                if (e.getAction() == KeyEvent.ACTION_UP) web.goBack();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
    }
}
