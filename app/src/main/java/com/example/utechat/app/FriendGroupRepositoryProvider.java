package com.example.utechat.app;

import android.content.Context;
import com.example.utechat.data.friends.FirebaseFriendGroupRepository;
import com.example.utechat.domain.repository.FriendGroupRepository;
import com.google.firebase.FirebaseApp;

/** Selects the configured Firestore repository; this feature has no misleading mock writes. */
public final class FriendGroupRepositoryProvider {
    private static FriendGroupRepository instance;
    private FriendGroupRepositoryProvider() {}
    public static synchronized FriendGroupRepository get(Context context) {
        if (instance == null) {
            Context app = context.getApplicationContext();
            FirebaseApp firebase = FirebaseApp.getApps(app).isEmpty() ? FirebaseApp.initializeApp(app) : FirebaseApp.getInstance();
            if (firebase != null) instance = new FirebaseFriendGroupRepository(app);
        }
        return instance;
    }
}
