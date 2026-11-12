package com.example.utechat.data.chat;

import android.content.Context;

import com.example.utechat.R;
import com.example.utechat.domain.model.Conversation;
import com.example.utechat.domain.model.ConversationPage;
import com.example.utechat.domain.repository.ConversationRepository;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.List;

/** Paged Firestore source for a user's participant conversations. */
public final class FirebaseConversationRepository implements ConversationRepository {
    private final FirebaseFirestore firestore;
    private final Context context;

    public FirebaseConversationRepository(Context context) {
        this.context = context.getApplicationContext();
        this.firestore = FirebaseFirestore.getInstance();
    }

    @Override
    public Subscription listenFirstPage(String userId, int pageSize, Callback callback) {
        if(userId==null){callback.onError(context.getString(R.string.auth_not_signed_in));return ()->{};}
        int size=Math.max(1,Math.min(pageSize,50));
        var registration=firestore.collection("conversations").whereArrayContains("participantIds",userId)
                .orderBy("lastMessageAt",Query.Direction.DESCENDING).limit(size+1L)
                .addSnapshotListener((snapshot,error)->{
                    if(error!=null)callback.onError(context.getString(R.string.conversations_load_error));
                    else if(snapshot!=null)callback.onSuccess(toPage(snapshot,size,userId));
                });
        return registration::remove;
    }

    @Override
    public void loadPage(String userId, String cursor, int pageSize, Callback callback) {
        if (userId == null || userId.trim().isEmpty()) {
            callback.onError(context.getString(R.string.auth_not_signed_in));
            return;
        }
        int safePageSize = Math.max(1, Math.min(pageSize, 50));
        Query base = firestore.collection("conversations")
                .whereArrayContains("participantIds", userId)
                .orderBy("lastMessageAt", Query.Direction.DESCENDING)
                .limit(safePageSize + 1);

        if (cursor == null || cursor.isEmpty()) {
            fetch(base, safePageSize, userId, callback);
            return;
        }

        firestore.collection("conversations").document(cursor).get()
                .addOnSuccessListener(cursorSnapshot -> {
                    if (!cursorSnapshot.exists()
                            || !containsUser(cursorSnapshot, userId)) {
                        callback.onError(context.getString(R.string.conversations_cursor_error));
                        return;
                    }
                    fetch(base.startAfter(cursorSnapshot), safePageSize, userId, callback);
                })
                .addOnFailureListener(error -> callback.onError(context.getString(R.string.conversations_load_error)));
    }

    private void fetch(Query query, int pageSize, String userId, Callback callback) {
        query.get().addOnSuccessListener(snapshot -> callback.onSuccess(toPage(snapshot, pageSize, userId)))
                .addOnFailureListener(error -> callback.onError(context.getString(R.string.conversations_load_error)));
    }

    private ConversationPage toPage(QuerySnapshot snapshot, int pageSize, String userId) {
        List<Conversation> items = new ArrayList<>();
        List<DocumentSnapshot> documents = snapshot.getDocuments();
        boolean hasMore = documents.size() > pageSize;
        int visibleCount = Math.min(pageSize, documents.size());
        for (int i = 0; i < visibleCount; i++) {
            DocumentSnapshot document = documents.get(i);
            if ("deleting".equals(document.getString("lifecycle"))) continue;
            try {
                Conversation item=FirestoreConversationMapper.fromSnapshot(document, userId);
                new ConversationReadState(context,userId).decorate(item,document.get("readStates"));
                items.add(item);
            } catch (RuntimeException malformedRecord) {
                // Ignore an incompatible legacy document; keep remaining valid conversations visible.
            }
        }
        String nextCursor = hasMore && visibleCount > 0
                ? documents.get(visibleCount - 1).getId() : null;
        return new ConversationPage(items, nextCursor);
    }

    private boolean containsUser(DocumentSnapshot snapshot, String userId) {
        Object value = snapshot.get("participantIds");
        return value instanceof List && ((List<?>) value).contains(userId);
    }

    @Override
    public boolean isMock() { return false; }
}
