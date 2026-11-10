package com.example.utechat.domain.repository;

import com.example.utechat.domain.model.User;

/** Backend-neutral async contract for authentication and the signed-in user's profile. */
public interface AuthRepository {
    interface Callback<T> {
        void onSuccess(T value);
        void onError(AuthException error);
    }
    final class AuthException extends Exception {
        public AuthException(String message) { super(message); }
    }
    User getCurrentUser();
    void loadCurrentUser(Callback<User> callback);
    void signIn(String email, String password, Callback<User> callback);
    void register(String displayName, String email, String password, Callback<User> callback);
    void updateProfile(String displayName, String bio, Callback<User> callback);
    void signOut(Runnable onComplete);
}
