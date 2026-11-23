package com.example.utechat.data.auth;

/** Convert the Vietnamese local mobile format or a standard international number to E.164. */
public final class PhoneNumbers {
    private PhoneNumbers() { }

    public static String normalize(String raw) {
        if (raw == null) return null;
        String phone = raw.trim().replaceAll("[ .()-]", "");
        if (phone.matches("0[35789][0-9]{8}")) phone = "+84" + phone.substring(1);
        return phone.matches("\\+[1-9][0-9]{7,14}") ? phone : null;
    }
}
