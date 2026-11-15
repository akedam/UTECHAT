package com.example.utechat.feature.profile.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.switchmaterial.SwitchMaterial;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.utechat.R;
import com.example.utechat.feature.profile.viewmodel.ProfileViewModel;
import com.example.utechat.feature.auth.ui.LoginActivity;
import com.example.utechat.data.auth.AuthRepositoryProvider;
import com.example.utechat.app.DevicePresenceManager;
import com.example.utechat.feature.notifications.NotificationPreferences;
import com.example.utechat.feature.friends.qr.FriendQrImage;
import com.example.utechat.domain.model.User;

/**
 * Fragment hiển thị hồ sơ cá nhân và cài đặt.
 * [MOCK] Hiển thị dữ liệu mẫu — kết nối Firestore ở G4.
 */
public class ProfileFragment extends Fragment {

    private ProfileViewModel viewModel;
    private ImageView ivAvatar;
    private TextView tvName, tvEmail, tvBio;
    private View btnEditProfile, btnLogout;
    private SwitchMaterial switchPresence, switchMessagePreview;
    private TextView tvPresenceSummary;
    private final androidx.activity.result.ActivityResultLauncher<String> saveQrDocument = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.CreateDocument("image/png"), uri -> {
                if (uri == null || !isAdded()) return;
                User user = AuthRepositoryProvider.get(requireContext()).getCurrentUser();
                if (user == null) return;
                var context = requireContext().getApplicationContext();
                new Thread(() -> {
                    int message;
                    try (var output = context.getContentResolver().openOutputStream(uri, "w")) {
                        if (output == null) throw new java.io.IOException("QR destination unavailable");
                        var bitmap = FriendQrImage.make(user.getUid());
                        try {
                            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
                                throw new java.io.IOException("QR export failed");
                        } finally { bitmap.recycle(); }
                        message = R.string.friend_qr_saved;
                    } catch (java.io.IOException | com.google.zxing.WriterException | RuntimeException failure) {
                        message = R.string.friend_qr_creation_error;
                    }
                    int result = message;
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                            Toast.makeText(context, result, Toast.LENGTH_SHORT).show());
                }, "UTEChat QR export").start();
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_profile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ivAvatar = view.findViewById(R.id.iv_avatar);
        tvName = view.findViewById(R.id.tv_name);
        tvEmail = view.findViewById(R.id.tv_email);
        tvBio = view.findViewById(R.id.tv_bio);
        btnEditProfile = view.findViewById(R.id.btn_edit_profile);
        btnLogout = view.findViewById(R.id.btn_logout);
        switchPresence = view.findViewById(R.id.switch_share_presence);
        switchMessagePreview = view.findViewById(R.id.switch_message_previews);
        tvPresenceSummary = view.findViewById(R.id.tv_presence_summary);

        viewModel = new ViewModelProvider(this).get(ProfileViewModel.class);

        observeViewModel();
        setupListeners();
        View privacy = view.findViewById(R.id.layout_privacy);
        View notifications = view.findViewById(R.id.layout_notifications);
        View fullscreen = view.findViewById(R.id.btn_call_full_screen);
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            fullscreen.setVisibility(View.VISIBLE);
            fullscreen.setOnClickListener(button -> startActivity(new Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    android.net.Uri.fromParts("package", requireContext().getPackageName(), null))));
        }
        View blocked = view.findViewById(R.id.btn_blocked_people);
        View qr = view.findViewById(R.id.btn_profile_qr);
        qr.setVisibility(AuthRepositoryProvider.isMock(requireContext()) ? View.GONE : View.VISIBLE);
        qr.setOnClickListener(v -> showMyQr());
        blocked.setVisibility(AuthRepositoryProvider.isMock(requireContext()) ? View.GONE : View.VISIBLE);
        blocked.setOnClickListener(v -> com.example.utechat.feature.safety.UserSafety.showBlockedAccounts(requireActivity()));
        privacy.setVisibility(View.GONE);
        notifications.setVisibility(View.GONE);
        view.findViewById(R.id.row_privacy).setOnClickListener(v ->
                privacy.setVisibility(privacy.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        view.findViewById(R.id.row_notifications).setOnClickListener(v ->
                notifications.setVisibility(notifications.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        view.findViewById(R.id.btn_profile_settings).setOnClickListener(v -> {
            privacy.setVisibility(View.VISIBLE);
            notifications.setVisibility(View.VISIBLE);
            privacy.requestFocus();
            privacy.getParent().requestChildFocus(privacy, privacy);
        });
        ivAvatar.setOnClickListener(v -> btnEditProfile.performClick());
        tvName.setOnClickListener(v -> btnEditProfile.performClick());
    }

    private void showMyQr() {
        User user = viewModel.getCurrentUser().getValue();
        if (user == null) user = AuthRepositoryProvider.get(requireContext()).getCurrentUser();
        if (user == null) return;
        String uid = user.getUid();
        android.graphics.Bitmap bitmap;
        try {
            bitmap = FriendQrImage.make(uid);
        } catch (com.google.zxing.WriterException | IllegalArgumentException error) {
            Toast.makeText(requireContext(), R.string.friend_qr_creation_error, Toast.LENGTH_SHORT).show();
            return;
        }
        View card = getLayoutInflater().inflate(R.layout.dialog_friend_qr, null);
        ((ImageView) card.findViewById(R.id.iv_friend_qr)).setImageBitmap(bitmap);
        ((TextView) card.findViewById(R.id.tv_friend_qr_name)).setText(user.getDisplayName());
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.friend_qr_mine).setView(card)
                .setNegativeButton(R.string.community_close, null)
                .setNeutralButton(R.string.friend_qr_save, (dialog, which) -> saveQrDocument.launch("UTEChat-QR.png"))
                .setPositiveButton(R.string.friend_qr_share, (dialog, which) -> shareQr(bitmap, uid))
                .show();
    }

    private void shareQr(android.graphics.Bitmap bitmap, String uid) {
        try {
            android.content.Context context = requireContext();
            java.io.File directory = new java.io.File(context.getCacheDir(), "friend-qr");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("QR cache unavailable");
            java.io.File accountDirectory = new java.io.File(directory, uid);
            if (!accountDirectory.isDirectory() && !accountDirectory.mkdirs()) throw new java.io.IOException("QR cache unavailable");
            java.io.File image = new java.io.File(accountDirectory, "UTEChat-QR.png");
            if (!image.isFile()) {
                java.io.File temporary = java.io.File.createTempFile("qr-", ".png", accountDirectory);
                try {
                    try (var output = new java.io.FileOutputStream(temporary)) {
                        if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) {
                            throw new java.io.IOException("QR export failed");
                        }
                    }
                    if (!temporary.renameTo(image)) throw new java.io.IOException("QR export failed");
                } finally {
                    temporary.delete();
                }
            }
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(context,
                    context.getPackageName() + ".friendqr", image);
            Intent share = new Intent(Intent.ACTION_SEND).setType("image/png")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setClipData(android.content.ClipData.newRawUri(getString(R.string.friend_qr_mine), uri));
            startActivity(Intent.createChooser(share, getString(R.string.friend_qr_share)));
        } catch (java.io.IOException | IllegalArgumentException error) {
            Toast.makeText(requireContext(), R.string.friend_qr_creation_error, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (viewModel != null) viewModel.refresh();
    }

    private void observeViewModel() {
        viewModel.getCurrentUser().observe(getViewLifecycleOwner(), user -> {
            if (user == null) return;
            tvName.setText(user.getDisplayName());
            tvEmail.setText(user.getEmail());
            tvBio.setText(user.getBio().isEmpty()
                    ? getString(R.string.profile_no_bio)
                    : user.getBio());
            String photo = user.getPhotoUrl();
            if (photo != null && photo.startsWith("https://")) {
                ivAvatar.setPadding(0, 0, 0, 0);
                ivAvatar.setImageTintList(null);
                com.bumptech.glide.Glide.with(ivAvatar).load(photo).circleCrop()
                        .placeholder(R.drawable.ic_nav_profile).error(R.drawable.ic_nav_profile).into(ivAvatar);
            } else {
                com.bumptech.glide.Glide.with(ivAvatar).clear(ivAvatar);
                int padding = Math.round(20 * getResources().getDisplayMetrics().density);
                ivAvatar.setPadding(padding, padding, padding, padding);
                ivAvatar.setImageResource(R.drawable.ic_nav_profile);
                ivAvatar.setImageTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE));
            }
        });
        viewModel.getProfileError().observe(getViewLifecycleOwner(), message -> {
            if (message != null && !message.isEmpty()) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void setupListeners() {
        switchPresence.setChecked(DevicePresenceManager.isVisible(requireContext()));
        boolean presenceAvailable = DevicePresenceManager.isAvailable(requireContext());
        switchPresence.setEnabled(presenceAvailable);
        tvPresenceSummary.setText(presenceAvailable ? R.string.presence_summary : R.string.presence_setup_needed);
        switchPresence.setOnCheckedChangeListener((button, checked) ->
                DevicePresenceManager.setVisible(requireContext(), checked));
        switchMessagePreview.setChecked(NotificationPreferences.showPreview(requireContext()));
        switchMessagePreview.setOnCheckedChangeListener((button, checked) ->
                NotificationPreferences.setShowPreview(requireContext(), checked));
        btnEditProfile.setOnClickListener(v -> {
            startActivity(new Intent(requireContext(), ProfileEditActivity.class));
        });

        btnLogout.setOnClickListener(v -> {
            btnLogout.setEnabled(false);
            AuthRepositoryProvider.get(requireContext()).signOut(() -> {
                if (!isAdded()) return;
                Intent intent = new Intent(requireContext(), LoginActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
            });
        });
    }
}
