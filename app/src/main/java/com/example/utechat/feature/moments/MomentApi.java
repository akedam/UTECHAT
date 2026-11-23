package com.example.utechat.feature.moments;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import com.example.utechat.R;
import com.example.utechat.data.backend.BackendClient;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public final class MomentApi {
    static final int MAX_PHOTO_BYTES = 512 * 1024;

    static final class Failure extends Exception {
        final int status;
        Failure(int status, String reason) { super(reason); this.status = status; }
    }

    private MomentApi() {}

    public static boolean validId(String id) { return id != null && id.matches("[a-f0-9]{64}"); }

    static String route(String id) { return "/api/moment?id=" + Uri.encode(id); }

    static JSONObject list(String view, String cursor) throws Exception {
        return json("GET", "/api/moments?view=" + view
                + (cursor.isEmpty() ? "" : "&before=" + Uri.encode(cursor)), null);
    }

    static JSONObject json(String method, String path, JSONObject data) throws Exception {
        return checked(BackendClient.request(method, path,
                data == null ? null : data.toString().getBytes(StandardCharsets.UTF_8),
                data == null ? null : "application/json", true)).json();
    }

    static JSONObject respond(String id, String action, Object value) throws Exception {
        return json("POST", "/api/moment", new JSONObject().put("id", id)
                .put("action", action).put("value", value));
    }

    static String publishError(Context context, Exception error) {
        if (!(error instanceof Failure)) return context.getString(R.string.tin_publish_network);
        Failure failure = (Failure) error;
        if (failure.status == 404) return context.getString(R.string.tin_publish_unavailable);
        if (failure.status == 401) return context.getString(R.string.auth_session_expired);
        switch (failure.getMessage()) {
            case "moment-friend-required": return context.getString(R.string.tin_publish_recipient);
            case "media-not-configured": return context.getString(R.string.tin_publish_setup);
            case "moment-storage-unavailable": return context.getString(R.string.tin_publish_storage_error);
            case "invalid-image":
            case "too-large": return context.getString(R.string.tin_publish_invalid);
            case "daily-upload-limit": return context.getString(R.string.tin_publish_quota);
            case "rate-limited": return context.getString(R.string.tin_publish_rate);
            case "moment-conflict": return context.getString(R.string.tin_publish_conflict);
            case "phone-session-required": return context.getString(R.string.auth_session_expired);
            default: return context.getString(R.string.tin_publish_error);
        }
    }

    static byte[] bytes(String id) throws Exception {
        byte[] bytes = checked(BackendClient.request("GET", "/api/moment-photo?id=" + Uri.encode(id),
                null, null, true)).body;
        if (bytes.length > MAX_PHOTO_BYTES) throw new Failure(502, "invalid-media");
        return bytes;
    }

    public static Bitmap photo(String id, boolean thumbnail) throws Exception {
        byte[] bytes = bytes(id);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth < 1 || options.outWidth > 960 || options.outWidth != options.outHeight)
            throw new Failure(502, "invalid-media");
        options.inJustDecodeBounds = false;
        if (thumbnail) options.inSampleSize = options.outWidth > 640 ? 4 : options.outWidth > 320 ? 2 : 1;
        Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (image == null) throw new Failure(502, "invalid-media");
        return image;
    }

    private static BackendClient.Response checked(BackendClient.Response response) throws Exception {
        if (response.ok()) return response;
        String reason;
        try { reason = response.json().optString("error"); }
        catch (Exception ignored) { reason = ""; }
        throw new Failure(response.status, reason);
    }
}
