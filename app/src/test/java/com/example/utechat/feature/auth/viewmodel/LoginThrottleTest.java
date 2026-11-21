package com.example.utechat.feature.auth.viewmodel;

import org.junit.Test;
import static com.example.utechat.domain.repository.AuthRepository.AuthException.Reason.*;
import static org.junit.Assert.*;

public class LoginThrottleTest {
    private static final long START = 10_000_000;

    @Test public void fifthWrongAttemptStartsCooldownAndLaterFailuresIncreaseIt() {
        LoginThrottle state = new LoginThrottle(0, 0, 0, false);
        for (int attempt = 0; attempt < 4; attempt++) {
            state = state.afterFailure(INVALID_CREDENTIALS, START);
            assertEquals(0, state.waitMillis(START));
        }
        long now = START;
        for (long delay : new long[] {30_000, 60_000, 120_000, 240_000, 300_000, 300_000}) {
            state = state.afterFailure(INVALID_CREDENTIALS, now);
            assertEquals(delay, state.waitMillis(now));
            assertEquals(1, state.waitMillis(now + delay - 1));
            now += delay;
            assertEquals(0, state.waitMillis(now));
        }
    }

    @Test public void firebaseCanStartCooldownImmediatelyAndNetworkErrorsAreNotCounted() {
        LoginThrottle state = new LoginThrottle(0, 0, 0, false);
        assertSame(state, state.afterFailure(OTHER, START));
        state = state.afterFailure(RATE_LIMITED, START);
        assertTrue(state.firebaseLimited);
        assertEquals(60_000, state.waitMillis(START));
        assertSame(state, state.afterFailure(OTHER, START + 60_000));
        state = state.afterFailure(INVALID_CREDENTIALS, START + 900_000);
        assertEquals(1, state.failures);
        assertEquals(0, state.waitMillis(START + 900_000));
        assertFalse(state.firebaseLimited);
    }
}
