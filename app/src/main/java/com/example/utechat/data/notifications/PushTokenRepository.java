package com.example.utechat.data.notifications;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.messaging.FirebaseMessaging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import android.os.Handler;
import android.os.Looper;

/** Stores per-device FCM routing tokens in an owner-only Firestore subcollection. */
public final class PushTokenRepository {
    private static final String PREFS = "utechat_push_registration";
    private static final String KEY_UID = "registered_uid";
    private static final String KEY_TOKEN_HASH = "registered_token_hash";

    private PushTokenRepository() { }

    public static void refreshCurrentToken(Context context) {
        try {
            FirebaseMessaging.getInstance().getToken()
                    .addOnSuccessListener(token -> syncToken(context, token));
        } catch (RuntimeException ignored) {
            // Firebase app configuration is optional in mock builds.
        }
    }

    public static void syncToken(Context context, String token) {
        if (token == null || token.trim().isEmpty()) return;
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) return;
            String hash = sha256(token);
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String previousUid = prefs.getString(KEY_UID, null);
            String previousHash = prefs.getString(KEY_TOKEN_HASH, null);
            if (user.getUid().equals(previousUid) && hash.equals(previousHash)) return;

            if (user.getUid().equals(previousUid) && previousHash != null && !hash.equals(previousHash)) {
                FirebaseFirestore.getInstance().collection("users").document(user.getUid())
                        .collection("deviceTokens").document(previousHash).delete();
            }

            Map<String, Object> data = new HashMap<>();
            data.put("token", token);
            data.put("platform", "android");
            data.put("updatedAt", System.currentTimeMillis());
            FirebaseFirestore.getInstance().collection("users").document(user.getUid())
                    .collection("deviceTokens").document(hash).set(data, SetOptions.merge())
                    .addOnSuccessListener(unused -> prefs.edit()
                            .putString(KEY_UID, user.getUid())
                            .putString(KEY_TOKEN_HASH, hash).apply());
        } catch (RuntimeException ignored) {
            // No token or auth data is written to logs; the next app start retries sync.
        }
    }

    public static void clearCurrentDevice(Context context, Runnable onComplete) {
        Context appContext = context.getApplicationContext();
        SharedPreferences prefs = appContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String uid = prefs.getString(KEY_UID, null);
        String hash = prefs.getString(KEY_TOKEN_HASH, null);
        AtomicBoolean finished = new AtomicBoolean(false);
        Runnable finish = () -> {
            if (!finished.compareAndSet(false, true)) return;
            try { FirebaseMessaging.getInstance().deleteToken(); } catch (RuntimeException ignored) { }
            if (onComplete != null) onComplete.run();
        };
        // Never trap the user on the logout screen if Firestore is offline.
        new Handler(Looper.getMainLooper()).postDelayed(finish, 1500L);
        if (uid == null || hash == null) {
            prefs.edit().clear().apply();
            finish.run();
            return;
        }
        try {
            FirebaseFirestore.getInstance().collection("users").document(uid)
                    .collection("deviceTokens").document(hash).delete()
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful()) prefs.edit().clear().apply();
                        finish.run();
                    });
        } catch (RuntimeException unavailable) { finish.run(); }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) result.append(String.format(Locale.ROOT, "%02x", b));
            return result.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
