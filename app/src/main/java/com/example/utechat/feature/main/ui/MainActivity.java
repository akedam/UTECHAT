package com.example.utechat.feature.main.ui;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.example.utechat.R;
import com.example.utechat.core.common.AuthGate;
import com.example.utechat.feature.conversations.ui.ConversationsFragment;
import com.example.utechat.feature.friends.ui.FriendsFragment;
import com.example.utechat.feature.profile.ui.ProfileFragment;
import com.example.utechat.feature.discover.ui.DiscoverFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * Activity chứa Bottom Navigation và 3 Fragment chính:
 * - Tab 0: Hội thoại (ConversationsFragment)
 * - Tab 1: Bạn bè (FriendsFragment)
 * - Tab 2: Hồ sơ (ProfileFragment)
 *
 * MainActivity không chứa logic nghiệp vụ — chỉ điều phối Fragment.
 */
public class MainActivity extends AppCompatActivity {

    private BottomNavigationView bottomNav;

    // Giữ instance fragment để không tạo lại khi switch tab
    private final ConversationsFragment conversationsFragment = new ConversationsFragment();
    private final FriendsFragment friendsFragment = new FriendsFragment();
    private final ProfileFragment profileFragment = new ProfileFragment();
    private final DiscoverFragment discoverFragment = new DiscoverFragment();

    private Fragment activeFragment = conversationsFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (AuthGate.redirectIfSignedOut(this)) return;
        setContentView(R.layout.activity_main);

        bottomNav = findViewById(R.id.bottom_nav);
        setupFragments();
        setupBottomNav();
    }

    private void setupFragments() {
        getSupportFragmentManager().beginTransaction()
                .add(R.id.fragment_container, profileFragment, "profile").hide(profileFragment)
                .add(R.id.fragment_container, discoverFragment, "discover").hide(discoverFragment)
                .add(R.id.fragment_container, friendsFragment, "friends").hide(friendsFragment)
                .add(R.id.fragment_container, conversationsFragment, "conversations")
                .commit();
    }

    private void setupBottomNav() {
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_conversations) {
                showFragment(conversationsFragment);
            } else if (id == R.id.nav_friends) {
                showFragment(friendsFragment);
            } else if (id == R.id.nav_profile) {
                showFragment(profileFragment);
            } else if (id == R.id.nav_discover) {
                showFragment(discoverFragment);
            }
            return true;
        });
        // Hiển thị tab mặc định
        bottomNav.setSelectedItemId(R.id.nav_conversations);
    }

    private void showFragment(Fragment fragment) {
        if (activeFragment == fragment) return;
        getSupportFragmentManager().beginTransaction()
                .hide(activeFragment)
                .show(fragment)
                .commit();
        activeFragment = fragment;
    }

    @Override
    public void onBackPressed() {
        if (bottomNav.getSelectedItemId() != R.id.nav_conversations) {
            bottomNav.setSelectedItemId(R.id.nav_conversations);
        } else {
            super.onBackPressed();
        }
    }
}
