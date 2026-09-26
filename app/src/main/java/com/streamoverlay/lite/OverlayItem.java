package com.streamoverlay.lite;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.UUID;

public class OverlayItem {
    public static final String TYPE_TEXT = "text";
    public static final String TYPE_IMAGE = "image";
    public static final String TYPE_DONATION = "donation";

    public String id = UUID.randomUUID().toString();
    public String type = TYPE_TEXT;
    public String title = "Overlay";
    public boolean enabled = true;
    public boolean locked = false;

    // x/y always represent the CONTENT top-left, not the editor controls around it.
    public int x = 24;
    public int y = 160;
    public int widthDp = 220;
    public int heightDp = 80;

    public String text = "LIVE";
    public float textSizeSp = 26f;
    public String textColor = "#FFFFFFFF";
    public String backgroundColor = "#00000000";
    public String textHorizontal = "center"; // left, center, right
    public String textVertical = "center";   // top, center, bottom

    public String imageUri = "";
    // Natural media width / height. Used by the proportional resize handle.
    public float imageAspectRatio = 0f;

    public String sourceUrl = "";

    public static OverlayItem textDefault() {
        OverlayItem i = new OverlayItem();
        i.type = TYPE_TEXT;
        i.title = "Text";
        i.text = "Tap untuk edit";
        i.widthDp = 230;
        i.heightDp = 72;
        i.textSizeSp = 26f;
        i.textColor = "#FFFFFFFF";
        i.backgroundColor = "#00000000";
        i.textHorizontal = "center";
        i.textVertical = "center";
        return i;
    }

    public static OverlayItem imageDefault(String uri) {
        OverlayItem i = new OverlayItem();
        i.type = TYPE_IMAGE;
        i.title = "Image / GIF";
        i.imageUri = uri == null ? "" : uri;
        i.widthDp = 180;
        i.heightDp = 180;
        i.imageAspectRatio = 0f;
        return i;
    }

    public static OverlayItem overlayLinkDefault(String url) {
        OverlayItem i = new OverlayItem();
        i.type = TYPE_DONATION;
        i.title = "Overlay Link";
        i.sourceUrl = url == null ? "" : url;
        i.widthDp = 320;
        i.heightDp = 180;
        return i;
    }

    // Compatibility alias for older source/repositories.
    public static OverlayItem donationDefault(String url) {
        return overlayLinkDefault(url);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("type", type);
        o.put("title", title);
        o.put("enabled", enabled);
        o.put("locked", locked);
        o.put("x", x);
        o.put("y", y);
        o.put("widthDp", widthDp);
        o.put("heightDp", heightDp);
        o.put("text", text);
        o.put("textSizeSp", textSizeSp);
        o.put("textColor", textColor);
        o.put("backgroundColor", backgroundColor);
        o.put("textHorizontal", textHorizontal);
        o.put("textVertical", textVertical);
        o.put("imageUri", imageUri);
        o.put("imageAspectRatio", imageAspectRatio);
        o.put("sourceUrl", sourceUrl);
        return o;
    }

    public static OverlayItem fromJson(JSONObject o) {
        OverlayItem i = new OverlayItem();
        i.id = o.optString("id", i.id);
        i.type = o.optString("type", TYPE_TEXT);
        i.title = o.optString("title", "Overlay");
        if (TYPE_DONATION.equals(i.type)
                && (i.title == null || i.title.trim().isEmpty()
                || "Donation Alert".equalsIgnoreCase(i.title)
                || "Donation source".equalsIgnoreCase(i.title))) {
            i.title = "Overlay Link";
        }
        i.enabled = o.optBoolean("enabled", true);
        i.locked = o.optBoolean("locked", false);
        i.x = o.optInt("x", 24);
        i.y = o.optInt("y", 160);
        i.widthDp = o.optInt("widthDp", TYPE_DONATION.equals(i.type) ? 320 : 220);
        i.heightDp = o.optInt("heightDp", TYPE_DONATION.equals(i.type) ? 180 : 80);
        i.text = o.optString("text", "LIVE");
        i.textSizeSp = (float) o.optDouble("textSizeSp", 26.0);
        i.textColor = o.optString("textColor", "#FFFFFFFF");
        i.backgroundColor = o.optString("backgroundColor", "#00000000");
        i.textHorizontal = o.optString("textHorizontal", "center");
        i.textVertical = o.optString("textVertical", "center");
        i.imageUri = o.optString("imageUri", "");
        i.imageAspectRatio = (float) o.optDouble("imageAspectRatio", 1.0);
        if (i.imageAspectRatio <= 0f) i.imageAspectRatio = 0f;
        i.sourceUrl = o.optString("sourceUrl", "");
        return i;
    }
}
