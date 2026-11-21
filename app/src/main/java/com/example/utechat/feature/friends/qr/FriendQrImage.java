package com.example.utechat.feature.friends.qr;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.io.IOException;
import java.io.InputStream;

public final class FriendQrImage {
    private static final int MAX_DECODE_EDGE = 2048;

    private FriendQrImage() { }

    public static Bitmap make(String uid) throws WriterException {
        BitMatrix matrix = FriendQrCode.encode(uid, 768);
        int size = matrix.getWidth();
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) pixels[y * size + x] = matrix.get(x, y) ? 0xff000000 : 0xffffffff;
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
    }

    public static String read(ContentResolver resolver, Uri uri) throws IOException, ReaderException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream stream = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(stream, null, options);
        }
        if (options.outWidth < 1 || options.outHeight < 1) throw new IOException("Invalid QR image");
        options.inSampleSize = 1;
        int longest = Math.max(options.outWidth, options.outHeight);
        while (longest / options.inSampleSize > MAX_DECODE_EDGE) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap;
        try (InputStream stream = resolver.openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(stream, null, options);
        }
        if (bitmap == null) throw new IOException("Could not decode QR image");
        try {
            int width = bitmap.getWidth(), height = bitmap.getHeight();
            if (width > MAX_DECODE_EDGE || height > MAX_DECODE_EDGE) throw new IOException("QR image too large");
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            var binary = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, height, pixels)));
            return new QRCodeReader().decode(binary).getText();
        } finally {
            bitmap.recycle();
        }
    }
}
