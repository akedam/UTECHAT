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

        // TODO (G4): Load current user data
        etDisplayName.setText(R.string.sample_name);
        etBio.setText(R.string.sample_bio);

        btnSave.setOnClickListener(v -> {
            // TODO (G4): viewModel.updateProfile(name, bio) → Firestore
            Toast.makeText(this, R.string.saved_mock_notice, Toast.LENGTH_SHORT).show();
            finish();
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
