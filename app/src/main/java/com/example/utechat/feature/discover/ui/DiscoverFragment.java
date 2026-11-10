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
import com.example.utechat.core.common.Constants;
import com.example.utechat.domain.model.*;
import com.example.utechat.feature.chat.ui.ChatActivity;
import com.example.utechat.feature.groups.viewmodel.GroupsViewModel;
import com.google.android.material.button.MaterialButton;
import java.util.*;

/** Groups are member-managed Firestore conversations; this is not a public server browser. */
public class DiscoverFragment extends Fragment {
    private GroupsViewModel vm; private EditText name; private LinearLayout rows; private TextView empty,error; private View root; private View createButton;
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,@Nullable ViewGroup parent,@Nullable Bundle state){return inflater.inflate(R.layout.fragment_discover,parent,false);}
    @Override public void onViewCreated(@NonNull View v,@Nullable Bundle state){super.onViewCreated(v,state);root=v;vm=new ViewModelProvider(this).get(GroupsViewModel.class);name=v.findViewById(R.id.et_group_name);rows=v.findViewById(R.id.layout_groups);empty=v.findViewById(R.id.tv_group_empty);error=v.findViewById(R.id.tv_groups_error);createButton=v.findViewById(R.id.btn_create_group);
        createButton.setOnClickListener(x->chooseMembersAndCreate());
        vm.groups().observe(getViewLifecycleOwner(),this::renderGroups);vm.error().observe(getViewLifecycleOwner(),s->{error.setText(s==null?"":s);error.setVisibility(s==null||s.isEmpty()?View.GONE:View.VISIBLE);});
        vm.friends().observe(getViewLifecycleOwner(),u->{if(u==null||u.isEmpty())createButton.setEnabled(false);else createButton.setEnabled(true);});
        vm.loading().observe(getViewLifecycleOwner(),b->{boolean busy=Boolean.TRUE.equals(b);v.findViewById(R.id.progress_groups).setVisibility(busy?View.VISIBLE:View.GONE);createButton.setEnabled(!busy&&vm.friends().getValue()!=null&&!vm.friends().getValue().isEmpty());});
        if(!vm.isReady()){error.setText(R.string.friends_firebase_required);error.setVisibility(View.VISIBLE);}
    }
    private void chooseMembersAndCreate(){List<User> fs=vm.friends().getValue();if(fs==null||fs.isEmpty()){Toast.makeText(requireContext(),R.string.groups_no_friends,Toast.LENGTH_SHORT).show();return;}
        String[] labels=new String[fs.size()];boolean[] checked=new boolean[fs.size()];for(int i=0;i<fs.size();i++)labels[i]=fs.get(i).getDisplayName();
        new AlertDialog.Builder(requireContext()).setTitle(R.string.groups_select_members).setMultiChoiceItems(labels,checked,(d,which,on)->{if(on){int count=0;for(boolean b:checked)if(b)count++;if(count>8){checked[which]=false;((AlertDialog)d).getListView().setItemChecked(which,false);Toast.makeText(requireContext(),R.string.groups_initial_member_limit,Toast.LENGTH_SHORT).show();return;}}checked[which]=on;})
            .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.groups_create_button,(d,w)->{List<User> picked=new ArrayList<>();for(int i=0;i<fs.size();i++)if(checked[i])picked.add(fs.get(i));String title=name.getText()==null?"":name.getText().toString();if(title.trim().isEmpty()){name.setError(getString(R.string.groups_name_invalid));return;}vm.create(title,picked);name.setText("");}).show();
    }
    private void renderGroups(List<Conversation> groups){rows.removeAllViews();boolean has=groups!=null&&!groups.isEmpty();empty.setVisibility(has?View.GONE:View.VISIBLE);if(!has)return;
        for(Conversation c:groups){LinearLayout card=new LinearLayout(requireContext());card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(12),dp(8),dp(12),dp(8));TextView title=new TextView(requireContext());title.setText(c.getTitle()==null?getString(R.string.conversation_unknown_title):c.getTitle());title.setTextSize(18);title.setTextColor(getResources().getColor(R.color.utechat_on_surface));card.addView(title);LinearLayout actions=new LinearLayout(requireContext());
            MaterialButton open=button(R.string.groups_open_chat);open.setOnClickListener(x->{Intent i=new Intent(requireContext(),ChatActivity.class);i.putExtra(Constants.EXTRA_CONVERSATION_ID,c.getId());i.putExtra(Constants.EXTRA_CONVERSATION_TITLE,c.getTitle());i.putExtra(Constants.EXTRA_CONVERSATION_TYPE,"group");startActivity(i);});actions.addView(open);
            MaterialButton members=button(R.string.groups_members);members.setOnClickListener(x->showMembers(c));actions.addView(members);
            MaterialButton leave=button(R.string.groups_leave);leave.setOnClickListener(x->confirmLeave(c));actions.addView(leave);card.addView(actions);rows.addView(card);
        }
    }
    private void showMembers(Conversation c){vm.watchMembers(c.getId());Observer<List<ConversationMember>> observer=new Observer<List<ConversationMember>>(){@Override public void onChanged(List<ConversationMember> items){if(items==null)return;vm.members().removeObserver(this);renderMembers(c,items);}};vm.members().observe(getViewLifecycleOwner(),observer);}
    private void renderMembers(Conversation c,List<ConversationMember> current){List<ConversationMember> snapshot=new ArrayList<>(current);User me=vm.currentUser();String myRole="member";for(ConversationMember m:snapshot)if(me!=null&&me.getUid().equals(m.getUserId()))myRole=m.getRole().name().toLowerCase(Locale.ROOT);boolean canManage="owner".equals(myRole)||"admin".equals(myRole);
        LinearLayout content=new LinearLayout(requireContext());content.setOrientation(LinearLayout.VERTICAL);ScrollView scroll=new ScrollView(requireContext());scroll.addView(content);
        Map<String,User> friends=new HashMap<>();List<User> fs=vm.friends().getValue();if(fs!=null)for(User u:fs)friends.put(u.getUid(),u);
        for(ConversationMember m:snapshot){String display=friends.containsKey(m.getUserId())?friends.get(m.getUserId()).getDisplayName():m.getUserId();String role=roleLabel(m.getRole());TextView row=new TextView(requireContext());row.setText(display+" · "+role);row.setPadding(dp(8),dp(8),dp(8),dp(8));content.addView(row);
            if("owner".equals(myRole)&&m.getRole()!=ConversationMember.Role.OWNER){MaterialButton transfer=button(R.string.groups_transfer_owner);transfer.setOnClickListener(x->new AlertDialog.Builder(requireContext()).setMessage(R.string.groups_confirm_transfer).setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,(dialog,w)->vm.transferOwnership(m.getUserId())).show());content.addView(transfer);}
            boolean canRemove="owner".equals(myRole)?m.getRole()!=ConversationMember.Role.OWNER:("admin".equals(myRole)&&m.getRole()==ConversationMember.Role.MEMBER);
            if(canRemove){MaterialButton remove=button(R.string.groups_remove_member);remove.setOnClickListener(x->new AlertDialog.Builder(requireContext()).setMessage(R.string.groups_confirm_remove).setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,(dialog,w)->vm.removeMember(m.getUserId())).show());content.addView(remove);}}
        if(canManage){MaterialButton add=button(R.string.groups_select_members);add.setOnClickListener(x->chooseAdditionalMember(snapshot));content.addView(add);}
        new AlertDialog.Builder(requireContext()).setTitle(R.string.groups_members).setView(scroll).setPositiveButton(android.R.string.ok,null).show();
    }
    private void chooseAdditionalMember(List<ConversationMember> current){List<User> fs=vm.friends().getValue();if(fs==null)return;Set<String> ids=new HashSet<>();for(ConversationMember m:current)ids.add(m.getUserId());List<User> eligible=new ArrayList<>();for(User u:fs)if(!ids.contains(u.getUid()))eligible.add(u);if(eligible.isEmpty()){Toast.makeText(requireContext(),R.string.groups_no_friends,Toast.LENGTH_SHORT).show();return;}
        String[] labels=new String[eligible.size()];for(int i=0;i<labels.length;i++)labels[i]=eligible.get(i).getDisplayName();new AlertDialog.Builder(requireContext()).setTitle(R.string.groups_select_members).setItems(labels,(d,i)->vm.addMember(eligible.get(i))).show();
    }
    private void confirmLeave(Conversation c){vm.watchMembers(c.getId());Observer<List<ConversationMember>> observer=new Observer<List<ConversationMember>>(){@Override public void onChanged(List<ConversationMember> items){if(items==null)return;vm.members().removeObserver(this);User me=vm.currentUser();for(ConversationMember m:items)if(me!=null&&me.getUid().equals(m.getUserId())&&m.getRole()==ConversationMember.Role.OWNER){Toast.makeText(requireContext(),R.string.groups_owner_transfer_first,Toast.LENGTH_LONG).show();return;}new AlertDialog.Builder(requireContext()).setMessage(R.string.groups_confirm_leave).setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,(d,w)->vm.leave()).show();}};vm.members().observe(getViewLifecycleOwner(),observer);}
    private String roleLabel(ConversationMember.Role role){switch(role){case OWNER:return getString(R.string.groups_member_role_owner);case ADMIN:return getString(R.string.groups_member_role_admin);default:return getString(R.string.groups_member_role_member);}}
    private MaterialButton button(int text){MaterialButton b=new MaterialButton(requireContext());b.setText(text);b.setMinHeight(dp(48));b.setInsetTop(0);b.setInsetBottom(0);return b;}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
}
