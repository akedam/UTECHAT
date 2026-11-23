package com.example.utechat.data.media;

import java.util.Locale;

public final class AttachmentPolicy {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    public static final String PDF = "application/pdf";
    public static final String WORD = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public static final String TEXT = "text/plain";

    private AttachmentPolicy() { }

    public static String typeForName(String name) {
        if (name == null || name.length() > 120 || !name.equals(name.trim())
                || name.matches("(?s).*[\\\\/\\p{Cc}\\p{Cf}].*")) return null;
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return null;
        switch (name.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "pdf": return PDF;
            case "docx": return WORD;
            case "txt": return TEXT;
            default: return null;
        }
    }
}
