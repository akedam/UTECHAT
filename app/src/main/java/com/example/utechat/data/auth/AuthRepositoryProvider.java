package com.example.utechat.data.auth;

import android.content.Context;
import com.example.utechat.domain.repository.AuthRepository;

/** Single composition point; switch this provider to Firebase when project config is available. */
public final class AuthRepositoryProvider {
    private static AuthRepository repository;
    private AuthRepositoryProvider() { }

    public static synchronized AuthRepository get(Context context) {
        if (repository == null) {
            Context appContext = context.getApplicationContext();
            com.google.firebase.FirebaseApp firebaseApp = com.google.firebase.FirebaseApp.initializeApp(appContext);
            repository = firebaseApp == null
                    ? new MockAuthRepository(appContext)
                    : new FirebaseAuthRepository(appContext);
        }
        return repository;
    }

    public static synchronized boolean isMock(Context context) {
        return get(context) instanceof MockAuthRepository;
    }
}
