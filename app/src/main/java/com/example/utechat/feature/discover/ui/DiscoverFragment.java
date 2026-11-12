package com.example.utechat.feature.discover.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.Observer;
import com.example.utechat.R;
import com.example.utechat.app.FriendGroupRepositoryProvider;
import com.example.utechat.core.common.Constants;
import com.example.utechat.domain.model.*;
import com.example.utechat.domain.repository.FriendGroupRepository;
import com.example.utechat.feature.chat.ui.ChatActivity;
import com.example.utechat.feature.groups.viewmodel.GroupsViewModel;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;
import java.util.*;

/** Member-managed group conversations, with separate primary, secondary and destructive actions. */
public class DiscoverFragment extends Fragment {
    private GroupsViewModel vm;
    private EditText name;
    private TextInputLayout nameLayout;
    private LinearLayout rows;
    private TextView error,count;
    private View empty,createButton;
    private final List<AlertDialog> dialogs=new ArrayList<>();

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,@Nullable ViewGroup parent,@Nullable Bundle state){
        return inflater.inflate(R.layout.fragment_discover,parent,false);
    }
    @Override public void onViewCreated(@NonNull View view,@Nullable Bundle state){
        super.onViewCreated(view,state);
        vm=new ViewModelProvider(this).get(GroupsViewModel.class);
        name=view.findViewById(R.id.et_group_name);nameLayout=view.findViewById(R.id.layout_group_name);
        rows=view.findViewById(R.id.layout_groups);empty=view.findViewById(R.id.tv_group_empty);
        error=view.findViewById(R.id.tv_groups_error);count=view.findViewById(R.id.tv_group_count);
        createButton=view.findViewById(R.id.btn_create_group);
        createButton.setOnClickListener(v->chooseMembersAndCreate());
        view.findViewById(R.id.btn_community_search).setOnClickListener(v->startActivity(new Intent(requireContext(),com.example.utechat.feature.search.ui.SearchActivity.class)));
        name.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){nameLayout.setError(null);}
            public void afterTextChanged(android.text.Editable text){}
        });
        vm.groups().observe(getViewLifecycleOwner(),this::renderGroups);
        vm.error().observe(getViewLifecycleOwner(),message->{error.setText(message);error.setVisibility(message==null||message.isEmpty()?View.GONE:View.VISIBLE);});
        vm.loading().observe(getViewLifecycleOwner(),loading->{
            boolean busy=Boolean.TRUE.equals(loading);view.findViewById(R.id.progress_groups).setVisibility(busy?View.VISIBLE:View.GONE);
            createButton.setEnabled(vm.isReady()&&!busy);
            for(int i=0;i<rows.getChildCount();i++){
                rows.getChildAt(i).findViewById(R.id.btn_community_members).setEnabled(!busy);
                rows.getChildAt(i).findViewById(R.id.btn_community_leave).setEnabled(!busy);
            }
        });
        if(!vm.isReady()){error.setText(R.string.friends_firebase_required);error.setVisibility(View.VISIBLE);}
    }
    private void chooseMembersAndCreate(){
        List<User> available=vm.friends().getValue();
        if(available==null||available.isEmpty()){
            show(dialogBuilder().setTitle(R.string.community_friends_title).setMessage(R.string.groups_no_friends)
                    .setNegativeButton(R.string.friends_cancel_button,null).setPositiveButton(R.string.community_friends_action,(d,w)->
                            ((com.example.utechat.feature.main.ui.MainActivity)requireActivity()).openFriends()).create(),false);
            return;
        }
        String title=name.getText()==null?"":name.getText().toString().trim();
        if(title.isEmpty()){nameLayout.setError(getString(R.string.groups_name_invalid));name.requestFocus();return;}
        List<User> friends=new ArrayList<>(available);
        String[] labels=new String[friends.size()];boolean[] checked=new boolean[friends.size()];
        for(int i=0;i<labels.length;i++)labels[i]=friends.get(i).getDisplayName();
        AlertDialog dialog=dialogBuilder().setTitle(R.string.community_choose_members)
                .setMultiChoiceItems(labels,checked,(d,index,on)->{
                    checked[index]=on;
                    if(selectedCount(checked)>8){checked[index]=false;((AlertDialog)d).getListView().setItemChecked(index,false);Toast.makeText(requireContext(),R.string.groups_initial_member_limit,Toast.LENGTH_SHORT).show();}
                    int selected=selectedCount(checked);((AlertDialog)d).getButton(AlertDialog.BUTTON_POSITIVE).setText(getString(R.string.community_create_count,selected));
                    ((AlertDialog)d).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(selected>0);
                }).setNegativeButton(R.string.friends_cancel_button,null).setPositiveButton(R.string.groups_create_button,null).create();
        show(dialog,false);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            List<User> picked=new ArrayList<>();for(int i=0;i<friends.size();i++)if(checked[i])picked.add(friends.get(i));
            if(picked.isEmpty())return;
            dialog.dismiss();
            vm.create(title,picked,new FriendGroupRepository.Result<String>(){
                public void success(String id){if(!isAdded()||getView()==null)return;name.setText("");name.clearFocus();Toast.makeText(requireContext(),R.string.community_created,Toast.LENGTH_SHORT).show();}
                public void error(String message){/* The page keeps the draft and shows the ViewModel error. */}
            });
        });
    }
    private int selectedCount(boolean[] checked){int count=0;for(boolean value:checked)if(value)count++;return count;}

    private void renderGroups(List<Conversation> groups){
        rows.removeAllViews();int size=groups==null?0:groups.size();empty.setVisibility(size==0?View.VISIBLE:View.GONE);
        count.setText(getString(R.string.community_group_count,size));if(groups==null)return;
        for(Conversation group:groups){
            View card=getLayoutInflater().inflate(R.layout.item_community_group,rows,false);
            ((TextView)card.findViewById(R.id.tv_community_group_name)).setText(group.getTitle()==null?getString(R.string.conversation_unknown_title):group.getTitle());
            String members=group.getParticipantIds()==null?getString(R.string.groups_members):getString(R.string.search_group_members,group.getParticipantIds().size());
            ((TextView)card.findViewById(R.id.tv_community_group_members)).setText(members);
            ((TextView)card.findViewById(R.id.tv_community_group_preview)).setText(group.getLastMessage()==null||group.getLastMessage().trim().isEmpty()?getString(R.string.community_no_messages):group.getLastMessage());
            String photo=group.getPhotoUrl();ImageView avatar=card.findViewById(R.id.iv_community_group);
            if(photo!=null&&photo.startsWith("https://")){avatar.setVisibility(View.VISIBLE);com.bumptech.glide.Glide.with(avatar).load(photo).circleCrop().into(avatar);}
            card.findViewById(R.id.community_group_identity).setOnClickListener(v->openChat(group));
            card.findViewById(R.id.btn_community_open).setOnClickListener(v->openChat(group));
            card.findViewById(R.id.btn_community_members).setOnClickListener(v->showMembers(group));
            card.findViewById(R.id.btn_community_leave).setOnClickListener(v->confirmLeave(group));
            boolean busy=Boolean.TRUE.equals(vm.loading().getValue());
            card.findViewById(R.id.btn_community_members).setEnabled(!busy);card.findViewById(R.id.btn_community_leave).setEnabled(!busy);
            rows.addView(card);
        }
    }
    private void openChat(Conversation group){
        startActivity(new Intent(requireContext(),ChatActivity.class).putExtra(Constants.EXTRA_CONVERSATION_ID,group.getId())
                .putExtra(Constants.EXTRA_CONVERSATION_TITLE,group.getTitle()).putExtra(Constants.EXTRA_CONVERSATION_TYPE,"group")
                .putExtra(Constants.EXTRA_CONVERSATION_PHOTO,group.getPhotoUrl()));
    }
    private void showMembers(Conversation group){
        vm.watchMembers(group.getId());
        vm.members().observe(getViewLifecycleOwner(),new Observer<List<ConversationMember>>(){public void onChanged(List<ConversationMember> items){
            if(items==null)return;vm.members().removeObserver(this);renderMembers(items);
        }});
    }
    private void renderMembers(List<ConversationMember> current){
        List<ConversationMember> snapshot=new ArrayList<>(current);User me=vm.currentUser();ConversationMember.Role myRole=ConversationMember.Role.MEMBER;
        for(ConversationMember member:snapshot)if(me!=null&&me.getUid().equals(member.getUserId()))myRole=member.getRole();
        boolean canManage=myRole!=ConversationMember.Role.MEMBER;
        LinearLayout content=new LinearLayout(requireContext());content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(8),dp(20),dp(8));
        ScrollView scroll=new ScrollView(requireContext());scroll.addView(content);
        Map<String,User> known=new HashMap<>();List<User> friends=vm.friends().getValue();if(friends!=null)for(User user:friends)known.put(user.getUid(),user);if(me!=null)known.put(me.getUid(),me);
        Map<String,TextView> labels=new HashMap<>();List<String> unknown=new ArrayList<>();
        AlertDialog dialog=dialogBuilder().setTitle(getString(R.string.groups_members)+" ("+snapshot.size()+")")
                .setView(scroll).setPositiveButton(R.string.community_close,null).create();
        for(ConversationMember member:snapshot){
            LinearLayout entry=new LinearLayout(requireContext());entry.setOrientation(LinearLayout.VERTICAL);entry.setPadding(0,dp(12),0,dp(12));
            TextView label=text(16,R.color.utechat_on_surface);label.setTypeface(null,android.graphics.Typeface.BOLD);labels.put(member.getUserId(),label);
            User profile=known.get(member.getUserId());label.setText(profile==null?getString(R.string.groups_member_role_member):memberName(profile,me));
            if(profile==null)unknown.add(member.getUserId());entry.addView(label);
            TextView role=text(13,R.color.community_muted);role.setPadding(0,dp(4),0,dp(4));role.setText(roleLabel(member.getRole()));entry.addView(role);
            LinearLayout actions=new LinearLayout(requireContext());actions.setOrientation(LinearLayout.VERTICAL);
            if(myRole==ConversationMember.Role.OWNER&&member.getRole()!=ConversationMember.Role.OWNER){
                MaterialButton transfer=button(R.string.groups_transfer_owner,false);actions.addView(transfer,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
                transfer.setOnClickListener(v->confirm(R.string.community_transfer_title,R.string.groups_confirm_transfer,R.string.groups_transfer_owner,false,()->{dialog.dismiss();vm.transferOwnership(member.getUserId());}));
            }
            boolean canRemove=myRole==ConversationMember.Role.OWNER?member.getRole()!=ConversationMember.Role.OWNER:myRole==ConversationMember.Role.ADMIN&&member.getRole()==ConversationMember.Role.MEMBER;
            if(canRemove){MaterialButton remove=button(R.string.groups_remove_member,true);actions.addView(remove,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
                remove.setOnClickListener(v->confirm(R.string.community_remove_title,R.string.groups_confirm_remove,R.string.groups_remove_member,true,()->{dialog.dismiss();vm.removeMember(member.getUserId());}));}
            if(actions.getChildCount()>0)entry.addView(actions);content.addView(entry);
            View divider=new View(requireContext());divider.setBackgroundColor(color(R.color.community_border));content.addView(divider,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(1)));
        }
        if(canManage){MaterialButton add=new MaterialButton(new androidx.appcompat.view.ContextThemeWrapper(requireContext(),R.style.CommunityPrimaryOverlay));add.setText(R.string.community_choose_members);
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(48));params.topMargin=dp(16);content.addView(add,params);
            add.setOnClickListener(v->{dialog.dismiss();chooseAdditionalMember(snapshot);});}
        show(dialog,false);
        FriendGroupRepository repository=FriendGroupRepositoryProvider.get(requireContext());
        if(repository!=null&&!unknown.isEmpty())repository.loadUsers(unknown,new FriendGroupRepository.Result<List<User>>(){
            public void success(List<User> users){if(!dialog.isShowing())return;for(User user:users){TextView label=labels.get(user.getUid());if(label!=null)label.setText(memberName(user,me));}}
            public void error(String message){/* Keep the role label if a public profile is no longer available. */}
        });
    }
    private String memberName(User user,User me){return me!=null&&me.getUid().equals(user.getUid())?getString(R.string.community_you,user.getDisplayName()):user.getDisplayName();}
    private void chooseAdditionalMember(List<ConversationMember> current){
        List<User> friends=vm.friends().getValue();if(friends==null)return;
        Set<String> ids=new HashSet<>();for(ConversationMember member:current)ids.add(member.getUserId());
        List<User> eligible=new ArrayList<>();for(User user:friends)if(!ids.contains(user.getUid()))eligible.add(user);
        if(eligible.isEmpty()){Toast.makeText(requireContext(),R.string.groups_no_friends,Toast.LENGTH_SHORT).show();return;}
        String[] labels=new String[eligible.size()];for(int i=0;i<labels.length;i++)labels[i]=eligible.get(i).getDisplayName();
        show(dialogBuilder().setTitle(R.string.community_choose_members).setItems(labels,(d,i)->vm.addMember(eligible.get(i)))
                .setNegativeButton(R.string.friends_cancel_button,null).create(),false);
    }
    private void confirmLeave(Conversation group){
        vm.watchMembers(group.getId());vm.members().observe(getViewLifecycleOwner(),new Observer<List<ConversationMember>>(){public void onChanged(List<ConversationMember> items){
            if(items==null)return;vm.members().removeObserver(this);User me=vm.currentUser();
            for(ConversationMember member:items)if(me!=null&&me.getUid().equals(member.getUserId())&&member.getRole()==ConversationMember.Role.OWNER){Toast.makeText(requireContext(),R.string.groups_owner_transfer_first,Toast.LENGTH_LONG).show();return;}
            confirm(R.string.community_leave_title,R.string.groups_confirm_leave,R.string.groups_leave,true,vm::leave);
        }});
    }
    private void confirm(int title,int message,int action,boolean danger,Runnable run){
        show(dialogBuilder().setTitle(title).setMessage(message).setNegativeButton(R.string.friends_cancel_button,null)
                .setPositiveButton(action,(d,w)->run.run()).create(),danger);
    }
    private void show(AlertDialog dialog,boolean danger){
        dialogs.add(dialog);dialog.setOnDismissListener(d->dialogs.remove(dialog));dialog.show();
        if(dialog.getButton(AlertDialog.BUTTON_POSITIVE)!=null){dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(new android.content.res.ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{color(R.color.community_muted),color(danger?R.color.community_danger:R.color.community_action)}));dialog.getButton(AlertDialog.BUTTON_POSITIVE).setMinHeight(dp(48));}
        if(dialog.getButton(AlertDialog.BUTTON_NEGATIVE)!=null)dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(color(R.color.community_muted));
    }
    private MaterialAlertDialogBuilder dialogBuilder(){return new MaterialAlertDialogBuilder(requireContext(),R.style.CommunityDialogOverlay);}
    private String roleLabel(ConversationMember.Role role){switch(role){case OWNER:return getString(R.string.groups_member_role_owner);case ADMIN:return getString(R.string.groups_member_role_admin);default:return getString(R.string.groups_member_role_member);}}
    private MaterialButton button(int label,boolean danger){MaterialButton button=new MaterialButton(new androidx.appcompat.view.ContextThemeWrapper(requireContext(),danger?R.style.CommunityDangerOverlay:R.style.CommunitySecondaryOverlay));button.setText(label);button.setMaxLines(2);button.setPadding(button.getPaddingLeft(),dp(8),button.getPaddingRight(),dp(8));return button;}
    private TextView text(int size,int color){TextView view=new TextView(requireContext());view.setTextSize(size);view.setTextColor(color(color));return view;}
    private int color(int id){return androidx.core.content.ContextCompat.getColor(requireContext(),id);}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    @Override public void onDestroyView(){for(AlertDialog dialog:new ArrayList<>(dialogs))dialog.dismiss();dialogs.clear();super.onDestroyView();}
}
