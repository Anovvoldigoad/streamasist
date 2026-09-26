package com.streamoverlay.lite;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class OverlayEditorActivity extends Activity {
    private static final int REQ_REPLACE_IMAGE = 7101;
    private OverlayRepository repo;
    private OverlayItem item;
    private EditText title, text, width, height, textSize, textColor, bgColor, duration;
    private Switch enabled, locked;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        repo = new OverlayRepository(this);
        item = repo.get(getIntent().getStringExtra("id"));
        if (item == null) { finish(); return; }
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF101114);
        LinearLayout root = Ui.column(this);
        Ui.pad(root, 18);
        scroll.addView(root);
        setContentView(scroll);

        TextView h = Ui.text(this, "Edit " + item.title, 26, Color.WHITE);
        h.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(h);

        title = addField(root, "Nama layer", item.title, false);
        if (!OverlayItem.TYPE_IMAGE.equals(item.type)) {
            text = addField(root,
                    OverlayItem.TYPE_DONATION.equals(item.type) ? "Template: {name} {amount} {message}" : "Teks",
                    item.text, true);
        }
        width = addField(root, "Lebar (dp)", String.valueOf(item.widthDp), false);
        height = addField(root, "Tinggi (dp)", String.valueOf(item.heightDp), false);
        width.setInputType(InputType.TYPE_CLASS_NUMBER);
        height.setInputType(InputType.TYPE_CLASS_NUMBER);

        if (!OverlayItem.TYPE_IMAGE.equals(item.type)) {
            textSize = addField(root, "Ukuran teks (sp)", String.valueOf(item.textSizeSp), false);
            textColor = addField(root, "Warna teks ARGB/HEX", item.textColor, false);
            bgColor = addField(root, "Background ARGB/HEX", item.backgroundColor, false);
        }

        if (OverlayItem.TYPE_DONATION.equals(item.type)) {
            duration = addField(root, "Durasi alert (ms)", String.valueOf(item.durationMs), false);
            duration.setInputType(InputType.TYPE_CLASS_NUMBER);
        }

        if (OverlayItem.TYPE_IMAGE.equals(item.type)) {
            TextView uri = Ui.text(this, item.imageUri, 12, 0xFF9FA5B0);
            uri.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
            root.addView(uri);
            Button replace = Ui.button(this, "Ganti image / GIF");
            replace.setOnClickListener(v -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                startActivityForResult(i, REQ_REPLACE_IMAGE);
            });
            root.addView(replace, Ui.matchWrap(4, this));
        }

        enabled = new Switch(this);
        enabled.setText("Overlay ON");
        enabled.setTextColor(Color.WHITE);
        enabled.setChecked(item.enabled);
        root.addView(enabled, Ui.matchWrap(12, this));

        locked = new Switch(this);
        locked.setText("LOCK (sentuhan tembus ke aplikasi live)");
        locked.setTextColor(Color.WHITE);
        locked.setChecked(item.locked);
        root.addView(locked, Ui.matchWrap(4, this));

        TextView help = Ui.text(this,
                "Saat UNLOCK, overlay bisa digeser langsung di atas aplikasi lain. Tap cepat pada overlay untuk kembali ke halaman edit ini.",
                13, 0xFFB8BDC7);
        root.addView(help, Ui.matchWrap(12, this));

        Button save = Ui.button(this, "Save & Apply");
        save.setOnClickListener(v -> save());
        root.addView(save, Ui.matchWrap(14, this));
    }

    private EditText addField(LinearLayout root, String label, String value, boolean multiline) {
        TextView l = Ui.text(this, label, 13, 0xFFB8BDC7);
        root.addView(l, Ui.matchWrap(10, this));
        EditText e = Ui.input(this, label);
        e.setText(value);
        e.setSingleLine(!multiline);
        if (multiline) {
            e.setMinLines(2);
            e.setGravity(android.view.Gravity.TOP);
        }
        root.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private int intOr(EditText e, int fallback) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception x) { return fallback; }
    }
    private long longOr(EditText e, long fallback) {
        try { return Long.parseLong(e.getText().toString().trim()); } catch (Exception x) { return fallback; }
    }
    private float floatOr(EditText e, float fallback) {
        try { return Float.parseFloat(e.getText().toString().trim()); } catch (Exception x) { return fallback; }
    }

    private void save() {
        item.title = title.getText().toString().trim().isEmpty() ? "Overlay" : title.getText().toString().trim();
        item.widthDp = Math.max(40, Math.min(1200, intOr(width, item.widthDp)));
        item.heightDp = Math.max(24, Math.min(1200, intOr(height, item.heightDp)));
        item.enabled = enabled.isChecked();
        item.locked = locked.isChecked();
        if (!OverlayItem.TYPE_IMAGE.equals(item.type)) {
            item.text = text.getText().toString();
            item.textSizeSp = Math.max(8f, Math.min(120f, floatOr(textSize, item.textSizeSp)));
            item.textColor = validColor(textColor.getText().toString(), item.textColor);
            item.backgroundColor = validColor(bgColor.getText().toString(), item.backgroundColor);
        }
        if (OverlayItem.TYPE_DONATION.equals(item.type)) {
            item.durationMs = Math.max(1000, Math.min(60000, longOr(duration, item.durationMs)));
        }
        repo.upsert(item);
        refreshService();
        Toast.makeText(this, "Applied", Toast.LENGTH_SHORT).show();
        finish();
    }

    private String validColor(String value, String fallback) {
        try { android.graphics.Color.parseColor(value.trim()); return value.trim(); }
        catch (Exception e) { return fallback; }
    }

    private void refreshService() {
        if (!repo.prefs().getBoolean("engine_enabled", false)) return;
        Intent i = new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_REFRESH);
        try { startService(i); } catch (Exception ignored) {}
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_REPLACE_IMAGE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception ignored) {}
            item.imageUri = uri.toString();
            repo.upsert(item);
            refreshService();
            recreate();
        }
    }
}
