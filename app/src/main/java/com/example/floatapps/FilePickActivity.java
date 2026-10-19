package com.example.floatapps;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;

/** Invisible helper: opens the system file picker for a web page's "choose file" button. */
public class FilePickActivity extends Activity {

    static ValueCallback<Uri[]> pending;
    static WebChromeClient.FileChooserParams params;

    /** Always answers the callback exactly once, so the page never gets stuck. */
    static boolean start(Context c, ValueCallback<Uri[]> cb, WebChromeClient.FileChooserParams p) {
        if (pending != null) {
            pending.onReceiveValue(null);
            pending = null;
        }
        pending = cb;
        params = p;
        try {
            Intent i = new Intent(c, FilePickActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            pending = null;
            params = null;
            cb.onReceiveValue(null);
        }
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (pending == null || params == null) {
            finish();
            return;
        }
        try {
            startActivityForResult(params.createIntent(), 1);
        } catch (Exception e) {
            finishWith(null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri[] result = null;
        try {
            result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        } catch (Exception ignored) {
        }
        finishWith(result);
    }

    private void finishWith(Uri[] result) {
        ValueCallback<Uri[]> cb = pending;
        pending = null;
        params = null;
        if (cb != null) cb.onReceiveValue(result);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (pending != null) {
            ValueCallback<Uri[]> cb = pending;
            pending = null;
            params = null;
            cb.onReceiveValue(null);
        }
        super.onDestroy();
    }
}
