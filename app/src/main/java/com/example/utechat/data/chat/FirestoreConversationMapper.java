package com.example.utechat.data.chat;

import com.example.utechat.domain.model.Conversation;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentSnapshot;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** Chuyển snapshot hiện tại/cũ sang model miền mà không giả định mọi trường đã tồn tại. */
public final class FirestoreConversationMapper {
    private FirestoreConversationMapper() {}

    public static Conversation fromSnapshot(DocumentSnapshot snapshot, String currentUserId) {
        Map<String, Object> data = snapshot.getData();
        Conversation conversation = new Conversation();
        conversation.setId(snapshot.getId());

        String type = string(data, "type");
        conversation.setType(Conversation.Type.fromSchemaValue(type));
        conversation.setParticipantIds(stringList(data == null ? null : data.get("participantIds")));
        conversation.setCreatedBy(string(data, "createdBy"));

        Map<String, Object> summaries = map(data == null ? null : data.get("participantSummaries"));
        String title = string(data, "title"); // legacy cached title
        String photoUrl = string(data, "photoUrl"); // legacy cached avatar
        if (conversation.isGroup()) {
            String groupName = string(data, "groupName");
            String groupPhoto = string(data, "groupPhotoUrl");
            conversation.setTitle(nonBlank(groupName) ? groupName : title);
            conversation.setPhotoUrl(nonBlank(groupPhoto) ? groupPhoto : photoUrl);
        } else {
            Map<String, Object> peer = peerSummary(summaries, currentUserId);
            String peerName = string(peer, "displayName");
            String peerPhoto = string(peer, "photoUrl");
            conversation.setTitle(nonBlank(title) ? title : peerName);
            conversation.setPhotoUrl(nonBlank(photoUrl) ? photoUrl : peerPhoto);
        }

        conversation.setLastMessage(string(data, "lastMessage"));
        conversation.setLastMessageId(string(data, "lastMessageId"));
        conversation.setLastMessageSenderId(string(data, "lastMessageSenderId"));
        conversation.setLastMessageType(string(data, "lastMessageType"));
        long createdAt = epochMillis(data == null ? null : data.get("createdAt"));
        long updatedAt = epochMillis(data == null ? null : data.get("updatedAt"));
        long lastMessageAt = epochMillis(data == null ? null : data.get("lastMessageAt"));
        if (lastMessageAt == 0) lastMessageAt = updatedAt > 0 ? updatedAt : createdAt;
        conversation.setCreatedAt(createdAt);
        conversation.setUpdatedAt(updatedAt > 0 ? updatedAt : lastMessageAt);
        conversation.setLastMessageAt(lastMessageAt);
        return conversation;
    }

    private static Map<String, Object> peerSummary(Map<String, Object> summaries, String currentUserId) {
        if (summaries == null) return null;
        for (Map.Entry<String, Object> entry : summaries.entrySet()) {
            if (currentUserId != null && currentUserId.equals(entry.getKey())) continue;
            return map(entry.getValue());
        }
        return null;
    }

    private static String string(Map<String, Object> data, String key) {
        if (data == null) return null;
        Object value = data.get(key);
        return value instanceof String ? (String) value : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List)) return null;
        List<String> result = new ArrayList<>();
        for (Object item : (List<?>) value) if (item instanceof String) result.add((String) item);
        return result;
    }

    private static long epochMillis(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof Timestamp) return ((Timestamp) value).toDate().getTime();
        if (value instanceof Date) return ((Date) value).getTime();
        return 0L;
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
