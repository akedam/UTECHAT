package com.example.utechat.feature.groups.viewmodel;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.example.utechat.R;
import com.example.utechat.app.FriendGroupRepositoryProvider;
import com.example.utechat.data.auth.AuthRepositoryProvider;
import com.example.utechat.domain.model.*;
import com.example.utechat.domain.repository.FriendGroupRepository;
import java.util.*;

public class GroupsViewModel extends AndroidViewModel {
    private final FriendGroupRepository repo; private final User me;
    private final MutableLiveData<List<Conversation>> groups=new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<List<User>> friends=new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<List<ConversationMember>> members=new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<String> error=new MutableLiveData<>();
    private final MutableLiveData<Boolean> loading=new MutableLiveData<>(false);
    private FriendGroupRepository.Subscription groupSub,friendSub,memberSub; private String memberGroup;
    public GroupsViewModel(@NonNull Application app){super(app);repo=FriendGroupRepositoryProvider.get(app);me=AuthRepositoryProvider.get(app).getCurrentUser();
        if(repo!=null&&me!=null){groupSub=repo.observeGroups(me.getUid(),(items,e)->{if(e!=null)error.postValue(e);else {error.postValue(null);groups.postValue(items);}});
            friendSub=repo.observeFriendships(me.getUid(),(items,e)->{if(e!=null){error.postValue(e);return;}List<String> ids=new ArrayList<>();for(Friendship f:items)if(f.isAccepted())ids.add(me.getUid().equals(f.getFromUid())?f.getToUid():f.getFromUid());repo.loadUsers(ids,new FriendGroupRepository.Result<List<User>>(){public void success(List<User> u){friends.postValue(u);}public void error(String s){error.postValue(s);}});});}}
    public LiveData<List<Conversation>> groups(){return groups;} public LiveData<List<User>> friends(){return friends;} public LiveData<List<ConversationMember>> members(){return members;} public LiveData<String> error(){return error;} public LiveData<Boolean> loading(){return loading;} public User currentUser(){return me;} public boolean isReady(){return repo!=null&&me!=null;}
    public void create(String name,List<User> selected){create(name,selected,null);}
    public void create(String name,List<User> selected,FriendGroupRepository.Result<String> callback){if(!isReady()||Boolean.TRUE.equals(loading.getValue()))return;loading.setValue(true);repo.createGroup(me.getUid(),name,selected,new FriendGroupRepository.Result<String>(){public void success(String id){error.setValue(null);loading.setValue(false);if(callback!=null)callback.success(id);}public void error(String s){error.setValue(s);loading.setValue(false);if(callback!=null)callback.error(s);}});}
    public void watchMembers(String id){if(repo==null)return;if(memberSub!=null)memberSub.remove();memberGroup=id;members.setValue(null);memberSub=repo.observeMembers(id,(items,e)->{if(e!=null)error.postValue(e);else members.postValue(items);});}
    public void addMember(User u){if(repo==null||memberGroup==null)return;loading.setValue(true);repo.addMember(memberGroup,u,result());}
    public void removeMember(String uid){if(repo==null||memberGroup==null)return;loading.setValue(true);repo.removeMember(memberGroup,uid,result());}
    public void transferOwnership(String uid){if(repo==null||memberGroup==null)return;loading.setValue(true);repo.transferOwnership(memberGroup,uid,result());}
    public void leave(){if(repo==null||memberGroup==null||me==null)return;loading.setValue(true);repo.leaveGroup(memberGroup,me.getUid(),result());}
    private FriendGroupRepository.Result<Void> result(){return new FriendGroupRepository.Result<Void>(){public void success(Void x){loading.setValue(false);error.setValue(null);}public void error(String s){loading.setValue(false);error.setValue(s);}};}
    @Override protected void onCleared(){if(groupSub!=null)groupSub.remove();if(friendSub!=null)friendSub.remove();if(memberSub!=null)memberSub.remove();super.onCleared();}
}
