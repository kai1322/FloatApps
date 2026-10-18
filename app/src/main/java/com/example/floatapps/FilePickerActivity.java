package com.example.floatapps;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Invisible helper screen. A floating window cannot open the system file picker by itself,
 * so the web page's "choose file" request is forwarded here and the result is handed back
 * to the WebView.
 */
public class FilePickerActivity extends Activity {

    private static final int REQ = 77;

    /** Set by FloatWindow right before this activity is started. */
    static ValueCallback<Uri[]> pending;
    /** Called (on the main thread) when the picker is finished, whatever the outcome. */
    static Runnable onDone;

    private boolean delivered;

    /** Answer the waiting web page with "nothing chosen" so its file input never hangs. */
    static void cancelPending() {
        ValueCallback<Uri[]> cb = pending;
        Runnable d = onDone;
        pending = null;
        onDone = null;
        if (cb != null) {
            try {
                cb.onReceiveValue(null);
            } catch (Exception ignored) {
            }
        }
        if (d != null) d.run();
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // nothing is waiting (e.g. the app was restarted by the system): just go away
        if (pending == null || b != null) {
            finish();
            return;
        }
        Intent in = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        in.addCategory(Intent.CATEGORY_OPENABLE);
        in.setType("*/*");
        in.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        in.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, getIntent().getBooleanExtra("multiple", false));

        List<String> types = new ArrayList<>();
        String[] acc = getIntent().getStringArrayExtra("accept");
        if (acc != null) {
            for (String a : acc) {
                if (a != null && a.contains("/") && !types.contains(a.trim())) types.add(a.trim());
            }
        }
        if (!types.isEmpty()) {
            in.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
        }
        try {
            startActivityForResult(in, REQ);
        } catch (Exception e) {
            deliver(null);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        Uri[] r = null;
        if (res == RESULT_OK && data != null) {
            r = WebChromeClient.FileChooserParams.parseResult(res, data);
            if (r != null) {
                for (Uri u : r) {
                    try {
                        // keep read access after this screen is closed (the page may upload later)
                        getContentResolver().takePersistableUriPermission(u,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        deliver(r);
    }

    private void deliver(Uri[] r) {
        if (!delivered) {
            delivered = true;
            ValueCallback<Uri[]> cb = pending;
            Runnable d = onDone;
            pending = null;
            onDone = null;
            if (cb != null) {
                try {
                    cb.onReceiveValue(r);
                } catch (Exception ignored) {
                }
            }
            if (d != null) d.run();
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        // closed in any other way (e.g. Back from a recreated screen): report "cancelled"
        if (!delivered) {
            delivered = true;
            cancelPending();
        }
        super.onDestroy();
    }
}
