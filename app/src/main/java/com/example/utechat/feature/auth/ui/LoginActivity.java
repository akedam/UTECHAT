package com.example.utechat.feature.auth.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Patterns;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.example.utechat.R;
import com.example.utechat.feature.auth.viewmodel.AuthViewModel;
import com.example.utechat.core.common.AuthGate;
import com.example.utechat.data.auth.AuthRepositoryProvider;

/** Sign in with email and password or request a password reset link. */
public class LoginActivity extends AppCompatActivity {

    private EditText etEmail, etPassword;
    private Button btnLogin;
    private TextView tvGoToRegister;
    private ProgressBar progressBar;
    private AuthViewModel viewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        bindViews();
        TextView mockNotice = findViewById(R.id.tv_mock_notice);
        mockNotice.setText(AuthRepositoryProvider.isMock(this)
                ? R.string.auth_mode_mock : R.string.auth_mode_firebase);
        viewModel = new ViewModelProvider(this).get(AuthViewModel.class);
        observeViewModel();
        setupListeners();
    }

    private void bindViews() {
        etEmail = findViewById(R.id.et_email);
        etPassword = findViewById(R.id.et_password);
        btnLogin = findViewById(R.id.btn_login);
        tvGoToRegister = findViewById(R.id.tv_go_to_register);
        progressBar = findViewById(R.id.progress_bar);
    }

    private void observeViewModel() {
        viewModel.getAuthState().observe(this, resource -> {
            if (resource == null) return;
            switch (resource.getStatus()) {
                case LOADING:
                    setLoading(true);
                    break;
                case SUCCESS:
                    setLoading(false);
                    AuthGate.openAfterAuth(this);
                    break;
                case ERROR:
                    setLoading(false);
                    Toast.makeText(this, resource.getMessage(), Toast.LENGTH_LONG).show();
                    break;
            }
        });
    }

    private void setupListeners() {
        btnLogin.setOnClickListener(v -> attemptLogin());
        View reset = findViewById(R.id.btn_forgot_password);
        reset.setVisibility(AuthRepositoryProvider.isMock(this) ? View.GONE : View.VISIBLE);
        reset.setOnClickListener(v -> showPasswordReset());

        tvGoToRegister.setOnClickListener(v -> {
            Intent register = new Intent(this, RegisterActivity.class);
            AuthGate.copyNotificationTarget(getIntent(), register);
            startActivity(register);
        });
    }

    private void showPasswordReset() {
        com.google.android.material.textfield.TextInputLayout field =
                new com.google.android.material.textfield.TextInputLayout(this);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        field.setPadding(padding, padding / 2, padding, 0);
        field.setHint(getString(R.string.reset_email_label));
        EditText email = new com.google.android.material.textfield.TextInputEditText(field.getContext());
        email.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setSingleLine(true);
        email.setText(etEmail.getText());
        field.addView(email);
        androidx.appcompat.app.AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.forgot_password)
                .setMessage(R.string.reset_password_description)
                .setView(field)
                .setNegativeButton(R.string.friends_cancel_button, null)
                .setPositiveButton(R.string.reset_password_send, null).create();
        dialog.show();
        Button send = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
        send.setOnClickListener(v -> {
            String address = email.getText().toString().trim();
            if (!Patterns.EMAIL_ADDRESS.matcher(address).matches()) {
                field.setError(getString(R.string.error_email_invalid));
                email.requestFocus();
                return;
            }
            field.setError(null);
            send.setEnabled(false);
            AuthRepositoryProvider.get(this).resetPassword(address, new com.example.utechat.domain.repository.AuthRepository.Callback<Void>() {
                @Override public void onSuccess(Void unused) {
                    if (!dialog.isShowing() || isFinishing() || isDestroyed()) return;
                    dialog.dismiss();
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(LoginActivity.this)
                            .setTitle(R.string.forgot_password).setMessage(R.string.reset_password_sent)
                            .setPositiveButton(R.string.community_close, null).show();
                }
                @Override public void onError(com.example.utechat.domain.repository.AuthRepository.AuthException error) {
                    if (!dialog.isShowing() || isFinishing() || isDestroyed()) return;
                    send.setEnabled(true);
                    field.setError(error.getMessage());
                }
            });
        });
    }

    private void attemptLogin() {
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString();

        if (email.isEmpty()) {
            etEmail.setError(getString(R.string.error_email_required));
            etEmail.requestFocus();
            return;
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            etEmail.setError(getString(R.string.error_email_invalid));
            etEmail.requestFocus();
            return;
        }
        if (password.length() < 6) {
            etPassword.setError(getString(R.string.error_password_too_short));
            etPassword.requestFocus();
            return;
        }

        viewModel.signIn(email, password);
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnLogin.setEnabled(!loading);
        etEmail.setEnabled(!loading);
        etPassword.setEnabled(!loading);
        tvGoToRegister.setEnabled(!loading);
    }

}
