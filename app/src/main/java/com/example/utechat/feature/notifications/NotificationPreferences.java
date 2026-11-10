package com.example.utechat.feature.notifications;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.core.app.NotificationManagerCompat;

import java.util.LinkedHashSet;
import java.util.Set;

/** Local-only notification privacy and duplicate-delivery preferences. */
public final class NotificationPreferences {
    private static final String PREFS = "utechat_notification_preferences";
    private static final String SHOW_PREVIEW = "show_message_preview";
    private static final String PERMISSION_PROMPTED = "notification_permission_prompted";
    private static final String SEEN_EVENTS = "seen_message_event_ids";
    private static final int MAX_SEEN_EVENTS = 100;

    private NotificationPreferences() { }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean showPreview(Context context) {
        return prefs(context).getBoolean(SHOW_PREVIEW, false);
    }

    public static void setShowPreview(Context context, boolean show) {
        prefs(context).edit().putBoolean(SHOW_PREVIEW, show).apply();
        if (!show) NotificationManagerCompat.from(context).cancelAll();
    }

    public static boolean wasPermissionPrompted(Context context) {
        return prefs(context).getBoolean(PERMISSION_PROMPTED, false);
    }

    public static void setPermissionPrompted(Context context) {
        prefs(context).edit().putBoolean(PERMISSION_PROMPTED, true).apply();
    }

    public static synchronized boolean markEventSeen(Context context, String eventId) {
        if (eventId == null || eventId.trim().isEmpty()) return false;
        SharedPreferences preferences = prefs(context);
        Set<String> current = preferences.getStringSet(SEEN_EVENTS, new LinkedHashSet<>());
        LinkedHashSet<String> events = new LinkedHashSet<>(current == null
                ? new LinkedHashSet<>() : current);
        if (events.contains(eventId)) return false;
        events.add(eventId);
        while (events.size() > MAX_SEEN_EVENTS) events.remove(events.iterator().next());
        preferences.edit().putStringSet(SEEN_EVENTS, events).apply();
        return true;
    }
}
