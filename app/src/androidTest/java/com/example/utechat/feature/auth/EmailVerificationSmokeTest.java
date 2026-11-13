package com.example.utechat.feature.auth;

import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.NoMatchingViewException;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.utechat.BuildConfig;
import com.example.utechat.R;
import com.example.utechat.feature.auth.ui.RegisterActivity;
import com.example.utechat.feature.auth.ui.SplashActivity;
import com.example.utechat.feature.notifications.NotificationPreferences;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Opt-in test on a dedicated test installation, with adb reverse for Auth and Firestore. */
public final class EmailVerificationSmokeTest {
    @Test public void registrationAndExistingSessionRequireVerification() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("verificationEmulator")));
        NotificationPreferences.setPermissionPrompted(InstrumentationRegistry.getInstrumentation().getTargetContext());
        FirebaseAuth auth = FirebaseAuth.getInstance();
        FirebaseFirestore firestore = FirebaseFirestore.getInstance();
        auth.useEmulator("127.0.0.1", BuildConfig.AUTH_EMULATOR_PORT);
        firestore.useEmulator("127.0.0.1", 18080);
        auth.signOut();
        String email = UUID.randomUUID() + "@example.test";
        String password = UUID.randomUUID().toString();
        String name = "QA xác minh";

        try (ActivityScenario<RegisterActivity> ignored = ActivityScenario.launch(RegisterActivity.class)) {
            onView(withId(R.id.et_display_name)).perform(replaceText(name));
            onView(withId(R.id.et_email)).perform(replaceText(email));
            onView(withId(R.id.et_password)).perform(replaceText(password));
            onView(withId(R.id.et_confirm_password)).perform(replaceText(password), closeSoftKeyboard());
            onView(withId(R.id.btn_register)).perform(click());
            awaitView(withText(R.string.verify_email_sent));
            onView(withId(R.id.verify_email_address)).check(matches(withText(email)));
            onView(withId(R.id.btn_verify_resend)).check(matches(not(isEnabled())));
            assertFalse(auth.getCurrentUser().isEmailVerified());
            String uid = auth.getUid();

            onView(withId(R.id.btn_verify_confirm)).perform(click());
            awaitView(withText(R.string.verify_email_pending));
            try (ActivityScenario<SplashActivity> splash = ActivityScenario.launch(SplashActivity.class)) {
                awaitView(withId(R.id.verify_email_address));
                onView(withId(R.id.verify_email_address)).check(matches(withText(email)));
                onView(withId(R.id.btn_verify_sign_out)).perform(click());
                awaitView(withId(R.id.btn_login));
                onView(withId(R.id.et_email)).perform(replaceText(email));
                onView(withId(R.id.et_password)).perform(replaceText(password), closeSoftKeyboard());
                onView(withId(R.id.btn_login)).perform(click());
                awaitView(withId(R.id.verify_email_address));
                onView(withId(R.id.verify_email_address)).check(matches(withText(email)));
                assertEquals(uid, auth.getUid());

                String project = auth.getApp().getOptions().getProjectId();
                Tasks.await(auth.applyActionCode(emulatorCode(project, email)), 10, TimeUnit.SECONDS);
                onView(withId(R.id.btn_verify_confirm)).perform(click());
                awaitView(withId(R.id.bottom_nav));
                assertTrue(auth.getCurrentUser().isEmailVerified());
                assertEquals(uid, auth.getUid());
                assertEquals(name, Tasks.await(firestore.document("users/" + uid).get(), 10, TimeUnit.SECONDS)
                        .getString("displayName"));
            }
        } finally {
            auth.signOut();
        }
    }

    private void awaitView(org.hamcrest.Matcher<android.view.View> matcher) {
        long deadline = SystemClock.elapsedRealtime() + 12_000;
        while (true) {
            try {
                onView(matcher).check(matches(isDisplayed()));
                return;
            } catch (NoMatchingViewException pending) {
                if (SystemClock.elapsedRealtime() >= deadline) throw pending;
                SystemClock.sleep(100);
            }
        }
    }

    private String emulatorCode(String project, String email) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:"
                + BuildConfig.AUTH_EMULATOR_PORT + "/emulator/v1/projects/" + project + "/oobCodes").openConnection();
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(5_000);
        try (var stream = connection.getInputStream()) {
            var codes = new JSONObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getJSONArray("oobCodes");
            for (int i = 0; i < codes.length(); i++) {
                JSONObject code = codes.getJSONObject(i);
                if (email.equals(code.optString("email")) && "VERIFY_EMAIL".equals(code.optString("requestType"))) {
                    return code.getString("oobCode");
                }
            }
            throw new AssertionError("Auth Emulator did not receive the verification email request");
        } finally {
            connection.disconnect();
        }
    }
}
