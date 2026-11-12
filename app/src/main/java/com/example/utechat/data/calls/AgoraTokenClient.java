package com.example.utechat.data.calls;

import android.os.Handler;
import android.os.Looper;

import com.example.utechat.BuildConfig;
import com.example.utechat.domain.model.CallSession;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Retrieves short-lived RTC credentials; never logs the Firebase ID token or Agora token. */
public final class AgoraTokenClient {
    public interface Callback { void onToken(String appId, String token, String channelName, int uid); void onError(); }
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public void fetch(CallSession call, Callback callback) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String endpoint = BuildConfig.AGORA_TOKEN_SERVER_URL;
        if (user == null || endpoint == null || endpoint.trim().isEmpty() || BuildConfig.AGORA_APP_ID.trim().isEmpty()) {
            callback.onError(); return;
        }
        user.getIdToken(false).addOnSuccessListener(result -> {
            String idToken = result.getToken();
            if (idToken == null) { callback.onError(); return; }
            IO.execute(() -> request(endpoint, idToken, call, user.getUid(), callback));
        }).addOnFailureListener(error -> callback.onError());
    }

    private void request(String endpoint, String firebaseToken, CallSession call, String uid, Callback callback) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(endpoint);
            if (!"https".equalsIgnoreCase(url.getProtocol())
                    && !(BuildConfig.DEBUG && ("10.0.2.2".equals(url.getHost()) || "localhost".equals(url.getHost())))) {
                throw new IllegalArgumentException("HTTPS required");
            }
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST"); connection.setConnectTimeout(15_000); connection.setReadTimeout(100_000);
            connection.setRequestProperty("Authorization", "Bearer " + firebaseToken);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setDoOutput(true);
            JSONObject request = new JSONObject(); request.put("callId", call.id);
            try (OutputStream out = connection.getOutputStream()) { out.write(request.toString().getBytes("UTF-8")); }
            int responseCode = connection.getResponseCode();
            if (BuildConfig.DEBUG) android.util.Log.d("UTEChatRTC", "Token endpoint status=" + responseCode);
            if (responseCode != 200) throw new IllegalStateException("token request failed");
            String body = read(connection.getInputStream()); JSONObject response = new JSONObject(body);
            String appId = response.optString("appId", BuildConfig.AGORA_APP_ID);
            String token = response.getString("token"), channel = response.getString("channelName");
            int rtcUid = response.getInt("uid");
            if (!BuildConfig.AGORA_APP_ID.equals(appId) || !call.channelName.equals(channel) || rtcUid <= 0) throw new IllegalStateException("invalid token response");
            main.post(() -> callback.onToken(appId, token, channel, rtcUid));
        } catch (Exception error) { main.post(callback::onError); }
        finally { if (connection != null) connection.disconnect(); }
    }

    private String read(InputStream stream) throws Exception {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"))) {
            String line; while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }
}
