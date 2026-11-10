package com.example.utechat.app;

import android.content.Context;

import com.example.utechat.data.chat.FirebaseMessageRepository;
import com.example.utechat.data.chat.MockMessageRepository;
import com.example.utechat.domain.repository.MessageRepository;
import com.google.firebase.FirebaseApp;

/** Selects Firebase for configured projects and the local labeled demo source otherwise. */
public final class MessageRepositoryProvider {
    private static MessageRepository instance;

    private MessageRepositoryProvider() {}

    public static synchronized MessageRepository get(Context context) {
        if (instance == null) {
            Context appContext = context.getApplicationContext();
            FirebaseApp firebase = FirebaseApp.getApps(appContext).isEmpty()
                    ? FirebaseApp.initializeApp(appContext)
                    : FirebaseApp.getInstance();
            instance = firebase == null
                    ? new MockMessageRepository(appContext)
                    : new FirebaseMessageRepository(appContext);
        }
        return instance;
    }

    public static synchronized boolean isMock(Context context) {
        return get(context).isMock();
    }
}
