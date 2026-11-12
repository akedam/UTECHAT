package com.example.utechat.data.chat;

import android.content.Context;

import com.example.utechat.R;
import com.example.utechat.domain.model.Message;
import com.example.utechat.domain.model.MessagePage;
import com.example.utechat.domain.repository.MessageRepository;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.Source;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Firebase source for idempotent text messages in direct and group conversations. */
public final class FirebaseMessageRepository implements MessageRepository {
    private final FirebaseFirestore firestore;
    private final Context context;

    public FirebaseMessageRepository(Context context) {
        this.context = context.getApplicationContext();
        firestore = FirebaseFirestore.getInstance();
    }

    @Override
    public Subscription listenToLatest(String conversationId, String currentUserId,
                                       String currentUserName, String peerName, int pageSize,
                                       Listener listener) {
        ListenerRegistration registration = messages(conversationId)
                .orderBy("sentAt", Query.Direction.ASCENDING)
                .limitToLast(Math.max(1, Math.min(pageSize, 50)))
                .addSnapshotListener(com.google.firebase.firestore.MetadataChanges.INCLUDE,
                        (snapshot, error) -> {
                            if (error != null) {
                                listener.onError(context.getString(R.string.chat_load_error));
                                return;
                            }
                            if (snapshot != null) listener.onMessages(map(snapshot, currentUserId, currentUserName,
                                    peerName, true));
                        });
        return registration::remove;
    }

    @Override
    public void loadOlder(String conversationId, String beforeMessageId, String currentUserId,
                          String currentUserName, String peerName, int pageSize, PageCallback callback) {
        if (beforeMessageId == null || beforeMessageId.isEmpty()) {
            callback.onSuccess(new MessagePage(new ArrayList<>(), false));
            return;
        }
        int safeSize = Math.max(1, Math.min(pageSize, 50));
        messages(conversationId).document(beforeMessageId).get()
                .addOnSuccessListener(cursor -> {
                    if (!cursor.exists()) {
                        callback.onError(context.getString(R.string.chat_history_error));
                        return;
                    }
                    messages(conversationId).orderBy("sentAt", Query.Direction.DESCENDING)
                            .startAfter(cursor).limit(safeSize + 1L).get()
                            .addOnSuccessListener(snapshot -> {
                                List<DocumentSnapshot> documents = snapshot.getDocuments();
                                boolean hasMore = documents.size() > safeSize;
                                int count = Math.min(safeSize, documents.size());
                                List<Message> older = new ArrayList<>();
                                for (int i = count - 1; i >= 0; i--) {
                                    try {
                                        older.add(map(documents.get(i), currentUserId,
                                                currentUserName, peerName, false));
                                    } catch (RuntimeException ignored) {
                                        // Skip malformed historical rows instead of crashing the conversation.
                                    }
                                }
                                callback.onSuccess(new MessagePage(older, hasMore));
                            })
                            .addOnFailureListener(error -> callback.onError(
                                    context.getString(R.string.chat_history_error)));
                })
                .addOnFailureListener(error -> callback.onError(context.getString(R.string.chat_history_error)));
    }

    @Override
    public void sendText(String conversationId, Message message, SendCallback callback) {
        sendMessage(conversationId, message, false, false, callback);
    }

    @Override
    public void sendImage(String conversationId, Message message, SendCallback callback) {
        sendMessage(conversationId, message, true, false, callback);
    }

    @Override public void sendPoll(String conversationId, Message message, SendCallback callback) {
        sendMessage(conversationId, message, false, true, callback);
    }

    private void sendMessage(String conversationId, Message message, boolean image, boolean poll, SendCallback callback) {
        if (conversationId == null || conversationId.isEmpty() || message.getId() == null
                || message.getId().isEmpty() || message.getSenderId() == null
                || message.getContent() == null
                || (!image && message.getContent().trim().isEmpty())
                || (poll && !validPoll(message))
                || (image && (message.getMediaUrl() == null || message.getContentType() == null
                    || message.getFileSize() <= 0 || message.getFileSize() > 8L * 1024L * 1024L))) {
            callback.onError(context.getString(R.string.chat_send_error));
            return;
        }

        long sentAt = message.getSentAt() > 0 ? message.getSentAt() : System.currentTimeMillis();
        message.setSentAt(sentAt);
        message.setCreatedAt(sentAt);
        Map<String, Object> payload = new HashMap<>();
        payload.put("senderId", message.getSenderId());
        payload.put("senderName", message.getSenderName() == null ? "" : message.getSenderName());
        payload.put("type", image ? "image" : poll ? "poll" : "text");
        payload.put("content", message.getContent());
        payload.put("sentAt", sentAt);
        payload.put("createdAt", sentAt);
        payload.put("readBy", Arrays.asList(message.getSenderId()));
        if (image) {
            payload.put("mediaPath", message.getMediaUrl());
            payload.put("contentType", message.getContentType());
            payload.put("fileSize", message.getFileSize());
        } else if (poll) {
            payload.put("pollQuestion", message.getContent());
            payload.put("pollOptions", new ArrayList<>(message.getPollOptions()));
            payload.put("pollVotes", new HashMap<String, Integer>());
        }

        String collectionId = "conversations";
        var conversationRef = firestore.collection(collectionId).document(conversationId);
        var messageRef = conversationRef.collection("messages").document(message.getId());
        Map<String, Object> summary = new HashMap<>();
        summary.put("lastMessage", image ? "image" : message.getContent());
        summary.put("lastMessageType", image ? "image" : poll ? "poll" : "text");
        summary.put("lastMessageAt", sentAt);
        summary.put("lastMessageSenderId", message.getSenderId());
        summary.put("lastMessageId", message.getId());
        summary.put("updatedAt", sentAt);

        com.google.firebase.firestore.WriteBatch batch = firestore.batch();
        batch.set(messageRef, payload);
        batch.set(conversationRef, summary, SetOptions.merge());
        batch.commit().addOnSuccessListener(ignored -> {
                    com.example.utechat.data.backend.BackendEvents.message(context, conversationId, message.getId());
                    callback.onSuccess();
                })
                .addOnFailureListener(error -> verifyIdempotentWrite(messageRef, message, image, poll, callback));
    }

    private boolean validPoll(Message message) {
        List<String> options = message.getPollOptions();
        if (message.getContent().trim().isEmpty() || message.getContent().trim().length() > 200
                || options == null || options.size() < 2 || options.size() > 6) return false;
        java.util.Set<String> unique = new java.util.HashSet<>();
        for (String option : options) {
            if (option == null || option.trim().isEmpty() || option.trim().length() > 80
                    || !unique.add(option.trim())) return false;
        }
        return true;
    }

    @Override public void votePoll(String conversationId, String messageId, String voterUid,
                                   int optionIndex, SendCallback callback) {
        if (conversationId == null || messageId == null || voterUid == null || optionIndex < 0) {
            callback.onError(context.getString(R.string.poll_vote_error)); return;
        }
        var reference = messages(conversationId).document(messageId);
        firestore.runTransaction(transaction -> {
            DocumentSnapshot snapshot = transaction.get(reference);
            Object rawOptions = snapshot.get("pollOptions");
            if (!snapshot.exists() || !"poll".equals(snapshot.getString("type"))
                    || !(rawOptions instanceof List) || optionIndex >= ((List<?>) rawOptions).size()) {
                throw new IllegalStateException("invalid_poll");
            }
            Object rawVotes = snapshot.get("pollVotes");
            Map<String, Object> votes = new HashMap<>();
            if (rawVotes instanceof Map) votes.putAll((Map<String, Object>) rawVotes);
            votes.put(voterUid, optionIndex);
            transaction.update(reference, "pollVotes", votes);
            return null;
        }).addOnSuccessListener(unused -> callback.onSuccess())
                .addOnFailureListener(error -> callback.onError(context.getString(R.string.poll_vote_error)));
    }

    private void verifyIdempotentWrite(com.google.firebase.firestore.DocumentReference reference,
                                       Message message, boolean image, boolean poll, SendCallback callback) {
        reference.get(Source.SERVER).addOnSuccessListener(snapshot -> {
            if (snapshot.exists()
                    && message.getSenderId().equals(snapshot.getString("senderId"))
                    && (image ? "image".equals(snapshot.getString("type"))
                                && message.getMediaUrl().equals(snapshot.getString("mediaPath"))
                                : poll ? "poll".equals(snapshot.getString("type"))
                                    && message.getContent().equals(snapshot.getString("pollQuestion"))
                                    && message.getPollOptions().equals(snapshot.get("pollOptions"))
                                : "text".equals(snapshot.getString("type"))
                                    && message.getContent().equals(snapshot.getString("content")))) {
                com.example.utechat.data.backend.BackendEvents.message(context,
                        reference.getParent().getParent().getId(), message.getId());
                callback.onSuccess();
            } else {
                callback.onError(context.getString(R.string.chat_send_error));
            }
        }).addOnFailureListener(error -> callback.onError(context.getString(R.string.chat_send_error)));
    }

    private com.google.firebase.firestore.CollectionReference messages(String conversationId) {
        return firestore.collection("conversations").document(conversationId).collection("messages");
    }

    private List<Message> map(QuerySnapshot snapshot, String currentUserId, String currentUserName,
                              String peerName, boolean includePending) {
        List<Message> result = new ArrayList<>();
        for (DocumentSnapshot document : snapshot.getDocuments()) {
            try {
                result.add(map(document, currentUserId, currentUserName, peerName, includePending));
            } catch (RuntimeException ignored) {
                // A bad/old message must not hide valid messages in this page.
            }
        }
        return result;
    }

    private Message map(DocumentSnapshot document, String currentUserId, String currentUserName,
                        String peerName, boolean includePending) {
        Message message = new Message();
        message.setId(document.getId());
        message.setSenderId(document.getString("senderId"));
        message.setType(Message.Type.fromSchemaValue(document.getString("type")));
        String content = document.getString("content");
        if (message.getType() == Message.Type.POLL) {
            String question = document.getString("pollQuestion");
            message.setContent(question == null ? (content == null ? "" : content) : question);
            message.setPollOptions(stringList(document.get("pollOptions")));
            message.setPollVotes(intMap(document.get("pollVotes")));
        } else {
            message.setContent(content == null ? "" : content);
        }
        message.setMediaUrl(document.getString("mediaPath"));
        message.setContentType(document.getString("contentType"));
        Object size = document.get("fileSize");
        message.setFileSize(size instanceof Number ? ((Number) size).longValue() : 0L);
        message.setSentAt(epochMillis(document.get("sentAt")));
        message.setCreatedAt(epochMillis(document.get("createdAt")));
        message.setUpdatedAt(epochMillis(document.get("updatedAt")));
        message.setReadBy(stringList(document.get("readBy")));
        boolean fromMe = currentUserId != null && currentUserId.equals(message.getSenderId());
        String storedSenderName = document.getString("senderName");
        message.setSenderName(storedSenderName == null || storedSenderName.trim().isEmpty()
                ? (fromMe ? currentUserName : peerName) : storedSenderName);
        message.setDeliveryStatus(includePending && document.getMetadata().hasPendingWrites()
                ? Message.DeliveryStatus.SENDING : Message.DeliveryStatus.SENT);
        return message;
    }

    private long epochMillis(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof com.google.firebase.Timestamp) {
            return ((com.google.firebase.Timestamp) value).toDate().getTime();
        }
        return value instanceof Date ? ((Date) value).getTime() : 0L;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List)) return null;
        List<String> result = new ArrayList<>();
        for (Object entry : (List<?>) value) if (entry instanceof String) result.add((String) entry);
        return result;
    }

    private Map<String, Integer> intMap(Object value) {
        if (!(value instanceof Map)) return new HashMap<>();
        Map<String, Integer> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (entry.getKey() instanceof String && entry.getValue() instanceof Number) {
                result.put((String) entry.getKey(), ((Number) entry.getValue()).intValue());
            }
        }
        return result;
    }

    @Override
    public boolean isMock() { return false; }
}
