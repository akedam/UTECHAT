package com.example.utechat.domain.model;

import androidx.annotation.NonNull;

/** Immutable call signaling record. Timestamps are epoch milliseconds. */
public final class CallSession {
    public final String id;
    public final String conversationId;
    public final String callerUid;
    public final String calleeUid;
    public final String channelName;
    public final String mediaType;
    public final String status;
    public final long createdAt;
    public final long expiresAt;

    public CallSession(@NonNull String id, @NonNull String conversationId,
                       @NonNull String callerUid, @NonNull String calleeUid,
                       @NonNull String channelName, @NonNull String mediaType,
                       @NonNull String status, long createdAt, long expiresAt) {
        this.id = id;
        this.conversationId = conversationId;
        this.callerUid = callerUid;
        this.calleeUid = calleeUid;
        this.channelName = channelName;
        this.mediaType = mediaType;
        this.status = status;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }
}
