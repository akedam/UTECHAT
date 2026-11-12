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

/** Opt-in only: uses the signed-in test account and leaves one test image in its existing direct chat. */
public final class BackendOnlineSmokeTest {
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
