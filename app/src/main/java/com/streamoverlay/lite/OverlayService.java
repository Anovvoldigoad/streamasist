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
    public static final String ACTION_CONTROLLER_VISIBILITY = "com.streamoverlay.lite.CONTROLLER_VISIBILITY";

    private static final int NOTIFICATION_ID = 4242;
    private static final String CHANNEL_ID = "overlay_engine";

    private static final int[] TEXT_COLORS = new int[]{
            Color.WHITE, Color.BLACK, 0xFFFF5252, 0xFFFFD740,
            0xFF69F0AE, 0xFF40C4FF, 0xFFE040FB
    };

    private static final int[] BG_COLORS = new int[]{
            Color.TRANSPARENT, 0x88000000, 0x88FFFFFF
    };

    private static final int EDITOR_SIDE_DP = 48;
    private static final int EDITOR_TOP_DP = 48;
    private static final int EDITOR_BOTTOM_DP = 50;
    private static final int TEXT_EDITOR_BOTTOM_DP = 92;

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
        String action = intent == null ? null : intent.getAction();

        if (ACTION_CONTROLLER_VISIBILITY.equals(action)) {
            controllerVisible = intent.getBooleanExtra("visible", false);
            repo.prefs().edit().putBoolean("controller_visible", controllerVisible).apply();

            if (!repo.prefs().getBoolean("engine_enabled", false)
                    || !Settings.canDrawOverlays(this)) {
                removeAll();
                return START_STICKY;
            }

            if (roots.isEmpty()) renderAll();
            else applyControllerModeToAll();
            return START_STICKY;
        }

        if (!repo.prefs().getBoolean("engine_enabled", false)
                || !Settings.canDrawOverlays(this)) {
            removeAll();
            stopSelf();
            return START_NOT_STICKY;
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
        addEditorChrome(root, item, chrome);

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
        WebView web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        // Avoid a black/white WebView flash while a widget source is loading.
        web.setVisibility(View.INVISIBLE);
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
                view.postDelayed(() -> {
                    forceTransparentPage(view);
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
        if (item.locked && !controllerVisible) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int maxContentW = Math.max(Ui.dp(this, 80), dm.widthPixels - editorSidePx() * 2);
        int maxContentH = Math.max(Ui.dp(this, 80), dm.heightPixels - editorTopPx() - editorBottomPx(item));
        int contentW = Math.min(Ui.dp(this, item.widthDp), maxContentW);
        int contentH = Math.min(Ui.dp(this, item.heightDp), maxContentH);

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
        clampPosition(lp, item);
        return lp;
    }

    private float lockedAlpha(boolean locked) {
        if (locked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return 0.80f;
        }
        return 1.0f;
    }

    private void addEditorChrome(FrameLayout root, OverlayItem item, List<View> chrome) {
        // MOVE is intentionally separate from resize handles so dragging never
        // accidentally changes the overlay size.
        TextView drag = chip("✥ MOVE");
        drag.setTextSize(12);
        FrameLayout.LayoutParams dragLp = new FrameLayout.LayoutParams(
                Ui.dp(this, 72), Ui.dp(this, 24), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        dragLp.topMargin = 0;
        root.addView(drag, dragLp);
        chrome.add(drag);
        installDrag(drag, root, item.id);

        TextView lock = chip("🔒");
        FrameLayout.LayoutParams lockLp = new FrameLayout.LayoutParams(
                Ui.dp(this, 40), Ui.dp(this, 24), Gravity.TOP | Gravity.END);
        lockLp.rightMargin = Ui.dp(this, 2);
        lockLp.topMargin = 0;
        root.addView(lock, lockLp);
        chrome.add(lock);
        lock.setOnClickListener(v -> setLockedRuntime(item.id, true));

        // Full frame resizing: all four sides + all four corners.
        // Image/GIF corners preserve the media aspect ratio. Side handles adjust
        // the frame freely, which is useful when a source has transparent space.
        addResizeHandle(root, item, chrome, "↖", Gravity.TOP | Gravity.START,
                Ui.dp(this, 26), Ui.dp(this, 26), false, false,
                true, true, false, false, OverlayItem.TYPE_IMAGE.equals(item.type));
        addResizeHandle(root, item, chrome, "↗", Gravity.TOP | Gravity.END,
                Ui.dp(this, 26), Ui.dp(this, 26), true, false,
                false, true, true, false, OverlayItem.TYPE_IMAGE.equals(item.type));
        addResizeHandle(root, item, chrome, "↙", Gravity.BOTTOM | Gravity.START,
                Ui.dp(this, 26), editorBottomPx(item) - Ui.dp(this, 22), false, true,
                true, false, false, true, OverlayItem.TYPE_IMAGE.equals(item.type));
        addResizeHandle(root, item, chrome, "↘", Gravity.BOTTOM | Gravity.END,
                Ui.dp(this, 26), editorBottomPx(item) - Ui.dp(this, 22), true, true,
                false, false, true, true, OverlayItem.TYPE_IMAGE.equals(item.type));

        // Edge handles. Margins keep them outside the content box.
        TextView topResize = resizeChip("↕");
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                Ui.dp(this, 28), Ui.dp(this, 22), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        topLp.topMargin = editorTopPx() - Ui.dp(this, 22);
        root.addView(topResize, topLp);
        chrome.add(topResize);
        installFrameResize(topResize, root, item.id, false, true, false, false, false);

        TextView bottomResize = resizeChip("↕");
        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(
                Ui.dp(this, 28), Ui.dp(this, 22), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bottomLp.bottomMargin = Math.max(0, editorBottomPx(item) - Ui.dp(this, 22));
        root.addView(bottomResize, bottomLp);
        chrome.add(bottomResize);
        installFrameResize(bottomResize, root, item.id, false, false, false, true, false);

        TextView leftResize = resizeChip("↔");
        FrameLayout.LayoutParams leftLp = new FrameLayout.LayoutParams(
                Ui.dp(this, 22), Ui.dp(this, 34), Gravity.START | Gravity.CENTER_VERTICAL);
        leftLp.leftMargin = editorSidePx() - Ui.dp(this, 22);
        root.addView(leftResize, leftLp);
        chrome.add(leftResize);
        installFrameResize(leftResize, root, item.id, true, false, false, false, false);

        TextView rightResize = resizeChip("↔");
        FrameLayout.LayoutParams rightLp = new FrameLayout.LayoutParams(
                Ui.dp(this, 22), Ui.dp(this, 34), Gravity.END | Gravity.CENTER_VERTICAL);
        rightLp.rightMargin = editorSidePx() - Ui.dp(this, 22);
        root.addView(rightResize, rightLp);
        chrome.add(rightResize);
        installFrameResize(rightResize, root, item.id, false, false, true, false, false);

        if (OverlayItem.TYPE_TEXT.equals(item.type)) {
            LinearLayout styleTools = new LinearLayout(this);
            styleTools.setOrientation(LinearLayout.HORIZONTAL);
            styleTools.setGravity(Gravity.CENTER_VERTICAL);
            styleTools.setPadding(Ui.dp(this, 3), Ui.dp(this, 1), Ui.dp(this, 3), Ui.dp(this, 1));
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
                    Ui.dp(this, 36), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            // First 24dp below the content is reserved for resize handles.
            styleLp.bottomMargin = Ui.dp(this, 28);
            root.addView(styleTools, styleLp);
            chrome.add(styleTools);

            LinearLayout alignTools = new LinearLayout(this);
            alignTools.setOrientation(LinearLayout.HORIZONTAL);
            alignTools.setGravity(Gravity.CENTER_VERTICAL);
            alignTools.setPadding(Ui.dp(this, 3), Ui.dp(this, 1), Ui.dp(this, 3), Ui.dp(this, 1));
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
                    Ui.dp(this, 36), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            alignLp.bottomMargin = 0;
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
            placeholder.setTextSize(12);
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setBackground(rounded(0x8817191F, 8));
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Ui.dp(this, 24), Gravity.TOP | Gravity.START);
            p.leftMargin = editorSidePx() + Ui.dp(this, 6);
            p.topMargin = Ui.dp(this, 2);
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
                        clampPosition(lp, item);
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
                        clampPosition(lp, item);
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

    private void applyControllerModeToAll() {
        for (Map.Entry<String, OverlayItem> e : itemCache.entrySet()) {
            applyEditorState(e.getKey(), e.getValue().locked);
        }
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
                        clampPosition(lp, item);
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

                        setContentSize(lp, item, newW, newH);
                        clampPosition(lp, item);
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
        boolean longPressUnlock = locked && controllerVisible;
        if (unlockSurface != null) unlockSurface.setVisibility(longPressUnlock ? View.VISIBLE : View.GONE);
        if (lockedHint != null) lockedHint.setVisibility(longPressUnlock ? View.VISIBLE : View.GONE);

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
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            lp.alpha = lockedAlpha(true);
        } else {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            lp.alpha = 1.0f;
        }

        try { wm.updateViewLayout(root, lp); } catch (Exception ignored) {}
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
