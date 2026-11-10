package com.example.utechat.domain.repository;

import com.example.utechat.domain.model.Message;
import com.example.utechat.domain.model.MessagePage;

import java.util.List;

public interface MessageRepository {
    interface Subscription { void remove(); }

    interface Listener {
        void onMessages(List<Message> latestMessages);
        void onError(String message);
    }

    interface PageCallback {
        void onSuccess(MessagePage page);
        void onError(String message);
    }

    interface SendCallback {
        void onSuccess();
        void onError(String message);
    }

    Subscription listenToLatest(String conversationId, String currentUserId,
                                String currentUserName, String peerName, int pageSize,
                                Listener listener);

    void loadOlder(String conversationId, String beforeMessageId, String currentUserId,
                   String currentUserName, String peerName, int pageSize, PageCallback callback);

    void sendText(String conversationId, Message message, SendCallback callback);

    void sendImage(String conversationId, Message message, SendCallback callback);

    boolean isMock();
}
