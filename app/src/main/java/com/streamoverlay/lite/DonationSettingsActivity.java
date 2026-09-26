package com.streamoverlay.lite;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class DonationSettingsActivity extends Activity {
    private OverlayRepository repo;
    private Switch bridgeEnabled, httpEnabled;
    private EditText packageFilter, keywords, endpoint, interval;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        repo = new OverlayRepository(this);
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF101114);
        LinearLayout root = Ui.column(this);
        Ui.pad(root, 18);
        scroll.addView(root);
        setContentView(scroll);

        TextView h = Ui.text(this, "Donation Sources", 26, Color.WHITE);
        h.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(h);
        TextView intro = Ui.text(this,
                "Pilih salah satu atau keduanya. Notification Bridge tidak mengirim data ke mana pun; ia hanya membaca notifikasi yang cocok dan menampilkannya sebagai alert lokal.",
                13, 0xFFB8BDC7);
        root.addView(intro, Ui.matchWrap(6, this));

        TextView nh = Ui.text(this, "1. Notification Bridge", 18, 0xFF7CFFB2);
        nh.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(nh, Ui.matchWrap(16, this));
        bridgeEnabled = new Switch(this);
        bridgeEnabled.setText("Aktifkan bridge");
        bridgeEnabled.setTextColor(Color.WHITE);
        bridgeEnabled.setChecked(repo.prefs().getBoolean("bridge_enabled", false));
        root.addView(bridgeEnabled);
        packageFilter = addField(root, "Filter package (opsional, contoh com.android.chrome)", repo.prefs().getString("bridge_package", ""));
        keywords = addField(root, "Keyword, pisahkan koma", repo.prefs().getString("bridge_keywords", "donasi,donate,saweria,trakteer"));
        Button access = Ui.button(this, "Buka Notification Access");
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        root.addView(access, Ui.matchWrap(6, this));

        TextView hh = Ui.text(this, "2. Generic JSON Feed", 18, 0xFF7CFFB2);
        hh.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(hh, Ui.matchWrap(18, this));
        TextView contract = Ui.text(this,
                "GET endpoint JSON: {\"event_id\":\"abc123\",\"name\":\"Budi\",\"amount\":25000,\"message\":\"Semangat!\"}. event_id harus unik agar tidak tampil dua kali.",
                12, 0xFFB8BDC7);
        root.addView(contract, Ui.matchWrap(4, this));
        httpEnabled = new Switch(this);
        httpEnabled.setText("Aktifkan JSON feed");
        httpEnabled.setTextColor(Color.WHITE);
        httpEnabled.setChecked(repo.prefs().getBoolean("http_enabled", false));
        root.addView(httpEnabled, Ui.matchWrap(6, this));
        endpoint = addField(root, "HTTPS endpoint", repo.prefs().getString("http_endpoint", ""));
        endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        interval = addField(root, "Interval polling detik (minimal 5)", String.valueOf(repo.prefs().getInt("http_interval", 8)));
        interval.setInputType(InputType.TYPE_CLASS_NUMBER);

        Button save = Ui.button(this, "Save Donation Settings");
        save.setOnClickListener(v -> save());
        root.addView(save, Ui.matchWrap(18, this));
    }

    private EditText addField(LinearLayout root, String label, String value) {
        TextView l = Ui.text(this, label, 13, 0xFFB8BDC7);
        root.addView(l, Ui.matchWrap(9, this));
        EditText e = Ui.input(this, label);
        e.setText(value);
        root.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private void save() {
        int seconds = 8;
        try { seconds = Math.max(5, Integer.parseInt(interval.getText().toString().trim())); } catch (Exception ignored) {}
        repo.prefs().edit()
                .putBoolean("bridge_enabled", bridgeEnabled.isChecked())
                .putString("bridge_package", packageFilter.getText().toString().trim())
                .putString("bridge_keywords", keywords.getText().toString().trim())
                .putBoolean("http_enabled", httpEnabled.isChecked())
                .putString("http_endpoint", endpoint.getText().toString().trim())
                .putInt("http_interval", seconds)
                .apply();
        if (repo.prefs().getBoolean("engine_enabled", false)) {
            Intent i = new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_REFRESH);
            try { startService(i); } catch (Exception ignored) {}
        }
        Toast.makeText(this, "Donation settings tersimpan", Toast.LENGTH_SHORT).show();
        finish();
    }
}
