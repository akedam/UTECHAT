package com.example.utechat.feature.friends.qr;

import android.content.Intent;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.widget.ImageView;
import androidx.core.content.FileProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.NoMatchingViewException;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.utechat.BuildConfig;
import com.example.utechat.R;
import com.example.utechat.feature.main.ui.MainActivity;
import com.example.utechat.feature.notifications.NotificationPreferences;
import com.example.utechat.feature.search.ui.SearchActivity;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Uses two verified fixtures in Auth Emulator and the local Firestore rules. */
public final class FriendQrSmokeTest {
    @Test public void profileQrOpensTheExactAccountAndRequiresAnExplicitRequest() throws Exception {
        var arguments = InstrumentationRegistry.getArguments();
        assumeTrue("true".equals(arguments.getString("friendQrEmulator")));
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        NotificationPreferences.setPermissionPrompted(context);
        FirebaseAuth auth = FirebaseAuth.getInstance();
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        auth.useEmulator("127.0.0.1", BuildConfig.AUTH_EMULATOR_PORT);
        db.useEmulator("127.0.0.1", 18080);
        String emailA = arguments.getString("friendQrEmailA"), emailB = arguments.getString("friendQrEmailB");
        String password = arguments.getString("friendQrPassword");

        try {
            String alice = signInAndPublish(auth, db, emailA, password);
            String bob = signInAndPublish(auth, db, emailB, password);
            String decodedUid;
            try (var main = ActivityScenario.launch(MainActivity.class)) {
                onView(withId(R.id.nav_profile)).perform(click());
                onView(withId(R.id.btn_profile_qr)).perform(click());
                var qrImage = new java.util.concurrent.atomic.AtomicReference<android.graphics.Bitmap>();
                onView(withId(R.id.iv_friend_qr)).check((view, error) -> {
                    if (error != null) throw error;
                    qrImage.set(((BitmapDrawable) ((ImageView) view).getDrawable()).getBitmap());
                });
                File directory = new File(context.getCacheDir(), "friend-qr");
                directory.mkdirs();
                File image = File.createTempFile("qr-test-", ".png", directory);
                try {
                    try (FileOutputStream output = new FileOutputStream(image)) {
                        assertTrue(qrImage.get().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
                    }
                    var uri = FileProvider.getUriForFile(context, context.getPackageName() + ".friendqr", image);
                    decodedUid = FriendQrCode.readUid(FriendQrImage.read(context.getContentResolver(), uri));
                } finally {
                    image.delete();
                }
                assertEquals(bob, decodedUid);
                pressBack();
            }

            try (var ownCode = ActivityScenario.<SearchActivity>launch(new Intent(context, SearchActivity.class)
                    .putExtra(SearchActivity.EXTRA_QR_UID, decodedUid))) {
                onView(withText(R.string.friend_qr_self)).check(matches(isDisplayed()));
            }
            Tasks.await(auth.signInWithEmailAndPassword(emailA, password), 10, TimeUnit.SECONDS);
            try (var peerCode = ActivityScenario.<SearchActivity>launch(new Intent(context, SearchActivity.class)
                    .putExtra(SearchActivity.EXTRA_QR_UID, decodedUid))) {
                awaitView(allOf(withId(R.id.tv_search_name), withText("QR Tester")));
                awaitView(allOf(withId(R.id.btn_search_action), withText(R.string.friends_add_button)));
                onView(withId(R.id.btn_search_action)).perform(click());
                awaitView(allOf(withId(R.id.btn_search_action), withText(R.string.search_request_sent)));
                String pair = alice.compareTo(bob) < 0 ? alice + "_" + bob : bob + "_" + alice;
                var request = Tasks.await(db.document("friendships/" + pair).get(Source.SERVER), 10, TimeUnit.SECONDS);
                assertEquals(alice, request.getString("fromUid"));
                assertEquals(bob, request.getString("toUid"));
                assertEquals("pending", request.getString("status"));
            }
        } finally {
            auth.signOut();
        }
    }

    private String signInAndPublish(FirebaseAuth auth, FirebaseFirestore db, String email, String password) throws Exception {
        var result = Tasks.await(auth.signInWithEmailAndPassword(email, password), 10, TimeUnit.SECONDS);
        assertTrue(result.getUser().isEmailVerified());
        String uid = result.getUser().getUid();
        Map<String, Object> profile = new HashMap<>(Map.of("uid", uid, "displayName", "QR Tester",
                "searchName", "qr tester", "updatedAt", System.currentTimeMillis()));
        profile.put("photoUrl", null);
        Tasks.await(db.document("publicProfiles/" + uid).set(profile), 10, TimeUnit.SECONDS);
        return uid;
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
}
