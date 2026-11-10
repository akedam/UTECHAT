package com.example.utechat.domain.repository;

public interface PresenceRepository {
    interface Listener {
        void onChanged(boolean online, long lastSeenMillis);
    }

    interface Subscription {
        void remove();
    }

    Subscription observe(String uid, Listener listener);
}
