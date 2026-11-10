package com.example.utechat.core.common;

import android.app.Activity;
import android.content.Intent;
import com.example.utechat.data.auth.AuthRepositoryProvider;
import com.example.utechat.feature.auth.ui.LoginActivity;

/** Local navigation guard for signed-in-only activities. Backend authorization remains mandatory. */
public final class AuthGate {
    private AuthGate() { }

    public static boolean redirectIfSignedOut(Activity activity) {
        if (AuthRepositoryProvider.get(activity).getCurrentUser() != null) return false;
        Intent intent = new Intent(activity, LoginActivity.class);
        Intent original = activity.getIntent();
        if (original != null && original.hasExtra(Constants.EXTRA_CONVERSATION_ID)) {
            intent.putExtra(Constants.EXTRA_CONVERSATION_ID, original.getStringExtra(Constants.EXTRA_CONVERSATION_ID));
            intent.putExtra(Constants.EXTRA_CONVERSATION_TITLE, original.getStringExtra(Constants.EXTRA_CONVERSATION_TITLE));
            intent.putExtra(Constants.EXTRA_CONVERSATION_TYPE, original.getStringExtra(Constants.EXTRA_CONVERSATION_TYPE));
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
        activity.finish();
        return true;
    }
}
