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
        assumeTrue("caller".equals(role)||"receiver".equals(role));
        String media=InstrumentationRegistry.getArguments().getString("rtcMedia","audio");
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertNotNull("Sign into a test account first",FirebaseAuth.getInstance().getCurrentUser());
        ActivityScenario<?> scenario;
        if("receiver".equals(role))scenario=ActivityScenario.launch(MainActivity.class);
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
            long deadline=System.currentTimeMillis()+110_000;
            while(call==null && System.currentTimeMillis()<deadline){call=currentCall();Thread.sleep(200);}
            assertNotNull("Incoming call activity never opened",call);
            if("receiver".equals(role)) {
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
            Thread.sleep("caller".equals(role)?4000:6000);
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
}
