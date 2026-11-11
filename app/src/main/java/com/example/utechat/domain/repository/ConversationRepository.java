package com.example.utechat.domain.repository;

import com.example.utechat.domain.model.ConversationPage;

public interface ConversationRepository {
    interface Subscription { void remove(); }
    interface Callback {
        void onSuccess(ConversationPage page);
        void onError(String message);
    }

    void loadPage(String userId, String cursor, int pageSize, Callback callback);
    default Subscription listenFirstPage(String userId, int pageSize, Callback callback) {
        loadPage(userId,null,pageSize,callback);
        return () -> {};
    }

    boolean isMock();
}
