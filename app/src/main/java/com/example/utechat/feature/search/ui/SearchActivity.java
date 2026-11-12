package com.example.utechat.feature.search.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.utechat.R;
import com.example.utechat.app.FriendGroupRepositoryProvider;
import com.example.utechat.core.common.AuthGate;
import com.example.utechat.core.common.Constants;
import com.example.utechat.domain.model.Conversation;
import com.example.utechat.domain.model.Friendship;
import com.example.utechat.domain.model.User;
import com.example.utechat.domain.repository.FriendGroupRepository;
import com.example.utechat.feature.chat.ui.ChatActivity;
import com.example.utechat.feature.friends.viewmodel.FriendsViewModel;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** A separate destination: searching never changes the lists or selected tab behind it. */
public final class SearchActivity extends AppCompatActivity implements SearchResultsAdapter.Listener {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private FriendsViewModel friends;
    private SearchResultsAdapter adapter;
    private FriendGroupRepository.Subscription groupsSubscription;
    private EditText query;
    private List<User> people=Collections.emptyList(),contacts=Collections.emptyList();
    private List<Conversation> groups=Collections.emptyList();
    private List<Friendship> requests=Collections.emptyList();
    private String friendError,groupError;
    private boolean openingChat;
    private final Runnable searchPeople=()->friends.search(query.getText().toString().trim());

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);if(AuthGate.redirectIfSignedOut(this))return;
        setContentView(R.layout.activity_search);applyInsets();
        query=findViewById(R.id.et_search_query);
        friends=new ViewModelProvider(this).get(FriendsViewModel.class);
        User viewer=friends.getCurrentUser();
        adapter=new SearchResultsAdapter(viewer==null?"":viewer.getUid(),this);
        RecyclerView results=findViewById(R.id.rv_search_results);
        results.setLayoutManager(new LinearLayoutManager(this));results.setAdapter(adapter);
        results.setItemAnimator(null);
        findViewById(R.id.btn_search_back).setOnClickListener(v->finish());
        findViewById(R.id.btn_search_clear).setOnClickListener(v->{query.setText("");query.requestFocus();showKeyboard();});
        query.setOnEditorActionListener((v,action,event)->{
            if(action!=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)return false;
            handler.removeCallbacks(searchPeople);
            if(query.getText().toString().trim().length()>=2)searchPeople.run();
            InputMethodManager keyboard=getSystemService(InputMethodManager.class);
            if(keyboard!=null)keyboard.hideSoftInputFromWindow(query.getWindowToken(),0);
            return true;
        });
        query.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void afterTextChanged(Editable s){}
            public void onTextChanged(CharSequence s,int start,int before,int count){
                handler.removeCallbacks(searchPeople);friends.clearSearch();
                findViewById(R.id.btn_search_clear).setVisibility(s.length()==0?View.GONE:View.VISIBLE);
                render();
                if(s.toString().trim().length()>=2)handler.postDelayed(searchPeople,350);
            }
        });
        friends.getFriends().observe(this,items->{contacts=items==null?Collections.emptyList():items;render();});
        friends.getSearchResults().observe(this,items->{people=items==null?Collections.emptyList():items;render();});
        friends.getRequests().observe(this,items->{requests=items==null?Collections.emptyList():items;render();});
        friends.getLoading().observe(this,loading->{
            findViewById(R.id.progress_search).setVisibility(Boolean.TRUE.equals(loading)?View.VISIBLE:View.INVISIBLE);
            adapter.setBusy(Boolean.TRUE.equals(loading));
        });
        friends.getError().observe(this,error->{friendError=error;renderError();});
        FriendGroupRepository repository=FriendGroupRepositoryProvider.get(this);
        if(repository!=null&&viewer!=null)groupsSubscription=repository.observeGroups(viewer.getUid(),(items,error)->{
            if(isDestroyed())return;groups=items==null?Collections.emptyList():items;groupError=error;render();renderError();
        });
        if(state!=null)query.setText(state.getString("query",""));
        query.requestFocus();query.post(this::showKeyboard);
    }
    private void applyInsets(){
        View root=findViewById(R.id.search_root);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)->{
            androidx.core.graphics.Insets bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    |androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            int bottom=Math.max(bars.bottom,insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom);
            view.setPadding(bars.left,bars.top,bars.right,bottom);
            android.graphics.drawable.LayerDrawable background=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                    androidx.appcompat.content.res.AppCompatResources.getDrawable(this,R.drawable.bg_header_gradient),
                    new android.graphics.drawable.ColorDrawable(androidx.core.content.ContextCompat.getColor(this,R.color.utechat_surface))});
            background.setLayerInset(1,0,bars.top,0,0);view.setBackground(background);return insets;
        });
        androidx.core.view.WindowCompat.getInsetsController(getWindow(),root).setAppearanceLightStatusBars(false);
        androidx.core.view.WindowCompat.getInsetsController(getWindow(),root).setAppearanceLightNavigationBars(
                (getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)!=android.content.res.Configuration.UI_MODE_NIGHT_YES);
        androidx.core.view.ViewCompat.requestApplyInsets(root);
    }
    private void showKeyboard(){if(isFinishing()||isDestroyed())return;InputMethodManager keyboard=getSystemService(InputMethodManager.class);if(keyboard!=null)keyboard.showSoftInput(query,InputMethodManager.SHOW_IMPLICIT);}
    private void render(){
        if(adapter==null||isDestroyed())return;
        String term=normalize(query.getText().toString());
        List<SearchResultsAdapter.Entry> rows=new ArrayList<>();Set<String> friendIds=new HashSet<>();
        List<User> matching=new ArrayList<>();
        for(User user:contacts){friendIds.add(user.getUid());if(normalize(user.getDisplayName()).contains(term))matching.add(user);}
        matching.sort((a,b)->normalize(a.getDisplayName()).compareTo(normalize(b.getDisplayName())));
        if(!matching.isEmpty()){rows.add(SearchResultsAdapter.Entry.section(getString(R.string.friends_title)));for(User user:matching)rows.add(SearchResultsAdapter.Entry.friend(user));}
        List<Conversation> matchingGroups=new ArrayList<>();
        for(Conversation group:groups)if(normalize(group.getTitle()).contains(term))matchingGroups.add(group);
        if(!matchingGroups.isEmpty()){
            rows.add(SearchResultsAdapter.Entry.section(getString(R.string.search_groups)));
            for(Conversation group:matchingGroups)rows.add(SearchResultsAdapter.Entry.group(group));
        }
        if(query.getText().toString().trim().length()>=2){
            Map<String,Friendship> pending=new HashMap<>();String uid=friends.getCurrentUser()==null?"":friends.getCurrentUser().getUid();
            for(Friendship request:requests)pending.put(uid.equals(request.getFromUid())?request.getToUid():request.getFromUid(),request);
            List<User> found=new ArrayList<>();for(User user:people)if(!friendIds.contains(user.getUid()))found.add(user);
            if(!found.isEmpty()){rows.add(SearchResultsAdapter.Entry.section(getString(R.string.search_people)));for(User user:found)rows.add(SearchResultsAdapter.Entry.person(user,pending.get(user.getUid())));}
        }
        adapter.submit(rows);
        TextView empty=findViewById(R.id.tv_search_empty);empty.setVisibility(rows.isEmpty()?View.VISIBLE:View.GONE);
        empty.setText(term.isEmpty()?R.string.search_start_hint:R.string.ui_no_search_results);
    }
    private void renderError(){TextView error=findViewById(R.id.tv_search_error);String text=friendError!=null?friendError:groupError;error.setText(text);error.setVisibility(text==null||text.isEmpty()?View.GONE:View.VISIBLE);}
    private static String normalize(String value){return value==null?"":Normalizer.normalize(value.trim(),Normalizer.Form.NFD).replaceAll("\\p{M}","").replace('đ','d').replace('Đ','D').toLowerCase(Locale.ROOT);}
    @Override public void openFriend(User user){
        if(openingChat)return;openingChat=true;
        friends.openDirect(user,new FriendGroupRepository.Result<String>(){
            public void success(String id){openingChat=false;if(!isFinishing()&&!isDestroyed())openChat(id,user.getDisplayName(),"direct",user.getPhotoUrl());}
            public void error(String error){openingChat=false;if(!isFinishing()&&!isDestroyed())Toast.makeText(SearchActivity.this,error,Toast.LENGTH_SHORT).show();}
        });
    }
    @Override public void openGroup(Conversation group){openChat(group.getId(),group.getTitle(),"group",group.getPhotoUrl());}
    private void openChat(String id,String title,String type,String photo){
        InputMethodManager keyboard=getSystemService(InputMethodManager.class);if(keyboard!=null)keyboard.hideSoftInputFromWindow(query.getWindowToken(),0);
        startActivity(new Intent(this,ChatActivity.class).putExtra(Constants.EXTRA_CONVERSATION_ID,id)
                .putExtra(Constants.EXTRA_CONVERSATION_TITLE,title).putExtra(Constants.EXTRA_CONVERSATION_TYPE,type).putExtra(Constants.EXTRA_CONVERSATION_PHOTO,photo));
    }
    @Override public void requestFriend(User user){friends.sendRequest(user);}
    @Override public void accept(Friendship request){friends.accept(request);}
    @Override protected void onSaveInstanceState(Bundle state){state.putString("query",query==null?"":query.getText().toString());super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);if(groupsSubscription!=null)groupsSubscription.remove();super.onDestroy();}
}
