package com.example.utechat.data.chat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.example.utechat.R;
import com.example.utechat.domain.model.Message;
import com.example.utechat.domain.model.MessagePage;
import com.example.utechat.domain.repository.MessageRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-memory demonstration chat. It never writes to Firebase or survives process restart. */
public final class MockMessageRepository implements MessageRepository {
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, List<Message>> conversations = new HashMap<>();
    private final Map<String, List<Listener>> listeners = new HashMap<>();

    public MockMessageRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public synchronized Subscription listenToLatest(String conversationId, String currentUserId,
                                                    String currentUserName, String peerName, int pageSize,
                                                    Listener listener) {
        ensureSamples(conversationId, currentUserId, currentUserName, peerName);
        List<Listener> conversationListeners = listeners.computeIfAbsent(conversationId, ignored -> new ArrayList<>());
        conversationListeners.add(listener);
        emit(conversationId, listener, pageSize);
        return () -> {
            synchronized (MockMessageRepository.this) {
                List<Listener> existing = listeners.get(conversationId);
                if (existing != null) existing.remove(listener);
            }
        };
    }

    @Override
    public synchronized void loadOlder(String conversationId, String beforeMessageId, String currentUserId,
                                       String currentUserName, String peerName, int pageSize,
                                       PageCallback callback) {
        mainHandler.post(() -> callback.onSuccess(new MessagePage(new ArrayList<>(), false)));
    }

    @Override
    public synchronized void sendText(String conversationId, Message message, SendCallback callback) {
        ensureSamples(conversationId, message.getSenderId(), message.getSenderName(),
                context.getString(R.string.mock_peer_name));
        List<Message> list = conversations.get(conversationId);
        for (Message existing : list) {
            if (existing.getId().equals(message.getId())) {
                callback.onSuccess();
                return;
            }
        }
        message.setType(Message.Type.TEXT);
        message.setDeliveryStatus(Message.DeliveryStatus.SENT);
        list.add(message);
        notifyListeners(conversationId);
        mainHandler.post(callback::onSuccess);
    }

    @Override
    public synchronized void sendImage(String conversationId, Message message, SendCallback callback) {
        mainHandler.post(() -> callback.onError(context.getString(R.string.image_storage_setup_error)));
    }

    @Override
    public boolean isMock() { return true; }

    private void ensureSamples(String conversationId, String currentUserId,
                               String currentUserName, String peerName) {
        if (conversations.containsKey(conversationId)) return;
        long now = System.currentTimeMillis();
        List<Message> sample = new ArrayList<>();
        sample.add(sample("mock_message_1", "mock_peer_uid", peerName,
                context.getString(R.string.mock_chat_received_one), now - 10 * 60_000L));
        sample.add(sample("mock_message_2", currentUserId, currentUserName,
                context.getString(R.string.mock_chat_sent_one), now - 9 * 60_000L));
        sample.add(sample("mock_message_3", "mock_peer_uid", peerName,
                context.getString(R.string.mock_chat_received_two), now - 8 * 60_000L));
        conversations.put(conversationId, sample);
    }

    private Message sample(String id, String senderId, String senderName, String text, long sentAt) {
        Message message = new Message();
        message.setId(id);
        message.setSenderId(senderId);
        message.setSenderName(senderName);
        message.setType(Message.Type.TEXT);
        message.setContent(text);
        message.setSentAt(sentAt);
        message.setCreatedAt(sentAt);
        message.setDeliveryStatus(Message.DeliveryStatus.SENT);
        return message;
    }

    private void emit(String conversationId, Listener listener, int pageSize) {
        List<Message> all = conversations.get(conversationId);
        int from = Math.max(0, all.size() - Math.max(1, pageSize));
        List<Message> snapshot = new ArrayList<>(all.subList(from, all.size()));
        mainHandler.post(() -> listener.onMessages(snapshot));
    }

    private synchronized void notifyListeners(String conversationId) {
        List<Listener> registered = listeners.get(conversationId);
        if (registered == null) return;
        for (Listener listener : new ArrayList<>(registered)) emit(conversationId, listener, 30);
    }
}
