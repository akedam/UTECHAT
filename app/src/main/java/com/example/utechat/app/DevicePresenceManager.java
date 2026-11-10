package com.example.utechat.app;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.google.firebase.FirebaseApp;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.OnDisconnect;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

/** Publishes one connection per active app process and lets the server remove it on disconnect. */
public final class DevicePresenceManager {
    private static final String PREFS = "utechat_presence_preferences";
    private static final String KEY_VISIBLE = "share_presence";

    private final Context appContext;
    private final FirebaseDatabase database;
    private String uid;
    private DatabaseReference activeConnection;
    private ValueEventListener connectedListener;

    public DevicePresenceManager(Context context) {
        appContext = context.getApplicationContext();
        FirebaseDatabase resolved = null;
        try {
            FirebaseApp app = FirebaseApp.getInstance();
            String url = app.getOptions().getDatabaseUrl();
            if (url != null && !url.trim().isEmpty()) resolved = FirebaseDatabase.getInstance(app, url);
        } catch (RuntimeException ignored) { }
        database = resolved;
    }

    public static boolean isVisible(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_VISIBLE, false);
    }

    public static void setVisible(Context context, boolean visible) {
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_VISIBLE, visible).apply();
        UteChatApplication app = UteChatApplication.from(context);
        if (app != null) app.getPresenceManager().updateVisibility(visible);
    }

    public static boolean isAvailable(Context context) {
        UteChatApplication app = UteChatApplication.from(context);
        return app != null && app.getPresenceManager().isAvailable();
    }

    public boolean isAvailable() { return database != null; }

    public synchronized void start(String userId) {
        if (database == null || userId == null || userId.trim().isEmpty()) return;
        if (userId.equals(uid) && connectedListener != null) return;
        stop();
        uid = userId;
        DatabaseReference connected = database.getReference(".info/connected");
        connectedListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean isConnected = snapshot.getValue(Boolean.class);
                if (Boolean.TRUE.equals(isConnected)) registerConnection(userId);
            }

            @Override public void onCancelled(@NonNull DatabaseError error) { }
        };
        connected.addValueEventListener(connectedListener);
    }

    private synchronized void registerConnection(String userId) {
        if (!userId.equals(uid) || database == null) return;
        DatabaseReference connection = database.getReference("presence").child(userId)
                .child("connections").push();
        DatabaseReference lastSeen = database.getReference("presence").child(userId).child("lastSeen");
        DatabaseReference visible = database.getReference("presence").child(userId).child("visible");
        OnDisconnect removeConnection = connection.onDisconnect();
        OnDisconnect updateLastSeen = lastSeen.onDisconnect();
        removeConnection.removeValue().addOnCompleteListener(removeTask -> {
            if (!removeTask.isSuccessful() || !userId.equals(uid)) return;
            updateLastSeen.setValue(ServerValue.TIMESTAMP).addOnCompleteListener(lastSeenTask -> {
                if (!lastSeenTask.isSuccessful() || !userId.equals(uid)) return;
                visible.setValue(isVisible(appContext)).addOnCompleteListener(visibleTask -> {
                    if (!visibleTask.isSuccessful() || !userId.equals(uid)) return;
                    activeConnection = connection;
                    connection.setValue(true);
                });
            });
        });
    }

    public synchronized void updateVisibility(boolean visible) {
        if (database == null || uid == null) return;
        database.getReference("presence").child(uid).child("visible").setValue(visible);
    }

    public synchronized void stop() {
        String previousUid = uid;
        uid = null;
        if (connectedListener != null && database != null) {
            database.getReference(".info/connected").removeEventListener(connectedListener);
            connectedListener = null;
        }
        DatabaseReference connection = activeConnection;
        activeConnection = null;
        if (connection != null) {
            connection.onDisconnect().cancel();
            connection.removeValue();
        }
        if (previousUid != null && database != null) {
            database.getReference("presence").child(previousUid).child("lastSeen")
                    .setValue(ServerValue.TIMESTAMP);
        }
    }
}
