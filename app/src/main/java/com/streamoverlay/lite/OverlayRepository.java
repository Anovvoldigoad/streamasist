package com.streamoverlay.lite;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class OverlayRepository {
    public static final String PREFS = "stream_overlay_prefs";
    private static final String KEY_ITEMS = "overlay_items";
    private final SharedPreferences prefs;

    public OverlayRepository(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized List<OverlayItem> getAll() {
        List<OverlayItem> out = new ArrayList<>();
        String raw = prefs.getString(KEY_ITEMS, "[]");
        try {
            JSONArray a = new JSONArray(raw);
            for (int n = 0; n < a.length(); n++) {
                JSONObject o = a.optJSONObject(n);
                if (o != null) out.add(OverlayItem.fromJson(o));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public synchronized OverlayItem get(String id) {
        for (OverlayItem i : getAll()) if (i.id.equals(id)) return i;
        return null;
    }

    public synchronized void upsert(OverlayItem item) {
        List<OverlayItem> all = getAll();
        boolean found = false;
        for (int n = 0; n < all.size(); n++) {
            if (all.get(n).id.equals(item.id)) {
                all.set(n, item);
                found = true;
                break;
            }
        }
        if (!found) all.add(item);
        saveAll(all);
    }

    public synchronized void delete(String id) {
        List<OverlayItem> all = getAll();
        all.removeIf(i -> i.id.equals(id));
        saveAll(all);
    }

    private synchronized void saveAll(List<OverlayItem> all) {
        JSONArray a = new JSONArray();
        try {
            for (OverlayItem i : all) a.put(i.toJson());
            prefs.edit().putString(KEY_ITEMS, a.toString()).apply();
        } catch (Exception ignored) {}
    }

    public SharedPreferences prefs() { return prefs; }
}
