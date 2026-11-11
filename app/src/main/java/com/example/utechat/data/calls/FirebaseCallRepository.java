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
    private long incomingGeneration;

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
        long generation = incomingGeneration;
        incomingRegistration = db.collection("calls").whereArrayContains("participants", uid)
                .whereEqualTo("calleeUid", uid).whereEqualTo("status", "ringing")
                .orderBy("createdAt", Query.Direction.ASCENDING).limit(10)
                .addSnapshotListener((snapshot, error) -> {
                    if (generation != incomingGeneration) return;
                    if (error != null) {
                        android.util.Log.w("UTEChatCalls", "Incoming query failed: " + error.getCode());
                        if (error.getCode() == com.google.firebase.firestore.FirebaseFirestoreException.Code.FAILED_PRECONDITION)
                            listenIncomingWithoutIndex(uid, listener, generation);
                        return;
                    }
                    deliverIncoming(snapshot, uid, listener);
                });
    }

    // Single-field array queries are also covered by the existing participant read rule.
    // Use this only while the production composite index is absent/building; never
    // limit before filtering, since older finished calls could hide a ringing call.
    private synchronized void listenIncomingWithoutIndex(String uid, IncomingListener listener, long generation) {
        if (generation != incomingGeneration) return;
        if (incomingRegistration != null) incomingRegistration.remove();
        incomingRegistration = db.collection("calls").whereArrayContains("participants", uid)
                .addSnapshotListener((snapshot, error) -> {
                    if (generation != incomingGeneration) return;
                    if (error != null) {
                        android.util.Log.e("UTEChatCalls", "Incoming fallback failed: " + error.getCode());
                        return;
                    }
                    deliverIncoming(snapshot, uid, listener);
                });
    }

    private void deliverIncoming(com.google.firebase.firestore.QuerySnapshot snapshot, String uid, IncomingListener listener) {
        if (snapshot == null) return;
        for (DocumentSnapshot document : snapshot.getDocuments()) {
            CallSession call = map(document);
            if (call == null || !uid.equals(call.calleeUid) || !"ringing".equals(call.status)) continue;
            if (call.expiresAt > System.currentTimeMillis()) listener.onIncoming(call);
            else updateStatus(call.id, "missed", new SilentCallback());
        }
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
            if (!doc.exists() || uid == null || !(uid.equals(caller) || uid.equals(callee)))
                throw new IllegalStateException("participant");
            // Both peers may finish at once, or retry after an acknowledgement is lost.
            // The first terminal transaction owns the single deterministic history row.
            if (CallHistory.isTerminal(old)) return null;
            if (!allowed(old, status, uid, caller, callee))
                throw new IllegalStateException("transition");
            String conversationId = doc.getString("conversationId");
            DocumentReference conversation = db.collection("conversations").document(conversationId);
            DocumentSnapshot parent = transaction.get(conversation);
            DocumentSnapshot profile = transaction.get(db.collection("publicProfiles").document(uid));
            long now = System.currentTimeMillis();
            Long previousUpdate = doc.getLong("updatedAt");
            long timestamp = Math.max(now, previousUpdate == null ? 0 : previousUpdate);
            Long lastMessageAt = parent.getLong("lastMessageAt");
            timestamp = Math.max(timestamp, lastMessageAt == null ? 0 : lastMessageAt);
            Map<String, Object> update = new HashMap<>();
            update.put("status", status); update.put("updatedAt", timestamp);
            if (CallHistory.isTerminal(status)) {
                Long expiry = doc.getLong("expiresAt");
                String content = CallHistory.text(doc.getString("mediaType"), old, status,
                        expiry != null && now >= expiry);
                String messageId = "call_" + callId;
                DocumentReference message = conversation.collection("messages").document(messageId);
                Map<String, Object> history = new HashMap<>();
                history.put("senderId", uid);
                history.put("senderName", profile.getString("displayName"));
                history.put("type", "text"); history.put("content", content);
                history.put("sentAt", timestamp); history.put("createdAt", timestamp);
                history.put("readBy", java.util.Collections.singletonList(uid));
                Map<String, Object> summary = new HashMap<>();
                summary.put("lastMessage", content); summary.put("lastMessageType", "text");
                summary.put("lastMessageAt", timestamp); summary.put("lastMessageId", messageId);
                summary.put("lastMessageSenderId", uid); summary.put("updatedAt", timestamp);
                transaction.set(message, history);
                transaction.update(conversation, summary);
            }
            transaction.update(ref, update); return null;
        }).addOnSuccessListener(unused -> callback.onSuccess(null))
                .addOnFailureListener(error -> {
                    android.util.Log.e("UTEChatCalls", "Call state/history transaction failed", error);
                    // A concurrent peer can commit first. Rules may reject this
                    // client's now-stale history writer before the SDK retries.
                    // Verify both durable records rather than report a false failure.
                    ref.get(com.google.firebase.firestore.Source.SERVER).addOnSuccessListener(ended -> {
                        String conversation = ended.getString("conversationId");
                        if (!CallHistory.isTerminal(ended.getString("status")) || conversation == null) {
                            callback.onError(context.getString(R.string.call_network_error)); return;
                        }
                        db.collection("conversations").document(conversation).collection("messages")
                                .document("call_" + callId).get(com.google.firebase.firestore.Source.SERVER)
                                .addOnSuccessListener(history -> {
                                    if (history.exists()) callback.onSuccess(null);
                                    else callback.onError(context.getString(R.string.call_network_error));
                                }).addOnFailureListener(failed -> callback.onError(context.getString(R.string.call_network_error)));
                    }).addOnFailureListener(failed -> callback.onError(context.getString(R.string.call_network_error)));
                });
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
    @Override public synchronized void stopIncomingListener() { incomingGeneration++; if (incomingRegistration != null) incomingRegistration.remove(); incomingRegistration = null; }
    private static final class SilentCallback implements Callback {
        @Override public void onSuccess(CallSession call) { }
        @Override public void onError(String safeMessage) { }
    }
}
