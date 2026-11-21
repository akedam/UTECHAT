package com.example.utechat.feature.friends.qr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.util.regex.Pattern;

public final class FriendQrCode {
    private static final String PREFIX = "utechat://friend/v1/";
    private static final Pattern UID = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private FriendQrCode() { }

    public static String readUid(String payload) {
        if (payload == null || payload.length() > PREFIX.length() + 128 || !payload.startsWith(PREFIX)) return null;
        String uid = payload.substring(PREFIX.length());
        return isUid(uid) ? uid : null;
    }

    public static boolean isUid(String uid) {
        return uid != null && UID.matcher(uid).matches();
    }

    public static BitMatrix encode(String uid, int size) throws WriterException {
        if (!isUid(uid)) throw new IllegalArgumentException("Invalid Firebase UID");
        return new QRCodeWriter().encode(PREFIX + uid, BarcodeFormat.QR_CODE, size, size);
    }
}
