package com.example.utechat.data.calls;

/** Shared wording for the one chat history entry committed with a terminal call state. */
public final class CallHistory {
    private CallHistory() { }
    public static boolean isTerminal(String status) {
        return "ended".equals(status) || "failed".equals(status) || "missed".equals(status)
                || "rejected".equals(status) || "busy".equals(status);
    }
    public static boolean isHistoryMessage(String id, String content) {
        return id != null && id.startsWith("call_") && content != null
                && (content.startsWith("📞 Cuộc gọi thoại") || content.startsWith("📹 Cuộc gọi video"));
    }
    public static String mediaType(String content) {
        return content != null && content.startsWith("📹") ? "video" : "audio";
    }
    public static String cardTitle(String content) {
        String kind = "video".equals(mediaType(content)) ? "video" : "thoại";
        if (content.contains(" nhỡ") || content.contains("chưa được trả lời"))
            return "Đã nhỡ cuộc gọi " + kind;
        // Keep all other outcomes distinct; a rejected or failed call isn't a missed call.
        return content.substring(content.indexOf(' ') + 1);
    }
    public static String text(String media, String previous, String status, boolean expired) {
        String kind = "video".equals(media) ? "📹 Cuộc gọi video" : "📞 Cuộc gọi thoại";
        switch (status) {
            case "rejected": return kind + " bị từ chối";
            case "busy": return kind + " không thành công: người nhận đang bận";
            case "failed": return kind + " không kết nối được";
            case "missed": return kind + " nhỡ";
            case "ended":
                if ("accepted".equals(previous)) return kind + " đã kết thúc";
                return kind + (expired ? " nhỡ" : " đã kết thúc (chưa được trả lời)");
            default: throw new IllegalArgumentException("A history entry requires a terminal call state");
        }
    }
}
