package com.example.utechat.data.backend;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.google.firebase.auth.FirebaseAuth;
import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Retries a committed message notification across process restarts and network changes. */
public final class MessageNotificationWorker extends Worker {
    public MessageNotificationWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull @Override public Result doWork() {
        String uid = getInputData().getString("uid");
        String key = getInputData().getString("key");
        if (uid == null || key == null || !uid.equals(FirebaseAuth.getInstance().getUid())) return Result.success();
        SharedPreferences outbox = BackendEvents.prefs(getApplicationContext(), uid);
        String saved = outbox.getString(key, null);
        if (saved == null) return Result.success();
        try {
            JSONObject event = new JSONObject(saved);
            if (System.currentTimeMillis() - event.getLong("createdAt") < TimeUnit.HOURS.toMillis(24)) {
                JSONObject body = new JSONObject().put("conversationId", event.getString("conversationId"))
                        .put("messageId", event.getString("id"));
                BackendClient.Response response = BackendClient.request("POST", "/api/message-notify",
                        body.toString().getBytes(StandardCharsets.UTF_8), "application/json", true);
                if (com.example.utechat.BuildConfig.DEBUG)
                    android.util.Log.d("UTEChatPush", "Message dispatch HTTP " + response.status);
                if (!response.ok() && response.status != 400 && response.status != 403
                        && response.status != 404 && response.status != 409) return Result.retry();
            }
        } catch (JSONException malformed) {
            // An unreadable record cannot be dispatched.
        } catch (Exception unavailable) {
            return Result.retry();
        }
        outbox.edit().remove(key).apply();
        return Result.success();
    }
}
