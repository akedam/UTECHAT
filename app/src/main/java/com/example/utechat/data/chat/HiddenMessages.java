package com.example.utechat.data.chat;

import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.Map;

public final class HiddenMessages {
    private HiddenMessages() { }

    public static CollectionReference forUser(String uid) {
        return FirebaseFirestore.getInstance().collection("users").document(uid).collection("hiddenMessages");
    }

    public static String key(String conversationId, String messageId) { return conversationId + ":" + messageId; }

    public static com.google.android.gms.tasks.Task<Void> hide(String uid, String conversationId, String messageId) {
        return forUser(uid).document(key(conversationId, messageId))
                .set(Map.of("conversationId", conversationId, "messageId", messageId));
    }
}
