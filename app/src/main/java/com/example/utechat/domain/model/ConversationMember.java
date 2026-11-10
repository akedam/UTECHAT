package com.example.utechat.domain.model;

/** Thành viên gắn với ID người dùng, vai trò và dấu thời gian tham gia. */
public class ConversationMember {
    public enum Role {
        OWNER, ADMIN, MEMBER;

        public static Role fromSchemaValue(String value) {
            if ("owner".equalsIgnoreCase(value)) return OWNER;
            if ("admin".equalsIgnoreCase(value)) return ADMIN;
            return MEMBER;
        }
    }

    private String userId;
    private Role role = Role.MEMBER;
    private long joinedAt;
    private long lastReadAt;
    private int unreadCount;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role == null ? Role.MEMBER : role; }
    public long getJoinedAt() { return joinedAt; }
    public void setJoinedAt(long joinedAt) { this.joinedAt = joinedAt; }
    public long getLastReadAt() { return lastReadAt; }
    public void setLastReadAt(long lastReadAt) { this.lastReadAt = lastReadAt; }
    public int getUnreadCount() { return unreadCount; }
    public void setUnreadCount(int unreadCount) { this.unreadCount = Math.max(0, unreadCount); }
}
