package com.example.utechat.domain.model;

import java.util.List;

/**
 * Domain model đại diện một cuộc hội thoại (1-1 hoặc nhóm).
 */
public class Conversation {

    public enum Type {
        DIRECT, GROUP;

        public static Type fromSchemaValue(String value) {
            if ("group".equalsIgnoreCase(value)) return GROUP;
            return DIRECT;
        }
    }

    private String id;
    private Type type;
    private String title;           // tên người kia (direct) hoặc tên nhóm (group)
    private String photoUrl;        // ảnh avatar người kia hoặc ảnh nhóm
    private String lastMessage;
    private String lastMessageId;
    private long lastMessageAt;     // epoch millis
    private String lastMessageSenderId;
    private String lastMessageType;
    private int unreadCount;
    private List<String> participantIds;
    private String createdBy;
    private long createdAt;
    private long updatedAt;

    public Conversation() {}

    // Getters & Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }

    public String getLastMessage() { return lastMessage; }
    public void setLastMessage(String lastMessage) { this.lastMessage = lastMessage; }

    public String getLastMessageId() { return lastMessageId; }
    public void setLastMessageId(String lastMessageId) { this.lastMessageId = lastMessageId; }

    public long getLastMessageAt() { return lastMessageAt; }
    public void setLastMessageAt(long lastMessageAt) { this.lastMessageAt = lastMessageAt; }

    public String getLastMessageSenderId() { return lastMessageSenderId; }
    public void setLastMessageSenderId(String id) { this.lastMessageSenderId = id; }

    public String getLastMessageType() { return lastMessageType; }
    public void setLastMessageType(String lastMessageType) { this.lastMessageType = lastMessageType; }

    public int getUnreadCount() { return unreadCount; }
    public void setUnreadCount(int unreadCount) { this.unreadCount = unreadCount; }

    public List<String> getParticipantIds() { return participantIds; }
    public void setParticipantIds(List<String> ids) { this.participantIds = ids; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public boolean isGroup() { return type == Type.GROUP; }
}
