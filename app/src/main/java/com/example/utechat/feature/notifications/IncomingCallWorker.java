package com.example.utechat.feature.notifications;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.example.utechat.app.UteChatApplication;
import com.example.utechat.domain.model.CallSession;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;
import java.util.concurrent.TimeUnit;

/** Keeps live-call verification inside an Android-managed job after FCM returns. */
public final class IncomingCallWorker extends Worker {
    public IncomingCallWorker(@NonNull Context context,@NonNull WorkerParameters parameters){super(context,parameters);}
    @NonNull @Override public Result doWork(){
        String callId=getInputData().getString("callId"),recipient=getInputData().getString("recipientUid");
        long received=getInputData().getLong("receivedAt",0);
        FirebaseUser user=FirebaseAuth.getInstance().getCurrentUser();
        if(user==null||!user.isEmailVerified()||!user.getUid().equals(recipient)||callId==null||!callId.matches("[A-Za-z0-9_-]{1,128}")
                ||System.currentTimeMillis()-received>45_000)return Result.success();
        try{
            DocumentSnapshot doc=Tasks.await(FirebaseFirestore.getInstance().collection("calls").document(callId).get(Source.SERVER),15,TimeUnit.SECONDS);
            FirebaseUser current=FirebaseAuth.getInstance().getCurrentUser();
            if(current==null||!current.isEmailVerified()||!current.getUid().equals(recipient)||!doc.exists()||!recipient.equals(doc.getString("calleeUid"))
                    ||!"ringing".equals(doc.getString("status")))return Result.success();
            Long expires=doc.getLong("expiresAt"),created=doc.getLong("createdAt");
            String conversation=doc.getString("conversationId"),caller=doc.getString("callerUid");
            if(expires==null||expires<=System.currentTimeMillis()||created==null||conversation==null||caller==null)return Result.success();
            CallSession call=new CallSession(callId,conversation,caller,recipient,callId,
                    "video".equals(doc.getString("mediaType"))?"video":"audio","ringing",created,expires);
            Context app=getApplicationContext();UteChatApplication application=UteChatApplication.from(app);
            if(application!=null&&application.isForeground())new android.os.Handler(android.os.Looper.getMainLooper()).post(()->application.deliverIncomingCall(call));
            else IncomingCallNotification.showVerified(app,call);
            if(com.example.utechat.BuildConfig.DEBUG)android.util.Log.d("UTEChatPush","Verified incoming call notification");
            return Result.success();
        }catch(Exception error){
            if(error instanceof InterruptedException)Thread.currentThread().interrupt();
            if(com.example.utechat.BuildConfig.DEBUG)android.util.Log.w("UTEChatPush","Call verification deferred: "+error.getClass().getSimpleName());
            return getRunAttemptCount()<2&&System.currentTimeMillis()-received<25_000?Result.retry():Result.success();
        }
    }
}
