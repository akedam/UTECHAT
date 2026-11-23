package com.example.utechat.data.backend;

import android.os.Handler;
import android.os.Looper;
import com.example.utechat.BuildConfig;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** The app contains public endpoints only. All privileged credentials stay on the backend. */
public final class BackendClient {
    public interface ReadyCallback { void onReady(); void onError(); }
    public static final class Response {
        public final int status;
        public final byte[] body;
        public final String contentType;
        Response(int status, byte[] body, String contentType) { this.status=status; this.body=body; this.contentType=contentType; }
        public JSONObject json() throws Exception { return new JSONObject(new String(body, StandardCharsets.UTF_8)); }
        public boolean ok() { return status >= 200 && status < 300; }
    }
    private static final ExecutorService IO = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private BackendClient() { }
    public static boolean configured() { return !BuildConfig.AGORA_TOKEN_SERVER_URL.trim().isEmpty(); }
    public static URL endpoint(String route) throws Exception {
        URL token = new URL(BuildConfig.AGORA_TOKEN_SERVER_URL);
        boolean local = BuildConfig.DEBUG && "http".equals(token.getProtocol())
                && ("10.0.2.2".equals(token.getHost()) || "localhost".equals(token.getHost()));
        if ((!"https".equals(token.getProtocol()) && !local) || token.getUserInfo()!=null
                || token.getQuery()!=null || token.getRef()!=null || !token.getPath().equals("/api/rtc-token"))
            throw new IllegalArgumentException("Invalid backend endpoint");
        return new URL(token, route);
    }
    public static void warmUp(ReadyCallback callback) {
        IO.execute(() -> {
            try {
                if (BuildConfig.DEBUG) android.util.Log.d("UTEChatBackend", "Warmup started");
                Response result = request("GET", "/healthz", null, null, false);
                if (BuildConfig.DEBUG) android.util.Log.d("UTEChatBackend", "Warmup response: " + result.status);
                if (!result.ok() || !"configured".equals(result.json().optString("status"))) throw new Exception();
                MAIN.post(callback::onReady);
            } catch (Exception error) {
                if (BuildConfig.DEBUG) android.util.Log.w("UTEChatBackend", "Warmup failed: " + error.getClass().getSimpleName());
                MAIN.post(callback::onError);
            }
        });
    }
    /** Call from an IO thread. Long read timeout deliberately accommodates Render Free cold starts. */
    public static Response request(String method, String route, byte[] body, String contentType, boolean authenticated) throws Exception {
        return request(method, route, body, contentType, authenticated, null);
    }

    public static Response request(String method, String route, byte[] body, String contentType,
                                   boolean authenticated, java.util.function.IntConsumer progress) throws Exception {
        return request(method, route, body, contentType, authenticated, progress, null);
    }

    public static Response phoneAuth(String action, JSONObject data, String proofToken) throws Exception {
        if (!java.util.Set.of("login", "register", "link", "reset", "status").contains(action)) throw new IllegalArgumentException();
        byte[] body = data == null ? null : data.toString().getBytes(StandardCharsets.UTF_8);
        return request(data == null ? "GET" : "POST", "/api/phone-auth/" + action, body,
                "application/json", false, null, proofToken);
    }

    private static Response request(String method, String route, byte[] body, String contentType,
                                    boolean authenticated, java.util.function.IntConsumer progress, String proofToken) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) endpoint(route).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method); connection.setConnectTimeout(15_000); connection.setReadTimeout(100_000);
            if (authenticated) {
                FirebaseUser user=FirebaseAuth.getInstance().getCurrentUser();
                if (user==null) throw new IllegalStateException("Signed out");
                String uid=user.getUid();
                String token=Tasks.await(user.getIdToken(false),30,TimeUnit.SECONDS).getToken();
                FirebaseUser current=FirebaseAuth.getInstance().getCurrentUser();
                if(token==null || current==null || !uid.equals(current.getUid())) throw new IllegalStateException("Session changed");
                connection.setRequestProperty("Authorization","Bearer "+token);
            }
            if (proofToken != null) connection.setRequestProperty("Authorization", "Bearer " + proofToken);
            if (body!=null) {
                connection.setDoOutput(true); connection.setFixedLengthStreamingMode(body.length);
                connection.setRequestProperty("Content-Type",contentType);
                try (java.io.OutputStream output = connection.getOutputStream()) {
                    if (progress == null) output.write(body);
                    else {
                        for (int offset = 0, last = -1; offset < body.length;) {
                            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                            int length = Math.min(32 * 1024, body.length - offset);
                            output.write(body, offset, length);
                            offset += length;
                            int percent = offset * 95 / body.length;
                            if (last < 0 || percent - last >= 5 || offset == body.length) {
                                progress.accept(percent);
                                last = percent;
                            }
                        }
                    }
                }
            }
            int status=connection.getResponseCode();
            InputStream stream=status>=200 && status<300 ? connection.getInputStream() : connection.getErrorStream();
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            if(stream!=null) try(InputStream input=stream) {
                byte[] buffer=new byte[32768]; int count;
                while((count=input.read(buffer))!=-1) {
                    if(output.size()+count>8*1024*1024) throw new IllegalStateException("Response too large");
                    output.write(buffer,0,count);
                }
            }
            return new Response(status,output.toByteArray(),connection.getContentType());
        } finally { connection.disconnect(); }
    }
}
