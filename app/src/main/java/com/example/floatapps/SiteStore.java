package com.example.floatapps;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Pinned sites and bookmarks, stored as a small JSON list in SharedPreferences. */
final class SiteStore {
    private SiteStore() { }

    static List<String[]> load(SharedPreferences sp, String key) {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString(key, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new String[]{o.optString("t"), o.optString("u")});
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void save(SharedPreferences sp, String key, List<String[]> list) {
        JSONArray a = new JSONArray();
        for (String[] p : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("t", p[0]);
                o.put("u", p[1]);
                a.put(o);
            } catch (Exception ignored) {
            }
        }
        sp.edit().putString(key, a.toString()).apply();
    }

    static void add(SharedPreferences sp, String key, String title, String url) {
        List<String[]> l = load(sp, key);
        for (String[] p : l) if (p[1].equals(url)) return;
        l.add(new String[]{title, url});
        save(sp, key, l);
    }

    static void remove(SharedPreferences sp, String key, String url) {
        List<String[]> l = load(sp, key);
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i)[1].equals(url)) {
                l.remove(i);
                break;
            }
        }
        save(sp, key, l);
    }
}
