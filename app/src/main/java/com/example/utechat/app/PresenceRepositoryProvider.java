package com.example.utechat.app;

import com.example.utechat.data.presence.FirebasePresenceRepository;
import com.example.utechat.domain.repository.PresenceRepository;
import com.google.firebase.FirebaseApp;
import com.google.firebase.database.FirebaseDatabase;

/** Presence stays unavailable until a Realtime Database URL is included in Firebase config. */
public final class PresenceRepositoryProvider {
    private static PresenceRepository instance;
    private PresenceRepositoryProvider() { }

    public static synchronized PresenceRepository get() {
        if (instance != null) return instance;
        try {
            FirebaseApp app = FirebaseApp.getInstance();
            String url = app.getOptions().getDatabaseUrl();
            if (url == null || url.trim().isEmpty()) return null;
            instance = new FirebasePresenceRepository(FirebaseDatabase.getInstance(app, url));
            return instance;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
