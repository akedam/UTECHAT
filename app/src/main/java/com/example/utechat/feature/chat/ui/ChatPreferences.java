package com.example.utechat.feature.chat.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import com.example.utechat.data.auth.AuthRepositoryProvider;
import com.example.utechat.domain.model.User;

/** Device-local appearance and notification choices, isolated by signed-in account. */
public final class ChatPreferences {
    private final SharedPreferences preferences;
    private final String prefix;

    public ChatPreferences(Context context, String conversationId) {
        User user = AuthRepositoryProvider.get(context).getCurrentUser();
        String account = user == null ? "signed_out" : user.getUid();
        preferences = context.getSharedPreferences("chat_ui_" + Uri.encode(account), Context.MODE_PRIVATE);
        prefix = Uri.encode(conversationId == null ? "" : conversationId) + ":";
    }
    public boolean enabled(String key) { return preferences.getBoolean(prefix + key, false); }
    public void setEnabled(String key, boolean value) { preferences.edit().putBoolean(prefix + key, value).apply(); }
    public String title(String fallback) { return preferences.getString(prefix + "nickname", fallback); }
    public void setTitle(String title) {
        if (title.trim().isEmpty()) preferences.edit().remove(prefix + "nickname").apply();
        else preferences.edit().putString(prefix + "nickname", title.trim()).apply();
    }
    public int wallpaper() { return preferences.getInt(prefix + "wallpaper", 0xFFE3E8EC); }
    public void setWallpaper(int color) { preferences.edit().putInt(prefix + "wallpaper", color).apply(); }
}
