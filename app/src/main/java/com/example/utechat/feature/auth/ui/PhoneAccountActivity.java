package com.example.utechat.feature.auth.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.utechat.R;
import com.example.utechat.core.common.AppLanguage;
import com.example.utechat.core.common.AuthGate;
import com.example.utechat.data.auth.AuthRepositoryProvider;
import com.example.utechat.data.auth.PhoneAccountClient;
import com.example.utechat.data.auth.PhoneNumbers;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.FirebaseException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.PhoneAuthCredential;
import com.google.firebase.auth.PhoneAuthOptions;
import com.google.firebase.auth.PhoneAuthProvider;

import java.util.concurrent.TimeUnit;

public final class PhoneAccountActivity extends AppCompatActivity {
    public static final String EXTRA_MODE = "phone_auth_mode";
    public static final String CHANGE = "change";
    private static final String LOGIN = "login";
    private static final String REGISTER = "register";
    private static final String RESET = "reset";
    private static final long RESEND_MS = 60_000;

    private String mode, requestedPhone, verificationId;
    private FirebaseUser account;
    private FirebaseAuth verifier;
    private PhoneAuthCredential automaticProof;
    private PhoneAuthProvider.ForceResendingToken resendToken;
    private long resendAt, loginRetryAt;
    private boolean busy;
    private LinearLayout form;
    private TextInputEditText phone, name, password, confirmation, code;
    private TextView feedback;
    private MaterialButton send, proceed;
    private final Runnable countdown = this::render;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (!REGISTER.equals(mode) && !RESET.equals(mode) && !CHANGE.equals(mode)) mode = LOGIN;
        if (AuthRepositoryProvider.isMock(this)) { AuthGate.replaceTask(this, LoginActivity.class); return; }
        account = FirebaseAuth.getInstance().getCurrentUser();
        if (CHANGE.equals(mode) && !PhoneAccountClient.hasVerifiedContact(account)) {
            AuthGate.openAfterAuth(this);
            return;
        }
        if (saved != null) {
            requestedPhone = saved.getString("requested_phone");
            verificationId = saved.getString("verification_id");
            resendAt = saved.getLong("resend_at");
            loginRetryAt = saved.getLong("login_retry_at");
        }
        createForm();
        render();
    }

    private void createForm() {
        ScrollView screen = new ScrollView(this);
        screen.setFillViewport(true);
        form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int side = getResources().getDimensionPixelSize(R.dimen.space_xl);
        form.setPadding(side, side, side, side);
        screen.addView(form);
        setContentView(screen);

        int heading = REGISTER.equals(mode) ? R.string.phone_register : CHANGE.equals(mode)
                ? R.string.account_change_password : RESET.equals(mode) ? R.string.forgot_password : R.string.phone_login;
        TextView title = new TextView(this);
        title.setText(heading);
        title.setTextSize(26);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        form.addView(title);
        if (REGISTER.equals(mode)) name = field(R.string.label_display_name, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS, "name", true);
        phone = field(R.string.phone_number, InputType.TYPE_CLASS_PHONE, "phone", true);
        if (CHANGE.equals(mode)) phone.setText(account.getPhoneNumber());
        if (requestedPhone != null) phone.setText(requestedPhone);
        password = field(LOGIN.equals(mode) || REGISTER.equals(mode) ? R.string.label_password : R.string.account_new_password,
                passwordType(), LOGIN.equals(mode) ? "password" : "newPassword", false);
        if (!LOGIN.equals(mode)) {
            confirmation = field(R.string.label_confirm_password, passwordType(), "newPassword", false);
            code = field(R.string.phone_otp_code, InputType.TYPE_CLASS_NUMBER, "smsOTPCode", false);
            send = button(R.string.phone_send_code, this::sendCode);
        }
        feedback = new TextView(this);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        form.addView(feedback);
        proceed = button(LOGIN.equals(mode) ? R.string.btn_login : R.string.phone_verify_continue, this::submit);
        if (LOGIN.equals(mode)) {
            button(R.string.phone_register, () -> open(REGISTER));
            button(R.string.forgot_password, () -> open(RESET));
        } else button(R.string.cd_back, this::finish);
    }

    private MaterialButton button(int label, Runnable click) {
        MaterialButton view = new MaterialButton(this);
        view.setMinHeight(getResources().getDimensionPixelSize(R.dimen.touch_target_min));
        view.setText(label);
        view.setOnClickListener(ignored -> click.run());
        form.addView(view);
        return view;
    }

    private TextInputEditText field(int label, int inputType, String autofill, boolean save) {
        TextInputLayout wrapper = new TextInputLayout(this);
        wrapper.setHint(getString(label));
        TextInputEditText input = new TextInputEditText(wrapper.getContext());
        input.setSingleLine(true);
        input.setInputType(inputType);
        input.setSaveEnabled(save);
        input.setId(label == R.string.phone_number ? R.id.phone_account_number : View.NO_ID);
        input.setAutofillHints(autofill);
        wrapper.addView(input);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = getResources().getDimensionPixelSize(R.dimen.space_sm);
        form.addView(wrapper, layout);
        return input;
    }

    private static int passwordType() { return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD; }
    private String value(TextInputEditText field) { return field == null || field.getText() == null ? "" : field.getText().toString(); }
    private void open(String target) {
        Intent intent = new Intent(this, PhoneAccountActivity.class).putExtra(EXTRA_MODE, target);
        AuthGate.copyNotificationTarget(getIntent(), intent);
        startActivity(intent);
    }

    private String validPhone() {
        String number = PhoneNumbers.normalize(value(phone));
        if (number == null || (CHANGE.equals(mode) && !number.equals(account.getPhoneNumber()))) {
            phone.setError(getString(R.string.phone_invalid));
            return null;
        }
        return number;
    }

    private void sendCode() {
        if (busy || SystemClock.elapsedRealtime() < resendAt) return;
        String number = validPhone();
        if (number == null) return;
        busy = true;
        render();
        PhoneAccountClient.checkAvailable(new PhoneAccountClient.Result<Boolean>() {
            @Override public void onSuccess(Boolean enabled) {
                if (!alive()) return;
                if (!enabled) { failed(R.string.phone_auth_unavailable); return; }
                verifier = PhoneAccountClient.verifier(PhoneAccountActivity.this);
                startVerification(number);
            }
            @Override public void onError(int message) { failed(message); }
        });
    }

    private void startVerification(String number) {
        if (!alive()) return;
        verifier.setLanguageCode(AppLanguage.tag(this));
        var options = PhoneAuthOptions.newBuilder(verifier).setActivity(this).setPhoneNumber(number)
                .setTimeout(60L, TimeUnit.SECONDS).setCallbacks(new PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                    @Override public void onVerificationCompleted(@NonNull PhoneAuthCredential credential) {
                        if (!alive()) return;
                        requestedPhone = number;
                        automaticProof = credential;
                        resendAt = SystemClock.elapsedRealtime() + RESEND_MS;
                        busy = false;
                        feedback.setText(R.string.phone_auto_verified);
                        render();
                    }
                    @Override public void onVerificationFailed(@NonNull FirebaseException error) {
                        if (error instanceof FirebaseTooManyRequestsException) resendAt = SystemClock.elapsedRealtime() + RESEND_MS;
                        failed(error instanceof FirebaseTooManyRequestsException ? R.string.phone_sms_limited : R.string.phone_sms_error);
                    }
                    @Override public void onCodeSent(@NonNull String id, @NonNull PhoneAuthProvider.ForceResendingToken token) {
                        if (!alive()) return;
                        requestedPhone = number;
                        verificationId = id;
                        automaticProof = null;
                        resendToken = token;
                        resendAt = SystemClock.elapsedRealtime() + RESEND_MS;
                        busy = false;
                        feedback.setText(getString(R.string.phone_code_sent, number));
                        render();
                    }
                });
        if (resendToken != null) options.setForceResendingToken(resendToken);
        try { PhoneAuthProvider.verifyPhoneNumber(options.build()); }
        catch (RuntimeException unavailable) { failed(R.string.phone_sms_error); }
    }

    private void submit() {
        if (busy || (LOGIN.equals(mode) && SystemClock.elapsedRealtime() < loginRetryAt)) return;
        String number = validPhone(), secret = value(password);
        if (number == null) return;
        if ((LOGIN.equals(mode) ? secret.isEmpty() : secret.codePointCount(0, secret.length()) < 8)
                || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 256) {
            password.setError(getString(R.string.phone_password_policy));
            return;
        }
        if (confirmation != null && !secret.equals(value(confirmation))) {
            confirmation.setError(getString(R.string.error_password_mismatch));
            return;
        }
        String displayName = value(name).trim();
        if (name != null && (displayName.isEmpty() || displayName.length() > 80)) {
            name.setError(getString(R.string.error_name_required));
            return;
        }
        if (LOGIN.equals(mode)) { exchange(LOGIN, number, secret, null, null); return; }
        if (!number.equals(requestedPhone)) return;
        PhoneAuthCredential credential = automaticProof;
        if (credential == null) {
            if (verificationId == null || !value(code).matches("[0-9]{6}")) { code.setError(getString(R.string.phone_code_invalid)); return; }
            credential = PhoneAuthProvider.getCredential(verificationId, value(code));
        }
        busy = true;
        render();
        verifier = PhoneAccountClient.verifier(this);
        verifier.signInWithCredential(credential).addOnSuccessListener(this, result -> {
            FirebaseUser user = result.getUser();
            if (user == null || (CHANGE.equals(mode) && !account.getUid().equals(user.getUid()))) {
                clearVerifier();
                failed(R.string.phone_code_invalid);
                return;
            }
            String action = REGISTER.equals(mode) ? REGISTER : RESET;
            exchange(action, null, secret, REGISTER.equals(mode) ? displayName : null, user);
        }).addOnFailureListener(this, this::verificationError);
    }

    private void exchange(String action, String number, String secret, String displayName, FirebaseUser proof) {
        busy = true;
        render();
        PhoneAccountClient.exchange(action, number, secret, displayName, proof, new PhoneAccountClient.Result<String>() {
            @Override public void onSuccess(String token) {
                clearVerifier();
                if (!alive()) return;
                FirebaseAuth.getInstance().signInWithCustomToken(token).addOnSuccessListener(PhoneAccountActivity.this, result -> {
                    if (CHANGE.equals(mode) || RESET.equals(mode)) Toast.makeText(PhoneAccountActivity.this, R.string.account_password_changed, Toast.LENGTH_SHORT).show();
                    AuthGate.openAfterAuth(PhoneAccountActivity.this);
                }).addOnFailureListener(PhoneAccountActivity.this, error -> failed(R.string.auth_session_expired));
            }
            @Override public void onError(int message) {
                clearVerifier();
                if (message == R.string.phone_login_rate_limited && LOGIN.equals(mode)) loginRetryAt = SystemClock.elapsedRealtime() + RESEND_MS;
                if (!LOGIN.equals(mode)) { verificationId = null; automaticProof = null; }
                failed(message);
            }
        });
    }

    private void clearVerifier() {
        if (verifier != null) verifier.signOut();
    }

    private void verificationError(Exception error) {
        automaticProof = null;
        failed(error instanceof FirebaseAuthInvalidCredentialsException ? R.string.phone_code_invalid : R.string.phone_sms_error);
    }

    private void failed(int message) {
        if (!alive()) return;
        busy = false;
        feedback.setText(message);
        render();
    }

    private void render() {
        if (proceed == null || !alive()) return;
        proceed.removeCallbacks(countdown);
        long remaining = Math.max(0, (LOGIN.equals(mode) ? loginRetryAt : resendAt) - SystemClock.elapsedRealtime());
        phone.setEnabled(!busy && requestedPhone == null && !CHANGE.equals(mode));
        password.setEnabled(!busy);
        if (confirmation != null) confirmation.setEnabled(!busy);
        if (name != null) name.setEnabled(!busy);
        if (send != null) {
            send.setEnabled(!busy && remaining == 0);
            send.setText(remaining > 0 ? getString(R.string.verify_email_resend_wait, (remaining + 999) / 1000)
                    : getString(requestedPhone == null ? R.string.phone_send_code : R.string.phone_resend_code));
            code.setEnabled(!busy && verificationId != null);
        }
        proceed.setEnabled(!busy && (LOGIN.equals(mode) ? remaining == 0 : verificationId != null || automaticProof != null));
        if (remaining > 0) proceed.postDelayed(countdown, Math.min(remaining, 1000));
    }

    private boolean alive() { return !isDestroyed() && !isFinishing(); }

    @Override protected void onSaveInstanceState(@NonNull Bundle state) {
        state.putString("requested_phone", requestedPhone);
        state.putString("verification_id", verificationId);
        state.putLong("resend_at", resendAt);
        state.putLong("login_retry_at", loginRetryAt);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        if (proceed != null) proceed.removeCallbacks(countdown);
        if (isFinishing()) clearVerifier();
        super.onDestroy();
    }
}
