package com.example.utechat.feature.main.ui;

import android.os.Bundle;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.example.utechat.R;
import com.example.utechat.core.common.AuthGate;
import com.example.utechat.core.common.Constants;
import com.example.utechat.feature.notifications.NotificationPreferences;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;
import com.example.utechat.feature.conversations.ui.ConversationsFragment;
import com.example.utechat.feature.friends.ui.FriendsFragment;
import com.example.utechat.feature.profile.ui.ProfileFragment;
import com.example.utechat.feature.discover.ui.DiscoverFragment;

/**
 * Activity chứa Bottom Navigation và 3 Fragment chính:
 * - Tab 0: Hội thoại (ConversationsFragment)
 * - Tab 1: Bạn bè (FriendsFragment)
 * - Tab 2: Hồ sơ (ProfileFragment)
 *
 * MainActivity không chứa logic nghiệp vụ — chỉ điều phối Fragment.
 */
public class MainActivity extends AppCompatActivity {

    private final ActivityResultLauncher<String> notificationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) Toast.makeText(this, R.string.notifications_permission_denied, Toast.LENGTH_LONG).show();
            });

    private LinearLayout bottomNav;
    private int selectedTabId = R.id.nav_conversations;

    // Giữ instance fragment để không tạo lại khi switch tab
    private ConversationsFragment conversationsFragment;
    private FriendsFragment friendsFragment;
    private ProfileFragment profileFragment;
    private DiscoverFragment discoverFragment;
    private Fragment activeFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (AuthGate.redirectIfSignedOut(this)) return;
        setContentView(R.layout.activity_main);

        bottomNav = findViewById(R.id.bottom_nav);
        setupFragments();
        setupBottomNav();
        if (savedInstanceState == null) openNotificationConversation(getIntent());
        explainAndRequestNotifications();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openNotificationConversation(intent);
    }

    private void openNotificationConversation(Intent intent) {
        if (intent == null || !intent.hasExtra(Constants.EXTRA_CONVERSATION_ID)) return;
        String conversationId = intent.getStringExtra(Constants.EXTRA_CONVERSATION_ID);
        if (conversationId == null || conversationId.trim().isEmpty()) return;
        Intent chat = new Intent(this, com.example.utechat.feature.chat.ui.ChatActivity.class);
        chat.putExtra(Constants.EXTRA_CONVERSATION_ID, conversationId);
        chat.putExtra(Constants.EXTRA_CONVERSATION_TITLE, intent.getStringExtra(Constants.EXTRA_CONVERSATION_TITLE));
        chat.putExtra(Constants.EXTRA_CONVERSATION_TYPE, intent.getStringExtra(Constants.EXTRA_CONVERSATION_TYPE));
        intent.removeExtra(Constants.EXTRA_CONVERSATION_ID);
        intent.removeExtra(Constants.EXTRA_CONVERSATION_TITLE);
        intent.removeExtra(Constants.EXTRA_CONVERSATION_TYPE);
        startActivity(chat);
    }

    private void explainAndRequestNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                || NotificationPreferences.wasPermissionPrompted(this)) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.notifications_permission_title)
                .setMessage(R.string.notifications_permission_message)
                .setPositiveButton(R.string.notifications_permission_allow, (dialog, which) -> {
                    NotificationPreferences.setPermissionPrompted(this);
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
                })
                .setNegativeButton(R.string.notifications_permission_skip, (dialog, which) ->
                        NotificationPreferences.setPermissionPrompted(this))
                .show();
    }

    private void setupFragments() {
        FragmentManager manager = getSupportFragmentManager();
        conversationsFragment = findOrCreate(manager, "conversations", ConversationsFragment.class);
        friendsFragment = findOrCreate(manager, "friends", FriendsFragment.class);
        profileFragment = findOrCreate(manager, "profile", ProfileFragment.class);
        discoverFragment = findOrCreate(manager, "discover", DiscoverFragment.class);

        for (Fragment fragment : new Fragment[]{conversationsFragment, friendsFragment, profileFragment, discoverFragment}) {
            if (fragment.isAdded() && !fragment.isHidden()) { activeFragment = fragment; break; }
        }
        if (activeFragment == null) activeFragment = conversationsFragment;

        androidx.fragment.app.FragmentTransaction transaction = manager.beginTransaction();
        addIfNeeded(transaction, conversationsFragment, "conversations");
        addIfNeeded(transaction, friendsFragment, "friends");
        addIfNeeded(transaction, profileFragment, "profile");
        addIfNeeded(transaction, discoverFragment, "discover");
        transaction.commitNow();
        if (!activeFragment.isVisible()) manager.beginTransaction().show(activeFragment).commitNow();
    }

    private <T extends Fragment> T findOrCreate(FragmentManager manager, String tag, Class<T> type) {
        Fragment restored = manager.findFragmentByTag(tag);
        if (type.isInstance(restored)) return type.cast(restored);
        try { return type.getDeclaredConstructor().newInstance(); }
        catch (ReflectiveOperationException impossible) { throw new IllegalStateException(impossible); }
    }

    private void addIfNeeded(androidx.fragment.app.FragmentTransaction transaction, Fragment fragment, String tag) {
        if (!fragment.isAdded()) transaction.add(R.id.fragment_container, fragment, tag);
        if (fragment != activeFragment) transaction.hide(fragment);
    }

    private void setupBottomNav() {
        int[] tabIds = {R.id.nav_conversations, R.id.nav_friends, R.id.nav_discover, R.id.nav_profile};
        for (int tabId : tabIds) {
            View tab = bottomNav.findViewById(tabId);
            tab.setOnClickListener(view -> showTab(tabId));
        }
        int selected = activeFragment == friendsFragment ? R.id.nav_friends
                : activeFragment == profileFragment ? R.id.nav_profile
                : activeFragment == discoverFragment ? R.id.nav_discover : R.id.nav_conversations;
        showTab(selected);
    }

    private void showTab(int tabId) {
        Fragment target = tabId == R.id.nav_friends ? friendsFragment
                : tabId == R.id.nav_discover ? discoverFragment
                : tabId == R.id.nav_profile ? profileFragment : conversationsFragment;
        if (activeFragment != target) {
            getSupportFragmentManager().beginTransaction()
                    .hide(activeFragment)
                    .show(target)
                    .commit();
            activeFragment = target;
        }
        selectedTabId = tabId;
        int[] tabIds = {R.id.nav_conversations, R.id.nav_friends, R.id.nav_discover, R.id.nav_profile};
        for (int id : tabIds) bottomNav.findViewById(id).setSelected(id == tabId);
    }

    @Override
    public void onBackPressed() {
        if (selectedTabId != R.id.nav_conversations) {
            showTab(R.id.nav_conversations);
        } else {
            super.onBackPressed();
        }
    }
}
