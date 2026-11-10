package com.example.utechat.domain.repository;

import androidx.annotation.Nullable;

import com.example.utechat.domain.model.CallSession;

public interface CallRepository {
    interface Callback { void onSuccess(CallSession call); void onError(String safeMessage); }
    interface Listener { void onChanged(@Nullable CallSession call, @Nullable String safeError); }
    interface IncomingListener { void onIncoming(CallSession call); }

    void createOutgoing(String conversationId, String mediaType, Callback callback);
    void listen(String callId, Listener listener);
    void listenIncoming(String uid, IncomingListener listener);
    void updateStatus(String callId, String status, Callback callback);
    void removeListener(String callId);
    void stopIncomingListener();
}
