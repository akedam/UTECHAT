package com.example.utechat.feature.friends.viewmodel;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.example.utechat.R;
import com.example.utechat.app.FriendGroupRepositoryProvider;
import com.example.utechat.app.PresenceRepositoryProvider;
import com.example.utechat.data.auth.AuthRepositoryProvider;
import com.example.utechat.domain.model.Friendship;
import com.example.utechat.domain.model.User;
import com.example.utechat.domain.repository.FriendGroupRepository;
import com.example.utechat.domain.repository.PresenceRepository;
import java.util.*;

public class FriendsViewModel extends AndroidViewModel {
    private final FriendGroupRepository repo;
    private final User currentUser;
    private final MutableLiveData<List<User>> friends = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<List<User>> results = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<List<Friendship>> requests = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<String> error = new MutableLiveData<>();
    private final MutableLiveData<Boolean> loading = new MutableLiveData<>(false);
    private FriendGroupRepository.Subscription subscription;
    private final PresenceRepository presenceRepository;
    private final Map<String, PresenceRepository.Subscription> presenceSubscriptions = new HashMap<>();
    private final Map<String, User> observedFriends = new LinkedHashMap<>();
    public FriendsViewModel(@NonNull Application app) {
        super(app); repo=FriendGroupRepositoryProvider.get(app);currentUser=AuthRepositoryProvider.get(app).getCurrentUser();
        presenceRepository=PresenceRepositoryProvider.get();
        if(repo!=null && currentUser!=null) subscription=repo.observeFriendships(currentUser.getUid(),this::onFriendships);
    }
    public LiveData<List<User>> getFriends(){return friends;} public LiveData<List<User>> getSearchResults(){return results;}
    public LiveData<List<Friendship>> getRequests(){return requests;} public LiveData<String> getError(){return error;}
    public LiveData<Boolean> getLoading(){return loading;} public boolean isMock(){return repo==null||repo.isMock();}
    public User getCurrentUser(){return currentUser;}
    public void search(String term){if(repo==null){error.setValue(getApplication().getString(R.string.friends_firebase_required));return;}loading.setValue(true);
        repo.searchUsers(term,new FriendGroupRepository.Result<List<User>>(){public void success(List<User> users){loading.setValue(false);if(currentUser!=null)users.removeIf(u->currentUser.getUid().equals(u.getUid()));results.setValue(users);error.setValue(null);}
            public void error(String s){loading.setValue(false);error.setValue(s);}});}
    public void sendRequest(User user){if(repo==null||currentUser==null)return;loading.setValue(true);repo.requestFriend(currentUser.getUid(),user.getUid(),done());}
    public void accept(Friendship f){repo.respondFriend(f.getId(),true,done());}
    public void decline(Friendship f){repo.respondFriend(f.getId(),false,done());}
    public void remove(Friendship f){repo.removeFriend(f.getId(),done());}
    public void openDirect(User user,FriendGroupRepository.Result<String> callback){if(repo==null||currentUser==null){callback.error(getApplication().getString(R.string.friends_firebase_required));return;}repo.openDirect(currentUser.getUid(),user.getUid(),callback);}
    private FriendGroupRepository.Result<Void> done(){return new FriendGroupRepository.Result<Void>(){public void success(Void v){loading.setValue(false);error.setValue(null);}
        public void error(String message){loading.setValue(false);error.setValue(message);}};}
    private void onFriendships(List<Friendship> all,String failure){if(failure!=null){error.postValue(failure);return;}error.postValue(null);if(currentUser==null)return;
        List<Friendship> accepted=new ArrayList<>(),pending=new ArrayList<>();Set<String> uids=new LinkedHashSet<>();
        for(Friendship f:all){if(f.isAccepted()){accepted.add(f);uids.add(peer(f));}else if(f.isPending()){pending.add(f);uids.add(peer(f));}}
        repo.loadUsers(new ArrayList<>(uids),new FriendGroupRepository.Result<List<User>>(){public void success(List<User> users){Map<String,User> byId=new HashMap<>();for(User u:users)byId.put(u.getUid(),u);
            List<User> friendUsers=new ArrayList<>();for(Friendship f:accepted){User u=byId.get(peer(f));if(u!=null)friendUsers.add(u);}publishFriendsAndPresence(friendUsers);
            for(Friendship f:pending)f.setOtherUser(byId.get(peer(f)));requests.postValue(pending);}
            public void error(String message){error.postValue(message);}});
    }
    private void publishFriendsAndPresence(List<User> friendUsers){
        observedFriends.clear();
        for(User user:friendUsers) observedFriends.put(user.getUid(),user);
        Iterator<Map.Entry<String,PresenceRepository.Subscription>> it=presenceSubscriptions.entrySet().iterator();
        while(it.hasNext()){Map.Entry<String,PresenceRepository.Subscription> entry=it.next();if(!observedFriends.containsKey(entry.getKey())){entry.getValue().remove();it.remove();}}
        friends.postValue(new ArrayList<>(observedFriends.values()));
        if(presenceRepository==null)return;
        for(User user:friendUsers){
            if(presenceSubscriptions.containsKey(user.getUid()))continue;
            String uid=user.getUid();
            PresenceRepository.Subscription registration=presenceRepository.observe(uid,(online,lastSeen)->{
                User current=observedFriends.get(uid);
                if(current==null)return;
                current.setOnline(online);current.setLastSeen(lastSeen);
                friends.postValue(new ArrayList<>(observedFriends.values()));
            });
            presenceSubscriptions.put(uid,registration);
        }
    }
    private String peer(Friendship f){return currentUser.getUid().equals(f.getFromUid())?f.getToUid():f.getFromUid();}
    @Override protected void onCleared(){if(subscription!=null)subscription.remove();for(PresenceRepository.Subscription item:presenceSubscriptions.values())item.remove();presenceSubscriptions.clear();super.onCleared();}
}
