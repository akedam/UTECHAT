package com.example.utechat.feature.calls.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.utechat.R;

/** Keeps an accepted, active media session alive while its call screen is backgrounded. */
public final class CallForegroundService extends Service {
    public static final String ACTION_START = "com.example.utechat.calls.START";
    public static final String ACTION_STOP = "com.example.utechat.calls.STOP";
    public static final String EXTRA_VIDEO = "video";
    private static final String CHANNEL_ID = "active_call";
    private static final int NOTIFICATION_ID = 9107;

    public static Intent startIntent(android.content.Context context, boolean video) {
        return new Intent(context, CallForegroundService.class).setAction(ACTION_START).putExtra(EXTRA_VIDEO, video);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY; }
        ensureChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_utechat)
                .setContentTitle(getString(R.string.call_in_call_notification))
                .setContentText(getString(R.string.call_state_connected))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            if (intent != null && intent.getBooleanExtra(EXTRA_VIDEO, false)) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            startForeground(NOTIFICATION_ID, notification, type);
        } else startForeground(NOTIFICATION_ID, notification);
        return START_NOT_STICKY;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                getString(R.string.call_notification_channel), NotificationManager.IMPORTANCE_LOW));
    }
    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
