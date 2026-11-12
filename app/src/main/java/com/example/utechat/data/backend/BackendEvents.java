package com.example.utechat.data.backend;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Small per-account outbox. A failed push never turns a committed chat message into a failed send. */
public final class BackendEvents {
    private static final ScheduledExecutorService IO=Executors.newScheduledThreadPool(2);
    private static final Set<String> ACTIVE=new HashSet<>();
    private BackendEvents() { }
    public static void message(Context context,String conversationId,String messageId) {
        enqueue(context,"message",conversationId,messageId);
    }
    public static void call(Context context,String callId) { enqueue(context,"call","",callId); }
    private static void enqueue(Context context,String kind,String conversationId,String id) {
        if(!BackendClient.configured()) return;
        FirebaseUser user=FirebaseAuth.getInstance().getCurrentUser(); if(user==null)return;
        try {
            JSONObject event=new JSONObject().put("kind",kind).put("conversationId",conversationId).put("id",id)
                    .put("createdAt",System.currentTimeMillis());
            String key=kind+":"+conversationId+":"+id;
            SharedPreferences prefs=prefs(context,user.getUid());
            // Bound the outbox when a tester has no network for a long time.
            if(prefs.getAll().size()>=200 && !prefs.contains(key)) return;
            prefs.edit().putString(key,event.toString()).apply();
            dispatch(context.getApplicationContext(),user.getUid(),key,0);
        } catch(Exception ignored) { }
    }
    public static void flush(Context context) {
        FirebaseUser user=FirebaseAuth.getInstance().getCurrentUser(); if(user==null || !BackendClient.configured())return;
        for(String key:prefs(context,user.getUid()).getAll().keySet()) dispatch(context.getApplicationContext(),user.getUid(),key,0);
    }
    private static SharedPreferences prefs(Context context,String uid) {
        return context.getSharedPreferences("backend_outbox_"+uid,Context.MODE_PRIVATE);
    }
    private static void dispatch(Context context,String uid,String key,int attempt) {
        String activeKey=uid+":"+key;
        synchronized(ACTIVE) { if(!ACTIVE.add(activeKey))return; }
        IO.execute(() -> {
            boolean retry=false;
            SharedPreferences prefs=prefs(context,uid);
            try {
                FirebaseUser user=FirebaseAuth.getInstance().getCurrentUser();
                if(user==null || !uid.equals(user.getUid()))return;
                String saved=prefs.getString(key,null); if(saved==null)return;
                JSONObject event=new JSONObject(saved);
                boolean call="call".equals(event.getString("kind"));
                long lifetime=call?45_000L:24*60*60_000L;
                if(System.currentTimeMillis()-event.getLong("createdAt")>lifetime) {prefs.edit().remove(key).apply();return;}
                JSONObject body=new JSONObject();
                if(call)body.put("callId",event.getString("id"));
                else body.put("conversationId",event.getString("conversationId")).put("messageId",event.getString("id"));
                BackendClient.Response result=BackendClient.request("POST",call?"/api/call-invite":"/api/message-notify",
                        body.toString().getBytes(StandardCharsets.UTF_8),"application/json",true);
                if(result.ok() || result.status==400 || result.status==403 || result.status==404 || result.status==409)
                    prefs.edit().remove(key).apply();
                else retry=true;
            } catch(Exception error) {retry=true;}
            finally {
                synchronized(ACTIVE) {ACTIVE.remove(activeKey);}
                if(retry && attempt<3)IO.schedule(() -> dispatch(context,uid,key,attempt+1),5L*(attempt+1),TimeUnit.SECONDS);
            }
        });
    }
}
