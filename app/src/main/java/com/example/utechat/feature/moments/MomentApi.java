package com.example.utechat.feature.moments;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
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

    static String publishError(Exception error) {
        if (!(error instanceof Failure)) return "Chưa gửi được ảnh. Kiểm tra kết nối và thử lại.";
        Failure failure = (Failure) error;
        if (failure.status == 404) return "Máy chủ đang chạy phiên bản chưa hỗ trợ đăng Tin.";
        if (failure.status == 401) return "Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại.";
        switch (failure.getMessage()) {
            case "moment-friend-required": return "Một người nhận không còn có thể xem tin. Hãy chọn lại.";
            case "media-not-configured": return "Máy chủ chưa bật kho ảnh cho Tin. Ảnh vẫn ở màn hình này.";
            case "moment-storage-unavailable": return "Kho ảnh đang gián đoạn. Hãy thử đăng lại.";
            case "invalid-image":
            case "too-large": return "Ảnh không hợp lệ hoặc quá lớn. Hãy chọn hoặc chụp ảnh khác.";
            case "daily-upload-limit": return "Bạn đã dùng hết dung lượng đăng ảnh trong ngày.";
            case "rate-limited": return "Bạn thao tác quá nhanh. Hãy thử lại sau.";
            case "moment-conflict": return "Lần đăng trước đã thay đổi. Hãy bỏ ảnh và chọn lại.";
            case "email-unverified": return "Bạn cần xác minh email trước khi đăng tin.";
            default: return "Chưa đăng được tin. Bạn có thể thử lại.";
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
