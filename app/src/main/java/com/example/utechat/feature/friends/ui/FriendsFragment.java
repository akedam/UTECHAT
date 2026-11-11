package com.example.utechat.feature.friends.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.utechat.R;
import com.example.utechat.core.common.Constants;
import com.example.utechat.domain.model.*;
import com.example.utechat.feature.chat.ui.ChatActivity;
import com.example.utechat.feature.friends.adapter.FriendsAdapter;
import com.example.utechat.feature.friends.viewmodel.FriendsViewModel;
import com.google.android.material.button.MaterialButton;
import java.util.*;

public class FriendsFragment extends Fragment {
    private FriendsViewModel vm; private FriendsAdapter adapter; private RecyclerView list;
    private EditText search; private LinearLayout resultRows, requestRows; private TextView empty,error; private ProgressBar progress;
    private String filter = "all";
    private View root;
    private List<User> friends = Collections.emptyList();
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,@Nullable ViewGroup parent,@Nullable Bundle state){return inflater.inflate(R.layout.fragment_friends,parent,false);}
    @Override public void onViewCreated(@NonNull View v,@Nullable Bundle state){super.onViewCreated(v,state);
        vm=new ViewModelProvider(this).get(FriendsViewModel.class);list=v.findViewById(R.id.rv_friends);search=v.findViewById(R.id.et_friend_search);resultRows=v.findViewById(R.id.layout_friend_results);requestRows=v.findViewById(R.id.layout_friend_requests);empty=v.findViewById(R.id.empty_state);error=v.findViewById(R.id.tv_friends_error);progress=v.findViewById(R.id.progress_friends);
        v.findViewById(R.id.tv_friends_source).setVisibility(vm.isMock()?View.VISIBLE:View.GONE);
        root=v;
        adapter=new FriendsAdapter(new FriendsAdapter.OnFriendClickListener(){
            public void onFriendClick(User user){openDirect(user);}
            public void onFriendCall(User user,String media){openDirect(user,media);}
        });list.setLayoutManager(new LinearLayoutManager(requireContext()));list.setAdapter(adapter);
        v.findViewById(R.id.filter_all).setOnClickListener(x->selectFilter("all"));
        v.findViewById(R.id.filter_online).setOnClickListener(x->selectFilter("online"));
        v.findViewById(R.id.filter_requests).setOnClickListener(x->selectFilter("requests"));
        selectFilter("all");
        v.findViewById(R.id.btn_friend_search).setOnClickListener(x->vm.search(search.getText().toString()));
        search.setOnEditorActionListener((t,action,event)->{vm.search(search.getText().toString());return true;});
        vm.getFriends().observe(getViewLifecycleOwner(),users->{friends=users==null?Collections.emptyList():users;filterFriends();});
        vm.getSearchResults().observe(getViewLifecycleOwner(),this::renderSearch);
        vm.getRequests().observe(getViewLifecycleOwner(),this::renderRequests);
        vm.getLoading().observe(getViewLifecycleOwner(),b->progress.setVisibility(Boolean.TRUE.equals(b)?View.VISIBLE:View.GONE));
        vm.getError().observe(getViewLifecycleOwner(),s->{error.setText(s==null?"":s);error.setVisibility(s==null||s.isEmpty()?View.GONE:View.VISIBLE);});
    }
    private void selectFilter(String value){
        filter=value;
        root.findViewById(R.id.filter_all).setSelected("all".equals(value));
        root.findViewById(R.id.filter_online).setSelected("online".equals(value));
        root.findViewById(R.id.filter_requests).setSelected("requests".equals(value));
        filterFriends();
    }
    private void filterFriends(){
        List<User> sorted=new ArrayList<>();
        for(User user:friends)if(!"online".equals(filter)||user.isOnline())sorted.add(user);
        java.text.Collator collator=java.text.Collator.getInstance(new Locale("vi","VN"));
        sorted.sort((a,b)->collator.compare(a.getDisplayName()==null?"":a.getDisplayName(),b.getDisplayName()==null?"":b.getDisplayName()));
        adapter.submitList(sorted);
        boolean requests="requests".equals(filter);
        list.setVisibility(requests?View.GONE:View.VISIBLE);
        empty.setText("online".equals(filter)?R.string.ui_no_online:R.string.empty_friends_message);
        empty.setVisibility(!requests&&sorted.isEmpty()?View.VISIBLE:View.GONE);
        requestRows.setVisibility(requests?View.VISIBLE:View.GONE);
        root.findViewById(R.id.empty_requests).setVisibility(requests&&requestRows.getChildCount()==0?View.VISIBLE:View.GONE);
    }
    private void renderSearch(List<User> users){resultRows.removeAllViews();if(users==null)return;for(User u:users){LinearLayout row=row(u.getDisplayName());MaterialButton action=button(R.string.friends_add_button);action.setOnClickListener(v->vm.sendRequest(u));row.addView(action);resultRows.addView(row);}}
    private void renderRequests(List<Friendship> items){requestRows.removeAllViews();if(items==null)return;String me=vm.getCurrentUser()==null?"":vm.getCurrentUser().getUid();
        for(Friendship f:items){User other=f.getOtherUser();String label=other==null?getString(R.string.friends_unknown_user):other.getDisplayName();LinearLayout row=row(label);boolean incoming=me.equals(f.getToUid());
            if(incoming){MaterialButton accept=button(R.string.friends_accept_button);accept.setOnClickListener(v->vm.accept(f));row.addView(accept);MaterialButton decline=button(R.string.friends_decline_button);decline.setOnClickListener(v->vm.decline(f));row.addView(decline);}
            else {MaterialButton cancel=button(R.string.friends_cancel_button);cancel.setOnClickListener(v->vm.decline(f));row.addView(cancel);}requestRows.addView(row);
        }
        filterFriends();
    }
    private LinearLayout row(String label){LinearLayout row=new LinearLayout(requireContext());row.setGravity(Gravity.CENTER_VERTICAL);row.setOrientation(LinearLayout.HORIZONTAL);row.setBackgroundResource(R.drawable.bg_list_item);int horizontal=getResources().getDimensionPixelSize(R.dimen.space_sm);int vertical=getResources().getDimensionPixelSize(R.dimen.space_xs);row.setPadding(horizontal,vertical,horizontal,vertical);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);params.bottomMargin=getResources().getDimensionPixelSize(R.dimen.list_item_gap);row.setLayoutParams(params);TextView name=new TextView(requireContext());name.setText(label);name.setTextColor(getResources().getColor(R.color.utechat_on_surface));name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,getResources().getDimension(R.dimen.text_md));name.setMinHeight(getResources().getDimensionPixelSize(R.dimen.touch_target_min));name.setGravity(Gravity.CENTER_VERTICAL);name.setLayoutParams(new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));row.addView(name);return row;}
    private MaterialButton button(int text){MaterialButton b=new MaterialButton(requireContext());b.setText(text);b.setMinHeight(getResources().getDimensionPixelSize(R.dimen.touch_target_min));b.setInsetTop(0);b.setInsetBottom(0);return b;}
    private void openDirect(User user){openDirect(user,null);}
    private void openDirect(User user,String media){vm.openDirect(user,new com.example.utechat.domain.repository.FriendGroupRepository.Result<String>(){
        public void success(String id){if(!isAdded())return;if(media!=null){startActivity(com.example.utechat.feature.calls.ui.CallActivity.outgoingIntent(requireContext(),id,user.getDisplayName(),media));return;}Intent i=new Intent(requireContext(),ChatActivity.class);i.putExtra(Constants.EXTRA_CONVERSATION_ID,id);i.putExtra(Constants.EXTRA_CONVERSATION_TITLE,user.getDisplayName());i.putExtra(Constants.EXTRA_CONVERSATION_TYPE,"direct");startActivity(i);}
        public void error(String message){if(isAdded())Toast.makeText(requireContext(),message,Toast.LENGTH_SHORT).show();}});}
}
