package com.example.utechat.core.common;

import android.app.Activity;
import android.content.Intent;
import com.example.utechat.data.auth.AuthRepositoryProvider;
import com.example.utechat.feature.auth.ui.LoginActivity;
import com.example.utechat.feature.auth.ui.PhoneAccountActivity;
import com.example.utechat.feature.main.ui.MainActivity;

/** Local navigation guard for signed-in-only activities. Backend authorization remains mandatory. */
public final class AuthGate {
    private AuthGate() { }

    public static boolean redirectIfSignedOut(Activity activity) {
        var repository = AuthRepositoryProvider.get(activity);
        if (repository.getCurrentUser() != null) return false;
        openAfterAuth(activity);
        return true;
    }

    public static void openAfterAuth(Activity activity) {
        var repository = AuthRepositoryProvider.get(activity);
        Class<?> screen = repository.getCurrentUser() != null ? MainActivity.class : loginScreen(activity);
        replaceTask(activity, screen);
    }

    public static Class<?> loginScreen(Activity activity) {
        return AuthRepositoryProvider.isMock(activity) ? LoginActivity.class : PhoneAccountActivity.class;
    }

    public static void replaceTask(Activity activity, Class<?> screen) {
        Intent intent = new Intent(activity, screen);
        copyNotificationTarget(activity.getIntent(), intent);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
        activity.finish();
    }

    public static void copyNotificationTarget(Intent source, Intent target) {
        String moment = com.example.utechat.feature.moments.MomentsFragment.EXTRA_ID;
        if (source != null && source.hasExtra(moment)) target.putExtra(moment, source.getStringExtra(moment));
        if (source == null || !source.hasExtra(Constants.EXTRA_CONVERSATION_ID)) return;
        target.putExtra(Constants.EXTRA_CONVERSATION_ID, source.getStringExtra(Constants.EXTRA_CONVERSATION_ID));
        target.putExtra(Constants.EXTRA_CONVERSATION_TITLE, source.getStringExtra(Constants.EXTRA_CONVERSATION_TITLE));
        target.putExtra(Constants.EXTRA_CONVERSATION_TYPE, source.getStringExtra(Constants.EXTRA_CONVERSATION_TYPE));
    }
}
