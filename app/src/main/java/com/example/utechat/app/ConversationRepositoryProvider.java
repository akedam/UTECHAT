package com.example.utechat.app;

import android.content.Context;

import com.example.utechat.data.chat.MockConversationRepository;
import com.example.utechat.data.chat.FirebaseConversationRepository;
import com.example.utechat.domain.repository.ConversationRepository;
import com.google.firebase.FirebaseApp;

/** Uses the explicitly labeled mock until conversation Firestore rules and data are ready. */
public final class ConversationRepositoryProvider {
    private static ConversationRepository instance;

    private ConversationRepositoryProvider() {}

    public static synchronized ConversationRepository get(Context context) {
        if (instance == null) {
            Context appContext = context.getApplicationContext();
            FirebaseApp firebase = FirebaseApp.getApps(appContext).isEmpty()
                    ? FirebaseApp.initializeApp(appContext)
                    : FirebaseApp.getInstance();
            instance = firebase == null
                    ? new MockConversationRepository(appContext)
                    : new FirebaseConversationRepository(appContext);
        }
        return instance;
    }
}
