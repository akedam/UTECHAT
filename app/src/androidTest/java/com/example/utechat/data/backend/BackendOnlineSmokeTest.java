package com.example.utechat.data.backend;

import android.content.Context;
import android.graphics.Bitmap;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.utechat.data.chat.FirebaseMessageRepository;
import com.example.utechat.domain.model.Message;
import com.example.utechat.domain.repository.MessageRepository;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;
import org.json.JSONObject;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in network tests: use signed-in test accounts and leave real images/call history in their existing chat. */
public final class BackendOnlineSmokeTest {
    @Test public void pushRegistrationMatchesCurrentAccount() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("pushRouting")));
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String uid=FirebaseAuth.getInstance().getUid();assertNotNull(uid);
        com.google.firebase.messaging.FirebaseMessaging messaging=com.google.firebase.messaging.FirebaseMessaging.getInstance();
        if("true".equals(InstrumentationRegistry.getArguments().getString("resetPush")))Tasks.await(messaging.deleteToken(),30,TimeUnit.SECONDS);
        String token=Tasks.await(messaging.getToken(),30,TimeUnit.SECONDS);
        com.example.utechat.data.notifications.PushTokenRepository.syncToken(context,token);
        java.security.MessageDigest sha=java.security.MessageDigest.getInstance("SHA-256");
        StringBuilder hash=new StringBuilder();for(byte b:sha.digest(token.getBytes("UTF-8")))hash.append(String.format(java.util.Locale.ROOT,"%02x",b));
        Thread.sleep(1500);
        java.util.Map<String,Object> data=new java.util.HashMap<>();data.put("token",token);data.put("platform","android");data.put("updatedAt",System.currentTimeMillis());
        Tasks.await(FirebaseFirestore.getInstance().collection("users").document(uid).collection("deviceTokens")
                .document(hash.toString()).set(data),20,TimeUnit.SECONDS);
        StringBuilder account=new StringBuilder();for(byte b:sha.digest(uid.getBytes("UTF-8")))account.append(String.format(java.util.Locale.ROOT,"%02x",b));
        android.util.Log.d("UTEChatPush","Routing check account="+account.substring(0,8)+", device="+hash.substring(0,8));
    }
    @Test public void unansweredAudioAndVideoLeaveOneHistoryCardEach() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("missedCalls")));
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String uid=FirebaseAuth.getInstance().getUid();assertNotNull(uid);
        FirebaseFirestore db=FirebaseFirestore.getInstance();String conversation=null;
        for(DocumentSnapshot doc:Tasks.await(db.collection("conversations").whereArrayContains("participantIds",uid)
                .limit(20).get(Source.SERVER),20,TimeUnit.SECONDS).getDocuments())
            if("direct".equals(doc.getString("type"))){conversation=doc.getId();break;}
        assertNotNull(conversation);
        com.example.utechat.data.calls.FirebaseCallRepository repository=new com.example.utechat.data.calls.FirebaseCallRepository(context);
        java.util.List<com.example.utechat.domain.model.CallSession> calls=new java.util.ArrayList<>();
        for(String media:new String[]{"audio","video"}){
            CountDownLatch created=new CountDownLatch(1);AtomicReference<com.example.utechat.domain.model.CallSession> result=new AtomicReference<>();
            repository.createOutgoing(conversation,media,new com.example.utechat.domain.repository.CallRepository.Callback(){
                @Override public void onSuccess(com.example.utechat.domain.model.CallSession call){result.set(call);created.countDown();}
                @Override public void onError(String error){created.countDown();}
            });
            assertTrue(created.await(20,TimeUnit.SECONDS));assertNotNull(result.get());calls.add(result.get());
        }
        // No CallActivity/timer is running: expiry and history must come from the deployed server.
        Thread.sleep(50_000);
        for(com.example.utechat.domain.model.CallSession call:calls){
            DocumentSnapshot ended=Tasks.await(db.collection("calls").document(call.id).get(Source.SERVER),20,TimeUnit.SECONDS);
            assertEquals("Server must reconcile unanswered calls","missed",ended.getString("status"));
            DocumentSnapshot history=Tasks.await(db.collection("conversations").document(conversation).collection("messages")
                    .document("call_"+call.id).get(Source.SERVER),20,TimeUnit.SECONDS);
            assertTrue(history.exists());assertEquals(uid,history.getString("senderId"));
            assertEquals("video".equals(call.mediaType)?"📹 Cuộc gọi video nhỡ":"📞 Cuộc gọi thoại nhỡ",history.getString("content"));
            assertEquals(java.util.Collections.singletonList(uid),history.get("readBy"));
            CountDownLatch retried=new CountDownLatch(1);
            repository.expireRinging(call.id,new com.example.utechat.domain.repository.CallRepository.Callback(){
                @Override public void onSuccess(com.example.utechat.domain.model.CallSession ignored){retried.countDown();}
                @Override public void onError(String error){fail(error);}
            });
            assertTrue(retried.await(20,TimeUnit.SECONDS));
            DocumentSnapshot unchanged=Tasks.await(history.getReference().get(Source.SERVER),20,TimeUnit.SECONDS);
            assertEquals("Retry must preserve the original history card",history.getData(),unchanged.getData());
        }
    }
    @Test public void backendWarmupCallbackCompletes() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("backendHealth")));
        CountDownLatch completed=new CountDownLatch(1);java.util.concurrent.atomic.AtomicBoolean ready=new java.util.concurrent.atomic.AtomicBoolean();
        BackendClient.warmUp(new BackendClient.ReadyCallback(){
            @Override public void onReady(){ready.set(true);completed.countDown();}
            @Override public void onError(){completed.countDown();}
        });
        assertTrue("Backend warmup callback timed out",completed.await(110,TimeUnit.SECONDS));
        assertTrue("Backend warmup failed",ready.get());
    }
    @Test public void privateImageAndNotificationRoundTrip() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("backendOnline")));
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        FirebaseUser user=FirebaseAuth.getInstance().getCurrentUser();assertNotNull("Sign into a tester account first",user);
        String uid=user.getUid();FirebaseFirestore db=FirebaseFirestore.getInstance();
        DocumentSnapshot conversation=null;
        for(DocumentSnapshot candidate:Tasks.await(db.collection("conversations").whereArrayContains("participantIds",uid)
                .limit(20).get(Source.SERVER),30,TimeUnit.SECONDS).getDocuments())
            if("direct".equals(candidate.getString("type"))){conversation=candidate;break;}
        assertNotNull("Create a direct tester chat first",conversation);
        String conversationId=conversation.getId();
        String messageId=db.collection("conversations").document(conversationId).collection("messages").document().getId();
        String path="conversations/"+conversationId+"/"+uid+"/"+messageId;
        Bitmap bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff008cff);
        ByteArrayOutputStream encoded=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,encoded);bitmap.recycle();
        byte[] bytes=encoded.toByteArray();String route="/api/media?path="+URLEncoder.encode(path,"UTF-8");
        BackendClient.Response uploaded=BackendClient.request("PUT",route,bytes,"image/png",true);
        assertEquals("Authenticated upload",200,uploaded.status);assertEquals(path,uploaded.json().getString("path"));
        assertEquals("Anonymous access must fail",401,BackendClient.request("GET",route,null,null,false).status);
        DocumentSnapshot profile=Tasks.await(db.collection("publicProfiles").document(uid).get(Source.SERVER),20,TimeUnit.SECONDS);
        Message image=new Message();image.setId(messageId);image.setSenderId(uid);image.setSenderName(profile.getString("displayName"));
        image.setType(Message.Type.IMAGE);image.setContent("");image.setMediaUrl(path);image.setContentType("image/png");image.setFileSize(bytes.length);
        CountDownLatch sent=new CountDownLatch(1);AtomicReference<String> failure=new AtomicReference<>();
        new FirebaseMessageRepository(context).sendImage(conversationId,image,new MessageRepository.SendCallback(){
            @Override public void onSuccess(){sent.countDown();}
            @Override public void onError(String error){failure.set(error);sent.countDown();}
        });
        assertTrue("Firestore image commit timed out",sent.await(30,TimeUnit.SECONDS));assertNull(failure.get());
        assertArrayEquals(bytes,BackendClient.request("GET",route,null,null,true).body);
        JSONObject event=new JSONObject().put("conversationId",conversationId).put("messageId",messageId);
        assertEquals("Trusted notification sender",200,BackendClient.request("POST","/api/message-notify",event.toString().getBytes("UTF-8"),"application/json",true).status);
    }
}
