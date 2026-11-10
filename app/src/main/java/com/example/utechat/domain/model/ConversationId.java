package com.example.utechat.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Tạo ID ổn định cho chat riêng; ID nhóm sẽ do Firestore cấp khi tạo nhóm. */
public final class ConversationId {
    private ConversationId() {}

    public static String direct(String firstUid, String secondUid) {
        if (firstUid == null || firstUid.trim().isEmpty()
                || secondUid == null || secondUid.trim().isEmpty()
                || firstUid.equals(secondUid)) {
            throw new IllegalArgumentException("A direct conversation needs two distinct user IDs");
        }
        String[] participants = {firstUid, secondUid};
        Arrays.sort(participants);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((participants[0] + ":" + participants[1])
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder id = new StringBuilder("direct_");
            for (byte value : digest) id.append(String.format(java.util.Locale.ROOT, "%02x", value));
            return id.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }
}
