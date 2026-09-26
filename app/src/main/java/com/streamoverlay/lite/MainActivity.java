package com.streamoverlay.lite;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_IMAGE = 7001;
    private static final int REQ_REPLACE_IMAGE = 7002;
    private static final int REQ_NOTIFICATIONS = 7003;

    private OverlayRepository repo;
    private LinearLayout listBox;
    private TextView permissionState;
    private Switch engineSwitch;
    private boolean rendering;
    private String replaceImageId;

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
        scroll.setBackgroundColor(0xFF0F1115);

        LinearLayout root = Ui.column(this);
        Ui.pad(root, 18);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        TextView title = Ui.text(this, "Stream Overlay Lite", 28, Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = Ui.text(this,
                "Overlay simpel buat live dari satu HP.",
                14, 0xFFB8BDC7);
        sub.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 12));
        root.addView(sub);

        permissionState = Ui.text(this, "", 14, Color.WHITE);
        root.addView(permissionState, Ui.matchWrap(4, this));

        Button permission = Ui.button(this, "Izinkan overlay");
        permission.setOnClickListener(v -> requestOverlayPermission());
        root.addView(permission, Ui.matchWrap(6, this));

        LinearLayout engineRow = Ui.row(this);
        TextView engineLabel = Ui.text(this, "Overlay Engine", 18, Color.WHITE);
        engineLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        engineRow.addView(engineLabel,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        engineSwitch = new Switch(this);
        engineRow.addView(engineSwitch);
        root.addView(engineRow, Ui.matchWrap(14, this));

        engineSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (rendering) return;
            if (checked) {
                if (!Settings.canDrawOverlays(this)) {
                    rendering = true;
                    engineSwitch.setChecked(false);
                    rendering = false;
                    Toast.makeText(this, "Izinkan overlay dulu.", Toast.LENGTH_SHORT).show();
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

        TextView addLabel = Ui.text(this, "Tambah overlay", 13, 0xFF8C93A0);
        addLabel.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 6));
        root.addView(addLabel);

        LinearLayout addRow = Ui.row(this);
        Button addText = Ui.button(this, "+ Text");
        Button addImage = Ui.button(this, "+ Image / GIF");
        Button addDonation = Ui.button(this, "+ Donation URL");

        addText.setOnClickListener(v -> addTextOverlay());
        addImage.setOnClickListener(v -> pickImage(null));
        addDonation.setOnClickListener(v -> showDonationUrlDialog(null));

        addRow.addView(addText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addRow.addView(addImage, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(addRow, Ui.matchWrap(4, this));
        root.addView(addDonation, Ui.matchWrap(6, this));

        TextView hint = Ui.text(this,
                "UNLOCK: geser, resize, dan edit langsung di layar. LOCK: posisi tetap dan overlay tidak bisa disentuh.",
                13, 0xFF9FA5B0);
        hint.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 10));
        root.addView(hint);

        TextView layerHeader = Ui.text(this, "OVERLAYS", 13, 0xFF7CFFB2);
        layerHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(layerHeader, Ui.matchWrap(6, this));

        listBox = Ui.column(this);
        root.addView(listBox, Ui.matchWrap(2, this));
    }

    private void renderState() {
        rendering = true;
        boolean granted = Settings.canDrawOverlays(this);
        permissionState.setText(granted ? "✓ Overlay permission aktif" : "! Overlay permission belum aktif");
        permissionState.setTextColor(granted ? 0xFF7CFFB2 : 0xFFFFC46B);
        engineSwitch.setChecked(repo.prefs().getBoolean("engine_enabled", false) && granted);
        rendering = false;
        renderLayers();

        if (repo.prefs().getBoolean("engine_enabled", false) && granted) {
            startOverlayEngine();
        }
    }

    private void renderLayers() {
        listBox.removeAllViews();
        List<OverlayItem> all = repo.getAll();
        if (all.isEmpty()) {
            TextView empty = Ui.text(this,
                    "Belum ada overlay.", 14, 0xFF9FA5B0);
            Ui.pad(empty, 14);
            listBox.addView(empty);
            return;
        }
        for (OverlayItem item : all) {
            listBox.addView(layerCard(item), Ui.matchWrap(8, this));
        }
    }

    private View layerCard(OverlayItem item) {
        LinearLayout card = Ui.column(this);
        Ui.pad(card, 12);
        card.setBackground(Ui.rounded(0xFF1B1D22, 12, this));

        LinearLayout top = Ui.row(this);
        String typeLabel = OverlayItem.TYPE_TEXT.equals(item.type) ? "TEXT"
                : OverlayItem.TYPE_IMAGE.equals(item.type) ? "IMAGE / GIF"
                : "DONATION URL";
        TextView name = Ui.text(this, item.title + "  ·  " + typeLabel, 16, Color.WHITE);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch enabled = new Switch(this);
        enabled.setChecked(item.enabled);
        top.addView(enabled);
        card.addView(top);

        if (OverlayItem.TYPE_DONATION.equals(item.type)) {
            TextView source = Ui.text(this,
                    item.sourceUrl == null || item.sourceUrl.isEmpty()
                            ? "Belum ada source URL"
                            : shortUrl(item.sourceUrl),
                    12, 0xFF9FA5B0);
            card.addView(source);
        } else if (OverlayItem.TYPE_TEXT.equals(item.type)) {
            TextView preview = Ui.text(this,
                    item.text == null ? "" : item.text,
                    13, 0xFFB8BDC7);
            card.addView(preview);
        }

        LinearLayout buttons = Ui.row(this);
        Button lock = Ui.button(this, item.locked ? "🔒 Locked" : "🔓 Unlock");
        Button edit = Ui.button(this,
                OverlayItem.TYPE_DONATION.equals(item.type) ? "Source"
                        : OverlayItem.TYPE_IMAGE.equals(item.type) ? "Replace"
                        : "Edit on screen");
        Button delete = Ui.button(this, "Delete");

        buttons.addView(lock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(edit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(delete, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(buttons);

        enabled.setOnCheckedChangeListener((b, checked) -> {
            item.enabled = checked;
            repo.upsert(item);
            ensureEngineIfNeeded();
            refreshOverlayEngine();
        });

        lock.setOnClickListener(v -> {
            item.locked = !item.locked;
            repo.upsert(item);
            ensureEngineIfNeeded();
            sendLockUpdate(item);
            renderLayers();
        });

        edit.setOnClickListener(v -> {
            if (OverlayItem.TYPE_DONATION.equals(item.type)) {
                showDonationUrlDialog(item);
            } else if (OverlayItem.TYPE_IMAGE.equals(item.type)) {
                pickImage(item.id);
            } else {
                item.enabled = true;
                item.locked = false;
                repo.upsert(item);
                ensureEngineIfNeeded();
                sendLockUpdate(item);
                Toast.makeText(this, "Tap teks di overlay lalu ketik.", Toast.LENGTH_SHORT).show();
                renderLayers();
            }
        });

        delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Hapus overlay?")
                .setMessage(item.title)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus", (d, w) -> {
                    repo.delete(item.id);
                    refreshOverlayEngine();
                    renderLayers();
                })
                .show());

        return card;
    }

    private String shortUrl(String url) {
        if (url.length() <= 48) return url;
        return url.substring(0, 45) + "…";
    }

    private void addTextOverlay() {
        OverlayItem item = OverlayItem.textDefault();
        repo.upsert(item);
        ensureEngineIfNeeded();
        refreshOverlayEngine();
        Toast.makeText(this, "Tap teks di overlay untuk edit langsung.", Toast.LENGTH_LONG).show();
        renderLayers();
    }

    private void showDonationUrlDialog(OverlayItem existing) {
        final EditText input = Ui.input(this, "https://... source URL");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (existing != null && existing.sourceUrl != null) input.setText(existing.sourceUrl);
        input.setSelection(input.getText().length());
        int p = Ui.dp(this, 16);
        input.setPadding(p, p, p, p);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Donation source" : "Ganti donation source")
                .setMessage("Paste link widget/source dari Saweria, Trakteer, Streamlabs, atau provider lain.")
                .setView(input)
                .setNegativeButton("Batal", null)
                .setPositiveButton(existing == null ? "Tambah" : "Simpan", null)
                .create();

        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String url = input.getText().toString().trim();
            if (!isValidHttps(url)) {
                input.setError("Gunakan URL https://");
                return;
            }
            OverlayItem item = existing == null ? OverlayItem.donationDefault(url) : existing;
            item.sourceUrl = url;
            item.enabled = true;
            if (existing == null) item.locked = false;
            repo.upsert(item);
            ensureEngineIfNeeded();
            refreshOverlayEngine();
            renderLayers();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private boolean isValidHttps(String value) {
        try {
            Uri uri = Uri.parse(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && !uri.getHost().trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private void pickImage(String idToReplace) {
        replaceImageId = idToReplace;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, idToReplace == null ? REQ_IMAGE : REQ_REPLACE_IMAGE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode == REQ_IMAGE || requestCode == REQ_REPLACE_IMAGE)
                && resultCode == RESULT_OK
                && data != null
                && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}

            if (requestCode == REQ_REPLACE_IMAGE && replaceImageId != null) {
                OverlayItem item = repo.get(replaceImageId);
                if (item != null) {
                    item.imageUri = uri.toString();
                    item.enabled = true;
                    repo.upsert(item);
                }
            } else {
                repo.upsert(OverlayItem.imageDefault(uri.toString()));
            }
            replaceImageId = null;
            ensureEngineIfNeeded();
            refreshOverlayEngine();
            renderLayers();
        }
    }

    private void requestOverlayPermission() {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(i);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    private void ensureEngineIfNeeded() {
        if (!Settings.canDrawOverlays(this)) return;
        if (!repo.prefs().getBoolean("engine_enabled", false)) {
            repo.prefs().edit().putBoolean("engine_enabled", true).apply();
            rendering = true;
            engineSwitch.setChecked(true);
            rendering = false;
            startOverlayEngine();
        }
    }

    private void startOverlayEngine() {
        Intent i = new Intent(this, OverlayService.class)
                .setAction(OverlayService.ACTION_REFRESH);
        try {
            startForegroundService(i);
        } catch (Exception e) {
            Toast.makeText(this, "Gagal memulai overlay: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void refreshOverlayEngine() {
        if (!repo.prefs().getBoolean("engine_enabled", false)
                || !Settings.canDrawOverlays(this)) return;
        Intent i = new Intent(this, OverlayService.class)
                .setAction(OverlayService.ACTION_REFRESH);
        try {
            startService(i);
        } catch (Exception ignored) {
            startOverlayEngine();
        }
    }

    private void sendLockUpdate(OverlayItem item) {
        if (!repo.prefs().getBoolean("engine_enabled", false)
                || !Settings.canDrawOverlays(this)) return;
        Intent i = new Intent(this, OverlayService.class)
                .setAction(OverlayService.ACTION_SET_LOCK)
                .putExtra("id", item.id)
                .putExtra("locked", item.locked);
        try {
            startService(i);
        } catch (Exception ignored) {
            startOverlayEngine();
        }
    }
}
