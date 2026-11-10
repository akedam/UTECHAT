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
            // TODO (G4): Load avatar với Glide từ user.getPhotoUrl()
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
