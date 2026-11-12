package com.example.utechat.data.backend;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.example.utechat.R;
import com.example.utechat.feature.calls.ui.CallActivity;
import com.example.utechat.feature.main.ui.MainActivity;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;
import org.junit.Test;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Run receiver and caller concurrently on two signed-in emulator accounts. Never enabled in normal CI. */
public final class TwoDeviceCallSmokeTest {
    @Test public void foregroundCallConnectsBothPeers() throws Exception {
        String role=InstrumentationRegistry.getArguments().getString("rtcRole");
        assumeTrue("caller".equals(role)||"receiver".equals(role)||"receiver_background".equals(role));
        boolean receiver=role.startsWith("receiver");
        String media=InstrumentationRegistry.getArguments().getString("rtcMedia","audio");
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertNotNull("Sign into a test account first",FirebaseAuth.getInstance().getCurrentUser());
        ActivityScenario<?> scenario;
        if(receiver)scenario=ActivityScenario.launch(MainActivity.class);
        else {
            String uid=FirebaseAuth.getInstance().getUid(),conversation=null;
            for(DocumentSnapshot doc:Tasks.await(FirebaseFirestore.getInstance().collection("conversations")
                    .whereArrayContains("participantIds",uid).limit(20).get(Source.SERVER),20,TimeUnit.SECONDS).getDocuments())
                if("direct".equals(doc.getString("type"))){conversation=doc.getId();break;}
            assertNotNull("A direct test chat is required",conversation);
            scenario=ActivityScenario.launch(CallActivity.outgoingIntent(context,conversation,"RTC test",media));
        }
        CallActivity call=null;
        try {
            long deadline=System.currentTimeMillis()+180_000;
            if("receiver_background".equals(role)) {
                android.app.UiAutomation automation=InstrumentationRegistry.getInstrumentation().getUiAutomation();
                automation.injectInputEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_HOME),true);
                automation.injectInputEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_HOME),true);
                android.app.PendingIntent open=null;
                android.app.NotificationManager manager=context.getSystemService(android.app.NotificationManager.class);
                manager.cancelAll();
                while(open==null&&System.currentTimeMillis()<deadline) {
                    for(android.service.notification.StatusBarNotification notification:manager.getActiveNotifications())
                        if(notification.getTag()!=null&&notification.getTag().startsWith("incoming_call_"))open=notification.getNotification().contentIntent;
                    if(open==null)Thread.sleep(200);
                }
                assertNotNull("FCM must post an incoming call notification while backgrounded",open);
                // Exercise the actual notification's route; System UI layout varies by Android version.
                // The test harness supplies the same background-start permission as a System UI tap.
                automation.adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND");
                try {
                    android.app.ActivityOptions options=android.app.ActivityOptions.makeBasic();
                    if(android.os.Build.VERSION.SDK_INT>=34)options.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    open.send(context,0,null,null,null,null,options.toBundle());
                } finally {automation.dropShellPermissionIdentity();}
            }
            while(call==null && System.currentTimeMillis()<deadline){call=currentCall();Thread.sleep(200);}
            assertNotNull("Incoming call activity never opened",call);
            if(receiver) {
                CallActivity incoming=call;
                InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                    View accept=incoming.findViewById(R.id.btn_accept_call);
                    assertEquals("Incoming answer control must be visible",View.VISIBLE,accept.getVisibility());accept.performClick();
                });
            }
            String status="";
            while(System.currentTimeMillis()<deadline){
                status=status(call);
                if("Đã kết nối".equals(status))break;
                assertFalse("Call ended before RTC connected: "+status,"closed".equals(status));
                Thread.sleep(200);
            }
            assertEquals("Both Agora peers must join the same channel", "Đã kết nối",status);
            if ("video".equals(media)) {
                long videoDeadline=System.currentTimeMillis()+20_000;
                while(!videoDecoded(call) && System.currentTimeMillis()<videoDeadline) Thread.sleep(200);
                assertTrue("Remote camera frames must be decoded, not just channel membership",videoDecoded(call));
            }
            android.graphics.Bitmap screen=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            if(screen!=null)try(java.io.FileOutputStream output=new java.io.FileOutputStream(new java.io.File(
                    context.getExternalFilesDir(null),"rtc-"+media+"-"+role+".png"))){
                screen.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);screen.recycle();
            }
            Thread.sleep("caller".equals(role)?6000:10000);
        } finally {
            CallActivity active=call;
            if(active!=null)InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                if(!active.isFinishing()&&!active.isDestroyed())active.findViewById(R.id.btn_end_call).performClick();
            });
            scenario.close();
        }
    }
    private CallActivity currentCall(){
        AtomicReference<CallActivity> result=new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            for(Activity activity:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                if(activity instanceof CallActivity)result.set((CallActivity)activity);
        });return result.get();
    }
    private String status(CallActivity call){
        AtomicReference<String> result=new AtomicReference<>("closed");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            if(!call.isDestroyed()&&!call.isFinishing())result.set(((TextView)call.findViewById(R.id.tv_call_status)).getText().toString());
        });return result.get();
    }
    private boolean videoDecoded(CallActivity call){
        java.util.concurrent.atomic.AtomicBoolean decoded=new java.util.concurrent.atomic.AtomicBoolean();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            if(!call.isFinishing()&&!call.isDestroyed())decoded.set(
                    call.findViewById(R.id.remote_video_container).getVisibility()==View.VISIBLE
                    && call.findViewById(R.id.audio_peer_state).getVisibility()==View.GONE);
        });return decoded.get();
    }
}
