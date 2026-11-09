package com.example.utechat.domain.model;

/**
 * Domain model đại diện một người dùng UTEChat.
 * Dùng trong UI và ViewModel — tách biệt với DTO từ Firestore.
 */
public class User {
    private String uid;
    private String displayName;
    private String email;
    private String photoUrl;
    private String bio;
    private boolean online;
    private long lastSeen; // epoch millis

    public User() {}

    public User(String uid, String displayName, String email,
                String photoUrl, String bio, boolean online, long lastSeen) {
        this.uid = uid;
        this.displayName = displayName;
        this.email = email;
        this.photoUrl = photoUrl;
        this.bio = bio;
        this.online = online;
        this.lastSeen = lastSeen;
    }

    public String getUid() { return uid; }
    public void setUid(String uid) { this.uid = uid; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }

    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }

    public boolean isOnline() { return online; }
    public void setOnline(boolean online) { this.online = online; }

    public long getLastSeen() { return lastSeen; }
    public void setLastSeen(long lastSeen) { this.lastSeen = lastSeen; }

    /** Trả về chữ cái đầu tên hiển thị, dùng cho avatar placeholder */
    public String getInitials() {
        if (displayName == null || displayName.isEmpty()) return "?";
        String[] parts = displayName.trim().split("\\s+");
        if (parts.length >= 2) {
            return String.valueOf(parts[0].charAt(0)).toUpperCase()
                    + String.valueOf(parts[parts.length - 1].charAt(0)).toUpperCase();
        }
        return String.valueOf(displayName.charAt(0)).toUpperCase();
    }
}
