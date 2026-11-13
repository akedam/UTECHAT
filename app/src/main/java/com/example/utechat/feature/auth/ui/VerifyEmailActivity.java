package com.example.utechat.feature.auth.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.example.utechat.R;
import com.example.utechat.core.common.AuthGate;
import com.example.utechat.data.auth.AuthRepositoryProvider;
import com.example.utechat.domain.model.User;
import com.example.utechat.domain.repository.AuthRepository;

public final class VerifyEmailActivity extends AppCompatActivity {
    private static final long RESEND_DELAY_MS = 60_000;
    private AuthRepository repository;
    private SharedPreferences emailRequests;
    private String email;
    private Button resend, confirm, signOut;
    private TextView status;
    private boolean busy;
    private final Runnable updateResend = this::refreshButtons;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        repository = AuthRepositoryProvider.get(this);
        email = repository.pendingVerificationEmail();
        if (email == null) {
            AuthGate.openAfterAuth(this);
            return;
        }
        emailRequests = getSharedPreferences("email_verification_requests", MODE_PRIVATE);
        setContentView(R.layout.activity_verify_email);
        ((TextView) findViewById(R.id.verify_email_address)).setText(email);
        status = findViewById(R.id.verify_email_status);
        resend = findViewById(R.id.btn_verify_resend);
        confirm = findViewById(R.id.btn_verify_confirm);
        signOut = findViewById(R.id.btn_verify_sign_out);
        resend.setOnClickListener(v -> sendLink());
        confirm.setOnClickListener(v -> checkEmail());
        signOut.setOnClickListener(v -> {
            setBusy(true);
            repository.signOut(() -> {
                if (isAlive()) AuthGate.openAfterAuth(this);
            });
        });
        if (state != null) showStatus(state.getCharSequence("status"));
        if (!emailRequests.contains(email)) sendLink();
    }

    @Override protected void onStart() {
        super.onStart();
        if (resend != null) refreshButtons();
    }

    @Override protected void onStop() {
        if (resend != null) resend.removeCallbacks(updateResend);
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (status != null) state.putCharSequence("status", status.getText());
        super.onSaveInstanceState(state);
    }

    private void sendLink() {
        long now = System.currentTimeMillis();
        if (busy || resendWait(now) != 0) return;
        emailRequests.edit().putLong(email, now).apply();
        setBusy(true);
        repository.sendVerificationEmail(new AuthRepository.Callback<Void>() {
            @Override public void onSuccess(Void unused) {
                if (!isAlive()) return;
                setBusy(false);
                showStatus(getString(R.string.verify_email_sent));
            }

            @Override public void onError(AuthRepository.AuthException error) {
                if (!isAlive()) return;
                setBusy(false);
                showStatus(getString(R.string.verify_email_send_failed, error.getMessage()));
            }
        });
    }

    private void checkEmail() {
        setBusy(true);
        repository.confirmEmailVerification(new AuthRepository.Callback<User>() {
            @Override public void onSuccess(User user) {
                emailRequests.edit().remove(email).apply();
                if (isAlive()) AuthGate.openAfterAuth(VerifyEmailActivity.this);
            }

            @Override public void onError(AuthRepository.AuthException error) {
                if (!isAlive()) return;
                setBusy(false);
                showStatus(error.getMessage());
            }
        });
    }

    private boolean isAlive() { return !isFinishing() && !isDestroyed(); }

    private void showStatus(CharSequence message) {
        status.setText(message);
        status.setVisibility(message == null || message.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private long resendWait(long now) {
        long last = emailRequests.getLong(email, 0);
        return last <= now ? Math.max(0, RESEND_DELAY_MS - (now - last)) : 0;
    }

    private void refreshButtons() {
        long wait = resendWait(System.currentTimeMillis());
        resend.setEnabled(!busy && wait == 0);
        resend.setText(wait == 0 ? getString(R.string.verify_email_resend)
                : getString(R.string.verify_email_resend_wait, (wait + 999) / 1_000));
        confirm.setEnabled(!busy);
        signOut.setEnabled(!busy);
        resend.removeCallbacks(updateResend);
        if (wait > 0 && getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            resend.postDelayed(updateResend, Math.min(1_000, wait));
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        findViewById(R.id.verify_email_progress).setVisibility(value ? View.VISIBLE : View.GONE);
        refreshButtons();
    }
}
