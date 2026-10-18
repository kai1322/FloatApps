package com.example.floatapps;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import org.json.JSONArray;
import org.json.JSONObject;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebBackForwardList;
import android.webkit.RenderProcessGoneDetail;
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

    // low-resource mode: at most this many browser tabs keep a live WebView; the rest are
    // "frozen" (only URL + page state kept) and rebuilt when selected
    static final int MAX_LIVE_TABS = 3;
    static final long TAB_GRACE_MS = 20000;

    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    static final int FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

    // bubble (minimized icon) sizes in dp: small / medium / large
    static final int[] BUB_DP = {38, 48, 60};
    static final int[] BUB_TEXT = {13, 16, 20};
    // YouTube mini player widths in dp: small / medium / large (16:9)
    static final int[] MINI_DP = {180, 240, 320};

    static final String JS_HAS_VIDEO = "(function(){var v=document.querySelector('video');"
            + "return !!(v&&v.currentSrc&&v.readyState>0&&v.duration>0);})()";
    static final String JS_MARK = "(function(){var v=document.querySelector('video');"
            + "window.__wasPlaying=!!(v&&!v.paused);return 'ok';})()";
    static final String JS_RESUME = "(function(){var v=document.querySelector('video');"
            + "if(v&&window.__wasPlaying&&v.paused){v.play();}return 'ok';})()";
    static final String JS_PAUSED = "(function(){var v=document.querySelector('video');"
            + "return (v&&v.paused)?'paused':'playing';})()";
    static final String JS_TOGGLE = "(function(){var v=document.querySelector('video');if(!v)return 'x';"
            + "if(v.paused){window.__userPaused=false;var p=v.play();if(p&&p.catch)p.catch(function(){});return 'playing';}"
            + "window.__userPaused=true;v.pause();return 'paused';})()";
    // if the page pauses the video by itself (not the user), start it again
    static final String JS_KEEP = "(function(){var v=document.querySelector('video');if(!v)return 'x';"
            + "if(v.paused&&!window.__userPaused){var p=v.play();if(p&&p.catch)p.catch(function(){});}"
            + "return v.paused?'paused':'playing';})()";
    // make the page believe it is always visible, so it does not pause itself
    static final String JS_NOHIDE = "(function(){try{['hidden','webkitHidden'].forEach(function(k){"
            + "Object.defineProperty(document,k,{configurable:true,get:function(){return false;}});});"
            + "['visibilityState','webkitVisibilityState'].forEach(function(k){"
            + "Object.defineProperty(document,k,{configurable:true,get:function(){return 'visible';}});});}catch(e){}"
            + "if(!window.__nohideSet){window.__nohideSet=true;['visibilitychange','webkitvisibilitychange','pagehide','blur']"
            + ".forEach(function(n){window.addEventListener(n,function(e){e.stopImmediatePropagation();},true);"
            + "document.addEventListener(n,function(e){e.stopImmediatePropagation();},true);});}return 'ok';})()";
    // make the <video> fill the whole (small) window and hide everything else
    static final String JS_MINI_ON = "(function(){var v=document.querySelector('video');"
            + "if(!v)return 'no';window.__userPaused=v.paused;window.__miniSaved=[];var a=v.parentElement;"
            + "while(a&&a!==document.documentElement){window.__miniSaved.push([a,a.getAttribute('style')]);"
            + "['transform','contain','filter','clip-path'].forEach(function(p){a.style.setProperty(p,'none','important');});"
            + "a.style.setProperty('overflow','visible','important');a=a.parentElement;}"
            + "var s=document.getElementById('__miniStyle');"
            + "if(!s){s=document.createElement('style');s.id='__miniStyle';document.head.appendChild(s);}"
            + "s.textContent='html,body{overflow:hidden !important;background:#000 !important;}"
            + "body *{visibility:hidden !important;}"
            + "video{visibility:visible !important;position:fixed !important;left:0 !important;top:0 !important;"
            + "width:100vw !important;height:100vh !important;max-width:none !important;max-height:none !important;"
            + "margin:0 !important;z-index:2147483647 !important;background:#000 !important;object-fit:contain !important;}';"
            + "return 'ok';})()";
    static final String JS_MINI_OFF = "(function(){var s=document.getElementById('__miniStyle');if(s)s.remove();"
            + "var arr=window.__miniSaved||[];for(var i=0;i<arr.length;i++){var a=arr[i][0],st=arr[i][1];"
            + "if(st===null)a.removeAttribute('style');else a.setAttribute('style',st);}"
            + "window.__miniSaved=[];try{window.dispatchEvent(new Event('resize'));}catch(e){}return 'ok';})()";

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
    // YouTube in-window mini player (the playing video shrinks while you browse the feed)
    boolean miniOn;
    Tab miniTab;
    FrameLayout miniCtl;   // controls layered over the mini player WebView
    TextView miniPlay;
    int restX, restY;          // where the WINDOW reappears
    int bubX, bubY, bubSize;   // where/how big the minimized ICON is (independent of the window)

    // browser tabs (non-browser windows have exactly one tab)
    class Tab {
        WebView wv;
        String title = "แท็บใหม่";
        String url;
        LinearLayout chip;
        TextView chipTitle;
        Bundle state;        // saved page state while the tab is frozen
        long used;           // last time the tab was selected
        int crashes;         // renderer crashes in a short time (to avoid reload loops)
        long lastCrash;
    }

    final List<Tab> tabs = new ArrayList<>();
    Tab curTab;
    WebView web; // always the WebView of the current tab
    FrameLayout webHost;
    LinearLayout tabRow, urlRow;
    EditText urlEdit;

    // pinned sites row, menu panel (pins / bookmarks), file picker state, power saving
    LinearLayout pinRow;
    HorizontalScrollView pinScroll;
    FrameLayout panel;
    boolean pickerHidden, webPaused;
    int pickX, pickY;
    final JSONObject sess;          // saved session of this window (null for a fresh window)
    final SharedPreferences bsp;    // pins + bookmarks
    final Runnable limitTick = new Runnable() {
        @Override
        public void run() {
            if (!rootAttached) return;
            enforceTabLimit();
            h.postDelayed(this, 30000);
        }
    };

    EditText editor;
    LinearLayout listBox;
    final Runnable saveTask = this::saveNow;
    final Runnable bubLong = this::bubbleLongPress;
    final Runnable hideLinkTask = this::hideLinkBar;
    // receives the real href of a long-pressed link (browser only)
    final Handler hrefHandler = new Handler(Looper.getMainLooper(), msg -> {
        String u = msg.getData().getString("url");
        String src = msg.getData().getString("src");
        if (u != null && !u.isEmpty()) {
            showLinkBar(u, (src != null && src.startsWith("http")) ? src : null);
        }
        return true;
    });

    // fullscreen video state
    View customView;
    WebChromeClient.CustomViewCallback customCb;
    int prevX, prevY, prevW, prevH;

    // text-selection toolbar state
    MiniActionMode selMode;

    FloatWindow(FloatService svc, String type, String title, int index) {
        this(svc, type, title, index, null);
    }

    FloatWindow(FloatService svc, String type, String title, int index, JSONObject sess) {
        this.svc = svc;
        this.wm = svc.wm;
        this.type = type;
        this.title = title;
        this.index = index;
        this.sess = sess;
        this.sp = svc.getSharedPreferences("float_prefs", Context.MODE_PRIVATE);
        this.bsp = svc.getSharedPreferences("float_browser", Context.MODE_PRIVATE);
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
        if (sess != null && sess.has("bx")) {
            bubX = sess.optInt("bx", bubX);
            bubY = sess.optInt("by", bubY);
        }

        int[] g = defaultGeom();
        if (sp.contains(type + ".w")) {
            g[2] = Math.min(sp.getInt(type + ".w", g[2]), dm.widthPixels);
            g[3] = Math.min(sp.getInt(type + ".h", g[3]), dm.heightPixels);
            g[0] = Math.max(0, Math.min(sp.getInt(type + ".x", g[0]), dm.widthPixels - g[2]));
            g[1] = Math.max(0, Math.min(sp.getInt(type + ".y", g[1]), dm.heightPixels - dp(60)));
        }

        if (sess != null && sess.has("w")) {
            g[2] = Math.max(dp(240), Math.min(sess.optInt("w", g[2]), dm.widthPixels));
            g[3] = Math.max(dp(160), Math.min(sess.optInt("h", g[3]), dm.heightPixels));
            g[0] = Math.max(0, Math.min(sess.optInt("x", g[0]), dm.widthPixels - g[2]));
            g[1] = Math.max(0, Math.min(sess.optInt("y", g[1]), dm.heightPixels - dp(60)));
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
            bar.addView(btn("◀", v -> onWebBack()),
                    new LinearLayout.LayoutParams(dp(36), MATCH));
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
                    case MotionEvent.ACTION_UP:
                        svc.scheduleSave();
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
                    case MotionEvent.ACTION_UP:
                        svc.scheduleSave();
                        return true;
                }
                return false;
            }
        });

        applyStyle();
    }

    // ---------- YouTube in-window mini player ----------
    static boolean isWatchUrl(String u) {
        return u != null && u.contains("/watch");
    }

    int readMiniSize() {
        int v = sp.getInt("minisize", 1);
        return (v < 0 || v >= MINI_DP.length) ? 1 : v;
    }

    final Runnable miniWatch = new Runnable() {
        @Override
        public void run() {
            if (!miniOn || miniTab == null) return;
            miniTab.wv.evaluateJavascript(JS_KEEP, r -> {
                if (miniPlay != null) miniPlay.setText(r != null && r.contains("paused") ? "▶" : "❚❚");
            });
            h.postDelayed(this, 1500);
        }
    };

    /** Back button: on a watch page with a video, shrink it into the mini player instead of leaving. */
    void onWebBack() {
        if (web == null) return;
        if (type.equals(T_YT) && !miniOn && isWatchUrl(web.getUrl())) {
            web.evaluateJavascript(JS_HAS_VIDEO, r -> {
                if ("true".equals(r) && web != null) collapseToMini();
                else if (web != null && web.canGoBack()) web.goBack();
            });
            return;
        }
        if (web.canGoBack()) web.goBack();
    }

    void collapseToMini() {
        if (web == null || curTab == null || !type.equals(T_YT)) return;
        if (miniOn) closeMini();
        final Tab player = curTab;
        String prev = homeUrl();
        try {
            WebBackForwardList bfl = web.copyBackForwardList();
            int ci = bfl.getCurrentIndex();
            if (ci > 0) {
                String u = bfl.getItemAtIndex(ci - 1).getUrl();
                if (u != null && u.startsWith("http") && !isWatchUrl(u)) prev = u;
            }
        } catch (Exception ignored) {
        }
        player.wv.evaluateJavascript(JS_NOHIDE, null);
        // The player keeps its place in the window (no detaching, so the page never thinks it was hidden)
        tabs.remove(player);
        miniTab = player;
        miniOn = true;
        curTab = null;
        web = null;
        newTab(prev, true);   // the page you were on before the video, added BELOW the player
        buildMini(player);
        player.wv.evaluateJavascript(JS_MINI_ON, null);
        h.removeCallbacks(miniWatch);
        h.postDelayed(miniWatch, 500);
    }

    void expandMini() {
        if (!miniOn || miniTab == null) return;
        final Tab player = miniTab;
        h.removeCallbacks(miniWatch);
        player.wv.evaluateJavascript(JS_MINI_OFF, null);
        if (miniCtl != null) {
            webHost.removeView(miniCtl);
            miniCtl = null;
        }
        player.wv.setLayoutParams(new FrameLayout.LayoutParams(MATCH, MATCH));
        // the page that was open behind the mini player is dropped
        Tab browse = curTab;
        if (browse != null) {
            tabs.remove(browse);
            webHost.removeView(browse.wv);
            browse.wv.stopLoading();
            browse.wv.destroy();
        }
        miniTab = null;
        miniOn = false;
        tabs.add(player);
        selectTab(player);
        h.postDelayed(() -> {
            if (player.wv != null) player.wv.evaluateJavascript(JS_KEEP, null);
        }, 400);
    }

    void closeMini() {
        if (!miniOn) return;
        h.removeCallbacks(miniWatch);
        Tab p = miniTab;
        if (miniCtl != null) {
            webHost.removeView(miniCtl);
            miniCtl = null;
        }
        if (p != null) {
            webHost.removeView(p.wv);
            p.wv.stopLoading();
            p.wv.destroy();
        }
        miniTab = null;
        miniOn = false;
    }

    void toggleMiniPlay() {
        if (miniTab == null) return;
        miniTab.wv.evaluateJavascript(JS_TOGGLE, r -> {
            miniPlay.setText(r != null && r.contains("paused") ? "▶" : "❚❚");
        });
    }

    /** Move/resize the mini player: the WebView and its control layer always share one geometry. */
    void setMiniGeom(int x, int y, int w, int hh) {
        if (miniTab == null || miniCtl == null) return;
        FrameLayout.LayoutParams a = (FrameLayout.LayoutParams) miniTab.wv.getLayoutParams();
        a.gravity = Gravity.TOP | Gravity.START;
        a.leftMargin = x;
        a.topMargin = y;
        a.width = w;
        a.height = hh;
        miniTab.wv.setLayoutParams(a);
        FrameLayout.LayoutParams b = (FrameLayout.LayoutParams) miniCtl.getLayoutParams();
        b.gravity = Gravity.TOP | Gravity.START;
        b.leftMargin = x;
        b.topMargin = y;
        b.width = w;
        b.height = hh;
        miniCtl.setLayoutParams(b);
    }

    int[] miniGeom() {
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) miniCtl.getLayoutParams();
        return new int[]{p.leftMargin, p.topMargin, p.width, p.height};
    }

    void saveMiniGeom() {
        if (miniCtl == null) return;
        int[] g = miniGeom();
        sp.edit().putInt("ytmini.x", g[0]).putInt("ytmini.y", g[1])
                .putInt("ytmini.w", g[2]).putInt("ytmini.h", g[3]).apply();
    }

    void buildMini(final Tab player) {
        int hostW = webHost.getWidth() > 0 ? webHost.getWidth() : lp.width - dp(2);
        int hostH = webHost.getHeight() > 0 ? webHost.getHeight() : lp.height - dp(36);
        int w = Math.min(sp.getInt("ytmini.w", dp(MINI_DP[readMiniSize()])), hostW);
        int hh = Math.min(sp.getInt("ytmini.h", Math.round(w * 9f / 16f)), hostH);
        int x = sp.getInt("ytmini.x", hostW - w - dp(8));
        int y = sp.getInt("ytmini.y", hostH - hh - dp(8));
        x = Math.max(0, Math.min(x, hostW - w));
        y = Math.max(0, Math.min(y, hostH - hh));

        miniCtl = new FrameLayout(svc);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(w, hh, Gravity.TOP | Gravity.START);
        clp.leftMargin = x;
        clp.topMargin = y;
        webHost.addView(miniCtl, clp);          // on top of the player and of the page behind it
        setMiniGeom(x, y, w, hh);

        // tap = expand, drag = move
        FrameLayout touch = new FrameLayout(svc);
        touch.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy;
            int sx, sy;
            boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (miniCtl == null) return false;
                int[] g = miniGeom();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX();
                        dy = e.getRawY();
                        sx = g[0];
                        sy = g[1];
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getRawX() - dx) > dp(8) || Math.abs(e.getRawY() - dy) > dp(8)) {
                            dragging = true;
                        }
                        if (dragging) {
                            int nx = Math.max(0, Math.min(sx + (int) (e.getRawX() - dx),
                                    Math.max(0, webHost.getWidth() - g[2])));
                            int ny = Math.max(0, Math.min(sy + (int) (e.getRawY() - dy),
                                    Math.max(0, webHost.getHeight() - g[3])));
                            setMiniGeom(nx, ny, g[2], g[3]);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragging) saveMiniGeom();
                        else expandMini();
                        return true;
                }
                return false;
            }
        });
        miniCtl.addView(touch, new FrameLayout.LayoutParams(MATCH, MATCH));

        miniPlay = label("❚❚", 13, Color.WHITE);
        miniPlay.setGravity(Gravity.CENTER);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setShape(GradientDrawable.OVAL);
        pbg.setColor(0xAA000000);
        miniPlay.setBackground(pbg);
        miniPlay.setOnClickListener(v -> toggleMiniPlay());
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(dp(30), dp(30),
                Gravity.BOTTOM | Gravity.START);
        pp.setMargins(dp(6), 0, 0, dp(6));
        miniCtl.addView(miniPlay, pp);

        TextView close = label("✕", 13, Color.WHITE);
        close.setGravity(Gravity.CENTER);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setShape(GradientDrawable.OVAL);
        cbg.setColor(0xAA000000);
        close.setBackground(cbg);
        close.setOnClickListener(v -> closeMini());
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(30), dp(30),
                Gravity.TOP | Gravity.END);
        cp.setMargins(0, dp(6), dp(6), 0);
        miniCtl.addView(close, cp);

        // free resize (drag the corner)
        TextView handle = label("◢", 16, 0xFFDDDDDD);
        handle.setGravity(Gravity.CENTER);
        handle.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy;
            int sw, sh;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (miniCtl == null) return false;
                int[] g = miniGeom();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX();
                        dy = e.getRawY();
                        sw = g[2];
                        sh = g[3];
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int maxW = Math.max(dp(120), webHost.getWidth() - g[0]);
                        int maxH = Math.max(dp(70), webHost.getHeight() - g[1]);
                        int nw = Math.max(dp(120), Math.min(sw + (int) (e.getRawX() - dx), maxW));
                        int nh = Math.max(dp(70), Math.min(sh + (int) (e.getRawY() - dy), maxH));
                        setMiniGeom(g[0], g[1], nw, nh);
                        return true;
                    case MotionEvent.ACTION_UP:
                        saveMiniGeom();
                        return true;
                }
                return false;
            }
        });
        miniCtl.addView(handle, new FrameLayout.LayoutParams(dp(30), dp(30),
                Gravity.BOTTOM | Gravity.END));
    }

    // ---------- link bar (browser) ----------
    void showLinkBar(final String url) {
        showLinkBar(url, null);
    }

    /** img != null -> the pressed thing is (or contains) an image that can be saved. */
    void showLinkBar(final String url, final String img) {
        linkBar.removeAllViews();
        TextView open = label("เปิดในแท็บใหม่", 13, Color.WHITE);
        open.setPadding(dp(9), dp(10), dp(9), dp(10));
        open.setOnClickListener(v -> {
            hideLinkBar();
            newTab(url, true);
        });
        TextView copy = label("คัดลอกลิงก์", 13, Color.WHITE);
        copy.setPadding(dp(9), dp(10), dp(9), dp(10));
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) svc.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("link", url));
            Toast.makeText(svc, "คัดลอกลิงก์แล้ว", Toast.LENGTH_SHORT).show();
            hideLinkBar();
        });
        linkBar.addView(open);
        linkBar.addView(copy);
        if (img != null) {
            TextView save = label("บันทึกรูป", 13, Color.WHITE);
            save.setPadding(dp(9), dp(10), dp(9), dp(10));
            save.setOnClickListener(v -> {
                hideLinkBar();
                startDownload(img, web != null ? web.getSettings().getUserAgentString() : null,
                        null, null);
            });
            linkBar.addView(save);
        }
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
            TextView menuBtn = btn("☰", v -> togglePanel());
            menuBtn.setTextSize(15);
            strip.addView(menuBtn, new LinearLayout.LayoutParams(dp(34), MATCH));
            TextView urlBtn = btn("URL", v -> toggleUrlRow());
            urlBtn.setTextSize(11);
            strip.addView(urlBtn, new LinearLayout.LayoutParams(dp(40), MATCH));
            strip.addView(btn("+", v -> newTab(HOME, true)), new LinearLayout.LayoutParams(dp(34), MATCH));
            box.addView(strip, new LinearLayout.LayoutParams(MATCH, dp(30)));

            // pinned sites: always one tap away
            pinScroll = new HorizontalScrollView(svc);
            pinScroll.setHorizontalScrollBarEnabled(false);
            pinScroll.setBackgroundColor(0xFF232323);
            pinRow = new LinearLayout(svc);
            pinRow.setOrientation(LinearLayout.HORIZONTAL);
            pinRow.setGravity(Gravity.CENTER_VERTICAL);
            pinRow.setPadding(dp(4), 0, dp(4), 0);
            pinScroll.addView(pinRow, new FrameLayout.LayoutParams(WRAP, MATCH));
            box.addView(pinScroll, new LinearLayout.LayoutParams(MATCH, dp(28)));
            refreshPins();

            urlRow = new LinearLayout(svc);
            urlRow.setOrientation(LinearLayout.HORIZONTAL);
            urlRow.setGravity(Gravity.CENTER_VERTICAL);
            urlRow.setBackgroundColor(0xFF2A2A2A);
            urlRow.setPadding(dp(4), dp(3), dp(4), dp(3));
            urlRow.setVisibility(View.GONE);
            urlEdit = new EditText(svc);
            urlEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            urlEdit.setMaxLines(1);
            urlEdit.setTextSize(13);
            urlEdit.setTextColor(Color.BLACK);
            urlEdit.setHint("พิมพ์ URL หรือคำค้นหา");
            urlEdit.setHintTextColor(0xFF777777);
            urlEdit.setBackgroundColor(0xFFEEEEEE);
            urlEdit.setPadding(dp(8), 0, dp(8), 0);
            urlEdit.setImeOptions(EditorInfo.IME_ACTION_GO);
            urlEdit.setOnEditorActionListener((v, actionId, ev) -> {
                goUrl(urlEdit.getText().toString());
                return true;
            });
            urlRow.addView(urlEdit, new LinearLayout.LayoutParams(0, dp(34), 1f));
            TextView goBtn = btn("ไป", v -> goUrl(urlEdit.getText().toString()));
            goBtn.setTextSize(14);
            urlRow.addView(goBtn, new LinearLayout.LayoutParams(dp(44), dp(34)));
            box.addView(urlRow, new LinearLayout.LayoutParams(MATCH, WRAP));

            box.addView(webHost, new LinearLayout.LayoutParams(MATCH, 0, 1f));
            openStartTabs(startUrl);
            return box;
        }
        openStartTabs(startUrl);
        return webHost;
    }

    Tab newTab(String url, boolean select) {
        final Tab t = new Tab();
        t.url = url;
        t.used = SystemClock.uptimeMillis();
        t.wv = createWebView(t);
        t.wv.setVisibility(View.GONE);
        webHost.addView(t.wv, 0, new FrameLayout.LayoutParams(MATCH, MATCH));
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
        hidePanel();
        if (t.wv == null) reviveTab(t);          // frozen tab -> rebuild it now
        t.used = SystemClock.uptimeMillis();
        curTab = t;
        web = t.wv;
        for (Tab o : tabs) {
            if (o.wv != null) o.wv.setVisibility(o == t ? View.VISIBLE : View.GONE);
            if (o.chip != null) {
                o.chip.setBackgroundColor(o == t ? 0xFF555555 : 0xFF2E2E2E);
                o.chip.setAlpha(o.wv == null ? 0.55f : 1f);
            }
        }
        enforceTabLimit();
        svc.scheduleSave();
    }

    void closeTab(Tab t) {
        int i = tabs.indexOf(t);
        if (i < 0) return;
        tabs.remove(t);
        if (tabRow != null && t.chip != null) tabRow.removeView(t.chip);
        if (t.wv != null) {
            webHost.removeView(t.wv);
            t.wv.stopLoading();
            t.wv.destroy();
            t.wv = null;
        }
        if (tabs.isEmpty()) {
            curTab = null;
            web = null;
            newTab(HOME, true);
        } else if (curTab == t) {
            selectTab(tabs.get(Math.min(i, tabs.size() - 1)));
        }
        svc.scheduleSave();
    }

    // ---------- low-resource tabs: freeze background tabs, rebuild them on demand ----------

    /** A tab that exists only as a chip + URL (restored session / frozen); no WebView, no RAM. */
    Tab addDiscardedTab(String url, String ttl) {
        Tab t = new Tab();
        t.url = url;
        if (ttl != null && !ttl.isEmpty()) t.title = ttl;
        tabs.add(t);
        if (tabRow != null) {
            addChip(t);
            t.chip.setBackgroundColor(0xFF2E2E2E);
            t.chip.setAlpha(0.55f);
        }
        return t;
    }

    /** Build the WebView of a frozen tab again (page state is restored when possible). */
    void reviveTab(Tab t) {
        if (t.wv != null || webHost == null) return;
        t.wv = createWebView(t);
        t.wv.setVisibility(View.GONE);
        webHost.addView(t.wv, 0, new FrameLayout.LayoutParams(MATCH, MATCH));
        boolean ok = false;
        if (t.state != null) {
            try {
                ok = t.wv.restoreState(t.state) != null;
            } catch (Exception ignored) {
            }
        }
        t.state = null;
        if (!ok) t.wv.loadUrl((t.url != null && !t.url.isEmpty()) ? t.url : homeUrl());
    }

    /** Free the memory of a tab but keep its chip, URL and page state. */
    void discardTab(Tab t, boolean force) {
        if (t.wv == null || t == miniTab) return;
        if (t == curTab && !force) return;
        Bundle b = new Bundle();
        try {
            t.wv.saveState(b);
            t.state = b;
        } catch (Exception e) {
            t.state = null;
        }
        try {
            String u = t.wv.getUrl();
            if (u != null && u.startsWith("http")) t.url = u;
            webHost.removeView(t.wv);
            t.wv.stopLoading();
            t.wv.destroy();
        } catch (Exception ignored) {
        }
        t.wv = null;
        if (t == curTab) web = null;
        if (t.chip != null) t.chip.setAlpha(0.55f);
    }

    /** Keep at most MAX_LIVE_TABS WebViews alive; the least recently used ones are frozen. */
    void enforceTabLimit() {
        if (!type.equals(T_WEB)) return;
        long now = SystemClock.uptimeMillis();
        while (true) {
            int live = 0;
            Tab oldest = null;
            for (Tab o : tabs) {
                if (o.wv == null) continue;
                live++;
                if (o == curTab || o == miniTab) continue;
                if (now - o.used < TAB_GRACE_MS) continue;
                if (oldest == null || o.used < oldest.used) oldest = o;
            }
            if (live <= MAX_LIVE_TABS || oldest == null) return;
            discardTab(oldest, false);
        }
    }

    /** The system is short on memory: freeze every page that is not on screen. */
    void trimMemory(boolean critical) {
        if (webHost == null) return;
        for (Tab t : new ArrayList<>(tabs)) {
            if (t == curTab || t == miniTab) continue;
            discardTab(t, false);
        }
        // a minimized window (not YouTube, which keeps playing) can free its page too
        if (critical && minimized && !type.equals(T_YT) && curTab != null) {
            discardTab(curTab, true);
        }
    }

    /** Minimized (non-YouTube) windows stop their pages; they continue when restored. */
    void pauseWebs(boolean pause) {
        if (type.equals(T_YT) || webPaused == pause) return;
        webPaused = pause;
        for (Tab t : tabs) {
            if (t.wv == null) continue;
            try {
                if (pause) t.wv.onPause();
                else t.wv.onResume();
            } catch (Exception ignored) {
            }
        }
    }

    boolean hasWeb() {
        return webHost != null;
    }

    /** Opens the tabs of a saved session (only the current one is loaded), or the start page. */
    void openStartTabs(String startUrl) {
        JSONArray arr = sess != null ? sess.optJSONArray("tabs") : null;
        if (arr == null || arr.length() == 0) {
            newTab(startUrl, true);
            return;
        }
        int cur = Math.max(0, Math.min(sess.optInt("cur", 0), arr.length() - 1));
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String u = o.optString("u", "");
            String ti = o.optString("t", "");
            if (!type.equals(T_WEB)) {          // single-page windows: just reopen their page
                newTab(u.isEmpty() ? startUrl : u, true);
                return;
            }
            if (i == cur) newTab(u.isEmpty() ? startUrl : u, true);
            else addDiscardedTab(u.isEmpty() ? homeUrl() : u, ti);
        }
        if (tabs.isEmpty()) newTab(startUrl, true);
    }

    WebView createWebView(final Tab t) {
        FWebView w = new FWebView(svc);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        if (type.equals(T_YT)) s.setMediaPlaybackRequiresUserGesture(false);
        if (type.equals(T_WEB)) {
            s.setSupportMultipleWindows(true);
            // long-press a link -> small bar: open in new tab / copy link
            w.setOnLongClickListener(v -> {
                WebView wv = (WebView) v;
                int tp = wv.getHitTestResult().getType();
                if (tp == WebView.HitTestResult.IMAGE_TYPE) {
                    String ex = wv.getHitTestResult().getExtra();
                    if (ex != null && ex.startsWith("http")) {
                        showLinkBar(ex, ex);
                        return true;
                    }
                }
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
        w.setDownloadListener((du, ua, cd, mime, len) -> startDownload(du, ua, cd, mime));

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

            @Override
            public void onPageStarted(WebView v, String u, Bitmap favicon) {
                t.url = u;
                if (miniOn && t == curTab && (isWatchUrl(u) || (u != null && u.contains("/shorts/")))) {
                    h.post(() -> closeMini());
                }
            }

            @Override
            public void onPageFinished(WebView v, String u) {
                if (type.equals(T_YT)) v.evaluateJavascript(JS_NOHIDE, null);
                if (t.wv == v) {
                    String cu = v.getUrl();
                    if (cu != null && cu.startsWith("http")) t.url = cu;
                    svc.scheduleSave();
                }
            }

            // The web renderer can die (e.g. Facebook's endless feed eats memory). Without this
            // the whole app is killed; instead rebuild just this tab and reload its page.
            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                if (t.wv == v) {
                    h.post(() -> {
                        if (t == miniTab) closeMini();
                        else recreateTab(t);
                    });
                }
                return true;
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

            // <input type=file> on a page -> system file picker
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                return pickFile(cb, params);
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

    // ---------- home page per type, URL entry, crash recovery ----------
    String homeUrl() {
        switch (type) {
            case T_FB: return "https://m.facebook.com/";
            case T_YT: return "https://m.youtube.com/";
            case T_TR: return "https://translate.google.com/?sl=auto&tl=th";
            default:   return HOME;
        }
    }

    void toggleUrlRow() {
        if (urlRow == null) return;
        if (urlRow.getVisibility() == View.VISIBLE) {
            urlRow.setVisibility(View.GONE);
            return;
        }
        urlRow.setVisibility(View.VISIBLE);
        String cur = (web != null) ? web.getUrl() : null;
        urlEdit.setText(cur == null ? "" : cur);
        urlEdit.selectAll();
        // the window only becomes focusable after this tap, so wait a moment for the keyboard
        h.postDelayed(() -> {
            urlEdit.requestFocus();
            InputMethodManager imm = (InputMethodManager) svc.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(urlEdit, InputMethodManager.SHOW_IMPLICIT);
        }, 250);
    }

    void goUrl(String in) {
        in = in.trim();
        if (in.isEmpty() || web == null) return;
        String u;
        if (in.startsWith("http://") || in.startsWith("https://")) u = in;
        else if (in.contains(".") && !in.contains(" ")) u = "https://" + in;
        else u = "https://www.google.com/search?q=" + Uri.encode(in);
        web.loadUrl(u);
        urlEdit.clearFocus();
        InputMethodManager imm = (InputMethodManager) svc.getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(urlEdit.getWindowToken(), 0);
        urlRow.setVisibility(View.GONE);
    }

    void recreateTab(Tab t) {
        if (!tabs.contains(t) || webHost == null || t.wv == null) return;
        // a page that kills its renderer again and again: stop reloading it, go home instead
        long now = SystemClock.uptimeMillis();
        t.crashes = (now - t.lastCrash < 20000) ? t.crashes + 1 : 1;
        t.lastCrash = now;
        boolean loop = t.crashes >= 3;
        if (loop) {
            t.crashes = 0;
            t.url = homeUrl();
        }
        boolean wasCurrent = (curTab == t);
        webHost.removeView(t.wv);
        try {
            t.wv.destroy();
        } catch (Exception ignored) {
        }
        t.wv = createWebView(t);
        webHost.addView(t.wv, 0, new FrameLayout.LayoutParams(MATCH, MATCH));
        t.wv.setVisibility(wasCurrent ? View.VISIBLE : View.GONE);
        if (wasCurrent) web = t.wv;
        t.wv.loadUrl((t.url != null && !t.url.isEmpty()) ? t.url : homeUrl());
        Toast.makeText(svc, loop ? "หน้านี้ทำให้ค้างซ้ำ เปิดหน้าแรกแทน"
                : "หน้าเว็บค้าง รีโหลดให้อัตโนมัติแล้ว", Toast.LENGTH_SHORT).show();
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

    // ---------- small helpers ----------
    void toast(String s) {
        Toast.makeText(svc, s, Toast.LENGTH_SHORT).show();
    }

    static String hostOf(String u) {
        try {
            String host = Uri.parse(u).getHost();
            return host == null ? String.valueOf(u) : host.replaceFirst("^www\\.", "");
        } catch (Exception e) {
            return String.valueOf(u);
        }
    }

    String curUrl() {
        String u = web != null ? web.getUrl() : null;
        if (u == null && curTab != null) u = curTab.url;
        return u;
    }

    String curTitle() {
        String t = curTab != null ? curTab.title : null;
        if (t == null || t.isEmpty() || t.equals("แท็บใหม่")) t = hostOf(curUrl());
        return t;
    }

    // ---------- browser menu: pinned sites, bookmarks, downloads ----------
    boolean panelOpen() {
        return panel != null && panel.getVisibility() == View.VISIBLE;
    }

    void hidePanel() {
        if (panel != null) panel.setVisibility(View.GONE);
    }

    void togglePanel() {
        if (panelOpen()) hidePanel();
        else showPanel();
    }

    TextView panelBtn(String t, View.OnClickListener l) {
        TextView b = label(t, 12, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF3A3A3A);
        bg.setCornerRadius(dp(8));
        b.setBackground(bg);
        b.setOnClickListener(l);
        return b;
    }

    void showPanel() {
        if (webHost == null) return;
        if (panel == null) {
            panel = new FrameLayout(svc);
            panel.setBackgroundColor(0xFF1E1E1E);
            panel.setClickable(true);     // do not let touches fall through to the page below
            webHost.addView(panel, new FrameLayout.LayoutParams(MATCH, MATCH));
        }
        panel.removeAllViews();

        ScrollView sv = new ScrollView(svc);
        LinearLayout box = new LinearLayout(svc);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(4), dp(8), dp(12));
        sv.addView(box, new FrameLayout.LayoutParams(MATCH, WRAP));
        panel.addView(sv, new FrameLayout.LayoutParams(MATCH, MATCH));

        LinearLayout head = new LinearLayout(svc);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView ttl = label("เมนูเบราว์เซอร์", 14, Color.WHITE);
        ttl.setPadding(dp(4), 0, 0, 0);
        head.addView(ttl, new LinearLayout.LayoutParams(0, WRAP, 1f));
        head.addView(btn("✕", v -> hidePanel()), new LinearLayout.LayoutParams(dp(40), dp(36)));
        box.addView(head, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout acts = new LinearLayout(svc);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"📌 ปักหมุด", "★ บุ๊กมาร์ก", "⬇ ไฟล์ที่โหลด"};
        View.OnClickListener[] fns = {
                v -> saveSite("pins"),
                v -> saveSite("bookmarks"),
                v -> openDownloads()
        };
        for (int i = 0; i < names.length; i++) {
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, dp(40), 1f);
            pl.setMargins(dp(2), 0, dp(2), 0);
            acts.addView(panelBtn(names[i], fns[i]), pl);
        }
        box.addView(acts, new LinearLayout.LayoutParams(MATCH, WRAP));

        addSection(box, "หมุดที่ปักไว้", "pins", true);
        addSection(box, "บุ๊กมาร์ก", "bookmarks", false);

        panel.setVisibility(View.VISIBLE);
        panel.bringToFront();
    }

    void addSection(LinearLayout box, String title, final String key, final boolean isPin) {
        List<SiteStore.Item> items = SiteStore.load(bsp, key);
        TextView hd = label(title + " (" + items.size() + ")", 13, 0xFF9DB7FF);
        hd.setPadding(dp(4), dp(14), 0, dp(4));
        box.addView(hd);
        if (items.isEmpty()) {
            TextView e = label(isPin ? "ยังไม่มีหมุด เปิดเว็บที่ต้องการแล้วกด 📌"
                    : "ยังไม่มีบุ๊กมาร์ก เปิดเว็บที่ต้องการแล้วกด ★", 12, 0xFF888888);
            e.setPadding(dp(4), dp(4), 0, dp(4));
            box.addView(e);
            return;
        }
        for (final SiteStore.Item it : items) {
            LinearLayout row = new LinearLayout(svc);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout txt = new LinearLayout(svc);
            txt.setOrientation(LinearLayout.VERTICAL);
            txt.setPadding(dp(6), dp(6), dp(6), dp(6));
            TextView t1 = label(it.title, 14, Color.WHITE);
            t1.setSingleLine(true);
            t1.setEllipsize(TextUtils.TruncateAt.END);
            TextView t2 = label(hostOf(it.url), 11, 0xFF999999);
            t2.setSingleLine(true);
            t2.setEllipsize(TextUtils.TruncateAt.END);
            txt.addView(t1);
            txt.addView(t2);
            txt.setOnClickListener(v -> openSite(it.url));
            row.addView(txt, new LinearLayout.LayoutParams(0, WRAP, 1f));

            TextView del = btn("✕", v -> {
                SiteStore.remove(bsp, key, it.url);
                if (isPin) refreshPins();
                h.post(this::showPanel);
            });
            del.setTextColor(0xFFFF8A8A);
            row.addView(del, new LinearLayout.LayoutParams(dp(40), dp(40)));
            box.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
    }

    /** Pin or bookmark the page that is open now. */
    void saveSite(String key) {
        boolean pin = key.equals("pins");
        String u = curUrl();
        if (u == null || !u.startsWith("http")) {
            toast("หน้านี้ยังบันทึกไม่ได้");
            return;
        }
        int r = SiteStore.add(bsp, key, curTitle(), u, pin ? 12 : 300);
        if (r == SiteStore.ADDED) toast(pin ? "ปักหมุดแล้ว" : "บันทึกบุ๊กมาร์กแล้ว");
        else if (r == SiteStore.DUPLICATE) toast(pin ? "ปักหมุดไว้แล้ว" : "มีบุ๊กมาร์กนี้แล้ว");
        else toast(pin ? "ปักหมุดได้สูงสุด 12 เว็บ" : "บุ๊กมาร์กเต็มแล้ว");
        if (pin) refreshPins();
        showPanel();
    }

    /** The row of pinned sites under the tab strip (hidden when there are none). */
    void refreshPins() {
        if (pinRow == null) return;
        pinRow.removeAllViews();
        List<SiteStore.Item> items = SiteStore.load(bsp, "pins");
        pinScroll.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        for (final SiteStore.Item it : items) {
            TextView c = label(it.title, 12, Color.WHITE);
            c.setSingleLine(true);
            c.setEllipsize(TextUtils.TruncateAt.END);
            c.setMaxWidth(dp(110));
            c.setGravity(Gravity.CENTER);
            c.setPadding(dp(10), 0, dp(10), 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF3A3A3A);
            bg.setCornerRadius(dp(11));
            c.setBackground(bg);
            c.setOnClickListener(v -> openSite(it.url));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(WRAP, dp(22));
            cp.setMargins(dp(2), 0, dp(2), 0);
            pinRow.addView(c, cp);
        }
    }

    /** Open a pinned/bookmarked site: jump to its tab if already open, else a new tab. */
    void openSite(String url) {
        hidePanel();
        for (Tab t : tabs) {
            if (SiteStore.same(url, t.url)) {
                selectTab(t);
                return;
            }
        }
        newTab(url, true);
    }

    // ---------- downloads ----------
    void startDownload(String url, String ua, String cd, String mime) {
        try {
            if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
                toast("ลิงก์แบบนี้ (blob/data) ยังโหลดตรง ๆ ไม่ได้");
                return;
            }
            String name = URLUtil.guessFileName(url, cd, mime);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            if (mime != null && !mime.isEmpty()) r.setMimeType(mime);
            String ck = CookieManager.getInstance().getCookie(url);
            if (ck != null) r.addRequestHeader("Cookie", ck);
            if (ua != null) r.addRequestHeader("User-Agent", ua);
            String ref = web != null ? web.getUrl() : null;
            if (ref != null && ref.startsWith("http")) r.addRequestHeader("Referer", ref);
            r.setTitle(name);
            r.setDescription(hostOf(url));
            r.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            DownloadManager dm = (DownloadManager) svc.getSystemService(Context.DOWNLOAD_SERVICE);
            dm.enqueue(r);
            toast("เริ่มดาวน์โหลด: " + name);
        } catch (Exception e) {
            toast("โหลดไฟล์ไม่ได้: " + e.getMessage());
        }
    }

    /** Opens the system Downloads list (the window goes to its bubble so it is not in the way). */
    void openDownloads() {
        try {
            Intent i = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            hidePanel();
            minimizeToBubble();
            svc.startActivity(i);
        } catch (Exception e) {
            toast("เปิดรายการดาวน์โหลดไม่ได้");
        }
    }

    // ---------- upload (a page asks for a file) ----------
    boolean pickFile(ValueCallback<Uri[]> cb, WebChromeClient.FileChooserParams p) {
        try {
            FilePickerActivity.cancelPending();      // an older request is still open: end it
            FilePickerActivity.pending = cb;
            FilePickerActivity.onDone = this::showAfterPicker;
            Intent i = new Intent(svc, FilePickerActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.putExtra("accept", p != null ? p.getAcceptTypes() : null);
            i.putExtra("multiple", p != null
                    && p.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE);
            hideForPicker();
            svc.startActivity(i);
        } catch (Exception e) {
            FilePickerActivity.cancelPending();      // answers the page with "no file"
            showAfterPicker();
            toast("เปิดตัวเลือกไฟล์ไม่ได้");
        }
        return true;
    }

    /** The window steps aside while the system file picker is on screen. */
    void hideForPicker() {
        if (minimized || !rootAttached || pickerHidden) return;
        pickerHidden = true;
        pickX = lp.x;
        pickY = lp.y;
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        lp.x = -30000;
        lp.y = -30000;
        updateLayout();
    }

    void showAfterPicker() {
        if (!pickerHidden) return;
        pickerHidden = false;
        if (minimized || !rootAttached) return;
        lp.flags = FLAGS;
        lp.x = pickX;
        lp.y = pickY;
        updateLayout();
    }

    // ---------- saved session (windows come back if the system kills the app) ----------
    JSONObject toJson() throws org.json.JSONException {
        JSONObject o = new JSONObject();
        o.put("type", type);
        int x = lp.x, y = lp.y, w = lp.width, hh = lp.height;
        if (customView != null) {
            x = prevX;
            y = prevY;
            w = prevW;
            hh = prevH;
        }
        if (minimized) {
            x = restX;
            y = restY;
        }
        if (pickerHidden) {
            x = pickX;
            y = pickY;
        }
        o.put("x", x);
        o.put("y", y);
        o.put("w", w);
        o.put("h", hh);
        o.put("minimized", minimized);
        o.put("bx", bubX);
        o.put("by", bubY);
        JSONArray ta = new JSONArray();
        int cur = 0, i = 0;
        for (Tab t : tabs) {
            String u = t.url;
            if (t.wv != null) {
                String cu = t.wv.getUrl();
                if (cu != null && cu.startsWith("http")) u = cu;
            }
            if (u == null || !u.startsWith("http")) u = homeUrl();
            JSONObject to = new JSONObject();
            to.put("u", u);
            to.put("t", t.title == null ? "" : t.title);
            ta.put(to);
            if (t == curTab) cur = i;
            i++;
        }
        o.put("tabs", ta);
        o.put("cur", cur);
        return o;
    }

    // ---------- show / minimize / restore / close ----------
    void show() {
        lp.flags = FLAGS;
        wm.addView(root, lp);
        rootAttached = true;
        h.postDelayed(limitTick, 30000);
    }

    /**
     * Minimize keeps the window alive (so a YouTube video keeps playing): it is only
     * moved far off-screen and made untouchable, and a small bubble is shown instead.
     * The bubble has its own saved position/size, independent of the window.
     */
    void minimize() {
        minimizeToBubble();
    }

    void prepareMinimize() {
        if (customView != null) exitFull();
        hideLinkBar();
        InputMethodManager imm = (InputMethodManager) svc.getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
    }

    void minimizeToBubble() {
        if (minimized || !rootAttached) return;
        prepareMinimize();
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
        pauseWebs(true);
        svc.updatePower();
        svc.scheduleSave();
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
        pauseWebs(false);
        if (curTab != null && curTab.wv == null) selectTab(curTab);   // rebuild a frozen page
        svc.updatePower();
        svc.scheduleSave();
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
        if (webHost != null) closeMini();
        if (webHost != null) webHost.removeAllViews();
        if (pickerHidden) {
            pickerHidden = false;
            FilePickerActivity.cancelPending();
        }
        for (Tab t : new ArrayList<>(tabs)) {
            if (t.wv == null) continue;
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
                if (panelOpen()) {
                    if (e.getAction() == KeyEvent.ACTION_UP) hidePanel();
                    return true;
                }
                if (customView != null) {
                    if (e.getAction() == KeyEvent.ACTION_UP) exitFull();
                    return true;
                }
                if (web != null && web.canGoBack()) {
                    if (e.getAction() == KeyEvent.ACTION_UP) onWebBack();
                    return true;
                }
            }
            return super.dispatchKeyEvent(e);
        }
    }
}
