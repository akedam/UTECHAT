package com.example.utechat.data.calls;

import org.junit.Test;
import static org.junit.Assert.*;

public class CallHistoryTest {
    @Test public void unansweredCardsKeepAudioAndVideoSeparate() {
        assertEquals("Đã nhỡ cuộc gọi thoại", CallHistory.cardTitle(CallHistory.text("audio","ringing","ended",false)));
        assertEquals("Đã nhỡ cuộc gọi video", CallHistory.cardTitle(CallHistory.text("video","ringing","missed",true)));
        assertEquals("video", CallHistory.mediaType(CallHistory.text("video","ringing","ended",false)));
        assertTrue(CallHistory.isHistoryMessage("call_existing", "📞 Cuộc gọi thoại nhỡ"));
        assertFalse(CallHistory.isHistoryMessage("ordinary-message", "📞 Cuộc gọi thoại nhỡ"));
        assertFalse(CallHistory.isHistoryMessage("call_existing", "Một tin nhắn khác"));
    }
    @Test public void endedAndRejectedAreNotRenderedAsMissed() {
        assertEquals("Cuộc gọi thoại đã kết thúc", CallHistory.cardTitle(CallHistory.text("audio","accepted","ended",false)));
        assertEquals("Cuộc gọi video bị từ chối", CallHistory.cardTitle(CallHistory.text("video","ringing","rejected",false)));
    }
    @Test public void acceptedCallsLeaveAnEndRecord() {
        assertEquals("📞 Cuộc gọi thoại đã kết thúc", CallHistory.text("audio", "accepted", "ended", false));
        assertEquals("📹 Cuộc gọi video đã kết thúc", CallHistory.text("video", "accepted", "ended", true));
    }
    @Test public void unansweredTimeoutIsDifferentFromManualHangup() {
        assertTrue(CallHistory.text("audio", "ringing", "ended", true).endsWith("nhỡ"));
        assertTrue(CallHistory.text("audio", "ringing", "ended", false).contains("chưa được trả lời"));
    }
    @Test public void allTerminalOutcomesHaveSpecificHistory() {
        for (String state : new String[]{"missed", "failed", "rejected", "busy", "ended"}) {
            assertTrue(CallHistory.isTerminal(state));
            assertFalse(CallHistory.text("video", "ringing", state, false).isEmpty());
        }
        assertFalse(CallHistory.isTerminal("ringing"));
        assertFalse(CallHistory.isTerminal("accepted"));
        assertFalse(CallHistory.isTerminal(null));
    }
    @Test(expected = IllegalArgumentException.class) public void ringingMustNotCreateHistory() {
        CallHistory.text("audio", "ringing", "ringing", false);
    }
}
