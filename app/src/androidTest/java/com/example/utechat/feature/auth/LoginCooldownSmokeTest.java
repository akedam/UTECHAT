package com.example.utechat.feature.auth;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
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
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.not;
import static org.junit.Assume.assumeTrue;

/** Run against Auth Emulator with adb reverse, using a disposable email. */
public class LoginCooldownSmokeTest {
    @Test public void wrongLoginStartsCooldownAndPasswordResetStaysAvailable() {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("loginCooldownEmulator")));
        FirebaseAuth.getInstance().useEmulator("127.0.0.1", BuildConfig.AUTH_EMULATOR_PORT);
        var prefs = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSharedPreferences("login_attempts", Context.MODE_PRIVATE);
        prefs.edit().clear().putInt("failures", 4).putLong("last_failure", System.currentTimeMillis()).commit();

        try (ActivityScenario<LoginActivity> activity = ActivityScenario.launch(LoginActivity.class)) {
            onView(withId(R.id.et_email)).perform(replaceText(UUID.randomUUID() + "@example.test"));
            onView(withId(R.id.et_password)).perform(replaceText("wrong-password"), closeSoftKeyboard());
            onView(withId(R.id.btn_login)).perform(click());
            long deadline = SystemClock.elapsedRealtime() + 10_000;
            while (!prefs.contains("try_after") && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
            onView(withText(R.string.auth_login_local_wait)).check(matches(isDisplayed()));
            onView(withId(R.id.btn_login)).check(matches(not(isEnabled())));
            activity.recreate();
            onView(withId(R.id.btn_login)).check(matches(not(isEnabled())));
            onView(withId(R.id.btn_forgot_password)).perform(click());
            onView(withText(R.string.reset_password_send)).check(matches(isDisplayed()));
        } finally {
            prefs.edit().clear().commit();
        }
    }
}
