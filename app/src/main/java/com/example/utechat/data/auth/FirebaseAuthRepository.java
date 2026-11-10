package com.example.utechat.data.auth;

import android.content.Context;
import androidx.annotation.NonNull;
import com.example.utechat.R;
import com.example.utechat.domain.model.User;
import com.example.utechat.domain.repository.AuthRepository;
import com.example.utechat.data.notifications.PushTokenRepository;
import com.example.utechat.app.UteChatApplication;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.Timestamp;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/** Firebase Auth and Firestore adapter. Firebase SDK owns and persists auth credentials/session. */
public final class FirebaseAuthRepository implements AuthRepository {
    private static final String USERS = "users";
    private final FirebaseAuth auth = FirebaseAuth.getInstance();
    private final FirebaseFirestore firestore = FirebaseFirestore.getInstance();
    private final Context context;

    public FirebaseAuthRepository(Context context) { this.context = context.getApplicationContext(); }

    @Override public User getCurrentUser() {
        FirebaseUser current = auth.getCurrentUser();
        if (current == null) return null;
        return new User(current.getUid(), current.getDisplayName() == null ? "" : current.getDisplayName(),
                current.getEmail() == null ? "" : current.getEmail(),
                current.getPhotoUrl() == null ? null : current.getPhotoUrl().toString(), "", true,
                System.currentTimeMillis());
    }

    @Override public void loadCurrentUser(Callback<User> callback) {
        FirebaseUser current = auth.getCurrentUser();
        if (current == null) {
            callback.onError(new AuthException(context.getString(R.string.auth_session_expired)));
            return;
        }
        loadProfile(current, callback);
    }

    @Override public void signIn(String email, String password, Callback<User> callback) {
        auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener(result -> {
                    FirebaseUser current = result.getUser();
                    if (current == null) callback.onError(new AuthException(context.getString(R.string.auth_account_unavailable)));
                    else loadProfile(current, callback);
                })
                .addOnFailureListener(error -> callback.onError(new AuthException(messageFor(error))));
    }

    @Override public void register(String displayName, String email, String password, Callback<User> callback) {
        auth.createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener(result -> {
                    FirebaseUser current = result.getUser();
                    if (current == null) {
                        callback.onError(new AuthException(context.getString(R.string.auth_create_failed)));
                        return;
                    }
                    UserProfileChangeRequest request = new UserProfileChangeRequest.Builder()
                            .setDisplayName(displayName).build();
                    current.updateProfile(request).addOnSuccessListener(ignored -> {
                        User user = new User(current.getUid(), displayName, email, null, "", true,
                                System.currentTimeMillis());
                        firestore.collection(USERS).document(current.getUid()).set(profileData(user))
                                .continueWithTask(task -> {
                                    if (!task.isSuccessful()) throw task.getException();
                                    return firestore.collection("publicProfiles").document(current.getUid())
                                            .set(publicProfileData(user));
                                })
                                .addOnSuccessListener(unused -> callback.onSuccess(user))
                                .addOnFailureListener(error -> callback.onError(new AuthException(
                                        context.getString(R.string.auth_profile_write_after_register))));
                    }).addOnFailureListener(error -> callback.onError(new AuthException(
                            context.getString(R.string.auth_name_write_after_register))));
                })
                .addOnFailureListener(error -> callback.onError(new AuthException(messageFor(error))));
    }

    @Override public void updateProfile(String displayName, String bio, Callback<User> callback) {
        FirebaseUser current = auth.getCurrentUser();
        if (current == null) {
            callback.onError(new AuthException(context.getString(R.string.auth_session_expired)));
            return;
        }
        User updated = new User(current.getUid(), displayName, current.getEmail(),
                current.getPhotoUrl() == null ? null : current.getPhotoUrl().toString(), bio, true,
                System.currentTimeMillis());
        UserProfileChangeRequest request = new UserProfileChangeRequest.Builder().setDisplayName(displayName).build();
        current.updateProfile(request).addOnSuccessListener(ignored ->
                firestore.collection(USERS).document(current.getUid()).set(profileData(updated), SetOptions.merge())
                        .continueWithTask(task -> {
                            if (!task.isSuccessful()) throw task.getException();
                            return firestore.collection("publicProfiles").document(current.getUid())
                                    .set(publicProfileData(updated), SetOptions.merge());
                        })
                        .addOnSuccessListener(unused -> callback.onSuccess(updated))
                        .addOnFailureListener(error -> callback.onError(new AuthException(
                                context.getString(R.string.auth_profile_save_failed)))) )
                .addOnFailureListener(error -> callback.onError(new AuthException(
                        context.getString(R.string.auth_name_save_failed))));
    }

    @Override public void signOut(Runnable onComplete) {
        UteChatApplication app = UteChatApplication.from(context);
        if (app != null) app.getPresenceManager().stop();
        PushTokenRepository.clearCurrentDevice(context, () -> {
            auth.signOut();
            if (onComplete != null) onComplete.run();
        });
    }

    private void loadProfile(FirebaseUser firebaseUser, Callback<User> callback) {
        firestore.collection(USERS).document(firebaseUser.getUid()).get()
                .addOnSuccessListener(snapshot -> {
                    User user = fromSnapshot(firebaseUser, snapshot);
                    // Backfill the safe public card for accounts registered before this schema existed.
                    firestore.collection("publicProfiles").document(firebaseUser.getUid())
                            .set(publicProfileData(user), SetOptions.merge())
                            .addOnCompleteListener(ignored -> callback.onSuccess(user));
                })
                .addOnFailureListener(error -> callback.onError(new AuthException(
                        context.getString(R.string.auth_profile_load_failed))));
    }

    private Map<String, Object> profileData(User user) {
        Map<String, Object> data = new HashMap<>();
        data.put("uid", user.getUid());
        data.put("displayName", user.getDisplayName());
        data.put("email", user.getEmail());
        data.put("bio", user.getBio() == null ? "" : user.getBio());
        data.put("photoUrl", user.getPhotoUrl());
        data.put("updatedAt", System.currentTimeMillis());
        return data;
    }

    private Map<String, Object> publicProfileData(User user) {
        Map<String, Object> data = new HashMap<>();
        data.put("uid", user.getUid());
        data.put("displayName", user.getDisplayName() == null ? "" : user.getDisplayName());
        data.put("searchName", user.getDisplayName() == null ? "" : user.getDisplayName().trim().toLowerCase(java.util.Locale.ROOT));
        data.put("photoUrl", user.getPhotoUrl());
        data.put("updatedAt", System.currentTimeMillis());
        return data;
    }

    private User fromSnapshot(FirebaseUser firebaseUser, DocumentSnapshot snapshot) {
        String name = snapshot.getString("displayName");
        String email = snapshot.getString("email");
        String bio = snapshot.getString("bio");
        String photo = snapshot.getString("photoUrl");
        if (name == null) name = firebaseUser.getDisplayName() == null ? "" : firebaseUser.getDisplayName();
        if (email == null) email = firebaseUser.getEmail() == null ? "" : firebaseUser.getEmail();
        User user = new User(firebaseUser.getUid(), name, email, photo, bio == null ? "" : bio,
                true, System.currentTimeMillis());
        user.setCreatedAt(epochMillis(snapshot.get("createdAt")));
        user.setUpdatedAt(epochMillis(snapshot.get("updatedAt")));
        return user;
    }

    private long epochMillis(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof Timestamp) return ((Timestamp) value).toDate().getTime();
        if (value instanceof Date) return ((Date) value).getTime();
        return 0L;
    }

    private String messageFor(@NonNull Exception error) {
        String code = error instanceof com.google.firebase.auth.FirebaseAuthException
                ? ((com.google.firebase.auth.FirebaseAuthException) error).getErrorCode() : "";
        switch (code) {
            case "ERROR_INVALID_EMAIL": return context.getString(R.string.auth_email_invalid_backend);
            case "ERROR_USER_NOT_FOUND":
            case "ERROR_WRONG_PASSWORD":
            case "ERROR_INVALID_CREDENTIAL": return context.getString(R.string.auth_credential_invalid);
            case "ERROR_EMAIL_ALREADY_IN_USE": return context.getString(R.string.auth_email_in_use);
            case "ERROR_WEAK_PASSWORD": return context.getString(R.string.auth_password_weak);
            case "ERROR_NETWORK_REQUEST_FAILED": return context.getString(R.string.auth_network_failed);
            case "ERROR_TOO_MANY_REQUESTS": return context.getString(R.string.auth_rate_limited);
            default: return context.getString(R.string.auth_generic_error);
        }
    }
}
