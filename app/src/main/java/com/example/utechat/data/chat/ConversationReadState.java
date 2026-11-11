package com.example.utechat.data.chat;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import com.example.utechat.domain.model.Conversation;
import com.example.utechat.domain.model.Message;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FieldPath;
import java.util.HashMap;
import java.util.Map;

/** Owner-specific server read cursor with a local cache for immediate/offline styling. */
public final class ConversationReadState {
    private final String uid;
    private final SharedPreferences local;
    private String submittedId;
    public ConversationReadState(Context context, String uid) {
        this.uid=uid;
        local=context.getSharedPreferences("conversation_reads_"+Uri.encode(uid==null?"signed_out":uid),Context.MODE_PRIVATE);
    }
    public void decorate(Conversation conversation, Object rawReadStates) {
        long readAt=0;String readId=null;
        if(rawReadStates instanceof Map) {
            Object state=((Map<?,?>)rawReadStates).get(uid);
            if(state instanceof Map) {
                Object time=((Map<?,?>)state).get("readAt"), id=((Map<?,?>)state).get("messageId");
                if(time instanceof Number)readAt=((Number)time).longValue();
                if(id instanceof String)readId=(String)id;
            }
        }
        String key=conversation.getId();long cachedAt=local.getLong(key+":at",0);
        String cachedId=local.getString(key+":id",null);
        if(cachedId!=null && (cachedAt>readAt || (cachedAt==readAt && cachedId.equals(conversation.getLastMessageId())))){
            readAt=cachedAt;readId=cachedId;
        }
        conversation.setUnread(UnreadState.isUnread(uid,conversation.getLastMessageSenderId(),conversation.getLastMessageId(),
                conversation.getLastMessageAt(),readId,readAt));
    }
    public void markRead(String conversationId, Message observed) {
        if(uid==null || conversationId==null || observed==null || observed.getId()==null || observed.getSentAt()<=0) return;
        long timestamp=observed.getSentAt();String messageId=observed.getId();
        if(timestamp>=local.getLong(conversationId+":at",0) && !messageId.equals(local.getString(conversationId+":id",null)))local.edit().putLong(conversationId+":at",timestamp)
                .putString(conversationId+":id",messageId).apply();
        if(messageId.equals(submittedId))return;
        submittedId=messageId;
        var ref=FirebaseFirestore.getInstance().collection("conversations").document(conversationId);
        FirebaseFirestore.getInstance().runTransaction(tx->{
            var parent=tx.get(ref);
            Object states=parent.get("readStates");
            Object previous=states instanceof Map?((Map<?,?>)states).get(uid):null;
            if(previous instanceof Map){
                Object at=((Map<?,?>)previous).get("readAt"),id=((Map<?,?>)previous).get("messageId");
                if(messageId.equals(id) || (at instanceof Number && ((Number)at).longValue()>timestamp))return null;
            }
            Map<String,Object> cursor=new HashMap<>();cursor.put("messageId",messageId);cursor.put("readAt",timestamp);
            tx.update(ref,FieldPath.of("readStates",uid),cursor);return null;
        }).addOnFailureListener(error->{submittedId=null;android.util.Log.w("UTEChatReadState","Read cursor sync failed",error);});
    }
}
