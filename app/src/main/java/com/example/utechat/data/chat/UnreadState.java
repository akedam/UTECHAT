package com.example.utechat.data.chat;

/** Latest preview unread state, including different messages sharing a timestamp. */
public final class UnreadState {
    private UnreadState() { }
    public static boolean isUnread(String user, String sender, String messageId, long timestamp,
                                   String readId, long readAt) {
        if (user == null || sender == null || user.equals(sender) || messageId == null || timestamp <= 0) return false;
        if (messageId.equals(readId)) return false;
        return timestamp >= readAt;
    }
}
