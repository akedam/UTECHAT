package com.example.utechat.core.common;

/**
 * Hằng số toàn ứng dụng.
 * Thêm giá trị ở đây khi nhiều hơn 2 nơi dùng chung.
 */
public final class Constants {

    private Constants() {} // Utility class — không khởi tạo

    // Firestore collection names
    public static final String COLLECTION_USERS = "users";
    public static final String COLLECTION_CONVERSATIONS = "conversations";
    public static final String COLLECTION_MESSAGES = "messages";
    public static final String COLLECTION_FRIENDSHIPS = "friendships";

    // Firestore field names
    public static final String FIELD_UID = "uid";
    public static final String FIELD_EMAIL = "email";
    public static final String FIELD_DISPLAY_NAME = "displayName";
    public static final String FIELD_PHOTO_URL = "photoUrl";
    public static final String FIELD_FCM_TOKEN = "fcmToken";
    public static final String FIELD_PARTICIPANT_IDS = "participantIds";
    public static final String FIELD_LAST_MESSAGE_AT = "lastMessageAt";
    public static final String FIELD_SENT_AT = "sentAt";
    public static final String FIELD_STATUS = "status";
    public static final String FIELD_FROM_UID = "fromUid";
    public static final String FIELD_TO_UID = "toUid";

    // Intent extras
    public static final String EXTRA_CONVERSATION_ID = "extra_conversation_id";
    public static final String EXTRA_CONVERSATION_TITLE = "extra_conversation_title";
    public static final String EXTRA_CONVERSATION_PHOTO = "extra_conversation_photo";
    public static final String EXTRA_CONVERSATION_TYPE = "extra_conversation_type";
    public static final String EXTRA_USER_ID = "extra_user_id";

    // SharedPreferences keys
    public static final String PREF_NAME = "utechat_prefs";

    // Firebase Storage paths
    public static final String STORAGE_AVATARS = "avatars/";
    public static final String STORAGE_CHAT_MEDIA = "chat_media/";

    // Message types
    public static final String MSG_TYPE_TEXT = "text";
    public static final String MSG_TYPE_IMAGE = "image";
    public static final String MSG_TYPE_FILE = "file";

    // Friendship status values
    public static final String FRIENDSHIP_PENDING = "pending";
    public static final String FRIENDSHIP_ACCEPTED = "accepted";
    public static final String FRIENDSHIP_BLOCKED = "blocked";
}
