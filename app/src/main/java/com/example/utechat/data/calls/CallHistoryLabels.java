package com.example.utechat.data.calls;

import android.content.Context;

import com.example.utechat.R;

public final class CallHistoryLabels {
    private CallHistoryLabels() { }

    public static String title(Context context, String content) {
        String type = context.getString("video".equals(CallHistory.mediaType(content))
                ? R.string.call_video_title : R.string.call_audio_title);
        int label = R.string.call_history_ended;
        if (content.contains(" nhỡ") || content.contains("chưa được trả lời")) label = R.string.call_history_missed;
        else if (content.contains("bị từ chối")) label = R.string.call_history_rejected;
        else if (content.contains("đang bận")) label = R.string.call_history_busy;
        else if (content.contains("không kết nối được")) label = R.string.call_history_failed;
        return context.getString(label, type);
    }
}
