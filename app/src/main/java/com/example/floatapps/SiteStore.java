package com.example.floatapps;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Tiny persistent list of sites (used for both pinned sites and bookmarks). */
final class SiteStore {

    static final int ADDED = 1, DUPLICATE = 0, FULL = -1;

    static class Item {
        final String title;
        final String url;

        Item(String title, String url) {
            this.title = title;
            this.url = url;
        }
    }

    private SiteStore() {
    }

    /** Compare two URLs ignoring a trailing slash and the #fragment. */
    static boolean same(String a, String b) {
        return norm(a).equals(norm(b));
    }

    private static String norm(String u) {
        if (u == null) return "";
        int i = u.indexOf('#');
        if (i >= 0) u = u.substring(0, i);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    static List<Item> load(SharedPreferences sp, String key) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString(key, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String u = o.optString("u", "");
                if (u.isEmpty()) continue;
                out.add(new Item(o.optString("t", u), u));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void write(SharedPreferences sp, String key, List<Item> items) {
        try {
            JSONArray a = new JSONArray();
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("t", it.title);
                o.put("u", it.url);
                a.put(o);
            }
            sp.edit().putString(key, a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static int add(SharedPreferences sp, String key, String title, String url, int max) {
        List<Item> items = load(sp, key);
        for (Item it : items) {
            if (same(it.url, url)) return DUPLICATE;
        }
        if (items.size() >= max) return FULL;
        items.add(new Item(title == null || title.isEmpty() ? url : title, url));
        write(sp, key, items);
        return ADDED;
    }

    static void remove(SharedPreferences sp, String key, String url) {
        List<Item> items = load(sp, key);
        List<Item> keep = new ArrayList<>();
        for (Item it : items) {
            if (!same(it.url, url)) keep.add(it);
        }
        write(sp, key, keep);
    }
}
