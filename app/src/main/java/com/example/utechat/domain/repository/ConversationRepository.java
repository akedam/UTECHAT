package com.example.utechat.domain.repository;

import com.example.utechat.domain.model.ConversationPage;

public interface ConversationRepository {
    interface Callback {
        void onSuccess(ConversationPage page);
        void onError(String message);
    }

    void loadPage(String userId, String cursor, int pageSize, Callback callback);

    boolean isMock();
}
