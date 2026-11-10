package com.example.utechat.domain.model;

/**
 * Domain model đại diện một tin nhắn trong hội thoại.
 */
public class Message {

    public enum Type {
        TEXT, IMAGE, FILE, POLL;

        public static Type fromSchemaValue(String value) {
            if ("image".equalsIgnoreCase(value)) return IMAGE;
            if ("file".equalsIgnoreCase(value)) return FILE;
            if ("poll".equalsIgnoreCase(value)) return POLL;
            return TEXT;
        }
    }

    public enum DeliveryStatus { SENDING, SENT, DELIVERED, READ, FAILED }

    private String id;
    private String conversationId;
    private String senderId;
    private String senderName;
    private Type type;
    private String content;         // text/caption; rỗng khi chỉ gửi ảnh
    private String mediaUrl;        // G7: protected Firebase Storage object path, never a download token URL
    private String contentType;
    private String localMediaUri;
    private int uploadProgress;
    private String fileName;        // null nếu không phải tệp
    private long fileSize;          // bytes, 0 nếu không phải tệp
    private long sentAt;            // epoch millis
    private long createdAt;         // epoch millis; aliases sentAt for new schema
    private long updatedAt;         // epoch millis; 0 when field is absent
    private boolean isRead;
    private java.util.List<String> readBy;
    private java.util.List<String> pollOptions;
    private java.util.Map<String, Integer> pollVotes;
    private DeliveryStatus deliveryStatus = DeliveryStatus.SENT;
    // isFromMe được tính tại UI layer dựa trên currentUserId

    public Message() {}

    // Getters & Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String id) { this.conversationId = id; }

    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }

    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getMediaUrl() { return mediaUrl; }
    public void setMediaUrl(String mediaUrl) { this.mediaUrl = mediaUrl; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    /** Process-local picker URI; never serialized to Firestore. */
    public String getLocalMediaUri() { return localMediaUri; }
    public void setLocalMediaUri(String localMediaUri) { this.localMediaUri = localMediaUri; }

    public int getUploadProgress() { return uploadProgress; }
    public void setUploadProgress(int uploadProgress) { this.uploadProgress = Math.max(0, Math.min(100, uploadProgress)); }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public long getSentAt() { return sentAt; }
    public void setSentAt(long sentAt) { this.sentAt = sentAt; }

    public long getCreatedAt() { return createdAt > 0 ? createdAt : sentAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public boolean isRead() { return isRead; }
    public void setRead(boolean read) { isRead = read; }

    public java.util.List<String> getReadBy() { return readBy; }
    public void setReadBy(java.util.List<String> readBy) { this.readBy = readBy; }

    public java.util.List<String> getPollOptions() { return pollOptions; }
    public void setPollOptions(java.util.List<String> pollOptions) { this.pollOptions = pollOptions; }

    public java.util.Map<String, Integer> getPollVotes() { return pollVotes; }
    public void setPollVotes(java.util.Map<String, Integer> pollVotes) { this.pollVotes = pollVotes; }

    public DeliveryStatus getDeliveryStatus() { return deliveryStatus; }
    public void setDeliveryStatus(DeliveryStatus deliveryStatus) {
        this.deliveryStatus = deliveryStatus == null ? DeliveryStatus.SENT : deliveryStatus;
    }
}
