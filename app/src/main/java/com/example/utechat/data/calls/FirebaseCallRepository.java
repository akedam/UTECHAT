package com.example.utechat.data.calls;

import android.content.Context;

import com.example.utechat.R;
import com.example.utechat.domain.model.CallSession;
import com.example.utechat.domain.repository.CallRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Firestore signaling only. RTC media is managed by AgoraCallController. */
public final class FirebaseCallRepository implements CallRepository {
    private static final long RING_TIMEOUT_MS = 45_000L;
    private final Context context;
    private final FirebaseFirestore db;
    private final Map<String, ListenerRegistration> callListeners = new ConcurrentHashMap<>();
    private ListenerRegistration incomingRegistration;

    public FirebaseCallRepository(Context context) {
        this.context = context.getApplicationContext();
        db = FirebaseFirestore.getInstance();
    }

    @Override public void createOutgoing(String conversationId, String mediaType, Callback callback) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || conversationId == null || conversationId.trim().isEmpty()
                || !("audio".equals(mediaType) || "video".equals(mediaType))) {
            callback.onError(context.getString(R.string.call_invalid_conversation)); return;
        }
        db.collection("conversations").document(conversationId).get()
                .addOnSuccessListener(parent -> {
                    Object members = parent.get("participantIds");
                    if (!parent.exists() || !"direct".equals(parent.getString("type"))
                            || !(members instanceof java.util.List)
                            || ((java.util.List<?>) members).size() != 2
                            || !((java.util.List<?>) members).contains(user.getUid())) {
                        callback.onError(context.getString(R.string.call_invalid_conversation)); return;
                    }
                    String peer = null;
                    for (Object member : (java.util.List<?>) members) {
                        if (member instanceof String && !user.getUid().equals(member)) peer = (String) member;
                    }
                    if (peer == null) { callback.onError(context.getString(R.string.call_invalid_conversation)); return; }
                    final String calleeUid = peer;
                    DocumentReference ref = db.collection("calls").document();
                    long now = System.currentTimeMillis();
                    Map<String, Object> data = new HashMap<>();
                    data.put("conversationId", conversationId);
                    data.put("callerUid", user.getUid());
                    data.put("calleeUid", calleeUid);
                    data.put("participants", Arrays.asList(user.getUid(), calleeUid));
                    data.put("channelName", ref.getId());
                    data.put("mediaType", mediaType);
                    data.put("status", "ringing");
                    data.put("createdAt", now);
                    data.put("updatedAt", now);
                    data.put("expiresAt", now + RING_TIMEOUT_MS);
                    ref.set(data).addOnSuccessListener(unused ->
                            callback.onSuccess(new CallSession(ref.getId(), conversationId, user.getUid(), calleeUid,
                                    ref.getId(), mediaType, "ringing", now, now + RING_TIMEOUT_MS)))
                            .addOnFailureListener(error -> callback.onError(context.getString(R.string.call_network_error)));
                }).addOnFailureListener(error -> callback.onError(context.getString(R.string.call_network_error)));
    }

    @Override public void listen(String callId, Listener listener) {
        removeListener(callId);
        ListenerRegistration registration = db.collection("calls").document(callId)
                .addSnapshotListener((snapshot, error) -> {
                    if (error != null) { listener.onChanged(null, context.getString(R.string.call_network_error)); return; }
                    CallSession call = snapshot == null || !snapshot.exists() ? null : map(snapshot);
                    listener.onChanged(call, null);
                });
        callListeners.put(callId, registration);
    }

    @Override public synchronized void listenIncoming(String uid, IncomingListener listener) {
        stopIncomingListener();
        incomingRegistration = db.collection("calls").whereArrayContains("participants", uid)
                .whereEqualTo("calleeUid", uid).whereEqualTo("status", "ringing")
                .orderBy("createdAt", Query.Direction.ASCENDING).limit(10)
                .addSnapshotListener((snapshot, error) -> {
                    if (error != null || snapshot == null) return;
                    for (DocumentSnapshot document : snapshot.getDocuments()) {
                        CallSession call = map(document);
                        if (call != null && call.expiresAt > System.currentTimeMillis()) listener.onIncoming(call);
                        else if (call != null) updateStatus(call.id, "missed", new SilentCallback());
                    }
                });
    }

    @Override public void updateStatus(String callId, String status, Callback callback) {
        if (!("accepted".equals(status) || "rejected".equals(status) || "busy".equals(status)
                || "ended".equals(status) || "failed".equals(status) || "missed".equals(status))) {
            callback.onError(context.getString(R.string.call_network_error)); return;
        }
        DocumentReference ref = db.collection("calls").document(callId);
        db.runTransaction(transaction -> {
            DocumentSnapshot doc = transaction.get(ref);
            String old = doc.getString("status");
            String uid = FirebaseAuth.getInstance().getUid();
            String caller = doc.getString("callerUid");
            String callee = doc.getString("calleeUid");
            if (!doc.exists() || uid == null || !(uid.equals(caller) || uid.equals(callee)) || !allowed(old, status, uid, caller, callee))
                throw new IllegalStateException("transition");
            Map<String, Object> update = new HashMap<>();
            update.put("status", status); update.put("updatedAt", System.currentTimeMillis());
            transaction.update(ref, update); return null;
        }).addOnSuccessListener(unused -> callback.onSuccess(null))
                .addOnFailureListener(error -> callback.onError(context.getString(R.string.call_network_error)));
    }

    private boolean allowed(String old, String next, String uid, String caller, String callee) {
        if ("ringing".equals(old)) return (uid.equals(callee) && ("accepted".equals(next) || "rejected".equals(next) || "busy".equals(next) || "missed".equals(next)))
                || (uid.equals(caller) && ("ended".equals(next) || "failed".equals(next)));
        return "accepted".equals(old) && ("ended".equals(next) || "failed".equals(next));
    }

    private CallSession map(DocumentSnapshot doc) {
        try {
            String conversation = doc.getString("conversationId"), caller = doc.getString("callerUid");
            String callee = doc.getString("calleeUid"), channel = doc.getString("channelName");
            String media = doc.getString("mediaType"), status = doc.getString("status");
            Long created = doc.getLong("createdAt"), expires = doc.getLong("expiresAt");
            if (conversation == null || caller == null || callee == null || channel == null || media == null || status == null) return null;
            return new CallSession(doc.getId(), conversation, caller, callee, channel, media, status,
                    created == null ? 0 : created, expires == null ? 0 : expires);
        } catch (RuntimeException malformed) { return null; }
    }

    @Override public void removeListener(String callId) { ListenerRegistration r = callListeners.remove(callId); if (r != null) r.remove(); }
    @Override public synchronized void stopIncomingListener() { if (incomingRegistration != null) incomingRegistration.remove(); incomingRegistration = null; }
    private static final class SilentCallback implements Callback {
        @Override public void onSuccess(CallSession call) { }
        @Override public void onError(String safeMessage) { }
    }
}
