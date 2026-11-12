package com.example.utechat.feature.search;

import android.app.Activity;
import android.widget.EditText;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.example.utechat.R;
import com.example.utechat.core.common.Constants;
import com.example.utechat.domain.model.ConversationId;
import com.example.utechat.domain.model.User;
import com.example.utechat.feature.chat.ui.ChatActivity;
import com.example.utechat.feature.friends.viewmodel.FriendsViewModel;
import com.example.utechat.feature.main.ui.MainActivity;
import com.example.utechat.feature.search.ui.SearchActivity;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;
import org.junit.Test;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in UI test using an existing accepted friend and chat; does not send messages. */
public final class SearchNavigationSmokeTest {
    @Test public void separateSearchAndFriendTouchesOpenTheExistingChat() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("searchNavigation")));
        assertNotNull(FirebaseAuth.getInstance().getCurrentUser());
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            MainActivity main=awaitActivity(MainActivity.class);
            AtomicReference<User> peer=new AtomicReference<>();
            long deadline=System.currentTimeMillis()+20_000;
            while(peer.get()==null&&System.currentTimeMillis()<deadline){
                onUi(()->{
                    Fragment fragment=main.getSupportFragmentManager().findFragmentByTag("friends");
                    if(fragment==null)return;
                    List<User> users=new ViewModelProvider(fragment).get(FriendsViewModel.class).getFriends().getValue();
                    if(users!=null&&!users.isEmpty())peer.set(users.get(0));
                });Thread.sleep(100);
            }
            assertNotNull("An accepted test friend is required",peer.get());
            String name=peer.get().getDisplayName();
            String id=ConversationId.direct(FirebaseAuth.getInstance().getUid(),peer.get().getUid());
            var ref=FirebaseFirestore.getInstance().collection("conversations").document(id);
            var before=Tasks.await(ref.get(Source.SERVER),20,TimeUnit.SECONDS);
            assertTrue("Use an existing chat for this test",before.exists());

            onView(withId(R.id.et_conversation_search)).perform(click());
            SearchActivity search=awaitActivity(SearchActivity.class);
            onUi(()->{assertNull(search.findViewById(R.id.bottom_nav));assertTrue(search.findViewById(R.id.et_search_query).hasFocus());});
            onView(withId(R.id.et_search_query)).perform(replaceText(name));
            Thread.sleep(600);
            onView(allOf(withId(R.id.tv_search_name),withText(name),isDisplayed())).perform(click());
            assertChat(id);pressBack();awaitActivity(SearchActivity.class);
            onView(withId(R.id.btn_search_clear)).perform(click());
            onUi(()->assertEquals("",((EditText)search.findViewById(R.id.et_search_query)).getText().toString()));
            onView(withId(R.id.btn_search_back)).perform(click());awaitActivity(MainActivity.class);

            onUi(main::openFriends);Thread.sleep(300);
            onView(allOf(withId(R.id.tv_friend_name),withText(name),isDisplayed())).perform(click());
            assertChat(id);pressBack();awaitActivity(MainActivity.class);
            int avatar=peer.get().getPhotoUrl()!=null&&peer.get().getPhotoUrl().startsWith("https://")?R.id.iv_friend_avatar:R.id.tv_avatar_placeholder;
            onView(allOf(withId(avatar),isDisplayed(),isDescendantOfA(allOf(withId(R.id.friend_row),hasDescendant(withText(name)))))).perform(click());
            assertChat(id);pressBack();awaitActivity(MainActivity.class);
            onView(allOf(withId(R.id.et_friend_search),isDisplayed())).perform(click());
            awaitActivity(SearchActivity.class);
            onView(withId(R.id.btn_search_back)).perform(click());awaitActivity(MainActivity.class);

            var after=Tasks.await(ref.get(Source.SERVER),20,TimeUnit.SECONDS);
            assertEquals("Opening a friend must preserve the conversation summary",before.getString("lastMessage"),after.getString("lastMessage"));
            assertEquals(before.getLong("lastMessageAt"),after.getLong("lastMessageAt"));
        }
    }
    private void assertChat(String id)throws Exception{
        ChatActivity chat=awaitActivity(ChatActivity.class);
        assertEquals(id,chat.getIntent().getStringExtra(Constants.EXTRA_CONVERSATION_ID));
    }
    private void onUi(Runnable action){InstrumentationRegistry.getInstrumentation().runOnMainSync(action);}
    private <T extends Activity>T awaitActivity(Class<T> type)throws Exception{
        AtomicReference<T> result=new AtomicReference<>();long deadline=System.currentTimeMillis()+15_000;
        while(result.get()==null&&System.currentTimeMillis()<deadline){
            onUi(()->{for(Activity activity:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                if(type.isInstance(activity))result.set(type.cast(activity));});
            if(result.get()==null)Thread.sleep(100);
        }
        assertNotNull("Expected screen: "+type.getSimpleName(),result.get());return result.get();
    }
}
