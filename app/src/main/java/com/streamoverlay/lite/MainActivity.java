package com.streamoverlay.lite;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_IMAGE = 7001;
    private static final int REQ_NOTIFICATIONS = 7002;
    private OverlayRepository repo;
    private LinearLayout root;
    private LinearLayout listBox;
    private TextView permissionState;
    private Switch engineSwitch;
    private boolean rendering;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repo = new OverlayRepository(this);
        buildUi();
        requestNotificationPermissionIfNeeded();
    }

    @Override protected void onResume() {
        super.onResume();
        renderState();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF101114);
        root = Ui.column(this);
        Ui.pad(root, 18);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        TextView title = Ui.text(this, "Stream Overlay Lite", 28, Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        TextView sub = Ui.text(this,
                "Overlay livestream profesional dari 1 HP — text, image/GIF, donation alert.",
                14, 0xFFB8BDC7);
        sub.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 12));
        root.addView(sub);

        permissionState = Ui.text(this, "", 14, Color.WHITE);
        root.addView(permissionState, Ui.matchWrap(6, this));

        Button permission = Ui.button(this, "Izinkan tampil di atas aplikasi lain");
        permission.setOnClickListener(v -> requestOverlayPermission());
        root.addView(permission, Ui.matchWrap(6, this));

        LinearLayout engineRow = Ui.row(this);
        TextView engineLabel = Ui.text(this, "Overlay Engine", 18, Color.WHITE);
        engineRow.addView(engineLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        engineSwitch = new Switch(this);
        engineRow.addView(engineSwitch);
        root.addView(engineRow, Ui.matchWrap(14, this));
        engineSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (rendering) return;
            if (checked) {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Berikan izin overlay dulu.", Toast.LENGTH_SHORT).show();
                    rendering = true;
                    engineSwitch.setChecked(false);
                    rendering = false;
                    requestOverlayPermission();
                    return;
                }
                repo.prefs().edit().putBoolean("engine_enabled", true).apply();
                startOverlayEngine();
            } else {
                repo.prefs().edit().putBoolean("engine_enabled", false).apply();
                stopService(new Intent(this, OverlayService.class));
            }
        });

        LinearLayout addRow = Ui.row(this);
        Button addText = Ui.button(this, "+ Text");
        Button addImage = Ui.button(this, "+ Image/GIF");
        Button addDonation = Ui.button(this, "+ Donate");
        addText.setOnClickListener(v -> {
            OverlayItem i = OverlayItem.textDefault();
            repo.upsert(i);
            edit(i.id);
        });
        addImage.setOnClickListener(v -> pickImage());
        addDonation.setOnClickListener(v -> {
            OverlayItem i = OverlayItem.donationDefault();
            repo.upsert(i);
            edit(i.id);
        });
        addRow.addView(addText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addRow.addView(addImage, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addRow.addView(addDonation, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(addRow, Ui.matchWrap(12, this));

        LinearLayout donationRow = Ui.row(this);
        Button test = Ui.button(this, "Test Donation");
        Button settings = Ui.button(this, "Donation Source");
        test.setOnClickListener(v -> testDonation());
        settings.setOnClickListener(v -> startActivity(new Intent(this, DonationSettingsActivity.class)));
        donationRow.addView(test, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        donationRow.addView(settings, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(donationRow, Ui.matchWrap(6, this));

        TextView hint = Ui.text(this,
                "LOCK = overlay tidak bisa disentuh dan sentuhan masuk ke aplikasi live. UNLOCK = bisa digeser; tap overlay untuk edit lagi.",
                13, 0xFF9FA5B0);
        hint.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 6));
        root.addView(hint);

        TextView layerHeader = Ui.text(this, "LAYERS", 13, 0xFF7CFFB2);
        layerHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(layerHeader, Ui.matchWrap(8, this));

        listBox = Ui.column(this);
        root.addView(listBox, Ui.matchWrap(4, this));
    }

    private void renderState() {
        rendering = true;
        boolean granted = Settings.canDrawOverlays(this);
        permissionState.setText(granted ? "✓ Overlay permission aktif" : "! Overlay permission belum aktif");
        permissionState.setTextColor(granted ? 0xFF7CFFB2 : 0xFFFFC46B);
        engineSwitch.setChecked(repo.prefs().getBoolean("engine_enabled", false) && granted);
        rendering = false;
        renderLayers();
        if (repo.prefs().getBoolean("engine_enabled", false) && granted) startOverlayEngine();
    }

    private void renderLayers() {
        listBox.removeAllViews();
        List<OverlayItem> all = repo.getAll();
        if (all.isEmpty()) {
            TextView empty = Ui.text(this, "Belum ada overlay. Tambahkan Text, Image/GIF, atau Donate.", 14, 0xFF9FA5B0);
            Ui.pad(empty, 14);
            listBox.addView(empty);
            return;
        }
        for (OverlayItem item : all) listBox.addView(layerCard(item), Ui.matchWrap(8, this));
    }

    private View layerCard(OverlayItem item) {
        LinearLayout card = Ui.column(this);
        Ui.pad(card, 12);
        card.setBackground(Ui.rounded(0xFF1B1D22, 12, this));

        LinearLayout top = Ui.row(this);
        TextView name = Ui.text(this, item.title + "  ·  " + item.type.toUpperCase(), 16, Color.WHITE);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch enabled = new Switch(this);
        enabled.setChecked(item.enabled);
        top.addView(enabled);
        card.addView(top);

        TextView pos = Ui.text(this,
                "x=" + item.x + "  y=" + item.y + "  " + item.widthDp + "×" + item.heightDp + "dp",
                12, 0xFF9FA5B0);
        card.addView(pos);

        LinearLayout buttons = Ui.row(this);
        Button lock = Ui.button(this, item.locked ? "🔒 LOCKED" : "🔓 UNLOCKED");
        Button edit = Ui.button(this, "Edit");
        Button delete = Ui.button(this, "Delete");
        buttons.addView(lock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(edit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(delete, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(buttons);

        enabled.setOnCheckedChangeListener((b, checked) -> {
            item.enabled = checked;
            repo.upsert(item);
            refreshOverlayEngine();
        });
        lock.setOnClickListener(v -> {
            item.locked = !item.locked;
            repo.upsert(item);
            refreshOverlayEngine();
            renderLayers();
        });
        edit.setOnClickListener(v -> edit(item.id));
        delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Hapus overlay?")
                .setMessage(item.title)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus", (d, w) -> {
                    repo.delete(item.id);
                    refreshOverlayEngine();
                    renderLayers();
                }).show());
        return card;
    }

    private void edit(String id) {
        Intent i = new Intent(this, OverlayEditorActivity.class);
        i.putExtra("id", id);
        startActivity(i);
    }

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, REQ_IMAGE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMAGE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            OverlayItem item = OverlayItem.imageDefault(uri.toString());
            repo.upsert(item);
            edit(item.id);
        }
    }

    private void requestOverlayPermission() {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(i);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    private void startOverlayEngine() {
        Intent i = new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_REFRESH);
        try { startForegroundService(i); }
        catch (Exception e) { Toast.makeText(this, "Gagal memulai overlay: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private void refreshOverlayEngine() {
        if (!repo.prefs().getBoolean("engine_enabled", false) || !Settings.canDrawOverlays(this)) return;
        Intent i = new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_REFRESH);
        try { startService(i); } catch (Exception ignored) { startOverlayEngine(); }
    }

    private void testDonation() {
        boolean hasDonation = false;
        for (OverlayItem item : repo.getAll()) if (OverlayItem.TYPE_DONATION.equals(item.type) && item.enabled) hasDonation = true;
        if (!hasDonation) {
            Toast.makeText(this, "Tambahkan Donation Alert dulu.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
            return;
        }
        if (!repo.prefs().getBoolean("engine_enabled", false)) {
            repo.prefs().edit().putBoolean("engine_enabled", true).apply();
            startOverlayEngine();
            rendering = true;
            engineSwitch.setChecked(true);
            rendering = false;
        }
        Intent i = new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_DONATION);
        i.putExtra("name", "Raka");
        i.putExtra("amount", "Rp25.000");
        i.putExtra("message", "Semangat stream-nya! 🔥");
        try { startService(i); } catch (Exception e) { startOverlayEngine(); }
    }
}
