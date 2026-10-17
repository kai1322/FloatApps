package com.example.floatapps;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** One floating window (or its minimized bubble). */
@SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
public class FloatWindow {

    static final String T_FB = "facebook", T_YT = "youtube",
            T_WEB = "browser", T_TR = "translate", T_EDIT = "editor", T_LIST = "windows";
    static final String[] ALL_TYPES = {T_FB, T_YT, T_WEB, T_TR, T_EDIT, T_LIST};

    static final String HOME = "https://www.google.com/";

    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    static final int FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

    // bubble (minimized icon) sizes in dp: small / medium / large
    static final int[] BUB_DP = {38, 48, 60};
    static final int[] BUB_TEXT = {13, 16, 20};

    // Third-party ad / tracker hosts that get blocked inside the web windows.
    static final String[] AD_HOSTS = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "amazon-adsystem.com", "adnxs.com", "taboola.com",
            "outbrain.com", "criteo.com", "criteo.net", "2mdn.net", "moatads.com",
            "adsrvr.org", "pubmatic.com", "rubiconproject.com", "openx.net",
            "scorecardresearch.com", "popads.net", "propellerads.com", "adcolony.com",
            "applovin.com", "mopub.com", "inmobi.com"
    };

    final FloatService svc;
    final WindowManager wm;
    final String type;
    final String title;
    final int index;
    final SharedPreferences sp;
    final Handler h = new Handler(Looper.getMainLooper());

    WinRoot root;
    WindowManager.LayoutParams lp;
    LinearLayout col, selBar, linkBar, bar;
    GradientDrawable rootBg;
    View contentView;
    TextView rz, bubble;
    WindowManager.LayoutParams blp;
    boolean rootAttached, bubbleAttached, minimized, bubLongDone;
    int restX, restY;          // where the WINDOW reappears
    int bubX, bubY, bubSize;   // where/how big the minimized ICON is (independent of the window)

    // browser tabs (non-browser windows have exactly one tab)
    class Tab {
        WebView wv;
        String title = "แท็บใหม่";
        LinearLayout chip;
        TextView chipTitle;
    }

    final List<Tab> tabs = new ArrayList<>();
    Tab curTab;
    WebView web; // always the WebView of the current tab
    FrameLayout webHost;
    LinearLayout tabRow;

    EditText editor;
    LinearLayout listBox;
    final Runnable saveTask = this::saveNow;
    final Runnable bubLong = this::bubbleLongPress;
    final Runnable hideLinkTask = this::hideLinkBar;
    // receives the real href of a long-pressed link (browser only)
    final Handler hrefHandler = new Handler(Looper.getMainLooper(), msg -> {
        String u = msg.getData().getString("url");
        if (u != null && !u.isEmpty()) showLinkBar(u);
        return true;
    });

    // fullscreen video state
    View customView;
    WebChromeClient.CustomViewCallback customCb;
    int prevX, prevY, prevW, prevH;

    // text-selection toolbar state
    MiniActionMode selMode;

    FloatWindow(FloatService svc, String type, String title, int index) {
        this.svc = svc;
        this.wm = svc.wm;
        this.type = type;
        this.title = title;
        this.index = index;
        this.sp = svc.getSharedPreferences("float_prefs", Context.MODE_PRIVATE);
        build();
    }

    // ---------- helpers ----------
    int dp(int v) {
        return Math.round(v * svc.getResources().getDisplayMetrics().density);
    }

    TextView label(String t, int size, int color) {
        TextView tv = new TextView(svc);
        tv.setText(t);
        tv.setTextSize(size);
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

    static boolean isWebType(String t) {
        return t.equals(T_FB) || t.equals(T_YT) || t.equals(T_WEB) || t.equals(T_TR);
    }

    void updateLayout() {
        if (rootAttached) wm.updateViewLayout(root, lp);
    }

    // ---------- saved geometry / style (driven from the settings screen) ----------
    int[] defaultGeom() {
        DisplayMetrics dm = svc.getResources().getDisplayMetrics();
        int w = Math.min((int) (dm.widthPixels * 0.88f), dp(520));
        int hgt = Math.min((int) (dm.heightPixels * 0.6f), dp(620));
        int x = dp(16) + (index % 6) * dp(28);
        int y = dp(60) + (index % 6) * dp(36);
        return new int[]{x, y, w, hgt};
    }

    /** Returns true if something was saved. */
    boolean saveGeomQuiet() {
        if (minimized || customView != null || !rootAttached) return false;
        sp.edit().putInt(type + ".x", lp.x).putInt(type + ".y", lp.y)
                .putInt(type + ".w", lp.width).putInt(type + ".h", lp.height).apply();
        return true;
    }

    void saveBubbleQuiet() {
        sp.edit().putInt(type + ".bx", bubX).putInt(type + ".by", bubY).apply();
    }

    int readBubSize() {
        int v = sp.getInt("bubsize", 1);
        return (v < 0 || v >= BUB_DP.length) ? 1 : v;
    }

    void loadBubblePrefs() {
        bubSize = readBubSize();
        bubX = sp.getInt(type + ".bx", dp(8));
        bubY = sp.getInt(type + ".by", dp(90) + (index % 8) * dp(58));
    }

    void bubbleLongPress() {
        bubLongDone = true;
        saveBubbleQuiet();
        if (bubble != null) bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        Toast.makeText(svc, "บันทึกตำแหน่งไอคอนแล้ว", Toast.LENGTH_SHORT).show();
    }

    void applyBubbleSize() {
        bubSize = readBubSize();
        if (bubble == null) return;
        blp.width = dp(BUB_DP[bubSize]);
        blp.height = dp(BUB_DP[bubSize]);
        bubble.setTextSize(BUB_TEXT[bubSize]);
        if (bubbleAttached) wm.updateViewLayout(bubble, blp);
    }

    /** Transparency: window content and the dark title bar are separate (buttons never fade). */
    void applyStyle() {
        int tw = sp.getInt("alpha_win", 0);
        int tb = sp.getInt("alpha_bar", 0);
        float aw = 1f - tw / 100f;
        rootBg.setColor(Color.argb(Math.round(255 * aw), 30, 30, 30));
        if (contentView != null) contentView.setAlpha(aw);
        bar.setBackgroundColor(Color.argb(Math.round(255 * (1f - tb / 100f)), 45, 45, 45));
    }

    // ---------- build UI ----------
    void build() {
        DisplayMetrics dm = svc.getResources().getDisplayMetrics();
        loadBubblePrefs();

        int[] g = defaultGeom();
        if (sp.contains(type + ".w")) {
            g[2] = Math.min(sp.getInt(type + ".w", g[2]), dm.widthPixels);
            g[3] = Math.min(sp.getInt(type + ".h", g[3]), dm.heightPixels);
            g[0] = Math.max(0, Math.min(sp.getInt(type + ".x", g[0]), dm.widthPixels - g[2]));
            g[1] = Math.max(0, Math.min(sp.getInt(type + ".y", g[1]), dm.heightPixels - dp(60)));
        }

        lp = new WindowManager.LayoutParams(g[2], g[3],
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, FLAGS, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = g[0];
        lp.y = g[1];
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;

        root = new WinRoot(svc);
        rootBg = new GradientDrawable();
        rootBg.setColor(0xFF1E1E1E);
        rootBg.setCornerRadius(dp(12));
        rootBg.setStroke(dp(1), 0xFF666666);
        root.setBackground(rootBg);
        root.setPadding(dp(1), dp(1), dp(1), dp(1));

        col = new LinearLayout(svc);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(MATCH, MATCH));

        // ---- compact title bar (drag to move): back / refresh / forward ... minimize / close ----
        boolean isWeb = isWebType(type);
        bar = new LinearLayout(svc);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF2D2D2D);
        if (isWeb) {
            bar.setPadding(dp(4), 0, 0, 0);
            bar.addView(btn("◀", v -> {
                if (web != null && web.canGoBack()) web.goBack();
            }), new LinearLayout.LayoutParams(dp(36), MATCH));
            bar.addView(btn("⟳", v -> {
                if (web != null) web.reload();
            }), new LinearLayout.LayoutParams(dp(36), MATCH));
            bar.addView(btn("▶", v -> {
                if (web != null && web.canGoForward()) web.goForward();
            }), new LinearLayout.LayoutParams(dp(36), MATCH));
            bar.addView(new View(svc), new LinearLayout.LayoutParams(0, MATCH, 1f));
        } else {
            bar.setPadding(dp(12), 0, 0, 0);
            TextView tv = label(title, 14, Color.WHITE);
            tv.setSingleLine(true);
            tv.setGravity(Gravity.CENTER_VERTICAL);
            bar.addView(tv, new LinearLayout.LayoutParams(0, MATCH, 1f));
        }
        bar.addView(btn("—", v -> minimize()), new LinearLayout.LayoutParams(dp(36), MATCH));
        bar.addView(btn("✕", v -> svc.closeWindow(this)), new LinearLayout.LayoutParams(dp(36), MATCH));
        col.addView(bar, new LinearLayout.LayoutParams(MATCH, dp(34)));

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
            case T_WEB:  content = buildWeb(HOME); break;
            case T_EDIT: content = buildEditor(); break;
            default:     content = buildList(); break;
        }
        contentView = content;
        col.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        // text-selection toolbar (copy / select all ...)
        selBar = new LinearLayout(svc);
        selBar.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(0xF0333333);
        sbg.setCornerRadius(dp(8));
        selBar.setBackground(sbg);
        selBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams sp2 = new FrameLayout.LayoutParams(WRAP, WRAP,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        sp2.topMargin = dp(38);
        root.addView(selBar, sp2);

        // "open link" bar (browser: long-press a link)
        linkBar = new LinearLayout(svc);
        linkBar.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable lbg = new GradientDrawable();
        lbg.setColor(0xF0333333);
        lbg.setCornerRadius(dp(8));
        linkBar.setBackground(lbg);
        linkBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams lpar = new FrameLayout.LayoutParams(WRAP, WRAP,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lpar.topMargin = dp(68);
        root.addView(linkBar, lpar);

        // resize handle (bottom-right)
        rz = label("◢", 18, 0xFFBBBBBB);
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
                        lp.width = Math.max(dp(240), sw + (int) (e.getRawX() - dx));
                        lp.height = Math.max(dp(160), sh + (int) (e.getRawY() - dy));
                        updateLayout();
                        return true;
                }
                return false;
            }
        });

        applyStyle();
    }

    // ---------- link bar (browser) ----------
    void showLinkBar(final String url) {
        linkBar.removeAllViews();
        TextView open = label("เปิดในแท็บใหม่", 14, Color.WHITE);
        open.setPadding(dp(14), dp(10), dp(14), dp(10));
        open.setOnClickListener(v -> {
            hideLinkBar();
            newTab(url, true);
        });
        TextView copy = label("คัดลอกลิงก์", 14, Color.WHITE);
        copy.setPadding(dp(14), dp(10), dp(14), dp(10));
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) svc.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("link", url));
            Toast.makeText(svc, "คัดลอกลิงก์แล้ว", Toast.LENGTH_SHORT).show();
            hideLinkBar();
        });
        linkBar.addView(open);
        linkBar.addView(copy);
        linkBar.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideLinkTask);
        h.postDelayed(hideLinkTask, 7000);
    }

    void hideLinkBar() {
        if (linkBar != null) linkBar.setVisibility(View.GONE);
        h.removeCallbacks(hideLinkTask);
    }

    // ---------- web window (with tabs for the browser) ----------
    class FWebView extends WebView {
        FWebView(Context c) {
            super(c);
        }

        // Overlay windows have no ActionMode host, so provide our own small toolbar.
        @Override
        public ActionMode startActionMode(ActionMode.Callback callback) {
            return startSel(callback);
        }

        @Override
        public ActionMode startActionMode(ActionMode.Callback callback, int mode) {
            return startSel(callback);
        }
    }

    View buildWeb(String startUrl) {
        webHost = new FrameLayout(svc);
        if (type.equals(T_WEB)) {
            LinearLayout box = new LinearLayout(svc);
            box.setOrientation(LinearLayout.VERTICAL);

            LinearLayout strip = new LinearLayout(svc);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            strip.setBackgroundColor(0xFF262626);
            HorizontalScrollView hs = new HorizontalScrollView(svc);
            hs.setHorizontalScrollBarEnabled(false);
            tabRow = new LinearLayout(svc);
            tabRow.setOrientation(LinearLayout.HORIZONTAL);
            hs.addView(tabRow, new FrameLayout.LayoutParams(WRAP, MATCH));
            strip.addView(hs, new LinearLayout.LayoutParams(0, MATCH, 1f));
            strip.addView(btn("+", v -> newTab(HOME, true)), new LinearLayout.LayoutParams(dp(34), MATCH));
            box.addView(strip, new LinearLayout.LayoutParams(MATCH, dp(30)));
            box.addView(webHost, new LinearLayout.LayoutParams(MATCH, 0, 1f));
            newTab(startUrl, true);
            return box;
        }
        newTab(startUrl, true);
        return webHost;
    }

    Tab newTab(String url, boolean select) {
        final Tab t = new Tab();
        t.wv = createWebView(t);
        t.wv.setVisibility(View.GONE);
        webHost.addView(t.wv, new FrameLayout.LayoutParams(MATCH, MATCH));
        tabs.add(t);
        if (tabRow != null) addChip(t);
        if (url != null) t.wv.loadUrl(url);
        if (select || curTab == null) selectTab(t);
        return t;
    }

    void addChip(final Tab t) {
        LinearLayout chip = new LinearLayout(svc);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(8), 0, 0, 0);
        TextView tt = label(t.title, 12, Color.WHITE);
        tt.setSingleLine(true);
        tt.setEllipsize(TextUtils.TruncateAt.END);
        tt.setMaxWidth(dp(100));
        chip.addView(tt, new LinearLayout.LayoutParams(WRAP, WRAP));
        TextView x = btn("✕", v -> h.post(() -> closeTab(t)));
        x.setTextSize(12);
        chip.addView(x, new LinearLayout.LayoutParams(dp(26), MATCH));
        chip.setOnClickListener(v -> selectTab(t));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(WRAP, MATCH);
        cp.setMargins(dp(2), dp(3), 0, 0);
        tabRow.addView(chip, cp);
        t.chip = chip;
        t.chipTitle = tt;
    }

    void selectTab(Tab t) {
        curTab = t;
        web = t.wv;
        for (Tab o : tabs) {
            o.wv.setVisibility(o == t ? View.VISIBLE : View.GONE);
            if (o.chip != null) o.chip.setBackgroundColor(o == t ? 0xFF555555 : 0xFF2E2E2E);
        }
    }

    void closeTab(Tab t) {
        int i = tabs.indexOf(t);
        if (i < 0) return;
        tabs.remove(t);
        if (tabRow != null && t.chip != null) tabRow.removeView(t.chip);
        webHost.removeView(t.wv);
        t.wv.stopLoading();
        t.wv.destroy();
        if (tabs.isEmpty()) {
            curTab = null;
            web = null;
            newTab(HOME, true);
        } else if (curTab == t) {
            selectTab(tabs.get(Math.min(i, tabs.size() - 1)));
        }
    }

    WebView createWebView(final Tab t) {
        FWebView w = new FWebView(svc);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        if (type.equals(T_WEB)) {
            s.setSupportMultipleWindows(true);
            // long-press a link -> small bar: open in new tab / copy link
            w.setOnLongClickListener(v -> {
                WebView wv = (WebView) v;
                int tp = wv.getHitTestResult().getType();
                if (tp == WebView.HitTestResult.SRC_ANCHOR_TYPE
                        || tp == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                    wv.requestFocusNodeHref(Message.obtain(hrefHandler));
                    return true;
                }
                return false;
            });
        }
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(w, true);

        w.setWebViewClient(new WebViewClient() {
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
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onReceivedTitle(WebView v, String ttl) {
                t.title = ttl;
                if (t.chipTitle != null) t.chipTitle.setText(ttl);
            }

            @Override
            public boolean onCreateWindow(WebView v, boolean isDialog, boolean isUserGesture,
                                          Message resultMsg) {
                if (!type.equals(T_WEB) || resultMsg == null) return false;
                Tab nt = newTab(null, true);
                WebView.WebViewTransport tr = (WebView.WebViewTransport) resultMsg.obj;
                tr.setWebView(nt.wv);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onCloseWindow(WebView v) {
                if (type.equals(T_WEB)) h.post(() -> closeTab(t));
            }

            @Override
            public void onShowCustomView(View view, WebChromeClient.CustomViewCallback cb) {
                enterFull(view, cb);
            }

            @Override
            public void onHideCustomView() {
                exitFull();
            }
        });
        return w;
    }

    // ---------- fullscreen video (exit with the player's own button or Back) ----------
    void enterFull(View v, WebChromeClient.CustomViewCallback cb) {
        if (customView != null) {
            cb.onCustomViewHidden();
            return;
        }
        customView = v;
        customCb = cb;
        prevX = lp.x;
        prevY = lp.y;
        prevW = lp.width;
        prevH = lp.height;
        DisplayMetrics dm = svc.getResources().getDisplayMetrics();
        lp.x = 0;
        lp.y = 0;
        lp.width = dm.widthPixels;
        lp.height = dm.heightPixels;
        lp.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        col.setVisibility(View.GONE);
        rz.setVisibility(View.GONE);
        root.addView(v, 1, new FrameLayout.LayoutParams(MATCH, MATCH));
        updateLayout();
    }

    void exitFull() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        lp.x = prevX;
        lp.y = prevY;
        lp.width = prevW;
        lp.height = prevH;
        lp.flags &= ~(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        col.setVisibility(View.VISIBLE);
        rz.setVisibility(View.VISIBLE);
        updateLayout();
        if (customCb != null) {
            WebChromeClient.CustomViewCallback cb = customCb;
            customCb = null;
            cb.onCustomViewHidden();
        }
    }

    // ---------- text selection toolbar ----------
    class MiniActionMode extends ActionMode {
        final ActionMode.Callback cb;
        final Menu menu;
        CharSequence ttl;
        View cview;
        boolean done;

        MiniActionMode(ActionMode.Callback cb) {
            this.cb = cb;
            this.menu = new PopupMenu(svc, root).getMenu();
        }

        @Override public void setTitle(CharSequence t) { ttl = t; }
        @Override public void setTitle(int r) { ttl = svc.getString(r); }
        @Override public void setSubtitle(CharSequence t) { }
        @Override public void setSubtitle(int r) { }
        @Override public void setCustomView(View v) { cview = v; }

        @Override
        public void invalidate() {
            if (done) return;
            cb.onPrepareActionMode(this, menu);
            rebuildSelBar();
        }

        @Override
        public void finish() {
            if (done) return;
            done = true;
            cb.onDestroyActionMode(this);
            hideSel(this);
        }

        @Override public Menu getMenu() { return menu; }
        @Override public CharSequence getTitle() { return ttl; }
        @Override public CharSequence getSubtitle() { return null; }
        @Override public View getCustomView() { return cview; }
        @Override public MenuInflater getMenuInflater() { return new MenuInflater(svc); }
        @Override public int getType() { return ActionMode.TYPE_FLOATING; }
    }

    ActionMode startSel(ActionMode.Callback cb) {
        if (selMode != null) selMode.finish();
        MiniActionMode m = new MiniActionMode(cb);
        if (!cb.onCreateActionMode(m, m.getMenu())) return null;
        cb.onPrepareActionMode(m, m.getMenu());
        selMode = m;
        rebuildSelBar();
        return m;
    }

    void hideSel(MiniActionMode m) {
        if (selMode == m) {
            selMode = null;
            rebuildSelBar();
        }
    }

    void rebuildSelBar() {
        selBar.removeAllViews();
        if (selMode == null) {
            selBar.setVisibility(View.GONE);
            return;
        }
        final MiniActionMode mode = selMode;
        Menu m = mode.getMenu();
        List<String> ok = Arrays.asList(
                svc.getString(android.R.string.copy), svc.getString(android.R.string.cut),
                svc.getString(android.R.string.paste), svc.getString(android.R.string.selectAll));
        int shown = 0;
        for (int pass = 0; pass < 2 && shown == 0; pass++) {
            for (int i = 0; i < m.size(); i++) {
                final MenuItem it = m.getItem(i);
                if (!it.isVisible()) continue;
                String t = String.valueOf(it.getTitle());
                if (pass == 0 && !ok.contains(t)) continue;
                TextView b = label(t, 14, Color.WHITE);
                b.setPadding(dp(14), dp(10), dp(14), dp(10));
                b.setOnClickListener(v -> mode.cb.onActionItemClicked(mode, it));
                selBar.addView(b);
                shown++;
            }
        }
        selBar.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
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

    /**
     * Minimize keeps the window alive (so a YouTube video keeps playing): it is only
     * moved far off-screen and made untouchable, and a small bubble is shown instead.
     * The bubble has its own saved position/size, independent of the window.
     */
    void minimize() {
        if (minimized || !rootAttached) return;
        if (customView != null) exitFull();
        hideLinkBar();
        InputMethodManager imm = (InputMethodManager) svc.getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
        restX = lp.x;
        restY = lp.y;
        minimized = true;
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        lp.x = -30000;
        lp.y = -30000;
        updateLayout();
        showBubble();
        svc.notifyChanged();
    }

    void restore() {
        if (!minimized) return;
        hideBubble();
        minimized = false;
        lp.flags = FLAGS;
        lp.x = restX;
        lp.y = restY;
        updateLayout();
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
        if (selMode != null) selMode.finish();
        customView = null;
        customCb = null;
        if (rootAttached) {
            wm.removeView(root);
            rootAttached = false;
        }
        hideBubble();
        if (webHost != null) webHost.removeAllViews();
        for (Tab t : new ArrayList<>(tabs)) {
            t.wv.stopLoading();
            t.wv.destroy();
        }
        tabs.clear();
        curTab = null;
        web = null;
    }

    // ---------- bubble (minimized) ----------
    String bubbleIcon() {
        switch (type) {
            case T_FB: return "f";
            case T_YT: return "▶";
            case T_WEB: return "G";
            case T_TR: return "A文";
            case T_EDIT: return "✎";
            default: return "▤";
        }
    }

    void showBubble() {
        if (bubble == null) {
            bubble = label(bubbleIcon(), BUB_TEXT[bubSize], Color.WHITE);
            bubble.setGravity(Gravity.CENTER);
            GradientDrawable oval = new GradientDrawable();
            oval.setShape(GradientDrawable.OVAL);
            oval.setColor(0xCC0D6EFD);
            bubble.setBackground(oval);
            blp = new WindowManager.LayoutParams(dp(BUB_DP[bubSize]), dp(BUB_DP[bubSize]),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
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
                            bubLongDone = false;
                            h.postDelayed(bubLong, 800);
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            float mx = e.getRawX() - dx, my = e.getRawY() - dy;
                            if (Math.abs(mx) > dp(8) || Math.abs(my) > dp(8)) {
                                dragging = true;
                                h.removeCallbacks(bubLong);
                            }
                            if (dragging && bubbleAttached) {
                                blp.x = sx + (int) mx;
                                blp.y = sy + (int) my;
                                bubX = blp.x;   // only the icon moves; the window keeps its own spot
                                bubY = blp.y;
                                wm.updateViewLayout(bubble, blp);
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            h.removeCallbacks(bubLong);
                            if (!dragging && !bubLongDone
                                    && e.getActionMasked() == MotionEvent.ACTION_UP) {
                                h.post(FloatWindow.this::restore);
                            }
                            return true;
                    }
                    return false;
                }
            });
        }
        applyBubbleSize();
        DisplayMetrics dm = svc.getResources().getDisplayMetrics();
        blp.x = Math.max(0, Math.min(bubX, dm.widthPixels - blp.width));
        blp.y = Math.max(0, Math.min(bubY, dm.heightPixels - blp.height));
        bubX = blp.x;
        bubY = blp.y;
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
            if (a == MotionEvent.ACTION_DOWN && linkBar != null
                    && linkBar.getVisibility() == View.VISIBLE) {
                float x = e.getX(), y = e.getY();
                boolean inside = x >= linkBar.getLeft() && x <= linkBar.getRight()
                        && y >= linkBar.getTop() && y <= linkBar.getBottom();
                if (!inside) hideLinkBar();
            }
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
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (customView != null) {
                    if (e.getAction() == KeyEvent.ACTION_UP) exitFull();
                    return true;
                }
                if (web != null && web.canGoBack()) {
                    if (e.getAction() == KeyEvent.ACTION_UP) web.goBack();
                    return true;
                }
            }
            return super.dispatchKeyEvent(e);
        }
    }
}
