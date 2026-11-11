package com.fastble.bledebugassistant.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.fastble.bledebugassistant.model.Beans;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地配置（对应原版 SharePrefUtil）：收藏、历史、过滤条件、发送/接收配置。
 */
public final class Prefs {

    private static final String SP = "ble_debug";

    private static final String KEY_FAVOURITE = "favourite";      // JSONArray [{mac,name}]
    private static final String KEY_HISTORY = "history";          // JSONArray [mac]
    private static final String KEY_FILTER = "filter";
    private static final String KEY_WRITE = "write";
    private static final String KEY_READ = "read";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    // ---------------- 收藏 ----------------

    public static boolean isFavourite(Context c, String mac) {
        if (mac == null) return false;
        for (String m : favouriteMacs(c)) {
            if (mac.equalsIgnoreCase(m)) return true;
        }
        return false;
    }

    public static List<String> favouriteMacs(Context c) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp(c).getString(KEY_FAVOURITE, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                out.add(arr.getJSONObject(i).optString("mac"));
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    public static void setFavourite(Context c, String mac, String name, boolean favourite) {
        try {
            JSONArray old = new JSONArray(sp(c).getString(KEY_FAVOURITE, "[]"));
            JSONArray arr = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.getJSONObject(i);
                if (!mac.equalsIgnoreCase(o.optString("mac"))) arr.put(o);
            }
            if (favourite) {
                JSONObject o = new JSONObject();
                o.put("mac", mac);
                o.put("name", name == null ? "" : name);
                arr.put(o);
            }
            sp(c).edit().putString(KEY_FAVOURITE, arr.toString()).apply();
        } catch (Exception ignore) {
        }
    }

    // ---------------- 连接历史 ----------------

    public static void addHistory(Context c, String mac) {
        if (mac == null) return;
        List<String> list = history(c);
        list.remove(mac);
        list.add(0, mac);
        while (list.size() > 20) list.remove(list.size() - 1);
        JSONArray arr = new JSONArray();
        for (String m : list) arr.put(m);
        sp(c).edit().putString(KEY_HISTORY, arr.toString()).apply();
    }

    public static List<String> history(Context c) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp(c).getString(KEY_HISTORY, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
        } catch (Exception ignore) {
        }
        return out;
    }

    // ---------------- 过滤条件 ----------------

    public static Beans.FilterConfig loadFilter(Context c) {
        Beans.FilterConfig f = new Beans.FilterConfig();
        try {
            JSONObject o = new JSONObject(sp(c).getString(KEY_FILTER, "{}"));
            f.macName = o.optString("macName", "");
            f.macMac = o.optString("macMac", "");
            f.broadcast = o.optString("broadcast", "");
            f.noName = o.optBoolean("noName", false);
            f.onlyLike = o.optBoolean("onlyLike", false);
            f.onlyBLE = o.optBoolean("onlyBLE", false);
            f.rssi = o.optInt("rssi", -100);
        } catch (Exception ignore) {
        }
        return f;
    }

    public static void saveFilter(Context c, Beans.FilterConfig f) {
        try {
            JSONObject o = new JSONObject();
            o.put("macName", f.macName);
            o.put("macMac", f.macMac);
            o.put("broadcast", f.broadcast);
            o.put("noName", f.noName);
            o.put("onlyLike", f.onlyLike);
            o.put("onlyBLE", f.onlyBLE);
            o.put("rssi", f.rssi);
            sp(c).edit().putString(KEY_FILTER, o.toString()).apply();
        } catch (Exception ignore) {
        }
    }

    // ---------------- 发送配置 ----------------

    public static Beans.WriteConfig loadWrite(Context c) {
        Beans.WriteConfig w = new Beans.WriteConfig();
        try {
            JSONObject o = new JSONObject(sp(c).getString(KEY_WRITE, "{}"));
            w.mode = o.optInt("mode", Beans.WriteConfig.MODE_SINGLE);
            w.hex = o.optBoolean("hex", true);
            w.writeType = o.optInt("writeType", 2);
            w.intervalMs = o.optInt("intervalMs", 10);
            w.content = o.optString("content", "");
            w.filePath = o.optString("filePath", "");
            w.randomLength = o.optBoolean("randomLength", false);
            w.randomMin = o.optInt("randomMin", 1);
            w.randomMax = o.optInt("randomMax", 20);
        } catch (Exception ignore) {
        }
        return w;
    }

    public static void saveWrite(Context c, Beans.WriteConfig w) {
        try {
            JSONObject o = new JSONObject();
            o.put("mode", w.mode);
            o.put("hex", w.hex);
            o.put("writeType", w.writeType);
            o.put("intervalMs", w.intervalMs);
            o.put("content", w.content);
            o.put("filePath", w.filePath);
            o.put("randomLength", w.randomLength);
            o.put("randomMin", w.randomMin);
            o.put("randomMax", w.randomMax);
            sp(c).edit().putString(KEY_WRITE, o.toString()).apply();
        } catch (Exception ignore) {
        }
    }

    // ---------------- 接收配置 ----------------

    public static Beans.ReadConfig loadRead(Context c) {
        Beans.ReadConfig r = new Beans.ReadConfig();
        try {
            JSONObject o = new JSONObject(sp(c).getString(KEY_READ, "{}"));
            r.hex = o.optBoolean("hex", true);
            r.saveToFile = o.optBoolean("saveToFile", false);
        } catch (Exception ignore) {
        }
        return r;
    }

    public static void saveRead(Context c, Beans.ReadConfig r) {
        try {
            JSONObject o = new JSONObject();
            o.put("hex", r.hex);
            o.put("saveToFile", r.saveToFile);
            sp(c).edit().putString(KEY_READ, o.toString()).apply();
        } catch (Exception ignore) {
        }
    }
}
