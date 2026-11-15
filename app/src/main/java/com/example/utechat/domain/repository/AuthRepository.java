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
    default String pendingVerificationEmail() { return null; }
    default void sendVerificationEmail(Callback<Void> callback) { throw new UnsupportedOperationException(); }
    default void confirmEmailVerification(Callback<User> callback) { throw new UnsupportedOperationException(); }
    void loadCurrentUser(Callback<User> callback);
    void signIn(String email, String password, Callback<User> callback);
    void resetPassword(String email, Callback<Void> callback);
    void register(String displayName, String email, String password, Callback<User> callback);
    void updateProfile(String displayName, String bio, Callback<User> callback);
    void signOut(Runnable onComplete);
}
