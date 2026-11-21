package com.example.utechat.feature.friends.qr;

import com.google.zxing.qrcode.decoder.Decoder;
import org.junit.Test;
import static org.junit.Assert.*;

public final class FriendQrCodeTest {
    @Test public void accountCodeDecodesToThatAccountOnly() throws Exception {
        var first = FriendQrCode.encode("AccountA_123", 1);
        var second = FriendQrCode.encode("AccountB_123", 1);
        assertNotEquals(first, second);
        assertEquals("AccountA_123", FriendQrCode.readUid(new Decoder().decode(symbol(first)).getText()));
        assertEquals("AccountB_123", FriendQrCode.readUid(new Decoder().decode(symbol(second)).getText()));
    }

    @Test public void readerRejectsOtherFormatsAndUnexpectedPathData() {
        assertNull(FriendQrCode.readUid("https://example.test/anything"));
        assertNull(FriendQrCode.readUid("utechat://friend/v2/AccountA"));
        assertNull(FriendQrCode.readUid("utechat://friend/v1/../AccountA"));
        assertNull(FriendQrCode.readUid("utechat://friend/v1/AccountA%2FB"));
        assertNull(FriendQrCode.readUid("utechat://friend/v1/AccountA?email=a@example.test"));
        assertNull(FriendQrCode.readUid("utechat://friend/v1/" + "a".repeat(129)));
    }

    private static com.google.zxing.common.BitMatrix symbol(com.google.zxing.common.BitMatrix image) {
        int[] rect = image.getEnclosingRectangle();
        var symbol = new com.google.zxing.common.BitMatrix(rect[2], rect[3]);
        for (int y = 0; y < rect[3]; y++) for (int x = 0; x < rect[2]; x++) {
            if (image.get(x + rect[0], y + rect[1])) symbol.set(x, y);
        }
        return symbol;
    }
}
