package com.example.utechat.data.auth;

import android.content.Context;
import com.example.utechat.R;
import com.example.utechat.core.common.AppLanguage;
import com.example.utechat.domain.model.User;
import com.example.utechat.domain.repository.AuthRepository;
import com.example.utechat.data.notifications.PushTokenRepository;
import com.example.utechat.app.UteChatApplication;
import com.google.android.gms.tasks.Tasks;
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
        return PhoneAccountClient.hasVerifiedContact(current) ? authProfile(current) : null;
    }

    private User authProfile(FirebaseUser current) {
        return new User(current.getUid(), current.getDisplayName() == null ? "" : current.getDisplayName(),
                current.getEmail() == null ? "" : current.getEmail(),
                current.getPhotoUrl() == null ? null : current.getPhotoUrl().toString(), "", true,
                System.currentTimeMillis());
    }

    @Override public void loadCurrentUser(Callback<User> callback) {
        FirebaseUser current = auth.getCurrentUser();
        if (!PhoneAccountClient.hasVerifiedContact(current)) {
            callback.onError(new AuthException(AppLanguage.text(context, R.string.auth_session_expired)));
            return;
        }
        loadProfile(current, callback);
    }

    // These email methods are kept on the repository interface for the offline mock.
    @Override public void signIn(String email, String password, Callback<User> callback) {
        callback.onError(phoneSignInRequired());
    }

    @Override public void resetPassword(String email, Callback<Void> callback) {
        callback.onError(phoneSignInRequired());
    }

    @Override public void register(String displayName, String email, String password, Callback<User> callback) {
        callback.onError(phoneSignInRequired());
    }

    private AuthException phoneSignInRequired() {
        return new AuthException(AppLanguage.text(context, R.string.phone_login));
    }

    @Override public void updateProfile(String displayName, String bio, Callback<User> callback) {
        updateProfile(displayName, bio, null, callback);
    }

    @Override public void updateProfile(String displayName, String bio, String photoUrl, Callback<User> callback) {
        FirebaseUser current = auth.getCurrentUser();
        if (!PhoneAccountClient.hasVerifiedContact(current)) {
            callback.onError(new AuthException(context.getString(R.string.auth_session_expired)));
            return;
        }
        var own = firestore.collection(USERS).document(current.getUid());
        var publicCard = firestore.collection("publicProfiles").document(current.getUid());
        firestore.runTransaction(transaction -> {
            DocumentSnapshot saved = transaction.get(own);
            String avatar = photoUrl != null ? photoUrl : fromSnapshot(current, saved).getPhotoUrl();
            User updated = new User(current.getUid(), displayName, current.getEmail() == null ? "" : current.getEmail(), avatar, bio,
                    true, System.currentTimeMillis());
            transaction.set(own, profileData(updated), SetOptions.merge());
            transaction.set(publicCard, publicProfileData(updated), SetOptions.merge());
            return updated;
        }).addOnSuccessListener(updated -> {
            UserProfileChangeRequest.Builder request = new UserProfileChangeRequest.Builder().setDisplayName(displayName);
            if (updated.getPhotoUrl() != null) request.setPhotoUri(android.net.Uri.parse(updated.getPhotoUrl()));
            current.updateProfile(request.build());
            callback.onSuccess(updated);
        }).addOnFailureListener(error -> callback.onError(new AuthException(
                AppLanguage.text(context, R.string.auth_profile_save_failed))));
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
        var profile = firestore.collection(USERS).document(firebaseUser.getUid());
        var publicProfile = firestore.collection("publicProfiles").document(firebaseUser.getUid());
        profile.get().continueWithTask(result -> {
                    if (!result.isSuccessful()) throw result.getException();
                    DocumentSnapshot snapshot = result.getResult();
                    if (!snapshot.exists()) {
                        return firestore.runTransaction(transaction -> {
                            DocumentSnapshot latest = transaction.get(profile);
                            User user = fromSnapshot(firebaseUser, latest);
                            if (!latest.exists()) {
                                transaction.set(profile, profileData(user));
                                transaction.set(publicProfile, publicProfileData(user));
                            }
                            return user;
                        });
                    }
                    User user = fromSnapshot(firebaseUser, snapshot);
                    // Backfill the safe public card for accounts registered before this schema existed.
                    publicProfile.set(publicProfileData(user), SetOptions.merge());
                    return Tasks.forResult(user);
                })
                .addOnSuccessListener(callback::onSuccess)
                .addOnFailureListener(error -> callback.onError(new AuthException(
                        AppLanguage.text(context, R.string.auth_profile_load_failed))));
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
        if (name.trim().isEmpty()) name = AppLanguage.text(context, R.string.call_generic_peer);
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

}
