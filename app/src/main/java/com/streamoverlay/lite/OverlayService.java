package com.streamoverlay.lite;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.PixelFormat;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class OverlayService extends Service {
    public static final String ACTION_REFRESH = "com.streamoverlay.lite.REFRESH";
    public static final String ACTION_DONATION = "com.streamoverlay.lite.DONATION";
    private static final int NOTIFICATION_ID = 4242;
    private static final String CHANNEL_ID = "overlay_engine";

    private WindowManager wm;
    private OverlayRepository repo;
    private final Map<String, View> views = new HashMap<>();
    private final Map<String, WindowManager.LayoutParams> params = new HashMap<>();
    private final Map<String, OverlayItem> itemCache = new HashMap<>();
    private Handler main;
    private ScheduledExecutorService poller;

    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        repo = new OverlayRepository(this);
        main = new Handler(Looper.getMainLooper());
        startAsForeground();
    }

    private void startAsForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Overlay Engine", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Keeps livestream overlays visible over other apps.");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(com.streamoverlay.lite.R.drawable.ic_launcher)
                .setContentTitle("Stream Overlay aktif")
                .setContentText("Tap untuk mengatur overlay")
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!repo.prefs().getBoolean("engine_enabled", false)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_DONATION.equals(intent.getAction())) {
            showDonation(
                    intent.getStringExtra("name"),
                    intent.getStringExtra("amount"),
                    intent.getStringExtra("message"));
        } else {
            renderAll();
            restartHttpPolling();
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        stopHttpPolling();
        removeAll();
        super.onDestroy();
    }

    private void renderAll() {
        removeAll();
        List<OverlayItem> all = repo.getAll();
        for (OverlayItem item : all) {
            if (!item.enabled) continue;
            View view = createView(item);
            if (view == null) continue;
            WindowManager.LayoutParams lp = createParams(item);
            try {
                wm.addView(view, lp);
                views.put(item.id, view);
                params.put(item.id, lp);
                itemCache.put(item.id, item);
            } catch (Exception ignored) {}
        }
    }

    private View createView(OverlayItem item) {
        if (OverlayItem.TYPE_IMAGE.equals(item.type)) return createImage(item);
        TextView t = new TextView(this);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        t.setTextSize(item.textSizeSp);
        try { t.setTextColor(Color.parseColor(item.textColor)); } catch (Exception e) { t.setTextColor(Color.WHITE); }
        try { t.setBackgroundColor(Color.parseColor(item.backgroundColor)); } catch (Exception e) { t.setBackgroundColor(0x66000000); }
        if (OverlayItem.TYPE_DONATION.equals(item.type)) {
            t.setText(formatTemplate(item.text, "Preview Donor", "Rp10.000", "Pesan donasi muncul di sini"));
            t.setVisibility(item.locked ? View.INVISIBLE : View.VISIBLE);
        } else {
            t.setText(item.text);
        }
        installMoveAndEdit(t, item);
        return t;
    }

    private View createImage(OverlayItem item) {
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(Color.TRANSPARENT);
        try {
            Uri uri = Uri.parse(item.imageUri);
            ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
            Drawable d = ImageDecoder.decodeDrawable(source);
            image.setImageDrawable(d);
            if (d instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) d).start();
        } catch (Exception e) {
            image.setImageResource(com.streamoverlay.lite.R.drawable.ic_launcher);
        }
        installMoveAndEdit(image, item);
        return image;
    }

    private WindowManager.LayoutParams createParams(OverlayItem item) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (item.locked) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Ui.dp(this, item.widthDp), Ui.dp(this, item.heightDp),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = item.x;
        lp.y = item.y;
        return lp;
    }

    private void installMoveAndEdit(View view, OverlayItem item) {
        if (item.locked) return;
        view.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY;
            float downRawX, downRawY;
            long downAt;
            boolean moved;

            @Override public boolean onTouch(View v, MotionEvent event) {
                WindowManager.LayoutParams lp = params.get(item.id);
                if (lp == null) return true;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = lp.x;
                        startY = lp.y;
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        downAt = System.currentTimeMillis();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = Math.round(event.getRawX() - downRawX);
                        int dy = Math.round(event.getRawY() - downRawY);
                        if (Math.abs(dx) > Ui.dp(OverlayService.this, 4) || Math.abs(dy) > Ui.dp(OverlayService.this, 4)) moved = true;
                        lp.x = startX + dx;
                        lp.y = startY + dy;
                        try { wm.updateViewLayout(v, lp); } catch (Exception ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        item.x = lp.x;
                        item.y = lp.y;
                        repo.upsert(item);
                        if (!moved && System.currentTimeMillis() - downAt < 550) openEditor(item.id);
                        return true;
                }
                return true;
            }
        });
    }

    private void openEditor(String id) {
        Intent i = new Intent(this, OverlayEditorActivity.class);
        i.putExtra("id", id);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { startActivity(i); } catch (Exception ignored) {}
    }

    private void showDonation(String name, String amount, String message) {
        if (name == null || name.isBlank()) name = "Anonymous";
        if (amount == null || amount.isBlank()) amount = "Donation";
        if (message == null) message = "";
        final String fName = name, fAmount = amount, fMessage = message;
        main.post(() -> {
            if (views.isEmpty()) renderAll();
            for (OverlayItem item : repo.getAll()) {
                if (!item.enabled || !OverlayItem.TYPE_DONATION.equals(item.type)) continue;
                View v = views.get(item.id);
                if (!(v instanceof TextView)) continue;
                TextView t = (TextView) v;
                t.setText(formatTemplate(item.text, fName, fAmount, fMessage));
                t.setVisibility(View.VISIBLE);
                long duration = Math.max(1000, Math.min(60000, item.durationMs));
                main.postDelayed(() -> {
                    OverlayItem latest = repo.get(item.id);
                    if (latest == null) return;
                    if (latest.locked) t.setVisibility(View.INVISIBLE);
                    else t.setText(formatTemplate(latest.text, "Preview Donor", "Rp10.000", "Pesan donasi muncul di sini"));
                }, duration);
            }
        });
    }

    private String formatTemplate(String template, String name, String amount, String message) {
        String t = template == null || template.isBlank() ? "{name} • {amount}\n{message}" : template;
        return t.replace("{name}", name).replace("{amount}", amount).replace("{message}", message);
    }

    private void restartHttpPolling() {
        stopHttpPolling();
        if (!repo.prefs().getBoolean("http_enabled", false)) return;
        String endpoint = repo.prefs().getString("http_endpoint", "").trim();
        if (endpoint.isEmpty()) return;
        int seconds = Math.max(5, repo.prefs().getInt("http_interval", 8));
        poller = Executors.newSingleThreadScheduledExecutor();
        poller.scheduleWithFixedDelay(() -> pollEndpoint(endpoint), 1, seconds, TimeUnit.SECONDS);
    }

    private void stopHttpPolling() {
        if (poller != null) {
            poller.shutdownNow();
            poller = null;
        }
    }

    private void pollEndpoint(String endpoint) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(endpoint).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() < 200 || c.getResponseCode() >= 300) return;
            StringBuilder b = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) b.append(line);
            }
            JSONObject event = parseEventObject(b.toString());
            if (event == null) return;
            String id = firstString(event, "event_id", "id", "transaction_id");
            if (id.isEmpty()) id = Integer.toHexString(event.toString().hashCode());
            String last = repo.prefs().getString("last_http_event", "");
            if (id.equals(last)) return;
            repo.prefs().edit().putString("last_http_event", id).apply();
            String name = firstString(event, "name", "donor_name", "supporter", "username");
            Object amountObj = event.has("amount") ? event.opt("amount") : event.opt("nominal");
            String amount = formatAmount(amountObj);
            String message = firstString(event, "message", "text", "note");
            showDonation(name, amount, message);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private JSONObject parseEventObject(String raw) {
        try {
            String s = raw.trim();
            if (s.startsWith("[")) {
                JSONArray a = new JSONArray(s);
                return a.length() > 0 ? a.optJSONObject(0) : null;
            }
            JSONObject o = new JSONObject(s);
            JSONArray events = o.optJSONArray("events");
            if (events != null && events.length() > 0) return events.optJSONObject(0);
            JSONObject event = o.optJSONObject("event");
            return event != null ? event : o;
        } catch (Exception e) { return null; }
    }

    private String firstString(JSONObject o, String... keys) {
        for (String k : keys) {
            String s = o.optString(k, "");
            if (!s.isBlank() && !"null".equalsIgnoreCase(s)) return s;
        }
        return "";
    }

    private String formatAmount(Object obj) {
        if (obj == null || obj == JSONObject.NULL) return "Donation";
        if (obj instanceof Number) {
            NumberFormat nf = NumberFormat.getNumberInstance(new Locale("id", "ID"));
            nf.setMaximumFractionDigits(0);
            return "Rp" + nf.format(((Number) obj).doubleValue());
        }
        String s = String.valueOf(obj).trim();
        if (s.toLowerCase(Locale.ROOT).startsWith("rp")) return s;
        try {
            double n = Double.parseDouble(s);
            NumberFormat nf = NumberFormat.getNumberInstance(new Locale("id", "ID"));
            nf.setMaximumFractionDigits(0);
            return "Rp" + nf.format(n);
        } catch (Exception e) { return s.isEmpty() ? "Donation" : s; }
    }

    private void removeAll() {
        for (View v : views.values()) {
            try { wm.removeView(v); } catch (Exception ignored) {}
        }
        views.clear();
        params.clear();
        itemCache.clear();
    }
}
