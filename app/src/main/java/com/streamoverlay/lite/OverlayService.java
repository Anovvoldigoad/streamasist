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
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OverlayService extends Service {
    public static final String ACTION_REFRESH = "com.streamoverlay.lite.REFRESH";
    public static final String ACTION_SET_LOCK = "com.streamoverlay.lite.SET_LOCK";
    public static final String ACTION_CONTROLLER_VISIBILITY = "com.streamoverlay.lite.CONTROLLER_VISIBILITY";
    public static final String ACTION_TOGGLE_GLOBAL = "com.streamoverlay.lite.TOGGLE_GLOBAL";

    private static final int NOTIFICATION_ID = 4242;
    private static final String CHANNEL_ID = "overlay_engine";

    private static final int[] TEXT_COLORS = new int[]{
            Color.WHITE, Color.BLACK, 0xFFFF5252, 0xFFFFD740,
            0xFF69F0AE, 0xFF40C4FF, 0xFFE040FB
    };

    private static final int[] BG_COLORS = new int[]{
            Color.TRANSPARENT, 0x88000000, 0x88FFFFFF
    };

    private static final int EDITOR_SIDE_DP = 24;
    private static final int EDITOR_TOP_DP = 0;
    private static final int EDITOR_BOTTOM_DP = 30;
    private static final int TEXT_EDITOR_BOTTOM_DP = 76;
    private static final int CONTROLLER_SIZE_DP = 40;
    private static final int CONTROLLER_MENU_WIDTH_DP = 170;

    private WindowManager wm;
    private OverlayRepository repo;
    private boolean controllerVisible;

    private final Map<String, FrameLayout> roots = new HashMap<>();
    private final Map<String, FrameLayout> contentHosts = new HashMap<>();
    private final Map<String, View> unlockSurfaces = new HashMap<>();
    private final Map<String, TextView> lockedHints = new HashMap<>();
    private final Map<String, WindowManager.LayoutParams> params = new HashMap<>();
    private final Map<String, OverlayItem> itemCache = new HashMap<>();
    private final Map<String, List<View>> editorChrome = new HashMap<>();
    private final Map<String, EditText> textEditors = new HashMap<>();
    private final Map<String, ImageView> imageViews = new HashMap<>();
    private final Map<String, WebView> webViews = new HashMap<>();

    private FrameLayout globalControllerRoot;
    private ImageView globalControllerAvatar;
    private ScrollView globalControllerMenu;
    private LinearLayout globalControllerMenuList;
    private WindowManager.LayoutParams globalControllerParams;
    private boolean globalControllerExpanded;

    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        repo = new OverlayRepository(this);
        controllerVisible = repo.prefs().getBoolean("controller_visible", false);
        startAsForeground();
    }

    private void startAsForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Overlay Engine",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Kontrol cepat overlay livestream.");
            nm.createNotificationChannel(channel);
        }
        boolean visible = repo.prefs().getBoolean("overlay_visible", true);
        Notification notification = buildForegroundNotification(visible);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildForegroundNotification(boolean visible) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent toggle = new Intent(this, OverlayService.class);
        toggle.setAction(ACTION_TOGGLE_GLOBAL);
        PendingIntent togglePi = PendingIntent.getService(
                this, 92, toggle,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(visible ? "Stream Overlay aktif" : "Stream Overlay nonaktif")
                .setContentText(visible
                        ? "Overlay tampil. Tap Overlay OFF untuk sembunyikan semua."
                        : "Overlay disembunyikan. Tap Overlay ON untuk tampilkan lagi.")
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(R.drawable.ic_launcher, visible ? "Overlay OFF" : "Overlay ON", togglePi)
                .build();
    }

    private void updateForegroundNotification(boolean visible) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildForegroundNotification(visible));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_TOGGLE_GLOBAL.equals(action)) {
            boolean visible = !repo.prefs().getBoolean("overlay_visible", true);
            repo.prefs().edit().putBoolean("overlay_visible", visible).apply();
            renderAll();
            updateForegroundNotification(visible);
            return START_STICKY;
        }

        if (ACTION_CONTROLLER_VISIBILITY.equals(action)) {
            controllerVisible = intent.getBooleanExtra("visible", false);
            repo.prefs().edit().putBoolean("controller_visible", controllerVisible).apply();

            if (!repo.prefs().getBoolean("engine_enabled", false)
                    || !Settings.canDrawOverlays(this)) {
                removeAll();
                return START_STICKY;
            }

            // Rebuild once so the floating controller appears only outside this app.
            renderAll();
            return START_STICKY;
        }

        if (!repo.prefs().getBoolean("engine_enabled", false)
                || !Settings.canDrawOverlays(this)) {
            removeAll();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!repo.prefs().getBoolean("overlay_visible", true)) {
            // Content overlays stay hidden, but the floating controller remains available.
            renderAll();
            updateForegroundNotification(false);
            return START_STICKY;
        }

        controllerVisible = repo.prefs().getBoolean("controller_visible", false);

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
        boolean visible = repo.prefs().getBoolean("overlay_visible", true);
        if (visible) {
            for (OverlayItem item : repo.getAll()) {
                if (!item.enabled) continue;
                addOverlay(item);
            }
        }
        showGlobalControllerIfNeeded();
    }

    private void addOverlay(OverlayItem item) {
        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.setBackgroundColor(Color.TRANSPARENT);

        FrameLayout contentHost = new FrameLayout(this);
        contentHost.setClipChildren(false);
        contentHost.setClipToPadding(false);
        contentHost.setBackgroundColor(Color.TRANSPARENT);

        View content;
        if (OverlayItem.TYPE_IMAGE.equals(item.type)) {
            content = createImage(item);
        } else if (OverlayItem.TYPE_DONATION.equals(item.type)) {
            content = createOverlayLinkSource(item);
        } else {
            content = createText(item);
        }
        contentHost.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        View unlockSurface = new View(this);
        unlockSurface.setBackgroundColor(Color.TRANSPARENT);
        unlockSurface.setClickable(true);
        unlockSurface.setLongClickable(true);
        unlockSurface.setVisibility(View.GONE);
        unlockSurface.setOnClickListener(v -> setLockedRuntime(item.id, false));
        unlockSurface.setOnLongClickListener(v -> {
            setLockedRuntime(item.id, false);
            return true;
        });
        contentHost.addView(unlockSurface, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams contentLp = new FrameLayout.LayoutParams(
                Ui.dp(this, item.widthDp),
                Ui.dp(this, item.heightDp));
        contentLp.leftMargin = editorSidePx();
        contentLp.topMargin = editorTopPx();
        root.addView(contentHost, contentLp);

        List<View> chrome = new ArrayList<>();
        addEditorChrome(root, contentHost, item, chrome);

        TextView lockedHint = new TextView(this);
        lockedHint.setText("Tahan untuk unlock");
        lockedHint.setTextSize(11);
        lockedHint.setTextColor(Color.WHITE);
        lockedHint.setGravity(Gravity.CENTER);
        lockedHint.setBackground(rounded(0xCC17191F, 9));
        lockedHint.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Ui.dp(this, 30), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hintLp.topMargin = Ui.dp(this, 6);
        root.addView(lockedHint, hintLp);
        lockedHint.setVisibility(View.GONE);

        WindowManager.LayoutParams lp = createParams(item);
        try {
            wm.addView(root, lp);
            roots.put(item.id, root);
            contentHosts.put(item.id, contentHost);
            unlockSurfaces.put(item.id, unlockSurface);
            lockedHints.put(item.id, lockedHint);
            params.put(item.id, lp);
            itemCache.put(item.id, item);
            editorChrome.put(item.id, chrome);
            syncContentHostToWindow(item.id);
            persistGeometry(item.id);
            applyEditorState(item.id, item.locked);
        } catch (Exception e) {
            destroyContent(item.id, content);
        }
    }

    private View createText(OverlayItem item) {
        EditText edit = new EditText(this);
        edit.setText(item.text == null ? "" : item.text);
        edit.setTextSize(item.textSizeSp);
        applyTextGravity(edit, item);
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
                int sourceW = Math.max(1, info.getSize().getWidth());
                int sourceH = Math.max(1, info.getSize().getHeight());
                item.imageAspectRatio = sourceW / (float) sourceH;
                repo.upsert(item);
                int sample = 1;
                // Keep enough detail for stream overlays without decoding huge camera images at full RAM cost.
                while (sourceW / sample > 2048 || sourceH / sample > 2048) sample *= 2;
                decoder.setTargetSampleSize(sample);
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

    private View createOverlayLinkSource(OverlayItem item) {
        // Keep a fixed browser canvas and scale the View as one visual surface.
        // Resizing the outer overlay must NOT resize/reflow the DOM viewport.
        if (item.sourceBaseWidthDp <= 0 || item.sourceBaseHeightDp <= 0) {
            item.sourceBaseWidthDp = Math.max(80, item.widthDp);
            item.sourceBaseHeightDp = Math.max(60, item.heightDp);
            item.sourceScale = 1.0f;
            repo.upsert(item);
        }

        FrameLayout canvas = new FrameLayout(this);
        canvas.setBackgroundColor(Color.TRANSPARENT);
        canvas.setClipChildren(true);
        canvas.setClipToPadding(true);

        WebView web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setVisibility(View.INVISIBLE);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setFocusable(false);
        web.setPivotX(0f);
        web.setPivotY(0f);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);
        web.setInitialScale(100);
        if (Build.VERSION.SDK_INT >= 21) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }

        FrameLayout.LayoutParams webLp = new FrameLayout.LayoutParams(
                Ui.dp(this, item.sourceBaseWidthDp),
                Ui.dp(this, item.sourceBaseHeightDp));
        webLp.gravity = Gravity.TOP | Gravity.START;
        canvas.addView(web, webLp);
        applyWebCanvasScale(web, item);

        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                forceTransparentPage(view);
                applyWebCanvasScale(view, item);
                view.postDelayed(() -> {
                    forceTransparentPage(view);
                    applyWebCanvasScale(view, item);
                    view.setVisibility(View.VISIBLE);
                }, 120);
                view.postDelayed(() -> forceTransparentPage(view), 700);
                view.postDelayed(() -> forceTransparentPage(view), 1800);
            }
        });

        String url = item.sourceUrl == null ? "" : item.sourceUrl.trim();
        if (isHttps(url)) {
            web.loadUrl(url);
            web.postDelayed(() -> {
                if (web.getVisibility() != View.VISIBLE) {
                    forceTransparentPage(web);
                    applyWebCanvasScale(web, item);
                    web.setVisibility(View.VISIBLE);
                }
            }, 2500);
        } else {
            web.loadDataWithBaseURL(
                    null,
                    "<html><body style='background:transparent'></body></html>",
                    "text/html",
                    "UTF-8",
                    null);
        }

        webViews.put(item.id, web);
        return canvas;
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

    private void applyWebCanvasScale(WebView web, OverlayItem item) {
        if (web == null || item == null) return;
        float scale = Math.max(0.35f, Math.min(4.00f, item.sourceScale));
        web.setPivotX(0f);
        web.setPivotY(0f);
        web.setScaleX(scale);
        web.setScaleY(scale);
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
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (item.locked && !controllerVisible) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int maxContentW = Math.max(Ui.dp(this, 80), dm.widthPixels - editorSidePx() * 2);
        int maxContentH = Math.max(Ui.dp(this, 80), dm.heightPixels - editorTopPx() - editorBottomPx(item));
        int contentW = OverlayItem.TYPE_DONATION.equals(item.type)
                ? Ui.dp(this, item.widthDp)
                : Math.min(Ui.dp(this, item.widthDp), maxContentW);
        int contentH = OverlayItem.TYPE_DONATION.equals(item.type)
                ? Ui.dp(this, item.heightDp)
                : Math.min(Ui.dp(this, item.heightDp), maxContentH);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                contentW + editorSidePx() * 2,
                contentH + editorTopPx() + editorBottomPx(item),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = item.x - editorSidePx();
        lp.y = item.y - editorTopPx();
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        lp.alpha = (item.locked && !controllerVisible) ? lockedAlpha(true) : 1.0f;
        // Intentionally do not clamp x/y. Users may park part of an overlay
        // outside the physical display and bring it back later.
        return lp;
    }

    private float lockedAlpha(boolean locked) {
        if (locked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return 0.80f;
        }
        return 1.0f;
    }

    private void addEditorChrome(FrameLayout root, FrameLayout contentHost,
                                 OverlayItem item, List<View> chrome) {
        TextView drag = chip("✥ MOVE");
        drag.setTextSize(12);
        FrameLayout.LayoutParams dragLp = new FrameLayout.LayoutParams(
                Ui.dp(this, 78), Ui.dp(this, 32), Gravity.CENTER);
        contentHost.addView(drag, dragLp);
        chrome.add(drag);
        installDrag(drag, root, item.id);

        TextView lock = chip("🔒");
        FrameLayout.LayoutParams lockLp = new FrameLayout.LayoutParams(
                Ui.dp(this, 44), Ui.dp(this, 36), Gravity.TOP | Gravity.END);
        lockLp.rightMargin = Ui.dp(this, 6);
        lockLp.topMargin = Ui.dp(this, 6);
        contentHost.addView(lock, lockLp);
        chrome.add(lock);
        lock.setOnClickListener(v -> setLockedRuntime(item.id, true));

        TextView resize = resizeChip("↘");
        resize.setTextSize(18);
        FrameLayout.LayoutParams resizeLp = new FrameLayout.LayoutParams(
                Ui.dp(this, 34), Ui.dp(this, 34), Gravity.BOTTOM | Gravity.END);
        root.addView(resize, resizeLp);
        chrome.add(resize);
        installResize(resize, root, item.id, OverlayItem.TYPE_IMAGE.equals(item.type));

        if (OverlayItem.TYPE_TEXT.equals(item.type)) {
            LinearLayout styleTools = new LinearLayout(this);
            styleTools.setOrientation(LinearLayout.HORIZONTAL);
            styleTools.setGravity(Gravity.CENTER_VERTICAL);
            styleTools.setPadding(Ui.dp(this, 2), Ui.dp(this, 1), Ui.dp(this, 2), Ui.dp(this, 1));
            styleTools.setBackground(rounded(0xCC17191F, 8));

            TextView smaller = miniChip("A−");
            TextView bigger = miniChip("A+");
            TextView color = miniChip("●");
            TextView bg = miniChip("BG");
            TextView done = miniChip("✓");
            styleTools.addView(smaller);
            styleTools.addView(bigger);
            styleTools.addView(color);
            styleTools.addView(bg);
            styleTools.addView(done);

            FrameLayout.LayoutParams styleLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Ui.dp(this, 34), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            styleLp.bottomMargin = Ui.dp(this, 2);
            root.addView(styleTools, styleLp);
            chrome.add(styleTools);

            LinearLayout alignTools = new LinearLayout(this);
            alignTools.setOrientation(LinearLayout.HORIZONTAL);
            alignTools.setGravity(Gravity.CENTER_VERTICAL);
            alignTools.setPadding(Ui.dp(this, 2), Ui.dp(this, 1), Ui.dp(this, 2), Ui.dp(this, 1));
            alignTools.setBackground(rounded(0xCC17191F, 8));

            TextView left = miniChip("⇤");
            TextView center = miniChip("↔");
            TextView right = miniChip("⇥");
            TextView top = miniChip("↑");
            TextView middle = miniChip("↕");
            TextView bottom = miniChip("↓");
            alignTools.addView(left);
            alignTools.addView(center);
            alignTools.addView(right);
            alignTools.addView(top);
            alignTools.addView(middle);
            alignTools.addView(bottom);

            FrameLayout.LayoutParams alignLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Ui.dp(this, 34), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            alignLp.bottomMargin = Ui.dp(this, 38);
            root.addView(alignTools, alignLp);
            chrome.add(alignTools);

            smaller.setOnClickListener(v -> changeTextSize(item.id, -2f));
            bigger.setOnClickListener(v -> changeTextSize(item.id, 2f));
            color.setOnClickListener(v -> cycleTextColor(item.id));
            bg.setOnClickListener(v -> cycleBackground(item.id));
            done.setOnClickListener(v -> finishTextEdit(item.id));
            left.setOnClickListener(v -> setTextHorizontal(item.id, "left"));
            center.setOnClickListener(v -> setTextHorizontal(item.id, "center"));
            right.setOnClickListener(v -> setTextHorizontal(item.id, "right"));
            top.setOnClickListener(v -> setTextVertical(item.id, "top"));
            middle.setOnClickListener(v -> setTextVertical(item.id, "center"));
            bottom.setOnClickListener(v -> setTextVertical(item.id, "bottom"));
        } else if (OverlayItem.TYPE_DONATION.equals(item.type)) {
            TextView placeholder = new TextView(this);
            placeholder.setText("Overlay Link");
            placeholder.setTextColor(Color.WHITE);
            placeholder.setTextSize(11);
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setBackground(rounded(0x8817191F, 8));
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Ui.dp(this, 26), Gravity.BOTTOM | Gravity.START);
            p.leftMargin = editorSidePx();
            p.bottomMargin = Ui.dp(this, 2);
            root.addView(placeholder, p);
            chrome.add(placeholder);
        }
    }

    private void addResizeHandle(FrameLayout root, OverlayItem item, List<View> chrome,
                                 String label, int gravity, int horizontalMargin, int verticalMargin,
                                 boolean marginRight, boolean marginBottom,
                                 boolean left, boolean top, boolean right, boolean bottom,
                                 boolean preserveImageRatio) {
        TextView handle = resizeChip(label);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Ui.dp(this, 22), Ui.dp(this, 22), gravity);
        if (marginRight) lp.rightMargin = horizontalMargin;
        else lp.leftMargin = horizontalMargin;
        if (marginBottom) lp.bottomMargin = Math.max(0, verticalMargin);
        else lp.topMargin = Math.max(0, verticalMargin);
        root.addView(handle, lp);
        chrome.add(handle);
        installFrameResize(handle, root, item.id, left, top, right, bottom, preserveImageRatio);
    }

    private TextView resizeChip(String label) {
        TextView v = new TextView(this);
        v.setText(label);
        v.setTextSize(12);
        v.setTextColor(Color.WHITE);
        v.setGravity(Gravity.CENTER);
        v.setBackground(rounded(0xCC17191F, 7));
        return v;
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


    private int editorSidePx() {
        return Ui.dp(this, EDITOR_SIDE_DP);
    }

    private int editorTopPx() {
        return Ui.dp(this, EDITOR_TOP_DP);
    }

    private int editorBottomPx(OverlayItem item) {
        return Ui.dp(this, OverlayItem.TYPE_TEXT.equals(item.type)
                ? TEXT_EDITOR_BOTTOM_DP : EDITOR_BOTTOM_DP);
    }

    private int windowWidthPx(OverlayItem item) {
        return Ui.dp(this, item.widthDp) + editorSidePx() * 2;
    }

    private int windowHeightPx(OverlayItem item) {
        return Ui.dp(this, item.heightDp) + editorTopPx() + editorBottomPx(item);
    }

    private int contentWidthPx(WindowManager.LayoutParams lp) {
        return Math.max(Ui.dp(this, 1), lp.width - editorSidePx() * 2);
    }

    private int contentHeightPx(WindowManager.LayoutParams lp, OverlayItem item) {
        return Math.max(Ui.dp(this, 1), lp.height - editorTopPx() - editorBottomPx(item));
    }

    private void setContentSize(WindowManager.LayoutParams lp, OverlayItem item, int contentW, int contentH) {
        lp.width = Math.max(Ui.dp(this, 1), contentW) + editorSidePx() * 2;
        lp.height = Math.max(Ui.dp(this, 1), contentH) + editorTopPx() + editorBottomPx(item);
    }

    private void syncContentHostToWindow(String id) {
        FrameLayout host = contentHosts.get(id);
        WindowManager.LayoutParams lp = params.get(id);
        OverlayItem item = itemCache.get(id);
        if (host == null || lp == null || item == null) return;
        FrameLayout.LayoutParams hp = (FrameLayout.LayoutParams) host.getLayoutParams();
        hp.width = contentWidthPx(lp);
        hp.height = contentHeightPx(lp, item);
        hp.leftMargin = editorSidePx();
        hp.topMargin = editorTopPx();
        host.setLayoutParams(hp);
    }

    private void installFrameResize(View handle, View root, String id,
                                    boolean resizeLeft, boolean resizeTop,
                                    boolean resizeRight, boolean resizeBottom,
                                    boolean preserveImageRatio) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            int startContentLeft;
            int startContentTop;
            int startContentW;
            int startContentH;
            float downX;
            float downY;

            @Override public boolean onTouch(View v, MotionEvent event) {
                WindowManager.LayoutParams lp = params.get(id);
                OverlayItem item = itemCache.get(id);
                if (lp == null || item == null || item.locked) return true;

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        finishTextEdit(id);
                        startContentLeft = lp.x + editorSidePx();
                        startContentTop = lp.y + editorTopPx();
                        startContentW = contentWidthPx(lp);
                        startContentH = contentHeightPx(lp, item);
                        downX = event.getRawX();
                        downY = event.getRawY();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int dx = Math.round(event.getRawX() - downX);
                        int dy = Math.round(event.getRawY() - downY);
                        int startRight = startContentLeft + startContentW;
                        int startBottom = startContentTop + startContentH;

                        int proposedLeft = resizeLeft ? startContentLeft + dx : startContentLeft;
                        int proposedRight = resizeRight ? startRight + dx : startRight;
                        int proposedTop = resizeTop ? startContentTop + dy : startContentTop;
                        int proposedBottom = resizeBottom ? startBottom + dy : startBottom;

                        int minW = Ui.dp(OverlayService.this,
                                OverlayItem.TYPE_TEXT.equals(item.type) ? 120 : 80);
                        int minH = Ui.dp(OverlayService.this,
                                OverlayItem.TYPE_TEXT.equals(item.type) ? 54 : 60);
                        DisplayMetrics dm = getResources().getDisplayMetrics();
                        int maxW = Math.max(minW, dm.widthPixels);
                        int maxH = Math.max(minH, dm.heightPixels);

                        int requestedW = Math.max(1, proposedRight - proposedLeft);
                        int requestedH = Math.max(1, proposedBottom - proposedTop);
                        int newW = clamp(requestedW, minW, maxW);
                        int newH = clamp(requestedH, minH, maxH);

                        boolean isCorner = (resizeLeft || resizeRight) && (resizeTop || resizeBottom);
                        if (preserveImageRatio && isCorner
                                && OverlayItem.TYPE_IMAGE.equals(item.type)
                                && item.imageAspectRatio > 0.05f) {
                            float ratio = item.imageAspectRatio;
                            float relativeW = Math.abs(newW - startContentW) / (float) Math.max(1, startContentW);
                            float relativeH = Math.abs(newH - startContentH) / (float) Math.max(1, startContentH);
                            if (relativeW >= relativeH) {
                                newH = Math.round(newW / ratio);
                            } else {
                                newW = Math.round(newH * ratio);
                            }
                            if (newW > maxW) {
                                newW = maxW;
                                newH = Math.round(newW / ratio);
                            }
                            if (newH > maxH) {
                                newH = maxH;
                                newW = Math.round(newH * ratio);
                            }
                            if (newW < minW) {
                                newW = minW;
                                newH = Math.round(newW / ratio);
                            }
                            if (newH < minH) {
                                newH = minH;
                                newW = Math.round(newH * ratio);
                            }
                        }

                        int newLeft = resizeLeft ? startRight - newW : startContentLeft;
                        int newTop = resizeTop ? startBottom - newH : startContentTop;

                        setContentSize(lp, item, newW, newH);
                        lp.x = newLeft - editorSidePx();
                        lp.y = newTop - editorTopPx();
                        syncContentHostToWindow(id);
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

    private void installResizeAxis(View handle, View root, String id, boolean horizontal) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            int startContentW;
            int startContentH;
            float down;

            @Override public boolean onTouch(View v, MotionEvent event) {
                WindowManager.LayoutParams lp = params.get(id);
                OverlayItem item = itemCache.get(id);
                if (lp == null || item == null || item.locked) return true;

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startContentW = contentWidthPx(lp);
                        startContentH = contentHeightPx(lp, item);
                        down = horizontal ? event.getRawX() : event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int delta = Math.round((horizontal ? event.getRawX() : event.getRawY()) - down);
                        DisplayMetrics dm = getResources().getDisplayMetrics();
                        int maxW = Math.max(Ui.dp(OverlayService.this, 80), dm.widthPixels - editorSidePx() * 2);
                        int maxH = Math.max(Ui.dp(OverlayService.this, 80), dm.heightPixels - editorTopPx() - editorBottomPx(item));
                        int newW = horizontal ? clamp(startContentW + delta, Ui.dp(OverlayService.this, 80), maxW) : startContentW;
                        int newH = horizontal ? startContentH : clamp(startContentH + delta, Ui.dp(OverlayService.this, 80), maxH);
                        setContentSize(lp, item, newW, newH);
                        syncContentHostToWindow(id);
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

    private void showGlobalControllerIfNeeded() {
        if (controllerVisible
                || !repo.prefs().getBoolean("engine_enabled", false)
                || !Settings.canDrawOverlays(this)) {
            removeGlobalController();
            return;
        }
        if (globalControllerRoot != null) return;

        final int size = Ui.dp(this, CONTROLLER_SIZE_DP);
        final int menuWidth = Ui.dp(this, 248);
        final int gap = Ui.dp(this, 6);
        final int screenW = getResources().getDisplayMetrics().widthPixels;
        final int screenH = getResources().getDisplayMetrics().heightPixels;

        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.setBackgroundColor(Color.TRANSPARENT);

        ImageView avatar = new ImageView(this);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable avatarBg = new GradientDrawable();
        avatarBg.setShape(GradientDrawable.OVAL);
        avatarBg.setColor(0xFF20232A);
        avatarBg.setStroke(Ui.dp(this, 1), 0xAA7CFFB2);
        avatar.setBackground(avatarBg);
        avatar.setClipToOutline(true);
        avatar.setImageResource(R.drawable.ic_launcher);
        avatar.setAlpha(0.56f);
        loadControllerAvatar(avatar);

        FrameLayout.LayoutParams avatarLp = new FrameLayout.LayoutParams(size, size);
        root.addView(avatar, avatarLp);

        ScrollView menu = new ScrollView(this);
        menu.setFillViewport(true);
        menu.setBackground(rounded(0xEE17191F, 18));
        menu.setVisibility(View.GONE);

        LinearLayout menuList = new LinearLayout(this);
        menuList.setOrientation(LinearLayout.VERTICAL);
        menuList.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        menu.addView(menuList, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        List<OverlayItem> installed = repo.getAll();
        if (installed.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Belum ada overlay");
            empty.setTextSize(12);
            empty.setTextColor(0xFFB8BDC7);
            empty.setGravity(Gravity.CENTER);
            menuList.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 44)));
        } else {
            for (OverlayItem item : installed) {
                menuList.addView(controllerOverlayRow(item), new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));
            }
        }

        int rowCount = Math.max(1, installed.size());
        int desiredMenuHeight = Ui.dp(this, 16 + rowCount * 52);
        int menuHeight = Math.min(Math.max(size, desiredMenuHeight), Math.max(size, screenH - Ui.dp(this, 40)));

        FrameLayout.LayoutParams menuLp = new FrameLayout.LayoutParams(menuWidth, menuHeight);
        menuLp.leftMargin = size + gap;
        root.addView(menu, menuLp);

        int savedX = repo.prefs().getInt("controller_bubble_x", Math.max(0, screenW - size - Ui.dp(this, 12)));
        int savedY = repo.prefs().getInt("controller_bubble_y", Ui.dp(this, 120));
        savedX = clamp(savedX, 0, Math.max(0, screenW - size));
        savedY = clamp(savedY, 0, Math.max(0, screenH - size));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = savedX;
        lp.y = savedY;

        try {
            wm.addView(root, lp);
        } catch (Exception ignored) {
            return;
        }

        globalControllerRoot = root;
        globalControllerAvatar = avatar;
        globalControllerMenu = menu;
        globalControllerMenuList = menuList;
        globalControllerParams = lp;
        globalControllerExpanded = false;

        final float[] downX = new float[1];
        final float[] downY = new float[1];
        final int[] startX = new int[1];
        final int[] startY = new int[1];
        final boolean[] moved = new boolean[1];

        avatar.setOnTouchListener((v, event) -> {
            if (globalControllerParams == null) return true;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = event.getRawX();
                    downY[0] = event.getRawY();
                    startX[0] = collapsedControllerX();
                    startY[0] = collapsedControllerY();
                    moved[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = Math.round(event.getRawX() - downX[0]);
                    int dy = Math.round(event.getRawY() - downY[0]);
                    if (!moved[0] && (Math.abs(dx) > Ui.dp(this, 6) || Math.abs(dy) > Ui.dp(this, 6))) {
                        moved[0] = true;
                        if (globalControllerExpanded) setControllerExpanded(false);
                    }
                    if (moved[0]) {
                        int maxX = Math.max(0, screenW - size);
                        int maxY = Math.max(0, screenH - size);
                        globalControllerParams.x = clamp(startX[0] + dx, 0, maxX);
                        globalControllerParams.y = clamp(startY[0] + dy, 0, maxY);
                        try { wm.updateViewLayout(globalControllerRoot, globalControllerParams); } catch (Exception ignored) {}
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (moved[0]) {
                        repo.prefs().edit()
                                .putInt("controller_bubble_x", globalControllerParams.x)
                                .putInt("controller_bubble_y", globalControllerParams.y)
                                .apply();
                    } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        setControllerExpanded(!globalControllerExpanded);
                    }
                    return true;
            }
            return true;
        });
    }

    private View controllerOverlayRow(OverlayItem item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 4), Ui.dp(this, 3), Ui.dp(this, 4), Ui.dp(this, 3));

        String type = OverlayItem.TYPE_TEXT.equals(item.type) ? "T"
                : OverlayItem.TYPE_IMAGE.equals(item.type) ? "IMG" : "LINK";
        TextView title = new TextView(this);
        title.setText((item.title == null || item.title.trim().isEmpty() ? "Overlay" : item.title) + " · " + type);
        title.setTextSize(11);
        title.setTextColor(Color.WHITE);
        title.setSingleLine(true);
        row.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        TextView lock = controllerChip(item.locked ? "UNLOCK" : "LOCK");
        LinearLayout.LayoutParams lockLp = new LinearLayout.LayoutParams(Ui.dp(this, 68), Ui.dp(this, 38));
        lockLp.leftMargin = Ui.dp(this, 4);
        row.addView(lock, lockLp);

        TextView power = controllerChip(item.enabled ? "OFF" : "ON");
        LinearLayout.LayoutParams powerLp = new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 38));
        powerLp.leftMargin = Ui.dp(this, 4);
        row.addView(power, powerLp);

        lock.setOnClickListener(v -> {
            OverlayItem current = repo.get(item.id);
            if (current == null) return;
            boolean newLocked = !current.locked;
            if (current.enabled && roots.containsKey(current.id)) {
                setLockedRuntime(current.id, newLocked);
            } else {
                current.locked = newLocked;
                repo.upsert(current);
            }
            lock.setText(newLocked ? "UNLOCK" : "LOCK");
        });

        power.setOnClickListener(v -> {
            OverlayItem current = repo.get(item.id);
            if (current == null) return;
            current.enabled = !current.enabled;
            repo.upsert(current);
            power.setText(current.enabled ? "OFF" : "ON");
            if (current.enabled) {
                if (repo.prefs().getBoolean("overlay_visible", true) && !roots.containsKey(current.id)) {
                    addOverlay(current);
                }
            } else {
                removeOverlayWindow(current.id);
            }
        });

        return row;
    }

    private TextView controllerChip(String label) {
        TextView v = new TextView(this);
        v.setText(label);
        v.setTextSize(10);
        v.setTextColor(Color.WHITE);
        v.setGravity(Gravity.CENTER);
        v.setBackground(rounded(0xFF2B2E36, 12));
        v.setClickable(true);
        return v;
    }

    private void loadControllerAvatar(ImageView avatar) {
        String raw = repo.prefs().getString("controller_avatar_uri", "");
        if (raw == null || raw.trim().isEmpty()) return;
        try {
            Uri uri = Uri.parse(raw);
            if (Build.VERSION.SDK_INT >= 28) {
                Drawable d = ImageDecoder.decodeDrawable(
                        ImageDecoder.createSource(getContentResolver(), uri),
                        (decoder, info, source) -> {
                            int w = info.getSize().getWidth();
                            int h = info.getSize().getHeight();
                            int max = Math.max(w, h);
                            if (max > 256) {
                                float scale = 256f / max;
                                decoder.setTargetSize(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)));
                            }
                        });
                avatar.setImageDrawable(d);
                if (d instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) d).start();
            }
        } catch (Exception ignored) {}
    }

    private void setControllerExpanded(boolean expanded) {
        if (globalControllerRoot == null || globalControllerParams == null || globalControllerMenu == null) return;
        int size = Ui.dp(this, CONTROLLER_SIZE_DP);
        int gap = Ui.dp(this, 6);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;

        if (expanded) {
            int avatarX = collapsedControllerX();
            int avatarY = collapsedControllerY();
            FrameLayout.LayoutParams avatarLp = (FrameLayout.LayoutParams) globalControllerAvatar.getLayoutParams();
            FrameLayout.LayoutParams menuLp = (FrameLayout.LayoutParams) globalControllerMenu.getLayoutParams();
            int menuWidth = menuLp.width;
            int menuHeight = menuLp.height;
            int total = size + gap + menuWidth;
            boolean openLeft = avatarX > screenW / 2;

            int rootY = clamp(avatarY, 0, Math.max(0, screenH - menuHeight));
            globalControllerParams.y = rootY;
            avatarLp.topMargin = avatarY - rootY;
            menuLp.topMargin = 0;

            if (openLeft) {
                int rootX = Math.max(0, avatarX - menuWidth - gap);
                globalControllerParams.x = rootX;
                avatarLp.leftMargin = menuWidth + gap;
                menuLp.leftMargin = 0;
            } else {
                globalControllerParams.x = avatarX;
                avatarLp.leftMargin = 0;
                menuLp.leftMargin = size + gap;
            }
            globalControllerAvatar.setLayoutParams(avatarLp);
            globalControllerMenu.setLayoutParams(menuLp);
            globalControllerParams.width = total;
            globalControllerParams.height = Math.max(size + avatarLp.topMargin, menuHeight);
            globalControllerMenu.setVisibility(View.VISIBLE);
        } else {
            int avatarScreenX = collapsedControllerX();
            int avatarScreenY = collapsedControllerY();
            FrameLayout.LayoutParams avatarLp = (FrameLayout.LayoutParams) globalControllerAvatar.getLayoutParams();
            avatarLp.leftMargin = 0;
            avatarLp.topMargin = 0;
            globalControllerAvatar.setLayoutParams(avatarLp);
            globalControllerMenu.setVisibility(View.GONE);
            globalControllerParams.x = avatarScreenX;
            globalControllerParams.y = avatarScreenY;
            globalControllerParams.width = size;
            globalControllerParams.height = size;
        }
        globalControllerExpanded = expanded;
        try { wm.updateViewLayout(globalControllerRoot, globalControllerParams); } catch (Exception ignored) {}
    }

    private int collapsedControllerX() {
        if (globalControllerParams == null || globalControllerAvatar == null) return 0;
        FrameLayout.LayoutParams avatarLp = (FrameLayout.LayoutParams) globalControllerAvatar.getLayoutParams();
        return globalControllerParams.x + avatarLp.leftMargin;
    }

    private int collapsedControllerY() {
        if (globalControllerParams == null || globalControllerAvatar == null) return 0;
        FrameLayout.LayoutParams avatarLp = (FrameLayout.LayoutParams) globalControllerAvatar.getLayoutParams();
        return globalControllerParams.y + avatarLp.topMargin;
    }

    private void removeGlobalController() {
        if (globalControllerAvatar != null) {
            Drawable d = globalControllerAvatar.getDrawable();
            if (d instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) d).stop();
        }
        if (globalControllerRoot != null) {
            try { wm.removeViewImmediate(globalControllerRoot); } catch (Exception ignored) {}
        }
        globalControllerRoot = null;
        globalControllerAvatar = null;
        globalControllerMenu = null;
        globalControllerMenuList = null;
        globalControllerParams = null;
        globalControllerExpanded = false;
    }

    private void applyControllerModeToAll() {
        for (Map.Entry<String, OverlayItem> e : itemCache.entrySet()) {
            applyEditorState(e.getKey(), e.getValue().locked);
        }
        showGlobalControllerIfNeeded();
    }

    private void applyTextGravity(EditText edit, OverlayItem item) {
        int horizontal = Gravity.CENTER_HORIZONTAL;
        if ("left".equals(item.textHorizontal)) horizontal = Gravity.START;
        else if ("right".equals(item.textHorizontal)) horizontal = Gravity.END;

        int vertical = Gravity.CENTER_VERTICAL;
        if ("top".equals(item.textVertical)) vertical = Gravity.TOP;
        else if ("bottom".equals(item.textVertical)) vertical = Gravity.BOTTOM;

        edit.setGravity(horizontal | vertical);
    }

    private void setTextHorizontal(String id, String value) {
        OverlayItem item = itemCache.get(id);
        EditText edit = textEditors.get(id);
        if (item == null || edit == null) return;
        item.textHorizontal = value;
        applyTextGravity(edit, item);
        repo.upsert(item);
    }

    private void setTextVertical(String id, String value) {
        OverlayItem item = itemCache.get(id);
        EditText edit = textEditors.get(id);
        if (item == null || edit == null) return;
        item.textVertical = value;
        applyTextGravity(edit, item);
        repo.upsert(item);
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

    private GradientDrawable lockedEditorBorder() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setStroke(Ui.dp(this, 1), 0x99FFFFFF);
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

    private void installResize(View handle, View root, String id, boolean preserveImageRatio) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            int startContentW;
            int startContentH;
            float downX;
            float downY;
            float startSourceScale;

            @Override public boolean onTouch(View v, MotionEvent event) {
                WindowManager.LayoutParams lp = params.get(id);
                OverlayItem item = itemCache.get(id);
                if (lp == null || item == null || item.locked) return true;

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        finishTextEdit(id);
                        startContentW = contentWidthPx(lp);
                        startContentH = contentHeightPx(lp, item);
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startSourceScale = item.sourceScale;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int dx = Math.round(event.getRawX() - downX);
                        int dy = Math.round(event.getRawY() - downY);
                        int minW = Ui.dp(OverlayService.this,
                                OverlayItem.TYPE_TEXT.equals(item.type) ? 120 : 80);
                        int minH = Ui.dp(OverlayService.this,
                                OverlayItem.TYPE_TEXT.equals(item.type) ? 54 : 80);
                        DisplayMetrics dm = getResources().getDisplayMetrics();
                        int maxW = Math.max(minW, dm.widthPixels - editorSidePx() * 2);
                        int maxH = Math.max(minH, dm.heightPixels - editorTopPx() - editorBottomPx(item));

                        int newW;
                        int newH;
                        if (preserveImageRatio && OverlayItem.TYPE_IMAGE.equals(item.type)
                                && item.imageAspectRatio > 0.05f) {
                            float ratio = item.imageAspectRatio;
                            if (Math.abs(dx) >= Math.abs(dy)) {
                                newW = clamp(startContentW + dx, minW, maxW);
                                newH = Math.round(newW / ratio);
                            } else {
                                newH = clamp(startContentH + dy, minH, maxH);
                                newW = Math.round(newH * ratio);
                            }
                            if (newW > maxW) {
                                newW = maxW;
                                newH = Math.round(newW / ratio);
                            }
                            if (newH > maxH) {
                                newH = maxH;
                                newW = Math.round(newH * ratio);
                            }
                            newW = Math.max(minW, newW);
                            newH = Math.max(minH, newH);
                        } else {
                            newW = clamp(startContentW + dx, minW, maxW);
                            newH = clamp(startContentH + dy, minH, maxH);
                        }

                        if (OverlayItem.TYPE_DONATION.equals(item.type)) {
                            int baseW = Math.max(Ui.dp(OverlayService.this, 80),
                                    Ui.dp(OverlayService.this, item.sourceBaseWidthDp));
                            int baseH = Math.max(Ui.dp(OverlayService.this, 60),
                                    Ui.dp(OverlayService.this, item.sourceBaseHeightDp));

                            float relX = Math.abs(dx) / (float) Math.max(1, startContentW);
                            float relY = Math.abs(dy) / (float) Math.max(1, startContentH);
                            float desiredScale;
                            if (relX >= relY) {
                                desiredScale = (startContentW + dx) / (float) baseW;
                            } else {
                                desiredScale = (startContentH + dy) / (float) baseH;
                            }
                            item.sourceScale = Math.max(0.35f, Math.min(4.00f, desiredScale));
                            newW = Math.max(Ui.dp(OverlayService.this, 80),
                                    Math.round(baseW * item.sourceScale));
                            newH = Math.max(Ui.dp(OverlayService.this, 60),
                                    Math.round(baseH * item.sourceScale));

                            WebView web = webViews.get(id);
                            if (web != null) applyWebCanvasScale(web, item);
                        }

                        setContentSize(lp, item, newW, newH);
                        syncContentHostToWindow(id);
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

    private void clampPosition(WindowManager.LayoutParams lp, OverlayItem item) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int contentW = contentWidthPx(lp);
        int contentH = contentHeightPx(lp, item);
        int minX = -editorSidePx();
        int minY = -editorTopPx();
        int maxX = dm.widthPixels - contentW - editorSidePx();
        int maxY = dm.heightPixels - contentH - editorTopPx();
        lp.x = clamp(lp.x, minX, Math.max(minX, maxX));
        lp.y = clamp(lp.y, minY, Math.max(minY, maxY));
    }

    private void persistGeometry(String id) {
        OverlayItem item = itemCache.get(id);
        WindowManager.LayoutParams lp = params.get(id);
        if (item == null || lp == null) return;
        item.x = lp.x + editorSidePx();
        item.y = lp.y + editorTopPx();
        item.widthDp = Math.max(1, pxToDp(contentWidthPx(lp)));
        item.heightDp = Math.max(1, pxToDp(contentHeightPx(lp, item)));
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

        // Preserve the CONTENT rectangle exactly. Editor controls live outside it.
        item.x = lp.x + editorSidePx();
        item.y = lp.y + editorTopPx();
        item.widthDp = Math.max(1, pxToDp(contentWidthPx(lp)));
        item.heightDp = Math.max(1, pxToDp(contentHeightPx(lp, item)));
        item.locked = locked;

        if (stored != null) {
            stored.x = item.x;
            stored.y = item.y;
            stored.widthDp = item.widthDp;
            stored.heightDp = item.heightDp;
            stored.locked = locked;
            stored.imageAspectRatio = item.imageAspectRatio;
            stored.textHorizontal = item.textHorizontal;
            stored.textVertical = item.textVertical;
            if (OverlayItem.TYPE_TEXT.equals(item.type)) {
                EditText edit = textEditors.get(id);
                if (edit != null) stored.text = edit.getText().toString();
            }
            item = stored;
            itemCache.put(id, item);
        }

        if (locked) finishTextEdit(id);
        repo.upsert(item);
        applyEditorState(id, locked);
    }

    private void applyEditorState(String id, boolean locked) {
        FrameLayout root = roots.get(id);
        FrameLayout contentHost = contentHosts.get(id);
        WindowManager.LayoutParams lp = params.get(id);
        OverlayItem item = itemCache.get(id);
        if (root == null || lp == null || item == null) return;

        List<View> chrome = editorChrome.get(id);
        if (chrome != null) {
            for (View v : chrome) v.setVisibility(locked ? View.GONE : View.VISIBLE);
        }

        View unlockSurface = unlockSurfaces.get(id);
        TextView lockedHint = lockedHints.get(id);
        boolean controllerUnlock = locked && controllerVisible;
        if (unlockSurface != null) unlockSurface.setVisibility(controllerUnlock ? View.VISIBLE : View.GONE);
        if (lockedHint != null) lockedHint.setVisibility(View.GONE);

        if (contentHost != null) {
            if (!locked) contentHost.setForeground(editorBorder());
            else if (controllerVisible) contentHost.setForeground(lockedEditorBorder());
            else contentHost.setForeground(null);
        }

        EditText edit = textEditors.get(id);
        if (edit != null) {
            edit.setCursorVisible(false);
            if (locked) {
                edit.clearFocus();
                edit.setFocusable(false);
            }
        }

        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (locked && !controllerVisible) {
            // Main overlay remains fully click-through over the game/live app.
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            lp.alpha = lockedAlpha(true);
        } else {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            lp.alpha = 1.0f;
        }

        try { wm.updateViewLayout(root, lp); } catch (Exception ignored) {}

        // Outside the controller app, locked overlays have ZERO touch surfaces.
        // No hidden gesture or hotspot is created, so gameplay touch is untouched.
    }

    private void removeOverlayWindow(String id) {
        if (id == null) return;
        ImageView image = imageViews.remove(id);
        if (image != null) {
            Drawable d = image.getDrawable();
            if (d instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) d).stop();
        }
        WebView web = webViews.remove(id);
        if (web != null) {
            try { web.stopLoading(); web.loadUrl("about:blank"); } catch (Exception ignored) {}
        }
        FrameLayout root = roots.remove(id);
        if (root != null) {
            try { wm.removeViewImmediate(root); } catch (Exception ignored) {}
        }
        if (web != null) {
            try { web.destroy(); } catch (Exception ignored) {}
        }
        contentHosts.remove(id);
        unlockSurfaces.remove(id);
        lockedHints.remove(id);
        params.remove(id);
        itemCache.remove(id);
        editorChrome.remove(id);
        textEditors.remove(id);
    }

    private void removeAll() {
        removeGlobalController();
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
        contentHosts.clear();
        unlockSurfaces.clear();
        lockedHints.clear();
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
