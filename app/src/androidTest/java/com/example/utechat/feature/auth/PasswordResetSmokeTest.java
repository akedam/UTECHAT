package com.example.utechat.feature.auth;

import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.NoMatchingViewException;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.utechat.BuildConfig;
import com.example.utechat.R;
import com.example.utechat.feature.auth.ui.LoginActivity;
import com.google.firebase.auth.FirebaseAuth;
import java.util.UUID;
import org.junit.Test;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assume.assumeTrue;

/** Run against the local Auth emulator over adb reverse tcp:19099 tcp:19099. */
public final class PasswordResetSmokeTest {
    @Test public void validatesEmailAndShowsConfirmation() {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("passwordResetEmulator")));
        FirebaseAuth.getInstance().useEmulator("127.0.0.1", BuildConfig.AUTH_EMULATOR_PORT);
        String email = InstrumentationRegistry.getArguments().getString("passwordResetEmail",
                UUID.randomUUID() + "@example.test");

        try (ActivityScenario<LoginActivity> scenario = ActivityScenario.launch(LoginActivity.class)) {
            onView(withId(R.id.btn_forgot_password)).perform(click());
            onView(isAssignableFrom(android.widget.EditText.class)).inRoot(isDialog())
                    .perform(replaceText("no-email"), closeSoftKeyboard());
            onView(withText(R.string.reset_password_send)).inRoot(isDialog()).perform(click());
            onView(withText(R.string.error_email_invalid)).inRoot(isDialog()).check(matches(isDisplayed()));

            onView(isAssignableFrom(android.widget.EditText.class)).inRoot(isDialog())
                    .perform(replaceText(email), closeSoftKeyboard());
            onView(withText(R.string.reset_password_send)).inRoot(isDialog()).perform(click());
            long deadline = SystemClock.elapsedRealtime() + 10_000;
            while (true) {
                try {
                    onView(withText(R.string.reset_password_sent)).inRoot(isDialog()).check(matches(isDisplayed()));
                    break;
                } catch (NoMatchingViewException pending) {
                    if (SystemClock.elapsedRealtime() >= deadline) throw pending;
                    SystemClock.sleep(100);
                }
            }
        }
    }
}
