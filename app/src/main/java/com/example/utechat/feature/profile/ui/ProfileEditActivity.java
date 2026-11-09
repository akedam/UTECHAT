package com.example.utechat.feature.profile.ui;

import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.example.utechat.R;
import com.example.utechat.core.common.AuthGate;
import com.example.utechat.data.auth.AuthRepositoryProvider;
import com.example.utechat.domain.model.User;
import com.example.utechat.domain.repository.AuthRepository;

/**
 * Activity chỉnh sửa hồ sơ cá nhân.
 * [MOCK] Form UI — kết nối Firebase Storage + Firestore ở G4.
 */
public class ProfileEditActivity extends AppCompatActivity {

    private EditText etDisplayName, etBio;
    private Button btnSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (AuthGate.redirectIfSignedOut(this)) return;
        setContentView(R.layout.activity_profile_edit);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.edit_profile_title);
        }

        etDisplayName = findViewById(R.id.et_display_name);
        etBio = findViewById(R.id.et_bio);
        btnSave = findViewById(R.id.btn_save);

        User user = AuthRepositoryProvider.get(this).getCurrentUser();
        if (user != null) {
            etDisplayName.setText(user.getDisplayName());
            etBio.setText(user.getBio());
        }
        btnSave.setEnabled(false);
        AuthRepositoryProvider.get(this).loadCurrentUser(new AuthRepository.Callback<User>() {
            @Override public void onSuccess(User loadedUser) {
                if (isFinishing()) return;
                etDisplayName.setText(loadedUser.getDisplayName());
                etBio.setText(loadedUser.getBio());
                btnSave.setEnabled(true);
            }

            @Override public void onError(AuthRepository.AuthException error) {
                if (isFinishing()) return;
                btnSave.setEnabled(true);
                Toast.makeText(ProfileEditActivity.this, error.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        btnSave.setOnClickListener(v -> {
            String name = etDisplayName.getText() == null ? "" : etDisplayName.getText().toString().trim();
            if (name.isEmpty()) {
                etDisplayName.setError(getString(R.string.error_name_required));
                etDisplayName.requestFocus();
                return;
            }
            btnSave.setEnabled(false);
            AuthRepositoryProvider.get(this).updateProfile(name,
                    etBio.getText() == null ? "" : etBio.getText().toString().trim(),
                    new AuthRepository.Callback<User>() {
                        @Override public void onSuccess(User user) {
                            Toast.makeText(ProfileEditActivity.this,
                                    AuthRepositoryProvider.isMock(ProfileEditActivity.this)
                                            ? R.string.saved_mock_notice : R.string.profile_saved_notice,
                                    Toast.LENGTH_SHORT).show();
                            finish();
                        }

                        @Override public void onError(AuthRepository.AuthException error) {
                            btnSave.setEnabled(true);
                            Toast.makeText(ProfileEditActivity.this, error.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
        });
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
