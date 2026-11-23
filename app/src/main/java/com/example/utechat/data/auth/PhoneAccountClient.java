package com.example.utechat.data.auth;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.example.utechat.R;
import com.example.utechat.data.backend.BackendClient;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Keeps OTP verification separate from the primary UTEChat session. */
public final class PhoneAccountClient {
    private static final String VERIFICATION_APP = "utechat-sms-verification";
    private static final java.util.concurrent.ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private PhoneAccountClient() { }

    public interface Result<T> { void onSuccess(T value); void onError(int message); }

    public static synchronized FirebaseAuth verifier(Context context) {
        FirebaseApp app;
        try { app = FirebaseApp.getInstance(VERIFICATION_APP); }
        catch (IllegalStateException missing) {
            app = FirebaseApp.initializeApp(context.getApplicationContext(), FirebaseApp.getInstance().getOptions(), VERIFICATION_APP);
        }
        return FirebaseAuth.getInstance(app);
    }

    public static boolean hasVerifiedContact(FirebaseUser user) {
        return user != null && user.getEmail() == null && user.getPhoneNumber() != null;
    }

    public static void checkAvailable(Result<Boolean> callback) {
        IO.execute(() -> {
            try {
                var response = BackendClient.phoneAuth("status", null, null);
                if (!response.ok() && response.status != 404) throw new IllegalStateException("Status unavailable");
                boolean enabled = response.ok() && response.json().optBoolean("enabled");
                MAIN.post(() -> callback.onSuccess(enabled));
            } catch (Exception error) { MAIN.post(() -> callback.onError(R.string.phone_auth_unavailable)); }
        });
    }

    public static void exchange(String action, String phone, String password, String name, FirebaseUser proof,
                                Result<String> callback) {
        IO.execute(() -> {
            try {
                String proofToken = proof == null ? null : Tasks.await(proof.getIdToken(true), 25, TimeUnit.SECONDS).getToken();
                var input = new JSONObject().put("password", password);
                if (phone != null) input.put("phone", phone);
                if (name != null) input.put("displayName", name);
                var response = BackendClient.phoneAuth(action, input, proofToken);
                if (!response.ok()) {
                    int error = errorLabel(response);
                    MAIN.post(() -> callback.onError(error));
                    return;
                }
                String token = response.json().getString("token");
                MAIN.post(() -> callback.onSuccess(token));
            } catch (Exception error) { MAIN.post(() -> callback.onError(R.string.phone_auth_unavailable)); }
        });
    }

    private static int errorLabel(BackendClient.Response response) {
        try {
            switch (response.json().optString("error")) {
                case "invalid-phone-credentials": return R.string.phone_wrong_credentials;
                case "phone-login-rate-limited":
                case "rate-limited": return R.string.phone_login_rate_limited;
                case "phone-password-reused": return R.string.phone_password_reused;
                case "phone-password-policy": return R.string.phone_password_policy;
                case "phone-account-exists": return R.string.phone_account_exists;
                case "email-account-unavailable": return R.string.phone_email_account_unavailable;
                case "phone-account-unavailable": return R.string.phone_reset_unavailable;
                case "fresh-phone-proof-required": return R.string.phone_otp_expired;
                default: return R.string.phone_auth_unavailable;
            }
        } catch (Exception error) { return R.string.phone_auth_unavailable; }
    }
}
