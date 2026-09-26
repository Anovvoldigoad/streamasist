package com.streamoverlay.lite;

import android.app.Notification;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DonationNotificationListener extends NotificationListenerService {
    private static final Pattern MONEY = Pattern.compile("(?i)(Rp\\s?[0-9][0-9.,]*)");

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        OverlayRepository repo = new OverlayRepository(this);
        if (!repo.prefs().getBoolean("bridge_enabled", false)) return;
        if (!repo.prefs().getBoolean("engine_enabled", false)) return;

        String packageFilter = repo.prefs().getString("bridge_package", "").trim();
        if (!packageFilter.isEmpty() && !matchesPackage(sbn.getPackageName(), packageFilter)) return;

        Notification n = sbn.getNotification();
        Bundle e = n.extras;
        String title = string(e.getCharSequence(Notification.EXTRA_TITLE));
        String text = string(e.getCharSequence(Notification.EXTRA_TEXT));
        String big = string(e.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String all = (title + " " + text + " " + big).trim();
        if (all.isEmpty()) return;

        String keywordCsv = repo.prefs().getString("bridge_keywords", "donasi,donate,saweria,trakteer");
        if (!matchesKeyword(all, keywordCsv)) return;

        Matcher m = MONEY.matcher(all);
        String amount = m.find() ? m.group(1) : "Donation";
        String message = !big.isEmpty() ? big : text;
        sendDonation(title.isEmpty() ? "Supporter" : title, amount, message);
    }

    private boolean matchesPackage(String actual, String csv) {
        for (String s : csv.split(",")) {
            String f = s.trim();
            if (!f.isEmpty() && actual.toLowerCase(Locale.ROOT).contains(f.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private boolean matchesKeyword(String text, String csv) {
        if (csv == null || csv.trim().isEmpty()) return true;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String s : csv.split(",")) {
            String k = s.trim().toLowerCase(Locale.ROOT);
            if (!k.isEmpty() && lower.contains(k)) return true;
        }
        return false;
    }

    private String string(CharSequence s) { return s == null ? "" : s.toString(); }

    private void sendDonation(String name, String amount, String message) {
        Intent i = new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_DONATION);
        i.putExtra("name", name);
        i.putExtra("amount", amount);
        i.putExtra("message", message);
        try { startService(i); } catch (Exception ignored) {}
    }
}
