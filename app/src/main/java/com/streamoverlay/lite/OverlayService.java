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
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OverlayService extends Service {
    public static final String ACTION_REFRESH = "com.streamoverlay.lite.REFRESH";
    public static final String ACTION_SET_LOCK = "com.streamoverlay.lite.SET_LOCK";

    private static final int NOTIFICATION_ID = 4242;
    private static final String CHANNEL_ID = "overlay_engine";

    private static final int[] TEXT_COLORS = new int[]{
            Color.WHITE, Color.BLACK, 0xFFFF5252, 0xFFFFD740,
            0xFF69F0AE, 0xFF40C4FF, 0xFFE040FB
    };

    private static final int[] BG_COLORS = new int[]{
            Color.TRANSPARENT, 0x88000000, 0x88FFFFFF
    };

    private WindowManager wm;
    private OverlayRepository repo;

    private final Map<String, FrameLayout> roots = new HashMap<>();
    private final Map<String, WindowManager.LayoutParams> params = new HashMap<>();
    private final Map<String, OverlayItem> itemCache = new HashMap<>();
    private final Map<String, List<View>> editorChrome = new HashMap<>();
    private final Map<String, EditText> textEditors = new HashMap<>();
    private final Map<String, ImageView> imageViews = new HashMap<>();
    private final Map<String, WebView> webViews = new HashMap<>();

    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        repo = new OverlayRepository(this);
        startAsForeground();
    }

    private void startAsForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Overlay Engine",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps livestream overlays visible over other apps.");
            nm.createNotificationChannel(channel);
        }

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Stream Overlay aktif")
                .setContentText("Tap untuk mengatur overlay")
                .setOngoing(true)
                .setContentIntent(pi)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!repo.prefs().getBoolean("engine_enabled", false)
                || !Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent == null ? null : intent.getAction();
        if (ACTION_SET_LOCK.equals(action)) {
            String id = intent.getStringExtra("id");
            boolean locked = intent.getBooleanExtra("locked", false);
            setLockedRuntime(id, locked);
        } else {
            renderAll();
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onDestroy() {
        removeAll();
        super.onDestroy();
    }

    private void renderAll() {
        removeAll();
        for (OverlayItem item : repo.getAll()) {
            if (!item.enabled) continue;
            addOverlay(item);
        }
    }

    private void addOverlay(OverlayItem item) {
        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.setBackgroundColor(Color.TRANSPARENT);

        View content;
        if (OverlayItem.TYPE_IMAGE.equals(item.type)) {
            content = createImage(item);
        } else if (OverlayItem.TYPE_DONATION.equals(item.type)) {
            content = createDonationSource(item);
        } else {
            content = createText(item);
        }

        root.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        List<View> chrome = new ArrayList<>();
        addEditorChrome(root, item, chrome);

        WindowManager.LayoutParams lp = createParams(item);
        try {
            wm.addView(root, lp);
            roots.put(item.id, root);
            params.put(item.id, lp);
            itemCache.put(item.id, item);
            editorChrome.put(item.id, chrome);
            applyEditorState(item.id, item.locked);
        } catch (Exception e) {
            destroyContent(item.id, content);
        }
    }

    private View createText(OverlayItem item) {
        EditText edit = new EditText(this);
        edit.setText(item.text == null ? "" : item.text);
        edit.setTextSize(item.textSizeSp);
        edit.setGravity(Gravity.CENTER);
        edit.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
        edit.setSingleLine(false);
        edit.setTextColor(parseColor(item.textColor, Color.WHITE));
        edit.setBackgroundColor(parseColor(item.backgroundColor, Color.TRANSPARENT));
        edit.setSelectAllOnFocus(false);
        edit.setCursorVisible(false);
        edit.setFocusableInTouchMode(true);
        edit.setFocusable(false);

        edit.setOnClickListener(v -> enterTextEdit(item.id));
        edit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                OverlayItem cached = itemCache.get(item.id);
                if (cached == null) return;
                cached.text = s.toString();
                repo.upsert(cached);
            }
        });

        textEditors.put(item.id, edit);
        return edit;
    }

    private View createImage(OverlayItem item) {
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(Color.TRANSPARENT);
        image.setAdjustViewBounds(true);
        try {
            Uri uri = Uri.parse(item.imageUri);
            ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
            Drawable drawable = ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
                int targetW = Math.max(Ui.dp(this, 48), Ui.dp(this, item.widthDp));
                int targetH = Math.max(Ui.dp(this, 48), Ui.dp(this, item.heightDp));
                decoder.setTargetSize(targetW, targetH);
            });
            image.setImageDrawable(drawable);
            if (drawable instanceof AnimatedImageDrawable) {
                ((AnimatedImageDrawable) drawable).start();
            }
        } catch (Exception e) {
            image.setImageResource(R.drawable.ic_launcher);
        }
        imageViews.put(item.id, image);
        return image;
    }

    private View createDonationSource(OverlayItem item) {
        WebView web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setFocusable(false);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        if (Build.VERSION.SDK_INT >= 21) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }

        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                forceTransparentPage(view);
                view.postDelayed(() -> forceTransparentPage(view), 700);
                view.postDelayed(() -> forceTransparentPage(view), 1800);
            }
        });

        String url = item.sourceUrl == null ? "" : item.sourceUrl.trim();
        if (isHttps(url)) {
            web.loadUrl(url);
        } else {
            web.loadDataWithBaseURL(
                    null,
                    "<html><body style='background:transparent'></body></html>",
                    "text/html",
                    "UTF-8",
                    null);
        }

        webViews.put(item.id, web);
        return web;
    }

    private void forceTransparentPage(WebView web) {
        if (web == null) return;
        String js = "(function(){"
                + "try{"
                + "document.documentElement.style.setProperty('background','transparent','important');"
                + "if(document.body){document.body.style.setProperty('background','transparent','important');"
                + "document.body.style.setProperty('background-color','transparent','important');}"
                + "var s=document.getElementById('__sol_transparent__');"
                + "if(!s){s=document.createElement('style');s.id='__sol_transparent__';"
                + "s.innerHTML='html,body{background:transparent!important;background-color:transparent!important;margin:0!important;overflow:hidden!important;}';"
                + "(document.head||document.documentElement).appendChild(s);}"
                + "}catch(e){}"
                + "})();";
        try {
            web.evaluateJavascript(js, null);
        } catch (Exception ignored) {}
    }

    private boolean isHttps(String value) {
        try {
            Uri uri = Uri.parse(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private WindowManager.LayoutParams createParams(OverlayItem item) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (item.locked) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Ui.dp(this, item.widthDp),
                Ui.dp(this, item.heightDp),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = item.x;
        lp.y = item.y;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        lp.alpha = lockedAlpha(item.locked);
        return lp;
    }

    private float lockedAlpha(boolean locked) {
        if (locked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return 0.80f;
        }
        return 1.0f;
    }

    private void addEditorChrome(FrameLayout root, OverlayItem item, List<View> chrome) {
        TextView drag = chip("↕");
        FrameLayout.LayoutParams dragLp = new FrameLayout.LayoutParams(
                Ui.dp(this, 42), Ui.dp(this, 42), Gravity.TOP | Gravity.START);
        root.addView(drag, dragLp);
        chrome.add(drag);
        installDrag(drag, root, item.id);

        TextView lock = chip("🔒");
        FrameLayout.LayoutParams lockLp = new FrameLayout.LayoutParams(
                Ui.dp(this, 48), Ui.dp(this, 42), Gravity.TOP | Gravity.END);
        root.addView(lock, lockLp);
        chrome.add(lock);
        lock.setOnClickListener(v -> setLockedRuntime(item.id, true));

        TextView resize = chip("↘");
        FrameLayout.LayoutParams resizeLp = new FrameLayout.LayoutParams(
                Ui.dp(this, 42), Ui.dp(this, 42), Gravity.BOTTOM | Gravity.END);
        root.addView(resize, resizeLp);
        chrome.add(resize);
        installResize(resize, root, item.id);

        if (OverlayItem.TYPE_TEXT.equals(item.type)) {
            LinearLayout tools = new LinearLayout(this);
            tools.setOrientation(LinearLayout.HORIZONTAL);
            tools.setGravity(Gravity.CENTER_VERTICAL);
            tools.setPadding(Ui.dp(this, 3), Ui.dp(this, 2), Ui.dp(this, 3), Ui.dp(this, 2));
            tools.setBackground(rounded(0xBB17191F, 8));

            TextView smaller = miniChip("A−");
            TextView bigger = miniChip("A+");
            TextView color = miniChip("●");
            TextView bg = miniChip("BG");
            TextView done = miniChip("✓");
            tools.addView(smaller);
            tools.addView(bigger);
            tools.addView(color);
            tools.addView(bg);
            tools.addView(done);

            FrameLayout.LayoutParams toolsLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Ui.dp(this, 38),
                    Gravity.BOTTOM | Gravity.START);
            root.addView(tools, toolsLp);
            chrome.add(tools);

            smaller.setOnClickListener(v -> changeTextSize(item.id, -2f));
            bigger.setOnClickListener(v -> changeTextSize(item.id, 2f));
            color.setOnClickListener(v -> cycleTextColor(item.id));
            bg.setOnClickListener(v -> cycleBackground(item.id));
            done.setOnClickListener(v -> finishTextEdit(item.id));
        } else if (OverlayItem.TYPE_DONATION.equals(item.type)) {
            TextView placeholder = new TextView(this);
            placeholder.setText("Donation source\nDrag • Resize • Lock");
            placeholder.setTextColor(Color.WHITE);
            placeholder.setTextSize(13);
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setBackground(rounded(0x66000000, 8));
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER);
            root.addView(placeholder, p);
            chrome.add(placeholder);
        }
    }

    private TextView chip(String label) {
        TextView v = new TextView(this);
        v.setText(label);
        v.setTextSize(18);
        v.setTextColor(Color.WHITE);
        v.setGravity(Gravity.CENTER);
        v.setBackground(rounded(0xCC17191F, 10));
        return v;
    }

    private TextView miniChip(String label) {
        TextView v = new TextView(this);
        v.setText(label);
        v.setTextSize(13);
        v.setTextColor(Color.WHITE);
        v.setGravity(Gravity.CENTER);
        int w = Ui.dp(this, 38);
        int h = Ui.dp(this, 34);
        v.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        return v;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(Ui.dp(this, radiusDp));
        return g;
    }

    private GradientDrawable editorBorder() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setStroke(Ui.dp(this, 1), 0xCC7CFFB2);
        g.setCornerRadius(Ui.dp(this, 8));
        return g;
    }

    private void installDrag(View handle, View root, String id) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            int startX;
            int startY;
            float downX;
            float downY;

            @Override public boolean onTouch(View v, MotionEvent event) {
                WindowManager.LayoutParams lp = params.get(id);
                OverlayItem item = itemCache.get(id);
                if (lp == null || item == null || item.locked) return true;

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        finishTextEdit(id);
                        startX = lp.x;
                        startY = lp.y;
                        downX = event.getRawX();
                        downY = event.getRawY();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        lp.x = startX + Math.round(event.getRawX() - downX);
                        lp.y = startY + Math.round(event.getRawY() - downY);
                        clampPosition(lp);
                        try { wm.updateViewLayout(root, lp); } catch (Exception ignored) {}
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        persistGeometry(id);
                        return true;
                }
                return true;
            }
        });
    }

    private void installResize(View handle, View root, String id) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            int startW;
            int startH;
            float downX;
            float downY;

            @Override public boolean onTouch(View v, MotionEvent event) {
                WindowManager.LayoutParams lp = params.get(id);
                OverlayItem item = itemCache.get(id);
                if (lp == null || item == null || item.locked) return true;

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        finishTextEdit(id);
                        startW = lp.width;
                        startH = lp.height;
                        downX = event.getRawX();
                        downY = event.getRawY();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int minW = Ui.dp(OverlayService.this,
                                OverlayItem.TYPE_TEXT.equals(item.type) ? 120 : 80);
                        int minH = Ui.dp(OverlayService.this,
                                OverlayItem.TYPE_TEXT.equals(item.type) ? 54 : 80);
                        DisplayMetrics dm = getResources().getDisplayMetrics();
                        int maxW = Math.max(minW, dm.widthPixels - Math.max(0, lp.x));
                        int maxH = Math.max(minH, dm.heightPixels - Math.max(0, lp.y));
                        lp.width = clamp(startW + Math.round(event.getRawX() - downX), minW, maxW);
                        lp.height = clamp(startH + Math.round(event.getRawY() - downY), minH, maxH);
                        try { wm.updateViewLayout(root, lp); } catch (Exception ignored) {}
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        persistGeometry(id);
                        return true;
                }
                return true;
            }
        });
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void clampPosition(WindowManager.LayoutParams lp) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int maxX = Math.max(0, dm.widthPixels - lp.width);
        int maxY = Math.max(0, dm.heightPixels - lp.height);
        lp.x = clamp(lp.x, 0, maxX);
        lp.y = clamp(lp.y, 0, maxY);
    }

    private void persistGeometry(String id) {
        OverlayItem item = itemCache.get(id);
        WindowManager.LayoutParams lp = params.get(id);
        if (item == null || lp == null) return;
        item.x = lp.x;
        item.y = lp.y;
        item.widthDp = Math.max(1, pxToDp(lp.width));
        item.heightDp = Math.max(1, pxToDp(lp.height));
        repo.upsert(item);
    }

    private int pxToDp(int px) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(px / density);
    }

    private void enterTextEdit(String id) {
        OverlayItem item = itemCache.get(id);
        EditText edit = textEditors.get(id);
        FrameLayout root = roots.get(id);
        WindowManager.LayoutParams lp = params.get(id);
        if (item == null || edit == null || root == null || lp == null || item.locked) return;

        lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        lp.alpha = 1f;
        try { wm.updateViewLayout(root, lp); } catch (Exception ignored) {}

        edit.setFocusable(true);
        edit.setFocusableInTouchMode(true);
        edit.setCursorVisible(true);
        edit.requestFocus();
        edit.setSelection(edit.length());

        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT);
    }

    private void finishTextEdit(String id) {
        EditText edit = textEditors.get(id);
        FrameLayout root = roots.get(id);
        WindowManager.LayoutParams lp = params.get(id);
        OverlayItem item = itemCache.get(id);
        if (edit == null || root == null || lp == null || item == null) return;

        item.text = edit.getText().toString();
        repo.upsert(item);
        edit.clearFocus();
        edit.setCursorVisible(false);
        edit.setFocusable(false);

        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(edit.getWindowToken(), 0);

        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (item.locked) lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { wm.updateViewLayout(root, lp); } catch (Exception ignored) {}
    }

    private void changeTextSize(String id, float delta) {
        OverlayItem item = itemCache.get(id);
        EditText edit = textEditors.get(id);
        if (item == null || edit == null) return;
        item.textSizeSp = Math.max(10f, Math.min(88f, item.textSizeSp + delta));
        edit.setTextSize(item.textSizeSp);
        repo.upsert(item);
    }

    private void cycleTextColor(String id) {
        OverlayItem item = itemCache.get(id);
        EditText edit = textEditors.get(id);
        if (item == null || edit == null) return;
        int current = parseColor(item.textColor, Color.WHITE);
        int next = TEXT_COLORS[0];
        for (int n = 0; n < TEXT_COLORS.length; n++) {
            if (TEXT_COLORS[n] == current) {
                next = TEXT_COLORS[(n + 1) % TEXT_COLORS.length];
                break;
            }
        }
        item.textColor = colorString(next);
        edit.setTextColor(next);
        repo.upsert(item);
    }

    private void cycleBackground(String id) {
        OverlayItem item = itemCache.get(id);
        EditText edit = textEditors.get(id);
        if (item == null || edit == null) return;
        int current = parseColor(item.backgroundColor, Color.TRANSPARENT);
        int next = BG_COLORS[0];
        for (int n = 0; n < BG_COLORS.length; n++) {
            if (BG_COLORS[n] == current) {
                next = BG_COLORS[(n + 1) % BG_COLORS.length];
                break;
            }
        }
        item.backgroundColor = colorString(next);
        edit.setBackgroundColor(next);
        repo.upsert(item);
    }

    private String colorString(int color) {
        return String.format("#%08X", color);
    }

    private int parseColor(String value, int fallback) {
        try {
            return Color.parseColor(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private void setLockedRuntime(String id, boolean locked) {
        if (id == null) return;

        FrameLayout root = roots.get(id);
        WindowManager.LayoutParams lp = params.get(id);
        OverlayItem item = itemCache.get(id);
        OverlayItem stored = repo.get(id);

        if (root == null || lp == null || item == null) {
            if (stored != null && stored.enabled) renderAll();
            return;
        }

        // Critical v2 rule: capture the exact geometry currently on-screen BEFORE
        // changing any WindowManager flags. Lock never recalculates x/y or size.
        item.x = lp.x;
        item.y = lp.y;
        item.widthDp = Math.max(1, pxToDp(lp.width));
        item.heightDp = Math.max(1, pxToDp(lp.height));
        item.locked = locked;

        if (stored != null) {
            stored.x = item.x;
            stored.y = item.y;
            stored.widthDp = item.widthDp;
            stored.heightDp = item.heightDp;
            stored.locked = locked;
            if (OverlayItem.TYPE_TEXT.equals(item.type)) {
                EditText edit = textEditors.get(id);
                if (edit != null) stored.text = edit.getText().toString();
            }
            item = stored;
            itemCache.put(id, item);
        }

        if (locked) {
            finishTextEdit(id);
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        lp.alpha = lockedAlpha(locked);

        applyEditorState(id, locked);
        try { wm.updateViewLayout(root, lp); } catch (Exception ignored) {}
        repo.upsert(item);
    }

    private void applyEditorState(String id, boolean locked) {
        FrameLayout root = roots.get(id);
        if (root == null) return;

        List<View> chrome = editorChrome.get(id);
        if (chrome != null) {
            for (View v : chrome) v.setVisibility(locked ? View.GONE : View.VISIBLE);
        }

        root.setBackground(locked ? null : editorBorder());

        EditText edit = textEditors.get(id);
        if (edit != null) {
            edit.setCursorVisible(false);
            if (locked) {
                edit.clearFocus();
                edit.setFocusable(false);
            }
        }
    }

    private void removeAll() {
        for (Map.Entry<String, FrameLayout> entry : roots.entrySet()) {
            String id = entry.getKey();
            ImageView image = imageViews.get(id);
            if (image != null) {
                Drawable d = image.getDrawable();
                if (d instanceof AnimatedImageDrawable) {
                    ((AnimatedImageDrawable) d).stop();
                }
            }

            WebView web = webViews.get(id);
            if (web != null) {
                try {
                    web.stopLoading();
                    web.loadUrl("about:blank");
                } catch (Exception ignored) {}
            }

            try { wm.removeViewImmediate(entry.getValue()); } catch (Exception ignored) {}

            if (web != null) {
                try { web.destroy(); } catch (Exception ignored) {}
            }
        }

        roots.clear();
        params.clear();
        itemCache.clear();
        editorChrome.clear();
        textEditors.clear();
        imageViews.clear();
        webViews.clear();
    }

    private void destroyContent(String id, View content) {
        if (content instanceof WebView) {
            try { ((WebView) content).destroy(); } catch (Exception ignored) {}
        }
    }
}
