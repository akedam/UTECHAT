package com.example.utechat.feature.profile.ui;

import android.os.Bundle;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.example.utechat.R;
import com.example.utechat.core.common.AuthGate;
import com.example.utechat.data.auth.AuthRepositoryProvider;
import com.example.utechat.data.backend.BackendClient;
import com.example.utechat.domain.model.User;
import com.example.utechat.domain.repository.AuthRepository;

/** Edits the shared UTEChat profile and uploads an optional avatar. */
public class ProfileEditActivity extends AppCompatActivity {

    private EditText etDisplayName, etBio;
    private Button btnSave, btnPhoto;
    private ImageView photoView;
    private Uri selectedPhoto;
    private final androidx.activity.result.ActivityResultLauncher<String> photoPicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;
                selectedPhoto = uri;
                com.bumptech.glide.Glide.with(this).load(uri).circleCrop().into(photoView);
            });

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
        btnPhoto = findViewById(R.id.btn_profile_photo);
        photoView = findViewById(R.id.iv_profile_photo);
        btnPhoto.setEnabled(BackendClient.configured() && !AuthRepositoryProvider.isMock(this));
        btnPhoto.setOnClickListener(view -> photoPicker.launch("image/*"));

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
                if (selectedPhoto == null && loadedUser.getPhotoUrl() != null)
                    com.bumptech.glide.Glide.with(ProfileEditActivity.this).load(loadedUser.getPhotoUrl()).circleCrop().into(photoView);
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
            btnPhoto.setEnabled(false);
            String bio = etBio.getText() == null ? "" : etBio.getText().toString().trim();
            if (selectedPhoto == null) saveProfile(name, bio, null);
            else uploadAvatar(selectedPhoto, name, bio);
        });
    }

    private void saveProfile(String name, String bio, String avatar) {
        AuthRepositoryProvider.get(this).updateProfile(name, bio, avatar, new AuthRepository.Callback<User>() {
                        @Override public void onSuccess(User user) {
                            Toast.makeText(ProfileEditActivity.this,
                                    AuthRepositoryProvider.isMock(ProfileEditActivity.this)
                                            ? R.string.saved_mock_notice : R.string.profile_saved_notice,
                                    Toast.LENGTH_SHORT).show();
                            finish();
                        }

                        @Override public void onError(AuthRepository.AuthException error) {
                            btnSave.setEnabled(true);
                            btnPhoto.setEnabled(BackendClient.configured());
                            Toast.makeText(ProfileEditActivity.this, error.getMessage(), Toast.LENGTH_LONG).show();
                        }
        });
    }

    private void uploadAvatar(Uri uri, String name, String bio) {
        String uid = com.google.firebase.auth.FirebaseAuth.getInstance().getUid();
        new Thread(() -> {
            try {
                byte[] bytes = encodeAvatar(uri);
                if (uid == null || !uid.equals(com.google.firebase.auth.FirebaseAuth.getInstance().getUid()))
                    throw new IllegalStateException("Session changed");
                BackendClient.Response response = BackendClient.request("PUT", "/api/avatar", bytes, "image/jpeg", true);
                if (!response.ok()) throw new java.io.IOException("Upload rejected");
                String path = response.json().getString("path");
                if (!path.startsWith("/api/avatar?uid=" + Uri.encode(uid) + "&v="))
                    throw new java.io.IOException("Unexpected avatar path");
                String url = BackendClient.endpoint(path).toString();
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()
                            && uid.equals(com.google.firebase.auth.FirebaseAuth.getInstance().getUid())) saveProfile(name, bio, url);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    btnSave.setEnabled(true);
                    btnPhoto.setEnabled(true);
                    Toast.makeText(this, R.string.profile_photo_upload_error, Toast.LENGTH_LONG).show();
                });
            }
        }, "UTEChat avatar upload").start();
    }

    private byte[] encodeAvatar(Uri uri) throws java.io.IOException {
        Bitmap photo;
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            photo = android.graphics.ImageDecoder.decodeBitmap(
                    android.graphics.ImageDecoder.createSource(getContentResolver(), uri), (decoder, info, source) -> {
                        int width = info.getSize().getWidth(), height = info.getSize().getHeight();
                        double scale = Math.min(1, 768.0 / Math.max(width, height));
                        decoder.setTargetSize(Math.max(1, (int)(width * scale)), Math.max(1, (int)(height * scale)));
                        decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                    });
        } else {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try (var input = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(input, null, options);
            }
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 1024) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            try (var input = getContentResolver().openInputStream(uri)) {
                photo = BitmapFactory.decodeStream(input, null, options);
            }
        }
        if (photo == null) throw new java.io.IOException("Invalid image");
        Bitmap avatar = Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888);
        try (var bytes = new java.io.ByteArrayOutputStream()) {
            int size = Math.min(photo.getWidth(), photo.getHeight());
            int x = (photo.getWidth() - size) / 2, y = (photo.getHeight() - size) / 2;
            android.graphics.Canvas canvas = new android.graphics.Canvas(avatar);
            canvas.drawColor(android.graphics.Color.WHITE);
            canvas.drawBitmap(photo, new android.graphics.Rect(x, y, x + size, y + size),
                    new android.graphics.Rect(0, 0, 384, 384), new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
            if (!avatar.compress(Bitmap.CompressFormat.JPEG, 82, bytes) || bytes.size() > 192 * 1024)
                throw new java.io.IOException("Invalid avatar");
            return bytes.toByteArray();
        } finally {
            photo.recycle();
            avatar.recycle();
        }
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
