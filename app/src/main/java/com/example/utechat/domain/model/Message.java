package com.example.utechat.domain.model;

/**
 * Domain model đại diện một tin nhắn trong hội thoại.
 */
public class Message {

    public enum Type { TEXT, IMAGE, FILE }

    private String id;
    private String conversationId;
    private String senderId;
    private String senderName;
    private Type type;
    private String content;         // text hoặc URL ảnh/tệp
    private String fileName;        // null nếu không phải tệp
    private long fileSize;          // bytes, 0 nếu không phải tệp
    private long sentAt;            // epoch millis
    private boolean isRead;
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

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public long getSentAt() { return sentAt; }
    public void setSentAt(long sentAt) { this.sentAt = sentAt; }

    public boolean isRead() { return isRead; }
    public void setRead(boolean read) { isRead = read; }
}
