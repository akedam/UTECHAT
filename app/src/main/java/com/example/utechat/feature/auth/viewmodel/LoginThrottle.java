package com.example.utechat.feature.auth.viewmodel;

import com.example.utechat.domain.repository.AuthRepository.AuthException.Reason;

/** Device-wide cooldown for login attempts made through the app. */
final class LoginThrottle {
    static final long MAX_WAIT_MS = 300_000;
    private static final long RESET_AFTER_MS = 900_000;
    private static final int FIRST_COOLDOWN = 5;
    private static final int MAX_FAILURES = 9;

    final int failures;
    final long lastFailure;
    final long tryAfter;
    final boolean firebaseLimited;

    LoginThrottle(int failures, long lastFailure, long tryAfter, boolean firebaseLimited) {
        this.failures = failures;
        this.lastFailure = lastFailure;
        this.tryAfter = tryAfter;
        this.firebaseLimited = firebaseLimited;
    }

    long waitMillis(long now) {
        if (now < lastFailure || now >= tryAfter) return 0;
        return Math.min(MAX_WAIT_MS, tryAfter - now);
    }

    LoginThrottle afterFailure(Reason reason, long now) {
        if (reason == Reason.OTHER) return this;
        boolean limited = reason == Reason.RATE_LIMITED;
        int recent = now >= lastFailure && now - lastFailure < RESET_AFTER_MS ? failures : 0;
        int next = Math.min(MAX_FAILURES, recent + 1);
        long delay = next < FIRST_COOLDOWN ? 0 : Math.min(MAX_WAIT_MS, 30_000L << (next - FIRST_COOLDOWN));
        if (limited) delay = Math.max(delay, 60_000);
        return new LoginThrottle(next, now, now + delay, limited);
    }
}
