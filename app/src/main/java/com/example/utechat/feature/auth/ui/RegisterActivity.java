package com.example.utechat.feature.auth.ui;

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

/** Registration form for email-and-password accounts. */
public class RegisterActivity extends AppCompatActivity {

    private EditText etDisplayName, etEmail, etPassword, etConfirmPassword;
    private Button btnRegister;
    private TextView tvGoToLogin;
    private ProgressBar progressBar;
    private AuthViewModel viewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        bindViews();
        TextView mockNotice = findViewById(R.id.tv_mock_notice);
        mockNotice.setText(AuthRepositoryProvider.isMock(this)
                ? R.string.auth_mode_mock : R.string.auth_mode_firebase);
        viewModel = new ViewModelProvider(this).get(AuthViewModel.class);
        observeViewModel();
        setupListeners();
    }

    private void bindViews() {
        etDisplayName = findViewById(R.id.et_display_name);
        etEmail = findViewById(R.id.et_email);
        etPassword = findViewById(R.id.et_password);
        etConfirmPassword = findViewById(R.id.et_confirm_password);
        btnRegister = findViewById(R.id.btn_register);
        tvGoToLogin = findViewById(R.id.tv_go_to_login);
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
        btnRegister.setOnClickListener(v -> attemptRegister());
        tvGoToLogin.setOnClickListener(v -> finish()); // quay lại Login
    }

    private void attemptRegister() {
        String name = etDisplayName.getText().toString().trim();
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString();
        String confirm = etConfirmPassword.getText().toString();

        if (name.isEmpty()) {
            etDisplayName.setError(getString(R.string.error_name_required));
            etDisplayName.requestFocus();
            return;
        }
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
        if (!password.equals(confirm)) {
            etConfirmPassword.setError(getString(R.string.error_password_mismatch));
            etConfirmPassword.requestFocus();
            return;
        }

        viewModel.register(name, email, password);
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnRegister.setEnabled(!loading);
        etDisplayName.setEnabled(!loading);
        etEmail.setEnabled(!loading);
        etPassword.setEnabled(!loading);
        etConfirmPassword.setEnabled(!loading);
        tvGoToLogin.setEnabled(!loading);
    }

}
