package com.example.utechat.data.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import com.example.utechat.R;
import com.example.utechat.domain.model.User;
import com.example.utechat.domain.repository.AuthRepository;
import java.util.UUID;

/**
 * Explicit demo-only implementation. It stores no password or token and is not authentication.
 * Replace with Firebase Auth before using accounts outside local UI demonstrations.
 */
public final class MockAuthRepository implements AuthRepository {
    private static final String PREFS = "utechat_mock_session";
    private static final String KEY_UID = "uid";
    private static final String KEY_NAME = "display_name";
    private static final String KEY_EMAIL = "email";
    private static final String KEY_BIO = "bio";
    private final SharedPreferences preferences;
    private final Context appContext;

    public MockAuthRepository(Context context) {
        appContext = context.getApplicationContext();
        preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override public User getCurrentUser() {
        String uid = preferences.getString(KEY_UID, null);
        if (uid == null) return null;
        return new User(uid, preferences.getString(KEY_NAME, ""),
                preferences.getString(KEY_EMAIL, ""), null,
                preferences.getString(KEY_BIO, ""), true, System.currentTimeMillis());
    }

    @Override public void signIn(String email, String password, Callback<User> callback) {
        // Demo only: any syntactically valid input creates a local mock session.
        User previous = getCurrentUser();
        String name = previous != null && email.equalsIgnoreCase(previous.getEmail())
                ? previous.getDisplayName() : appContext.getString(R.string.mock_student_name);
        String bio = previous != null && email.equalsIgnoreCase(previous.getEmail())
                ? previous.getBio() : "";
        deliver(callback, saveUser(new User("mock_" + UUID.randomUUID(), name, email,
                null, bio, true, System.currentTimeMillis())));
    }

    @Override public void register(String displayName, String email, String password, Callback<User> callback) {
        deliver(callback, saveUser(new User("mock_" + UUID.randomUUID(), displayName, email,
                null, "", true, System.currentTimeMillis())));
    }

    @Override public void updateProfile(String displayName, String bio, Callback<User> callback) {
        User current = getCurrentUser();
        if (current == null) {
            callback.onError(new AuthException(appContext.getString(R.string.auth_session_expired)));
            return;
        }
        current.setDisplayName(displayName);
        current.setBio(bio);
        deliver(callback, saveUser(current));
    }

    @Override public void loadCurrentUser(Callback<User> callback) {
        User current = getCurrentUser();
        if (current == null) callback.onError(new AuthException(appContext.getString(R.string.auth_not_signed_in)));
        else deliver(callback, current);
    }

    private void deliver(Callback<User> callback, User user) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> callback.onSuccess(user), 450);
    }

    @Override public void signOut() {
        preferences.edit().clear().apply();
    }

    private User saveUser(User user) {
        preferences.edit()
                .putString(KEY_UID, user.getUid())
                .putString(KEY_NAME, user.getDisplayName())
                .putString(KEY_EMAIL, user.getEmail())
                .putString(KEY_BIO, user.getBio())
                .apply();
        return user;
    }
}
