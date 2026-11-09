package com.example.utechat.domain.model;

/**
 * Domain model đại diện mối quan hệ bạn bè giữa hai người dùng.
 */
public class Friendship {

    public enum Status { PENDING, ACCEPTED, BLOCKED }

    private String id;
    private String fromUid;
    private String toUid;
    private Status status;
    private long createdAt;
    private long updatedAt;

    // Thông tin người dùng kia — được nối từ users collection tại repository
    private User otherUser;

    public Friendship() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getFromUid() { return fromUid; }
    public void setFromUid(String fromUid) { this.fromUid = fromUid; }

    public String getToUid() { return toUid; }
    public void setToUid(String toUid) { this.toUid = toUid; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public User getOtherUser() { return otherUser; }
    public void setOtherUser(User otherUser) { this.otherUser = otherUser; }

    public boolean isAccepted() { return status == Status.ACCEPTED; }
    public boolean isPending() { return status == Status.PENDING; }
}
