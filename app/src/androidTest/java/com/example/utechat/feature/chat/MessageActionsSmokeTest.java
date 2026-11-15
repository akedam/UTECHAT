package com.example.utechat.feature.chat;

import android.content.Intent;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.NoMatchingViewException;
import androidx.test.espresso.Root;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.utechat.BuildConfig;
import com.example.utechat.R;
import com.example.utechat.core.common.Constants;
import com.example.utechat.feature.chat.ui.ChatActivity;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;
import static org.hamcrest.Matchers.allOf;

/** Uses an isolated Firestore conversation with two verified Auth Emulator users. */
public final class MessageActionsSmokeTest {
    @Test public void hideLeavesPeerCopyAndRecallReplacesTheMessageAndSummary() throws Exception {
        var args = InstrumentationRegistry.getArguments();
        assumeTrue("true".equals(args.getString("messageActionsEmulator")));
        String id = args.getString("conversationId");
        String password = args.getString("testPassword");
        FirebaseAuth auth = FirebaseAuth.getInstance();
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        auth.useEmulator("127.0.0.1", BuildConfig.AUTH_EMULATOR_PORT);
        db.useEmulator("127.0.0.1", 18080);
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent chat = new Intent(context, ChatActivity.class).putExtra(Constants.EXTRA_CONVERSATION_ID, id)
                .putExtra(Constants.EXTRA_CONVERSATION_TITLE, "QA menu").putExtra(Constants.EXTRA_CONVERSATION_TYPE, "direct");

        try {
            Tasks.await(auth.signInWithEmailAndPassword(args.getString("emailA"), password), 12, TimeUnit.SECONDS);
            try (var activity = ActivityScenario.<ChatActivity>launch(chat)) {
                awaitView(withText("QA private delete"));
                message("QA private delete").perform(longClick());
                onView(withContentDescription(R.string.reaction_heart)).perform(click());
                awaitView(withText("❤️"));
                delete("QA private delete", R.string.message_delete_mine);
                Tasks.await(db.waitForPendingWrites(), 12, TimeUnit.SECONDS);
                activity.recreate();
                awaitView(withText("QA shared recall"));
                message("QA private delete").check(doesNotExist());
                message("QA shared recall").perform(longClick());
                onView(withText(R.string.message_more)).perform(click());
                onView(withText(R.string.message_pin)).perform(click());
                awaitView(withId(R.id.tv_pinned_message));
                delete("QA shared recall", R.string.message_delete_everyone);
                onView(withId(android.R.id.button1)).perform(click());
                awaitView(withText(R.string.message_recalled));
                var summary = Tasks.await(db.document("conversations/" + id).get(Source.SERVER), 12, TimeUnit.SECONDS);
                assertEquals("recalled", summary.getString("lastMessageType"));
                assertEquals(null, summary.getString("pinnedMessageId"));
            }

            Tasks.await(auth.signInWithEmailAndPassword(args.getString("emailB"), password), 12, TimeUnit.SECONDS);
            try (var activity = ActivityScenario.<ChatActivity>launch(chat)) {
                awaitView(withText("QA private delete"));
                awaitView(withText(R.string.message_recalled));
                message("QA private delete").perform(longClick());
                onView(withText(R.string.message_more)).perform(click());
                onView(withText(R.string.message_delete)).perform(click());
                onView(withText(R.string.message_delete_everyone)).check(doesNotExist());
                onView(withText(R.string.message_delete_mine)).check(matches(isDisplayed()));
            }
        } finally {
            auth.signOut();
        }
    }

    private void delete(String message, int action) {
        message(message).perform(longClick());
        onView(withText(R.string.message_more)).perform(click());
        onView(withText(R.string.message_delete)).perform(click());
        onView(withText(action)).perform(click());
    }

    private androidx.test.espresso.ViewInteraction message(String label) {
        return onView(allOf(withId(R.id.tv_message_content), withText(label))).inRoot(new org.hamcrest.TypeSafeMatcher<Root>() {
            @Override protected boolean matchesSafely(Root root) { return root.getDecorView().hasWindowFocus(); }
            @Override public void describeTo(org.hamcrest.Description description) { description.appendText("Window with focus"); }
        });
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
