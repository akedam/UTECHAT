package com.example.utechat.feature.notifications;

import com.example.utechat.data.auth.PhoneAccountClient;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.example.utechat.R;
import com.example.utechat.app.UteChatApplication;
import com.example.utechat.core.common.Constants;
import com.example.utechat.core.common.AppLanguage;
import com.example.utechat.data.notifications.PushTokenRepository;
import com.example.utechat.feature.main.ui.MainActivity;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.Map;

/** Handles data-only chat events. The trusted sender must not include a notification payload. */
public final class UTEChatMessagingService extends FirebaseMessagingService {
    @Override public void onNewToken(@NonNull String token) {
        PushTokenRepository.syncToken(getApplicationContext(), token);
    }

    @Override public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        Map<String, String> data = remoteMessage.getData();
        if (com.example.utechat.BuildConfig.DEBUG) android.util.Log.d("UTEChatPush", "FCM event=" + data.get("eventType")
                + ", recipientMatches=" + java.util.Objects.equals(FirebaseAuth.getInstance().getUid(),data.get("recipientUid")));
        if ("call".equals(data.get("eventType"))) {
            IncomingCallNotification.receive(this, data.get("callId"), data.get("recipientUid"));
            return;
        }
        String event = data.get("eventType");
        if ("moment".equals(event) || "moment-revoked".equals(event)) {
            receiveMoment(data.get("momentId"), data.get("recipientUid"), "moment-revoked".equals(event));
            return;
        }
        if (!"message".equals(data.get("eventType"))) return;
        String conversationId = data.get("conversationId");
        String messageId = data.get("messageId");
        String recipientUid = data.get("recipientUid");
        if (empty(conversationId) || empty(messageId) || conversationId.length() > 256
                || messageId.length() > 256 || empty(recipientUid)) return;
        FirebaseUser currentUser;
        try { currentUser = FirebaseAuth.getInstance().getCurrentUser(); }
        catch (RuntimeException unavailable) { return; }
        if (!PhoneAccountClient.hasVerifiedContact(currentUser) || !recipientUid.equals(currentUser.getUid())) return;
        if (new com.example.utechat.feature.chat.ui.ChatPreferences(this, conversationId).enabled("muted")) return;
        if (!NotificationPreferences.markEventSeen(this, conversationId + ":" + messageId)) return;
        if (UteChatApplication.isChatVisible(conversationId)) {
            playMessageSound();
            return;
        }

        boolean showPreview = NotificationPreferences.showPreview(this);
        String title = AppLanguage.text(this, R.string.notifications_generic_title);
        String body = AppLanguage.text(this, R.string.notifications_generic_body);
        if (showPreview) {
            String sender = data.get("senderName");
            String conversationTitle = data.get("conversationTitle");
            title = !empty(conversationTitle) ? conversationTitle
                    : !empty(sender) ? AppLanguage.text(this, R.string.notifications_new_message_title, limit(sender, 80)) : title;
            title = limit(title, 120);
            String messageType = data.get("messageType");
            if ("image".equals(messageType)) body = AppLanguage.text(this, R.string.conversation_preview_image);
            else if ("voice".equals(messageType)) body = AppLanguage.text(this, R.string.voice_message);
            else if (!empty(data.get("preview"))) body = limit(data.get("preview"), 300);
        }
        String type = "group".equals(data.get("conversationType")) ? "group" : "direct";
        postNotification(conversationId, title, body, type,
                limit(data.get("conversationTitle"), 80));
    }

    private void playMessageSound() {
        android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return;
        android.app.NotificationChannel channel = manager.getNotificationChannel(getString(R.string.notifications_channel_id));
        if (channel == null || channel.getImportance() < android.app.NotificationManager.IMPORTANCE_DEFAULT
                || channel.getSound() == null) return;
        android.media.Ringtone sound = android.media.RingtoneManager.getRingtone(this, channel.getSound());
        if (sound != null) {
            sound.setAudioAttributes(channel.getAudioAttributes());
            sound.play();
        }
    }

    private void receiveMoment(String id, String recipient, boolean revoked) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (id == null || !id.matches("[a-f0-9]{64}") || !PhoneAccountClient.hasVerifiedContact(user)
                || !user.getUid().equals(recipient)) return;
        if (revoked) {
            NotificationManagerCompat.from(this).cancel("utechat_moment_" + id, 0);
            com.example.utechat.feature.moments.MomentWidget.invalidate(this);
            NotificationPreferences.markEventSeen(this, "moment:" + id);
            return;
        }
        com.example.utechat.feature.moments.MomentWidget.refresh(this);
        if (!NotificationPreferences.markEventSeen(this, "moment:" + id)) return;
        Intent open = com.example.utechat.feature.moments.MomentsFragment.intent(this, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent tap = PendingIntent.getActivity(this, id.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder notification = new NotificationCompat.Builder(this, getString(R.string.notifications_channel_id))
                .setSmallIcon(R.drawable.ic_stat_utechat)
                .setColor(getColor(R.color.utechat_primary))
                .setContentTitle(AppLanguage.text(this, R.string.moments_notification_title))
                .setContentText(AppLanguage.text(this, R.string.moments_notification_body))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .setAutoCancel(true)
                .setContentIntent(tap);
        try {
            NotificationManagerCompat.from(this).notify("utechat_moment_" + id, 0, notification.build());
        } catch (SecurityException ignored) {
            // The widget refresh also works with notifications disabled.
        }
    }

    private void postNotification(String conversationId, String title, String body,
                                  String type, String navigationTitle) {
        Intent openChat = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("utechat://conversation/" + Uri.encode(conversationId)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(Constants.EXTRA_CONVERSATION_ID, conversationId)
                .putExtra(Constants.EXTRA_CONVERSATION_TITLE,
                        empty(navigationTitle) ? getString(R.string.conversation_unknown_title) : navigationTitle)
                .putExtra(Constants.EXTRA_CONVERSATION_TYPE, type == null ? "direct" : type);
        int requestCode = conversationId.hashCode();
        PendingIntent contentIntent = PendingIntent.getActivity(this, requestCode, openChat,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this,
                getString(R.string.notifications_channel_id))
                .setSmallIcon(R.drawable.ic_stat_utechat)
                .setColor(getColor(R.color.utechat_primary))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        try {
            NotificationManagerCompat.from(this)
                    .notify("utechat_conversation_" + conversationId, 0, builder.build());
        } catch (SecurityException ignored) {
            // Android 13+ can deny notification permission at runtime.
        }
    }

    private boolean empty(String value) { return value == null || value.trim().isEmpty(); }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength - 1).trim() + "…";
    }
}
