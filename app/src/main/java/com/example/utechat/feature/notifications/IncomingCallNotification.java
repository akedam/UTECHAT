package com.example.utechat.feature.notifications;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import com.example.utechat.R;
import com.example.utechat.domain.model.CallSession;
import com.example.utechat.feature.calls.ui.CallActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/** Verify the live call before showing a tappable incoming-call notification. Never auto-launch from background. */
public final class IncomingCallNotification {
    private static final String CHANNEL="incoming_calls";
    private IncomingCallNotification() { }
    public static void receive(Context context,String callId,String recipientUid) {
        if(callId==null || !callId.matches("[A-Za-z0-9_-]{1,128}"))return;
        Context app=context.getApplicationContext();
        FirebaseUser user=FirebaseAuth.getInstance().getCurrentUser();
        if(user==null || !user.isEmailVerified() || !user.getUid().equals(recipientUid))return;
        androidx.work.OneTimeWorkRequest.Builder work=new androidx.work.OneTimeWorkRequest.Builder(IncomingCallWorker.class)
                .setInputData(new androidx.work.Data.Builder().putString("callId",callId).putString("recipientUid",recipientUid)
                        .putLong("receivedAt",System.currentTimeMillis()).build())
                .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR,10,java.util.concurrent.TimeUnit.SECONDS);
        // Before Android 12 expedited work needs a separate foreground notification.
        // A normal job keeps the verified-call notification as the only notification.
        if(android.os.Build.VERSION.SDK_INT>=31)work.setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST);
        androidx.work.WorkManager.getInstance(app).enqueueUniqueWork("incoming-call-"+recipientUid+"-"+callId,
                androidx.work.ExistingWorkPolicy.KEEP,work.build());
    }
    public static void showVerified(Context app,CallSession call) {
            if(call.expiresAt<=System.currentTimeMillis())return;
            FirebaseUser user=FirebaseAuth.getInstance().getCurrentUser();
            if(user==null || !user.isEmailVerified() || !user.getUid().equals(call.calleeUid))return;
            String callId=call.id;
            if(!NotificationPreferences.markEventSeen(app,"call:"+call.calleeUid+":"+callId))return;
            NotificationManager manager=app.getSystemService(NotificationManager.class);
            if(manager==null)return;
            manager.createNotificationChannel(new NotificationChannel(CHANNEL,app.getString(R.string.call_notification_channel),NotificationManager.IMPORTANCE_HIGH));
            Intent intent=CallActivity.incomingIntent(app,call).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent open=PendingIntent.getActivity(app,callId.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder notification=new NotificationCompat.Builder(app,CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_utechat).setColor(app.getColor(R.color.utechat_primary))
                    .setContentTitle(app.getString("video".equals(call.mediaType)?R.string.call_incoming_video:R.string.call_incoming_audio))
                    .setContentText(app.getString(R.string.call_open_incoming)).setContentIntent(open)
                    .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true)
                    .setTimeoutAfter(Math.max(1,call.expiresAt-System.currentTimeMillis()));
            try {NotificationManagerCompat.from(app).notify("incoming_call_"+callId,0,notification.build());}
            catch(SecurityException ignored){ }
    }
    public static void cancel(Context context,String id) {
        if(id!=null)NotificationManagerCompat.from(context).cancel("incoming_call_"+id,0);
    }
}
