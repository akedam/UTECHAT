package com.example.utechat.feature.notifications;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import com.example.utechat.R;
import com.example.utechat.app.UteChatApplication;
import com.example.utechat.domain.model.CallSession;
import com.example.utechat.feature.calls.ui.CallActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;
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
        if(user==null || !user.getUid().equals(recipientUid))return;
        FirebaseFirestore.getInstance().collection("calls").document(callId).get(Source.SERVER).addOnSuccessListener(doc -> {
            FirebaseUser current=FirebaseAuth.getInstance().getCurrentUser();
            if(current==null || !current.getUid().equals(recipientUid) || !doc.exists()
                    || !recipientUid.equals(doc.getString("calleeUid")) || !"ringing".equals(doc.getString("status")))return;
            Long expires=doc.getLong("expiresAt"),created=doc.getLong("createdAt");
            String conversation=doc.getString("conversationId"),caller=doc.getString("callerUid");
            if(expires==null || expires<=System.currentTimeMillis() || created==null || conversation==null || caller==null)return;
            CallSession call=new CallSession(callId,conversation,caller,recipientUid,callId,
                    "video".equals(doc.getString("mediaType"))?"video":"audio","ringing",created,expires);
            UteChatApplication application=UteChatApplication.from(app);
            if(application!=null && application.isForeground()){application.deliverIncomingCall(call);return;}
            showVerified(app,call);
        });
    }
    public static void showVerified(Context app,CallSession call) {
            if(call.expiresAt<=System.currentTimeMillis())return;
            FirebaseUser user=FirebaseAuth.getInstance().getCurrentUser();
            if(user==null || !user.getUid().equals(call.calleeUid))return;
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
